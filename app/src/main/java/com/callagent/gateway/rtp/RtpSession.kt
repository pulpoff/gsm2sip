package com.callagent.gateway.rtp

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.callagent.gateway.DeviceProfile
import com.callagent.gateway.RootShell
import com.callagent.gateway.gsm.GsmCallManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTP session: handles bidirectional audio between speaker/mic and remote RTP endpoint.
 *
 * On S4 Mini (MSM8930), VOICE_DOWNLINK routes through the physical mic
 * (HAL usecase incall-rec-downlink → voice-handset-mic).  Speaker mode
 * plays GSM caller audio through the speaker; the mic picks it up.
 *
 * Audio path:
 * - Capture: VOICE_DOWNLINK via mic → gain boost → encode → RTP → SIP agent.
 * - Playback: RTP → decode → AudioTrack (usage from profile).
 *   MSM8930: USAGE_MEDIA → STREAM_MUSIC.  incall_music_enabled=true
 *   injects STREAM_MUSIC into voice TX, bypassing modem AEC.
 *   Exynos 9820: USAGE_VOICE_COMMUNICATION → STREAM_VOICE_CALL.
 *   Samsung HAL may route this into the modem uplink directly.
 *
 * The Magisk module disables Android's audio concurrency restrictions.
 * Uses G.722 codec for wideband (16 kHz), falls back to PCMA (G.711 A-law).
 */
class RtpSession(
    private val context: Context,
    private val localPort: Int,
    private val remoteAddr: String,
    private val remotePort: Int,
    private val payloadType: Int = RtpPacket.PT_PCMA
) {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // Codec
    private val g722Encoder = G722Codec()
    private val g722Decoder = G722Codec()

    // RTP state
    private var txSequence = 0
    private var txTimestamp = 0L
    private val txSsrc = (Math.random() * 0xFFFFFFFFL).toLong()

    // Symmetric RTP: latch onto the actual source address of received packets
    @Volatile private var latchedAddr: InetAddress? = null
    @Volatile private var latchedPort: Int = 0

    // Jitter buffer for received packets.  Capacity 8 (160ms) absorbs
    // network jitter without audible gaps.  The playback loop drains
    // excess above 5 (100ms) to bound latency — acceptable for a GSM
    // bridge that already has 100-200ms of inherent GSM latency.
    // Previous capacity=3 was too aggressive: any slight jitter caused
    // packet drops and choppy audio.
    private val jitterBuffer = ArrayBlockingQueue<ByteArray>(8)

    // RTP inactivity tracking
    @Volatile private var lastRtpReceivedTime = 0L
    private val RTP_TIMEOUT_MS = 30_000L  // 30 seconds with no RTP = dead call

    // Packet counters and audio diagnostics
    @Volatile var txPacketCount = 0L; private set
    @Volatile var rxPacketCount = 0L; private set
    @Volatile var playbackFrames = 0L; private set
    @Volatile var captureRms = 0; private set
    @Volatile var playbackRms = 0; private set
    @Volatile var rawCaptureRms = 0; private set  // Before echo gate — 0 means source is silent
    @Volatile var audioSourceName = "none"; private set
    @Volatile private var playbackUsageName = "MEDIA"
    @Volatile private var firstRxInfo = ""
    @Volatile private var firstTxInfo = ""

    // Capture and playback rates may differ.  VOICE_CALL on MSM8930
    // only initializes at 8 kHz; G.722 decoding outputs 16 kHz PCM.
    private var captureRate = 8000
    private var playbackRate = 8000

    // Audio session ID from AudioRecord (for logging/diagnostics)
    private var audioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE

    // Silence detection: track audio source IDs that produce no audio so we
    // can fall back to alternatives.  E.g., VOICE_CALL initializes on
    // Exynos 9820 but delivers silence — the HAL doesn't route voice data
    // to the capture path.  Falling back to MIC captures the caller's voice
    // acoustically from the speaker.
    private val silentSourceIds = mutableSetOf<Int>()
    @Volatile private var currentSourceId: Int = -1
    private data class SourceConfig(val source: Int, val name: String, val rate: Int)
    // Silence detection thresholds — only counted during non-echo periods
    // (when decayingPlaybackRms <= echoGateThreshold) to avoid false resets
    // from incall_music echo leaking back through VOICE_CALL capture.
    private val SILENCE_RMS_THRESHOLD = 10   // Below this = truly dead source (ADC noise floor)
    private val SILENCE_FRAME_LIMIT = 25     // 25 non-echo frames (~0.5s) — fast fallback for testing

    var listener: Listener? = null

    interface Listener {
        fun onRtpStarted()
        fun onRtpStopped()
        fun onRtpError(error: String)
        fun onRtpTimeout() {}  // No RTP received for RTP_TIMEOUT_MS
        fun onRtpStats(stats: String) {}  // Periodic detailed stats
    }

    fun start() {
        if (running.getAndSet(true)) return
        Log.i(TAG, "Starting RTP session: local=$localPort remote=$remoteAddr:$remotePort pt=$payloadType")

        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(localPort))
                soTimeout = 100
                receiveBufferSize = 262144
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind RTP socket on port $localPort: ${e.message}")
            running.set(false)
            listener?.onRtpError("Socket bind failed: ${e.message}")
            return
        }

        if (!initAudio()) {
            running.set(false)
            return
        }

        // Send initial RTP keepalive to punch NAT pinhole before audio starts
        try {
            val remoteInet = InetAddress.getByName(remoteAddr)
            val silence = RtpPacket(payloadType, 0, 0, txSsrc, ByteArray(160)).encode()
            socket?.send(DatagramPacket(silence, silence.size, remoteInet, remotePort))
            Log.i(TAG, "Sent NAT punch-through packet to $remoteAddr:$remotePort")
        } catch (e: Exception) {
            Log.w(TAG, "NAT punch-through failed: ${e.message}")
        }

        lastRtpReceivedTime = System.currentTimeMillis()
        Thread({ receiveLoop() }, "RTP-Recv-$localPort").start()
        Thread({ playbackLoop() }, "RTP-Play-$localPort").start()
        Thread({ captureInitAndLoop() }, "RTP-Capt-$localPort").start()
        Thread({ timeoutLoop() }, "RTP-Timeout-$localPort").start()

        listener?.onRtpStarted()
    }

    /**
     * Initialize AudioRecord and AudioTrack.
     *
     * AudioRecord: Tries VOICE_DOWNLINK first (on S4 Mini this routes through
     * the physical mic via incall-rec-downlink HAL usecase).
     * AudioTrack: USAGE_MEDIA (STREAM_MUSIC) so that Qualcomm's
     * incall_music_enabled=true parameter injects it into voice TX (uplink).
     * USAGE_VOICE_COMMUNICATION maps to STREAM_VOICE_CALL which the HAL
     * does NOT inject via incall_music — that's why SIP→GSM was silent.
     */
    private fun initAudio(): Boolean {
        // Playback rate matches codec output rate.  G.722 decodes to 16 kHz.
        playbackRate = when (payloadType) {
            RtpPacket.PT_PCMA, RtpPacket.PT_PCMU -> 8000
            else -> 16000  // G.722
        }

        // Try telephony capture sources, then mic sources.
        // When using G.722 (wideband), prefer 16kHz capture to avoid upsampling
        // artifacts.  HD Voice (AMR-WB/EVS) provides native 16kHz audio.
        val configs = mutableListOf<SourceConfig>()

        val wideband = payloadType != RtpPacket.PT_PCMA && payloadType != RtpPacket.PT_PCMU

        // VOICE_CALL (source 4): captures uplink+downlink mixed digitally.
        // Best option on MSM8930 — if it initializes, it provides clean
        // digital capture of the caller's voice.  Requires CAPTURE_AUDIO_OUTPUT.
        if (wideband) {
            // G.722: prefer 16kHz native capture — avoids upsampling artifacts
            // in the 4-8kHz upper band that cause AI agent false interruptions.
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL@16k", 16000))
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000))
        } else {
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000))
        }
        // Mic-based sources: in speaker mode, the physical mic picks up the
        // caller's voice from the speaker.  This is acoustic coupling — not
        // ideal but functional when digital capture sources fail.
        // VOICE_RECOGNITION bypasses noise suppression that can mute call audio.
        configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION", 8000))
        configs.add(SourceConfig(MediaRecorder.AudioSource.MIC, "MIC", 8000))
        configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION", 8000))
        // VOICE_DOWNLINK (source 3): DEAD LAST — on MSM8930 it initializes
        // successfully (STATE_INITIALIZED) but captures SILENCE because the
        // Incall_Rec mixer controls don't exist on this SoC.  If it were
        // earlier in the list, it would "win" over mic-based sources that
        // actually work.  Kept only for devices where it genuinely works.
        if (wideband) {
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK@16k", 16000))
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000))
        } else {
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000))
        }

        var record: AudioRecord? = null
        var usedSource = "none"
        var usedRate = 8000

        // Retry loop: cold boot can cause OP_RECORD_AUDIO denial even
        // after appops set.  Android's PermissionController re-revokes
        // RECORD_AUDIO for background apps almost immediately.
        // Re-assert appops before EACH attempt to combat the race.
        val maxAttempts = 1
        for (attempt in 1..maxAttempts) {
            // Re-assert RECORD_AUDIO appops before each attempt.
            // On cold boot, the single appops call in CallOrchestrator
            // gets re-revoked before AudioRecord creation.  Re-asserting
            // here with a propagation delay keeps the permission alive.
            reAssertAppOps()
            // 500ms propagation delay (was 150ms).  On cold boot, system services
            // are all starting simultaneously and AudioFlinger takes longer to see
            // the appops change.  150ms was too short — all 5 attempts would fail.
            Thread.sleep(500)

            for (cfg in configs) {
                try {
                    val minBuf = AudioRecord.getMinBufferSize(
                        cfg.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBuf <= 0) {
                        Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate}: invalid minBuf=$minBuf")
                        continue
                    }
                    val bufSize = minBuf.coerceAtLeast(cfg.rate / 50 * 2 * 2) // 40ms (two RTP frames)
                    val rec = AudioRecord(
                        cfg.source, cfg.rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize
                    )
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        record = rec
                        usedSource = cfg.name
                        usedRate = cfg.rate
                        audioSourceName = cfg.name
                        currentSourceId = cfg.source
                        Log.i(TAG, "AudioRecord OK: ${cfg.name} @ ${cfg.rate}Hz (buf=$bufSize, attempt=$attempt)")
                        break
                    } else {
                        Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate}: state=${rec.state}")
                        rec.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate} failed: ${e.message}")
                }
            }
            if (record != null) break

            if (attempt < maxAttempts) {
                val delayMs = attempt * 1000L
                Log.w(TAG, "All audio sources failed (attempt $attempt/$maxAttempts), retrying in ${delayMs}ms")
                Thread.sleep(delayMs)
            }
        }

        if (record != null) {
            audioRecord = record
            audioSessionId = record.audioSessionId
            captureRate = usedRate

            // Diagnostic: check critical permissions and ABOX state
            logCaptureDiagnostics(record)
        }

        // Minimum buffer for lowest latency.  incall_music injects
        // AudioTrack output digitally into the modem uplink — there is no
        // acoustic speaker→mic path, so deep-buffer headroom is unnecessary.
        // Writing silence when the jitter buffer is empty prevents underruns.
        val minPlayBuf = AudioTrack.getMinBufferSize(
            playbackRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val playBufSize = minPlayBuf

        // Profile-controlled playback usage:
        // USAGE_MEDIA (default / MSM8930): Maps to STREAM_MUSIC.  Qualcomm's
        // incall_music_enabled=true injects STREAM_MUSIC into voice TX.
        // USAGE_VOICE_COMMUNICATION (Exynos 9820): Maps to STREAM_VOICE_CALL.
        // Samsung's HAL may route this into the modem uplink when a voice
        // call is active.  On Exynos, no incall_music mixer exists, so
        // USAGE_MEDIA only plays on the speaker without reaching the modem.
        //
        // IMPORTANT: Do NOT use PERFORMANCE_MODE_LOW_LATENCY here.
        // Low-latency forces the HAL to use "low-latency-playback" usecase
        // which maps to MultiMedia5.  On MSM8930 (Galaxy S4 Mini), only
        // MultiMedia1 and MultiMedia2 have Incall_Music mixer controls.
        // MultiMedia5 has no incall_music mixer, so audio plays on the
        // earpiece but is NEVER injected into the modem uplink.
        // Using default (deep-buffer-playback → MultiMedia1) ensures the
        // Incall_Music Audio Mixer MultiMedia1 routes audio to the caller.
        val usage = if (profile.playbackUsage >= 0) profile.playbackUsage
                    else AudioAttributes.USAGE_MEDIA
        val contentType = if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION)
            AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC
        playbackUsageName = when (usage) {
            AudioAttributes.USAGE_MEDIA -> "MEDIA"
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            else -> "usage=$usage"
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(playbackRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(playBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track

        // No platform AEC — AudioTrack is on USAGE_MEDIA (different stream
        // from AudioRecord), so platform AEC can't reference it anyway.
        // For VOICE_DOWNLINK, AEC was over-canceling (capRMS dropped from 252 to ~5).
        // Echo cancellation is handled by Asterisk on the server side.

        Log.i(TAG, "Audio init: playRate=$playbackRate playUsage=$playbackUsageName capture=${if (audioRecord != null) audioSourceName else "deferred"} profile=${profile.name}")
        return true
    }

    /**
     * Initialize AudioRecord with retries, then run the capture loop.
     *
     * On cold boot, AudioFlinger refuses to create record tracks for
     * 10-30+ seconds after the voice call starts ("could not create record
     * track, status: -1").  The audio HAL needs time to fully initialize
     * the recording infrastructure after the modem voice path starts.
     *
     * Playback (SIP→GSM via incall_music) runs in a separate thread and
     * starts immediately.  This method retries capture init for up to 30s
     * so the caller hears the agent right away, even if the reverse
     * direction (GSM→SIP) takes longer to come up.
     */
    private fun captureInitAndLoop() {
        // Fast path: AudioRecord was already initialized in initAudio (warm boot)
        if (audioRecord != null) {
            if (captureLoop() || !running.get()) return
            // Source produced silence for 3s — fall through to try alternatives
            Log.w(TAG, "Primary source $audioSourceName silent, searching for working source (skip: $silentSourceIds)")
        }

        // Deferred/fallback source finding + capture loop.
        // Rebuilds the source list each iteration, excluding sources detected
        // as silent.  This handles both cold-boot AudioRecord unavailability
        // AND sources that initialize but deliver no audio (e.g., VOICE_CALL
        // on Exynos 9820 where the HAL doesn't route voice data to capture).
        val wideband = payloadType != RtpPacket.PT_PCMA && payloadType != RtpPacket.PT_PCMU
        val defaultRemoteInet = InetAddress.getByName(remoteAddr)
        val maxFallbacks = 5  // Maximum silent-source fallback cycles

        for (fallback in 0 until maxFallbacks) {
            if (!running.get()) return

            // Build source list, filtering out sources that produced silence
            val configs = buildCaptureConfigs(wideband)
            if (configs.isEmpty()) {
                Log.e(TAG, "All capture sources produced silence — no working source found")
                break
            }

            if (fallback > 0 || audioRecord == null) {
                Log.w(TAG, "Trying capture sources (fallback=$fallback): ${configs.joinToString { it.name }} (silent: $silentSourceIds)")
            }

            // Try to initialize AudioRecord with one of the remaining sources
            val maxAttempts = if (fallback == 0 && audioRecord == null) 15 else 3
            var sourceFound = false

            for (attempt in 1..maxAttempts) {
                if (!running.get()) return

                reAssertAppOps()
                sendSilencePackets(500, defaultRemoteInet)

                for (cfg in configs) {
                    if (cfg.source in silentSourceIds) continue
                    try {
                        val minBuf = AudioRecord.getMinBufferSize(
                            cfg.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                        )
                        if (minBuf <= 0) continue
                        val bufSize = minBuf.coerceAtLeast(cfg.rate / 50 * 2 * 2)
                        val rec = AudioRecord(
                            cfg.source, cfg.rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufSize
                        )
                        if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            if (!running.get()) { rec.release(); return }
                            audioRecord = rec
                            audioSessionId = rec.audioSessionId
                            captureRate = cfg.rate
                            audioSourceName = cfg.name
                            currentSourceId = cfg.source
                            Log.i(TAG, "AudioRecord OK: ${cfg.name} @ ${cfg.rate}Hz (buf=$bufSize, fallback=$fallback attempt=$attempt)")
                            sourceFound = true
                            break
                        } else {
                            Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate}: state=${rec.state}")
                            rec.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate} failed: ${e.message}")
                    }
                }

                if (sourceFound) break

                if (attempt < maxAttempts && running.get()) {
                    Log.w(TAG, "All remaining sources failed (fallback=$fallback attempt=$attempt/$maxAttempts), retrying in 2s")
                    sendSilencePackets(2000, defaultRemoteInet)
                }
            }

            if (!sourceFound) {
                Log.e(TAG, "Could not initialize any remaining capture source")
                break
            }

            // Run capture loop — returns false if silence detected
            Log.i(TAG, "Capture ready (fallback=$fallback), starting capture loop with $audioSourceName")
            if (captureLoop() || !running.get()) return
            // Silence detected — silentSourceIds updated, loop again with remaining sources
            Log.w(TAG, "Source $audioSourceName silent after 3s (skip: $silentSourceIds), trying next fallback")
        }

        // All sources exhausted — DON'T tear down the call.  Playback
        // (SIP→GSM via incall_music) still works if we keep NAT alive.
        // Continue sending silence RTP so the caller at least hears the agent.
        Log.e(TAG, "All capture sources exhausted — capture disabled, keeping NAT alive for playback")
        while (running.get()) {
            sendSilencePackets(5000, defaultRemoteInet)
        }
    }

    /** Build the prioritized list of capture source configs, excluding
     *  sources already detected as silent. */
    private fun buildCaptureConfigs(wideband: Boolean): List<SourceConfig> {
        val configs = mutableListOf<SourceConfig>()
        if (wideband) {
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL@16k", 16000))
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000))
        } else {
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000))
        }
        configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION", 8000))
        configs.add(SourceConfig(MediaRecorder.AudioSource.MIC, "MIC", 8000))
        configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION", 8000))
        if (wideband) {
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK@16k", 16000))
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000))
        } else {
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000))
        }
        return configs.filterNot { it.source in silentSourceIds }
    }

    /**
     * Send silence RTP packets for the specified duration (ms).
     *
     * Keeps the NAT pinhole alive and prevents Asterisk's RTP timeout
     * while AudioRecord is unavailable on cold boot.  Uses proper RTP
     * sequencing (txSequence/txTimestamp) so captureLoop() can take
     * over seamlessly when AudioRecord finally initializes.
     */
    private fun sendSilencePackets(durationMs: Long, defaultRemoteInet: InetAddress) {
        val intervalMs = 20L  // one RTP frame = 20ms
        val end = System.currentTimeMillis() + durationMs

        // Pre-encode silence for the negotiated codec (reused every frame)
        val silencePayload = when (payloadType) {
            RtpPacket.PT_PCMA -> ByteArray(160) { 0xD5.toByte() }  // A-law silence
            else -> ByteArray(160)  // G.722: zeros → near-silence
        }

        while (System.currentTimeMillis() < end && running.get()) {
            try {
                val destAddr = latchedAddr ?: defaultRemoteInet
                val destPort = if (latchedAddr != null) latchedPort else remotePort

                val packet = RtpPacket(payloadType, txSequence, txTimestamp, txSsrc, silencePayload)
                val data = packet.encode()
                socket?.send(DatagramPacket(data, data.size, destAddr, destPort))

                txSequence = (txSequence + 1) and 0xFFFF
                txPacketCount++
                txTimestamp += 160  // 20ms at 8000Hz RTP clock
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Silence RTP send error: ${e.message}")
            }
            Thread.sleep(intervalMs)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "Stopping RTP session on port $localPort")

        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null

        audioTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioTrack = null

        socket?.close()
        socket = null
        jitterBuffer.clear()

        listener?.onRtpStopped()
    }

    // ── Capture: VOICE_CALL → echo gate → gain → encode → RTP send ──

    // Audio parameters from device profile
    private val profile get() = GsmCallManager.profile
    private val captureGain get() = profile.captureGain
    private val playbackGain get() = profile.playbackGain

    // Double-talk detection: VOICE_CALL captures uplink+downlink from
    // the modem DSP.  The uplink contains the SIP agent's voice (injected
    // via incall_music).  Instead of a hard echo gate (which made the
    // bridge half-duplex — caller COMPLETELY silenced during agent speech),
    // we adaptively estimate the echo level and detect when the caller is
    // speaking simultaneously (double-talk / barge-in).
    //
    // How it works: incall_music injects AudioTrack digitally into the
    // modem uplink with a consistent gain ratio.  We track that ratio
    // (echoGainRatio = captureRMS / playbackRMS) during echo-only frames.
    // When captureRMS significantly exceeds the expected echo level,
    // the caller must be speaking — forward the audio (some echo leaks
    // but the caller is audible).  This enables full-duplex barge-in.
    private val echoGateThreshold get() = profile.echoGateThreshold
    @Volatile private var currentPlaybackActive = false

    // Decaying echo reference: instead of a hard on/off echo gate, we
    // track a decaying playback RMS that persists through brief inter-word
    // gaps.  When the agent pauses between words (50-100ms), the echo
    // tail in the capture pipeline still has energy.  Without decay, the
    // gate flips off and the echo tail passes the noise gate, reaching
    // the agent as "caller speech" = false interruption.
    //
    // Decay factor 0.80 per 20ms frame:
    //   50ms (2-3 frames): ~64% of peak → echo tail suppressed
    //   100ms (5 frames):  ~33% → still suppressed
    //   200ms (10 frames): ~11% → fading, real speech passes easily
    //   300ms (15 frames): ~3%  → effectively zero
    // This allows barge-in after ~200ms while suppressing echo tails.
    private var decayingPlaybackRms = 0

    // Adaptive echo gain: ratio of capture RMS to playback RMS during
    // confirmed echo-only frames.  Updated via exponential moving average.
    // Initial 1.0 = assume 1:1 coupling; adapts to actual modem DSP gain.
    @Volatile private var echoGainRatio = 1.0f
    private var echoGainSamples = 0
    private val DOUBLE_TALK_RATIO get() = profile.doubleTalkRatio

    // Noise gate: below this RMS, send silence instead of captured audio.
    // Threshold is device-specific (modem DSP noise floor varies).
    private val noiseGateThreshold get() = profile.noiseGateThreshold

    // Diagnostic counters: track how frames are classified for stats logging
    @Volatile private var echoGatedFrames = 0L
    @Volatile private var noiseGatedFrames = 0L
    @Volatile private var forwardedFrames = 0L
    @Volatile private var doubleTalkFrames = 0L  // caller spoke during agent playback

    // CPU tracking: process CPU ticks at last sample for delta calculation
    private var lastCpuTicks = 0L
    private var lastCpuSampleMs = 0L

    /**
     * Capture loop: read from AudioRecord, gate, encode, send via RTP.
     * Returns true on normal exit (call ended), false if the source was
     * detected as silent (rawCapRMS near zero for 3 seconds) and should
     * be replaced with a fallback source.
     */
    private fun captureLoop(): Boolean {
        val record = audioRecord ?: return false

        record.startRecording()
        Log.i(TAG, "Capture started: source=$audioSourceName capRate=$captureRate session=$audioSessionId gain=${captureGain}x profile=${profile.name} state=${record.recordingState}")
        // Also report via RTP stats so it appears in the app log viewer
        listener?.onRtpStats("Capture: source=$audioSourceName rate=$captureRate gain=${captureGain}x profile=${profile.name}")

        // Buffer: 20ms of PCM at the actual capture sample rate
        val samplesPerFrame = captureRate / 50  // 160 @ 8kHz, 320 @ 16kHz
        val pcmBuf = ByteArray(samplesPerFrame * 2)
        val defaultRemoteInet = InetAddress.getByName(remoteAddr)
        var silenceFrameCount = 0

        while (running.get()) {
            try {
                val read = record.read(pcmBuf, 0, pcmBuf.size)
                if (read <= 0) continue

                // Measure raw capture level BEFORE echo gate for diagnostics.
                // If rawCaptureRms=0, the audio source itself is silent
                // (HAL/modem not providing audio).  If rawCaptureRms>0 but
                // captureRms=0, the echo gate is suppressing.
                rawCaptureRms = pcmRms(pcmBuf)

                // Silence detection: only count during non-echo periods (when
                // decayingPlaybackRms <= echoGateThreshold).  incall_music echo
                // leaks back through VOICE_CALL capture, spiking rawCapRMS
                // above the threshold during agent speech — this would reset a
                // naive counter even though the source delivers NO caller audio.
                // By only counting non-echo frames, we accurately detect dead
                // sources regardless of whether the agent is speaking.
                val noEchoPeriod = decayingPlaybackRms <= echoGateThreshold
                if (noEchoPeriod) {
                    if (rawCaptureRms < SILENCE_RMS_THRESHOLD) {
                        silenceFrameCount++
                        if (silenceFrameCount == 10 || silenceFrameCount == 20) {
                            Log.w(TAG, "Source $audioSourceName low audio (no-echo): rawCapRMS=$rawCaptureRms silence=${silenceFrameCount}/${SILENCE_FRAME_LIMIT} frames")
                        }
                        if (silenceFrameCount >= SILENCE_FRAME_LIMIT) {
                            val msg = "Source $audioSourceName SILENT ($silenceFrameCount non-echo frames) — trying fallback"
                            Log.w(TAG, msg)
                            listener?.onRtpStats(msg)
                            silentSourceIds.add(currentSourceId)
                            try { record.stop() } catch (_: Exception) {}
                            record.release()
                            audioRecord = null
                            return false  // Signal: try another source
                        }
                    } else {
                        silenceFrameCount = 0  // Source delivered audio during non-echo → working
                    }
                }
                // During echo periods: don't update counter (can't distinguish
                // caller audio from incall_music echo)

                // Double-talk-aware gating: replaces the old hard echo gate
                // that made the bridge half-duplex (caller completely silenced
                // during agent speech).  Now uses adaptive echo level estimation
                // to detect when the caller is speaking over the agent.
                //
                // VOICE_CALL captures uplink+downlink.  incall_music injects
                // the agent's voice digitally into the uplink with a consistent
                // gain ratio.  We track that ratio and detect when capture energy
                // exceeds the expected echo — that excess is the caller's voice.
                // Update decaying echo reference: tracks playback level
                // through brief inter-word gaps to suppress echo tails.
                if (currentPlaybackActive && playbackRms > 0) {
                    decayingPlaybackRms = playbackRms
                } else if (decayingPlaybackRms > 0) {
                    decayingPlaybackRms = (decayingPlaybackRms * 0.80).toInt()
                }

                val shouldForward: Boolean
                if (decayingPlaybackRms > echoGateThreshold) {
                    // Agent is speaking (or echo tail still decaying) —
                    // check for double-talk (barge-in).
                    val expectedEcho = (echoGainRatio * decayingPlaybackRms).toInt()
                        .coerceAtLeast(noiseGateThreshold)
                    if (rawCaptureRms > (expectedEcho * DOUBLE_TALK_RATIO).toInt()) {
                        // Double-talk: caller speaking over agent — forward.
                        // Some echo leaks through but caller is audible.
                        shouldForward = true
                        doubleTalkFrames++
                    } else {
                        // Echo-only: agent speaking, caller silent — send silence.
                        // Update echo gain estimate from confirmed echo frames.
                        if (currentPlaybackActive && playbackRms > 500) {
                            val r = rawCaptureRms.toFloat() / playbackRms
                            echoGainRatio = echoGainRatio * 0.95f + r * 0.05f
                            echoGainSamples++
                        }
                        shouldForward = false
                        echoGatedFrames++
                    }
                } else if (rawCaptureRms < noiseGateThreshold) {
                    // Noise gate: only modem digital noise, no caller speech.
                    shouldForward = false
                    noiseGatedFrames++
                } else {
                    // Caller speaking, agent silent — forward normally.
                    shouldForward = true
                }

                if (shouldForward) {
                    // Apply gain boost
                    if (captureGain > 1) {
                        for (i in 0 until read / 2) {
                            val lo = pcmBuf[i * 2].toInt() and 0xFF
                            val hi = pcmBuf[i * 2 + 1].toInt()
                            val sample = ((hi shl 8) or lo) * captureGain
                            val clamped = sample.coerceIn(-32768, 32767)
                            pcmBuf[i * 2] = (clamped and 0xFF).toByte()
                            pcmBuf[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
                        }
                    }
                    captureRms = pcmRms(pcmBuf)
                    forwardedFrames++
                } else {
                    java.util.Arrays.fill(pcmBuf, 0, read, 0.toByte())
                    captureRms = 0
                }

                // Encode based on codec and capture sample rate.
                // G.722 expects 16 kHz PCM; if capture is 8 kHz, upsample first.
                val encoded = when (payloadType) {
                    RtpPacket.PT_G722 -> {
                        val pcm16k = if (captureRate == 8000) upsample8kTo16k(pcmBuf) else pcmBuf
                        g722Encoder.encode(pcm16k)
                    }
                    RtpPacket.PT_PCMA -> {
                        if (captureRate == 8000) PcmaCodec.encode8k(pcmBuf)
                        else PcmaCodec.encode(pcmBuf)
                    }
                    else -> {
                        val pcm16k = if (captureRate == 8000) upsample8kTo16k(pcmBuf) else pcmBuf
                        g722Encoder.encode(pcm16k)
                    }
                }

                // Log first 3 packets with raw PCM + encoded for debugging
                if (txPacketCount < 3) {
                    val hexHead = encoded.take(16).joinToString(" ") { "%02X".format(it) }
                    // Raw PCM hex: first 32 bytes (16 samples) to confirm buffer content
                    val pcmHex = pcmBuf.take(32).joinToString(" ") { "%02X".format(it) }
                    Log.i(TAG, "TX#$txPacketCount: rawRMS=$rawCaptureRms capRMS=$captureRms pcm=[$pcmHex] enc=[$hexHead]")
                    if (txPacketCount == 0L) firstTxInfo = "capRMS=$captureRms enc=$hexHead"
                }

                val destAddr = latchedAddr ?: defaultRemoteInet
                val destPort = if (latchedAddr != null) latchedPort else remotePort

                val packet = RtpPacket(payloadType, txSequence, txTimestamp, txSsrc, encoded)
                val data = packet.encode()
                socket?.send(DatagramPacket(data, data.size, destAddr, destPort))

                txSequence = (txSequence + 1) and 0xFFFF
                txPacketCount++
                txTimestamp += 160 // 20ms at 8000Hz RTP clock
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Capture error: ${e.message}")
            }
        }
        return true  // Normal exit (call ended)
    }

    // ── Receive: RTP recv → jitter buffer ───────────────

    private fun receiveLoop() {
        val buf = ByteArray(4096)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket?.receive(packet) ?: break
                val rtp = RtpPacket.decode(buf, packet.length) ?: continue

                // Symmetric RTP: latch onto the actual source address
                if (latchedAddr == null) {
                    latchedAddr = packet.address
                    latchedPort = packet.port
                    Log.i(TAG, "Symmetric RTP: latched to ${packet.address.hostAddress}:${packet.port}")
                }

                lastRtpReceivedTime = System.currentTimeMillis()
                rxPacketCount++
                // Log first packet details for debugging
                if (rxPacketCount == 1L) {
                    val hexHead = rtp.payload.take(16).joinToString(" ") { "%02X".format(it) }
                    firstRxInfo = "pt=${rtp.payloadType} len=${rtp.payload.size} hex=$hexHead"
                    Log.i(TAG, "First RX: $firstRxInfo")
                }
                if (rtp.payloadType == payloadType || rtp.payloadType == RtpPacket.PT_PCMA || rtp.payloadType == RtpPacket.PT_G722) {
                    if (!jitterBuffer.offer(rtp.payload)) {
                        jitterBuffer.poll() // drop oldest
                        jitterBuffer.offer(rtp.payload)
                    }
                }
            } catch (_: SocketTimeoutException) {
                // normal
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Receive error: ${e.message}")
            }
        }
    }

    // ── RTP inactivity timeout ─────────────────────────

    private fun timeoutLoop() {
        // Early re-assertion at 3s: combat Android re-revoking RECORD_AUDIO
        // when screen is off, and re-toggle incall_music after speaker route
        // is fully settled (~1.5s after configureAudioBridge).
        try { Thread.sleep(3_000) } catch (_: InterruptedException) { return }
        if (running.get()) {
            reAssertAppOps()
            reToggleIncallMusic()
        }

        while (running.get()) {
            try {
                // 15s interval (was 5s) — each appops su call spawns a JVM
                // (~500ms on MSM8930).  15s is sufficient to catch screen-off
                // revocations while reducing CPU load by 3x.
                Thread.sleep(15_000)

                // Periodic appops re-assertion: Android's AppOpsService
                // re-revokes RECORD_AUDIO for background apps when screen
                // is off.  Re-asserting periodically keeps capture alive.
                reAssertAppOps()

                // Log detailed stats every 5s
                val extraInfo = buildString {
                    if (firstRxInfo.isNotEmpty() && txPacketCount < 500) append(" [RX: $firstRxInfo]")
                    if (firstTxInfo.isNotEmpty() && txPacketCount < 500) append(" [TX: $firstTxInfo]")
                }
                // Include current volume state for debugging
                val volInfo = try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.let {
                        val vc = it.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                        val vm = it.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val muted = it.isStreamMute(AudioManager.STREAM_VOICE_CALL)
                        val micMute = it.isMicrophoneMute
                        " vol:vc=$vc(m=$muted),mu=$vm mic=$micMute"
                    } ?: ""
                } catch (_: Exception) { "" }
                // CPU/memory/thread diagnostics
                val cpuInfo = getCpuStats()
                val stats = "tx=$txPacketCount rx=$rxPacketCount play=$playbackFrames " +
                        "capRMS=$captureRms rawCapRMS=$rawCaptureRms playRMS=$playbackRms src=$audioSourceName " +
                        "rate=${captureRate}/${playbackRate} jbuf=${jitterBuffer.size} " +
                        "gates:echo=$echoGatedFrames noise=$noiseGatedFrames fwd=$forwardedFrames dt=$doubleTalkFrames echoG=${"%.2f".format(echoGainRatio)}" +
                        "$cpuInfo$volInfo" + extraInfo
                Log.i(TAG, "RTP: $stats")
                listener?.onRtpStats(stats)

                val elapsed = System.currentTimeMillis() - lastRtpReceivedTime
                if (elapsed > RTP_TIMEOUT_MS) {
                    Log.w(TAG, "RTP timeout: no packets received for ${elapsed / 1000}s")
                    listener?.onRtpTimeout()
                    break
                }
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    // ── Playback: jitter buffer → decode → speaker → mic → GSM uplink ──

    private fun playbackLoop() {
        val track = audioTrack ?: return

        // No prefill — the silence-frame loop below feeds the AudioTrack
        // continuously, preventing underruns.  Removing the old 20ms prefill
        // saves that much initial latency.
        track.play()
        Log.i(TAG, "Playback started (rate=$playbackRate usage=$playbackUsageName deepBuffer=true)")

        // CRITICAL: Set incall_music_enabled=true AFTER AudioTrack.play().
        // The Qualcomm HAL starts the incall-music usecase only when there
        // is an active STREAM_MUSIC output.  If set before AudioTrack exists,
        // the HAL routes through deep-buffer-playback instead of incall-music,
        // and the audio never reaches the voice TX (uplink).
        enableIncallMusic()
        // Set mixer controls via root — also handles Voice Tx Mute=0.
        // No separate ensureVoiceTxOpen() call here: the mixer thread below
        // already issues that command, and waiting for su -c was blocking
        // the playback thread for ~100ms.
        enableIncallMusicViaMixer()

        // Silence frame for when jitter buffer is empty — prevents underruns
        // that cause BUFFER TIMEOUT and AudioTrack disable/restart cycles.
        val silenceFrame = ByteArray(playbackRate * 2 / 50) // 20ms of silence

        // Track silence→speech transitions for fade-in.
        // Modem DSP AGC/DTX settles during silence; abrupt speech onset
        // gets over-amplified causing distortion at the start of each phrase.
        // A short fade-in smooths the transition.
        var wasPlayingSilence = true

        while (running.get()) {
            try {
                // Drain excess packets to bound latency.  When network
                // jitter causes burst arrivals, the buffer can accumulate.
                // Keep at most 5 (100ms) — enough headroom to absorb
                // jitter without audible gaps.  100ms is still well within
                // the GSM bridge's inherent latency budget.
                while (jitterBuffer.size > 5) {
                    jitterBuffer.poll()
                }

                val encoded = jitterBuffer.poll(18, TimeUnit.MILLISECONDS)
                if (encoded == null) {
                    // Write silence to keep AudioTrack fed and prevent underruns.
                    track.write(silenceFrame, 0, silenceFrame.size)
                    playbackRms = 0
                    currentPlaybackActive = false
                    wasPlayingSilence = true
                    continue
                }

                // Decode based on codec.  G.722 outputs 16 kHz natively;
                // PCMA always decodes to 8 kHz (playbackRate==8000).
                val pcm = when (payloadType) {
                    RtpPacket.PT_G722 -> g722Decoder.decodeToBytes(encoded)
                    RtpPacket.PT_PCMA -> {
                        if (playbackRate == 8000) PcmaCodec.decode8k(encoded)
                        else PcmaCodec.decode(encoded)
                    }
                    else -> g722Decoder.decodeToBytes(encoded)
                }

                // Fade-in after silence: prevents modem DSP AGC spike that
                // causes distorted/harsh beginning of each agent phrase.
                // 5ms ramp (40 samples @ 8kHz) is enough to smooth the onset.
                if (wasPlayingSilence) {
                    applyFadeIn(pcm)
                    wasPlayingSilence = false
                }

                // Measure RMS BEFORE gain for the echo gate.  The echo gate
                // threshold was calibrated to raw codec output levels.  If
                // playbackGain > 1, measuring after gain would lower the
                // effective threshold, over-suppressing caller speech.
                val rawRms = pcmRms(pcm)
                currentPlaybackActive = rawRms > echoGateThreshold

                // Software gain: boost PCM before writing to AudioTrack.
                // This increases the digital level injected via incall_music
                // without changing STREAM_MUSIC volume (which clips at >14%).
                if (playbackGain > 1) {
                    for (i in 0 until pcm.size / 2) {
                        val lo = pcm[i * 2].toInt() and 0xFF
                        val hi = pcm[i * 2 + 1].toInt()
                        val sample = ((hi shl 8) or lo) * playbackGain
                        val clamped = sample.coerceIn(-32768, 32767)
                        pcm[i * 2] = (clamped and 0xFF).toByte()
                        pcm[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
                    }
                }

                playbackRms = if (playbackGain > 1) pcmRms(pcm) else rawRms
                track.write(pcm, 0, pcm.size)
                playbackFrames++
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Playback error: ${e.message}")
            }
        }
    }

    /**
     * Apply a 5ms linear fade-in to the start of a PCM buffer.
     * Smooths the silence→speech transition that otherwise causes
     * the modem's uplink AGC/DTX to over-amplify the first samples.
     */
    private fun applyFadeIn(pcm: ByteArray, fadeMs: Int = 5) {
        val fadeSamples = playbackRate * fadeMs / 1000
        val totalSamples = pcm.size / 2
        val count = fadeSamples.coerceAtMost(totalSamples)
        for (i in 0 until count) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            val faded = (sample.toLong() * (i + 1) / count).toInt().coerceIn(-32768, 32767)
            pcm[i * 2] = (faded and 0xFF).toByte()
            pcm[i * 2 + 1] = ((faded shr 8) and 0xFF).toByte()
        }
    }

    /**
     * Upsample 8 kHz PCM16 to 16 kHz using 4-tap Catmull-Rom interpolation.
     * Each input sample produces two output samples: the original sample
     * and a midpoint computed as (-s[i-1] + 9*s[i] + 9*s[i+1] - s[i+2]) / 16.
     *
     * This provides ~35 dB spectral image rejection vs ~6 dB for simple
     * linear interpolation, suppressing artifacts in the 4-8 kHz band that
     * G.722's upper sub-band would otherwise encode as spurious content.
     */
    private fun upsample8kTo16k(input: ByteArray): ByteArray {
        val n = input.size / 2
        val output = ByteArray(n * 4) // 2x samples, 2 bytes each

        // Read all input samples into an array for random access
        val s = IntArray(n)
        for (i in 0 until n) {
            val lo = input[i * 2].toInt() and 0xFF
            val hi = input[i * 2 + 1].toInt()
            s[i] = (hi shl 8) or lo
        }

        for (i in 0 until n) {
            // Even output: original sample (pass-through)
            val even = s[i]

            // Odd output: 4-tap Catmull-Rom midpoint interpolation
            val sm1 = if (i > 0) s[i - 1] else s[0]
            val s0 = s[i]
            val s1 = if (i + 1 < n) s[i + 1] else s[n - 1]
            val s2 = if (i + 2 < n) s[i + 2] else s[n - 1]
            val mid = ((-sm1 + 9 * s0 + 9 * s1 - s2 + 8) shr 4).coerceIn(-32768, 32767)

            output[i * 4] = (even and 0xFF).toByte()
            output[i * 4 + 1] = ((even shr 8) and 0xFF).toByte()
            output[i * 4 + 2] = (mid and 0xFF).toByte()
            output[i * 4 + 3] = ((mid shr 8) and 0xFF).toByte()
        }
        return output
    }

    /** Compute RMS level of PCM16 little-endian audio buffer (0-32767) */
    private fun pcmRms(pcm: ByteArray): Int {
        val sampleCount = pcm.size / 2
        if (sampleCount == 0) return 0
        var sumSquares = 0L
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSquares += sample.toLong() * sample
        }
        return Math.sqrt(sumSquares.toDouble() / sampleCount).toInt()
    }

    /**
     * Set incall_music_enabled=true via AudioManager.
     * Must be called AFTER AudioTrack.play() so the HAL has an active
     * STREAM_MUSIC output to route through the incall-music usecase.
     *
     * Also re-enforces stream volumes here as a secondary safeguard.
     * GsmCallManager sets volumes in configureAudioBridge(), but Android's
     * AudioPolicyManager can reset them when the speaker route change
     * completes.  By the time we get here (after AudioTrack.play()),
     * the route change is long finished, so our volume sticks.
     *
     * On Samsung Exynos (ABOX HAL), the audio_hw_proxy should handle
     * incall_music_enabled internally by routing SIFS→NSRC→voice TX.
     * We also try Samsung-specific parameter names as fallbacks in case
     * the standard parameter is not implemented in this LineageOS build.
     */
    private fun enableIncallMusic() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                // Force a false→true transition to start the incall-music
                // usecase in the HAL.  configureAudioBridge() no longer
                // primes true (it caused problems: the earpiece→speaker
                // route change would tear down the voice path and lose the
                // incall-music state, then the second true here was a no-op).
                //
                // By this point:
                //  1. AudioTrack.play() has started (active STREAM_MUSIC output)
                //  2. Speaker route is settled (configureAudioBridge ran 1+ sec ago)
                //  3. restoreAudio from previous call set false (clean slate)
                //
                // The explicit false first ensures the HAL processes it as a
                // genuine state transition, even if some stale true leaked.
                // 50ms gap lets the HAL fully tear down before re-creating.
                val param = profile.incallMusicParam
                if (param.isNotEmpty()) {
                    it.setParameters("${param}=false")
                    Thread.sleep(50)
                    it.setParameters("${param}=true")
                }

                // Samsung Exynos: additional HAL params for incall music injection.
                // Only when incallMusicParam is configured (empty = skip to avoid
                // breaking VOICE_CALL capture).
                // Samsung Exynos: additional HAL params for incall music.
                // These are "best effort" — on Exynos 9820, no visible mixer
                // effect is observed, but they may help on other Exynos devices.
                if (profile.name.contains("Exynos") && param.isNotEmpty()) {
                    it.setParameters("g_call_path=on")
                    it.setParameters("abox_incall_music=on")
                    it.setParameters("incall_music=1")
                }

                GsmCallManager.enforceVolumes(it)

                val msg = "incall_music: param=${param.ifEmpty { "NONE" }}, mode=${it.mode}" +
                    if (param.isNotEmpty() && profile.name.contains("Exynos")) " +g_call_path +abox_incall_music +incall_music=1" else ""
                Log.i(TAG, msg)
                listener?.onRtpStats(msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set incall_music_enabled: ${e.message}")
            listener?.onRtpStats("incall_music FAILED: ${e.message}")
        }
    }

    /**
     * Fallback: use root (Magisk) to set incall_music mixer controls
     * directly via tinymix.  Device-specific commands from the profile.
     *
     * The bare 'tinymix' in profile commands is resolved to the
     * discovered full path via [DeviceProfile.resolveCmd].
     */
    private fun enableIncallMusicViaMixer() {
        val mixerCmd = profile.mixerIncallMusicCmd
        if (mixerCmd.isEmpty()) {
            Log.i(TAG, "Mixer: no incall_music commands for ${profile.name}")
            return
        }
        val resolvedMixerCmd = DeviceProfile.resolveCmd(mixerCmd)
        if (resolvedMixerCmd.isEmpty()) {
            val msg = "Mixer: tinymix not found — cannot set incall_music mixer"
            Log.e(TAG, msg)
            listener?.onRtpStats(msg)
            return
        }
        Thread({
            try {
                // Run incall_music mixer commands, then readback key controls (card 0)
                val bin = DeviceProfile.tinymixBin
                val cmd = "$resolvedMixerCmd; " +
                    "echo 'NSRC1B:'; $bin 'ABOX NSRC1 Bridge' 2>&1; " +
                    "echo 'NSRC1:'; $bin 'ABOX NSRC1' 2>&1; " +
                    "echo 'NSRC0:'; $bin 'ABOX NSRC0' 2>&1; " +
                    "echo 'SPUS0:'; $bin 'ABOX SPUS OUT0' 2>&1"
                val output = RootShell.execForOutput(cmd, timeoutMs = 8000)
                val msg = "Mixer incall_music: $output"
                Log.i(TAG, msg)
                listener?.onRtpStats(msg)
            } catch (e: Exception) {
                Log.w(TAG, "Mixer fallback failed: ${e.message}")
                listener?.onRtpStats("Mixer incall_music FAILED: ${e.message}")
            }
        }, "RTP-Mixer").start()
    }

    /**
     * Re-assert RECORD_AUDIO appops via root.  Android's AppOpsService
     * re-revokes this permission for background apps when the screen turns
     * off, killing VOICE_CALL capture (rawCapRMS drops to ~6).  Called
     * at 3s and then every 5s from timeoutLoop to keep capture alive.
     *
     * CRITICAL: Must use --uid flag to set the UID-level mode.
     * `appops set <pkg>` sets the package mode, but AudioFlinger checks
     * the UID mode (set by PermissionController).  UID mode overrides
     * package mode.  Without --uid, the command "succeeds" (exit=0) but
     * AudioFlinger still denies with "Request denied by app op: 27".
     */
    private fun reAssertAppOps() {
        try {
            val pkg = context.packageName
            val t0 = System.currentTimeMillis()
            // Use execForOutput to capture stderr/stdout from appops commands.
            // Previous approach hid all errors and put killall last (exit=1 always).
            // Now: appops get --uid is the LAST command so exit code is meaningful,
            // and all errors are captured via 2>&1.
            // AUTO_REVOKE_PERMISSIONS_IF_UNUSED: Android 11+ (API 30)
            // appops --uid flag: Android 10+ (API 29)
            val autoRevoke = if (Build.VERSION.SDK_INT >= 30)
                "appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>&1; " else ""
            val uidFlag = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
            val result = RootShell.execForOutput(
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "pm grant $pkg android.permission.RECORD_AUDIO 2>&1; " +
                autoRevoke +
                "appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                "appops set $pkg RECORD_AUDIO allow 2>&1; " +
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
            )
            val elapsed = System.currentTimeMillis() - t0
            val allowed = result.contains("allow", ignoreCase = true)
            Log.i(TAG, "appops re-assert: [$result] ok=$allowed (${elapsed}ms)")

            if (!allowed) {
                // Fallback: try cmd appops (different IPC path to AppOpsService)
                val fb = RootShell.execForOutput(
                    "cmd appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops set $pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
                )
                Log.w(TAG, "appops fallback cmd: [$fb]")
            } else {
                Log.d(TAG, "appops RECORD_AUDIO verified: allow")
            }
        } catch (e: Exception) {
            Log.w(TAG, "appops re-assert failed: ${e.message}")
        }
    }

    /**
     * Re-toggle incall_music_enabled false→true as a safety net.
     * Called ~3s after RTP start when the speaker route change is
     * guaranteed to be complete.  Handles edge cases where the initial
     * toggle in enableIncallMusic() fired before the route settled.
     */
    private fun reToggleIncallMusic() {
        val param = profile.incallMusicParam
        if (param.isEmpty()) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                it.setParameters("${param}=false")
                Thread.sleep(50)
                it.setParameters("${param}=true")
                GsmCallManager.enforceVolumes(it)
                Log.i(TAG, "${param} re-toggled (3s safety), mode=${it.mode}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "${param} re-toggle failed: ${e.message}")
        }
    }

    /**
     * Log diagnostic info about capture capability:
     * Phase 1: NSRC routing + bridge state
     * Phase 2: mixer_paths.xml incall_music voice path definitions
     * Phase 3: /proc/asound capture/playback PCM status
     * Phase 6: Delayed NSRC re-check (t+5s)
     * Phase 7: ALSA capture PCM probe (tinycap, if available)
     */
    private fun logCaptureDiagnostics(record: AudioRecord) {
        try {
            // Check CAPTURE_AUDIO_OUTPUT (system permission, not runtime)
            val hasCaptureOutput = context.checkCallingOrSelfPermission(
                "android.permission.CAPTURE_AUDIO_OUTPUT"
            ) == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Diag: CAPTURE_AUDIO_OUTPUT=$hasCaptureOutput " +
                "session=${record.audioSessionId} state=${record.state} " +
                "recState=${record.recordingState} source=$audioSourceName")
            listener?.onRtpStats("Diag: CAPTURE_AUDIO_OUTPUT=$hasCaptureOutput")

            // Comprehensive ABOX routing dump via root
            val tinymix = DeviceProfile.tinymixBin
            if (tinymix.isNotEmpty()) {
                Thread({
                    try {
                        // Phase 1: NSRC routing + bridge state
                        val routingCmd = buildString {
                            append("echo '=== NSRC routing ==='; ")
                            for (i in 0..2) {
                                append("echo -n 'NSRC${i}='; $tinymix 'ABOX NSRC${i}' 2>/dev/null || echo 'N/A'; ")
                                append("echo -n 'NSRC${i}_Bridge='; $tinymix 'ABOX NSRC${i} Bridge' 2>/dev/null || echo 'N/A'; ")
                            }
                            append("echo -n 'SoundType='; $tinymix 'ABOX Sound Type' 2>/dev/null || echo 'N/A'")
                        }
                        val routing = RootShell.execForOutput(routingCmd, timeoutMs = 8000)
                        for (line in routing.lines().filter { it.isNotBlank() }) {
                            Log.i(TAG, "Diag: $line")
                        }

                        // Phase 2: mixer_paths.xml — voice call & incall_music paths
                        val mixerPathsCmd = buildString {
                            append("echo '=== mixer_paths voice/incall ==='; ")
                            // Search for incall_music path definitions
                            append("for f in /vendor/etc/mixer_paths*.xml /vendor/etc/audio/mixer_paths*.xml; do ")
                            append("  if [ -f \"\$f\" ]; then ")
                            append("    echo \"--- \$f ---\"; ")
                            // Grep for incall_music path (if it exists, shows what controls to set)
                            append("    grep -B2 -A15 'incall.music\\|incallmusic' \"\$f\" 2>/dev/null | head -40; ")
                            // Also show voice_call path for comparison
                            append("    echo '--- voice_call path ---'; ")
                            append("    grep -B1 -A10 'name=\"voice-call\"\\|name=\"voice_call\"' \"\$f\" 2>/dev/null | head -30; ")
                            // Search for any path that mentions UAIF (modem TX)
                            append("    echo '--- UAIF paths ---'; ")
                            append("    grep -i 'uaif' \"\$f\" 2>/dev/null | head -20; ")
                            append("  fi; done")
                        }
                        val mixerPaths = RootShell.execForOutput(mixerPathsCmd, timeoutMs = 5000)
                        for (line in mixerPaths.lines().filter { it.isNotBlank() }) {
                            Log.i(TAG, "Diag: $line")
                        }

                        // Phase 3: Active capture PCMs + hw_params
                        val pcmCmd = buildString {
                            append("echo '=== capture PCMs ==='; ")
                            append("for f in /proc/asound/card0/pcm*c/sub0/status; do ")
                            append("s=\$(head -1 \$f 2>/dev/null); d=\${f%/status}; n=\${d##*/}; ")
                            append("echo \"\$n: \$s\"; ")
                            append("if echo \"\$s\" | grep -q RUNNING; then ")
                            append("echo \"  hw: \$(cat \${d}/hw_params 2>/dev/null | head -5 | tr '\\n' ' ')\"; ")
                            append("fi; done; ")
                            // Also dump playback PCMs
                            append("echo '=== playback PCMs ==='; ")
                            append("for f in /proc/asound/card0/pcm*p/sub0/status; do ")
                            append("s=\$(head -1 \$f 2>/dev/null); d=\${f%/status}; n=\${d##*/}; ")
                            append("if echo \"\$s\" | grep -q RUNNING; then ")
                            append("echo \"\$n: \$s  hw: \$(cat \${d}/hw_params 2>/dev/null | head -3 | tr '\\n' ' ')\"; ")
                            append("fi; done")
                        }
                        val pcmResult = RootShell.execForOutput(pcmCmd, timeoutMs = 3000)
                        for (line in pcmResult.lines().filter { it.isNotBlank() }) {
                            Log.i(TAG, "Diag: $line")
                        }

                        // Phase 4-5 removed in v2.8.43 — routing map fully
                        // established (Madera codec, SLIMTX, HPOUT, DSP, ASRC).

                        // Phase 6: Delayed re-check (5s) — see if routing changes
                        // after enableIncallMusic/enableIncallMusicViaMixer run.
                        Thread.sleep(5000)
                        if (running.get()) {
                            val recheck = buildString {
                                append("echo '=== NSRC re-check (t+5s) ==='; ")
                                for (i in 0..2) {
                                    append("echo -n 'NSRC${i}='; $tinymix 'ABOX NSRC${i}' 2>/dev/null || echo 'N/A'; ")
                                    append("echo -n 'NSRC${i}_Bridge='; $tinymix 'ABOX NSRC${i} Bridge' 2>/dev/null || echo 'N/A'; ")
                                }
                                append("echo -n 'SoundType='; $tinymix 'ABOX Sound Type' 2>/dev/null || echo 'N/A'")
                            }
                            val recheckResult = RootShell.execForOutput(recheck, timeoutMs = 8000)
                            for (line in recheckResult.lines().filter { it.isNotBlank() }) {
                                Log.i(TAG, "Diag: $line")
                            }
                        }

                        // Phase 7: Probe ALSA capture PCMs for non-zero audio data.
                        // All AudioRecord sources return silence (rawCapRMS=0) on this
                        // device.  This probe reads raw bytes from each RUNNING capture
                        // PCM via /proc/asound to find which device has modem downlink
                        // audio (for future direct ALSA capture implementation).
                        if (running.get()) {
                            val probeCmd = buildString {
                                append("echo '=== ALSA capture PCM probe ==='; ")
                                // List all capture PCM devices with their card/device IDs
                                append("for d in /proc/asound/card0/pcm*c; do ")
                                append("  n=\${d##*/}; s=\$(head -1 \$d/sub0/status 2>/dev/null); ")
                                append("  echo \"\$n: \$s\"; ")
                                append("  if echo \"\$s\" | grep -q RUNNING; then ")
                                // Read hw_params to know format/channels/rate
                                append("    echo \"  hw_params: \$(cat \$d/sub0/hw_params 2>/dev/null | tr '\\n' ' ' | head -c 200)\"; ")
                                // Try to read from the PCM device and check for non-zero data.
                                // Extract device number from pcmNNNc format.
                                append("    devnum=\${n#pcm}; devnum=\${devnum%c}; ")
                                // Use tinycap if available, otherwise try dd from device node
                                append("    if [ -x /data/local/tmp/tinycap ]; then ")
                                append("      timeout 1 /data/local/tmp/tinycap /data/local/tmp/probe_\$n.raw -D 0 -d \$devnum -c 4 -r 48000 -b 16 -T 1 2>&1; ")
                                append("      if [ -f /data/local/tmp/probe_\$n.raw ]; then ")
                                append("        sz=\$(wc -c < /data/local/tmp/probe_\$n.raw); ")
                                append("        nz=\$(od -An -tx1 /data/local/tmp/probe_\$n.raw | tr ' ' '\\n' | grep -v '^00\$' | grep -v '^\$' | wc -l); ")
                                append("        echo \"  probe: \${sz}B, \${nz} non-zero bytes\"; ")
                                append("        rm -f /data/local/tmp/probe_\$n.raw; ")
                                append("      fi; ")
                                append("    else ")
                                append("      echo '  tinycap not found — push to /data/local/tmp/tinycap'; ")
                                append("    fi; ")
                                append("  fi; ")
                                append("done")
                            }
                            val probeResult = RootShell.execForOutput(probeCmd, timeoutMs = 15000)
                            for (line in probeResult.lines().filter { it.isNotBlank() }) {
                                Log.i(TAG, "Diag: $line")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Diag routing check failed: ${e.message}")
                    }
                }, "CaptureDiag").start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Capture diagnostics failed: ${e.message}")
        }
    }

    /**
     * Read CPU/memory/thread stats for diagnostics.
     * - System load from /proc/loadavg (1/5/15 min averages; >2.0 = overloaded on dual-core)
     * - Process CPU% from /proc/self/stat utime+stime delta over sample interval
     * - JVM heap usage and thread count
     */
    private fun getCpuStats(): String {
        return try {
            val sb = StringBuilder()

            // /proc/loadavg is blocked by SELinux (proc_loadavg) on priv_app.
            // Process CPU% — read utime(14) + stime(15) from /proc/self/stat
            // These are in clock ticks (typically 100 Hz on ARM).
            try {
                val statFields = java.io.File("/proc/self/stat").readText().trim()
                    .substringAfter(") ")  // skip past "(comm) "
                    .split(" ")
                // Fields after ") ": index 0=state, ..., 11=utime, 12=stime
                val utime = statFields.getOrNull(11)?.toLongOrNull() ?: 0L
                val stime = statFields.getOrNull(12)?.toLongOrNull() ?: 0L
                val totalTicks = utime + stime
                val nowMs = System.currentTimeMillis()

                if (lastCpuSampleMs > 0) {
                    val tickDelta = totalTicks - lastCpuTicks
                    val msDelta = nowMs - lastCpuSampleMs
                    if (msDelta > 0) {
                        // Convert ticks to ms (100 ticks/sec = 10ms/tick)
                        val cpuMs = tickDelta * 10
                        val cpuPct = (cpuMs * 100) / msDelta
                        sb.append(" cpu=${cpuPct}%")
                    }
                }
                lastCpuTicks = totalTicks
                lastCpuSampleMs = nowMs
            } catch (_: Exception) {}

            // Thread count and JVM heap
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val maxMb = rt.maxMemory() / (1024 * 1024)
            val threads = Thread.activeCount()
            sb.append(" mem=${usedMb}/${maxMb}M thr=$threads")

            sb.toString()
        } catch (_: Exception) { "" }
    }

    companion object {
        private const val TAG = "RtpSession"
    }
}

/**
 * G.711 A-law (PCMA) codec — fallback if G.722 is not negotiated.
 * Narrowband (8 kHz), adequate for basic voice.
 */
object PcmaCodec {
    private val ALAW_ENCODE_TABLE = intArrayOf(
        1, 1, 2, 2, 3, 3, 3, 3,
        4, 4, 4, 4, 4, 4, 4, 4,
        5, 5, 5, 5, 5, 5, 5, 5,
        5, 5, 5, 5, 5, 5, 5, 5,
        6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7
    )

    fun encodeSample(pcm: Short): Byte {
        var sample = pcm.toInt()
        val sign = (sample shr 8) and 0x80
        if (sign != 0) sample = -sample
        if (sample > 32635) sample = 32635

        val exponent = if (sample >= 256) ALAW_ENCODE_TABLE[(sample shr 8) and 0x7F]
        else ALAW_ENCODE_TABLE[(sample shr 4) and 0x1F].let { if (sample < 16) 0 else it }

        val mantissa = if (exponent > 0) (sample shr (exponent + 3)) and 0x0F else (sample shr 4) and 0x0F
        val encoded = (sign or (exponent shl 4) or mantissa) xor 0xD5
        return encoded.toByte()
    }

    fun decodeSample(alaw: Byte): Short {
        var value = (alaw.toInt() and 0xFF) xor 0xD5
        val sign = value and 0x80
        val exponent = (value shr 4) and 7
        var mantissa = value and 0x0F

        mantissa = (mantissa shl 4) + 8
        if (exponent > 0) mantissa = (mantissa + 256) shl (exponent - 1)

        return if (sign != 0) (-mantissa).toShort() else mantissa.toShort()
    }

    /** Encode 8kHz PCM16 to A-law directly (no sample rate conversion). */
    fun encode8k(pcm: ByteArray): ByteArray {
        val sampleCount = pcm.size / 2
        val out = ByteArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort()
            out[i] = encodeSample(sample)
        }
        return out
    }

    /** Encode 16kHz PCM16 to A-law with 2:1 averaging downsample.
     *  Averages each pair of adjacent samples before encoding, acting as
     *  a simple low-pass filter that prevents aliasing artifacts from
     *  high frequencies folding back into the 4kHz output band. */
    fun encode(pcm: ByteArray): ByteArray {
        val sampleCount = pcm.size / 2
        val outSize = sampleCount / 2
        val out = ByteArray(outSize)
        var outIdx = 0
        for (i in 0 until sampleCount step 2) {
            val lo0 = pcm[i * 2].toInt() and 0xFF
            val hi0 = pcm[i * 2 + 1].toInt()
            val s0 = (hi0 shl 8) or lo0
            val s1 = if (i + 1 < sampleCount) {
                val lo1 = pcm[(i + 1) * 2].toInt() and 0xFF
                val hi1 = pcm[(i + 1) * 2 + 1].toInt()
                (hi1 shl 8) or lo1
            } else s0
            val avg = ((s0 + s1) / 2).toShort()
            out[outIdx++] = encodeSample(avg)
            if (outIdx >= outSize) break
        }
        return out
    }

    /** Decode A-law to 8kHz PCM16 directly (no sample rate conversion). */
    fun decode8k(alaw: ByteArray): ByteArray {
        val out = ByteArray(alaw.size * 2)
        var outIdx = 0
        for (byte in alaw) {
            val sample = decodeSample(byte)
            out[outIdx++] = (sample.toInt() and 0xFF).toByte()
            out[outIdx++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Decode A-law to 16kHz PCM16 with 1:2 linear-interpolation upsampling.
     *  Each input sample produces two output samples: the original and a
     *  midpoint interpolated toward the next sample.  Eliminates the
     *  aliasing/zipper noise from the previous zero-order hold approach. */
    fun decode(alaw: ByteArray): ByteArray {
        val out = ByteArray(alaw.size * 4)
        var outIdx = 0
        for (i in alaw.indices) {
            val s0 = decodeSample(alaw[i]).toInt()
            val s1 = if (i + 1 < alaw.size) decodeSample(alaw[i + 1]).toInt() else s0
            val mid = (s0 + s1) / 2
            out[outIdx++] = (s0 and 0xFF).toByte()
            out[outIdx++] = ((s0 shr 8) and 0xFF).toByte()
            out[outIdx++] = (mid and 0xFF).toByte()
            out[outIdx++] = ((mid shr 8) and 0xFF).toByte()
        }
        return out
    }
}
