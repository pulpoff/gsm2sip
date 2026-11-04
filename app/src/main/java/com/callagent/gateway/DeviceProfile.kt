package com.callagent.gateway

import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Device-specific audio profile.  Each supported device has different
 * mixer controls, volume calibration, and audio HAL behavior.
 *
 * The mixer commands are shell strings executed via RootShell in a
 * single `su` call.  Tinymix control names and default volumes are
 * entirely SoC/codec-specific.
 *
 * Auto-detection uses Build.HARDWARE and Build.BOARD.  Unknown devices
 * fall back to GenericProfile which skips mixer hacks and relies on
 * Android APIs only.
 */
data class DeviceProfile(
    val name: String,

    // ── Mixer commands (executed via RootShell) ──

    /** Shell command to set up audio bridge (mute speaker, mute mic,
     *  enable incall_music, etc.).  All 2>/dev/null for missing controls. */
    val mixerSetupCmd: String,

    /** Shell command to restore mixer state when call ends. */
    val mixerRestoreCmd: String,

    /** Shell command to enable incall_music mixer (called from RtpSession
     *  after AudioTrack.play() when route is settled). */
    val mixerIncallMusicCmd: String,

    /** Keep the phone's own speaker and microphone out of the call.
     *
     *  For a digital bridge they are not part of the audio path at all, and
     *  leaving them live is actively harmful: the microphone mixes the room
     *  into the GSM uplink, and whatever the speaker plays comes back through
     *  it, so the caller hears their own voice repeating.
     *
     *  Muting goes through AudioManager rather than the ALSA voice controls —
     *  Voice Rx/Tx Device Mute are re-applied by the HAL when it programs the
     *  voice-call mixer path and had no lasting effect. */
    val silenceLocalAudio: Boolean = false,
    /** Start capture before the incall_music playback track is created.
     *  Pixel 4a needs this order (the modem TX path otherwise waits on a
     *  voice path not yet opened -> random one-way calls).  The S4 Mini
     *  needs the opposite: capture and incall_music share its MultiMedia1
     *  front-end, and opening capture first left the caller silent
     *  (rawCapRMS 6-7 on every source, 2026-10-23) where 1.2.0, which
     *  opened playback first, captured the caller cleanly. */
    val primeCaptureBeforePlayback: Boolean = true,
    /** Give up on a capture source that has not yet carried sound once it
     *  has been silent for SILENCE_FRAME_LIMIT frames.  Off where the
     *  source is known: on the S4 Mini the caller's line is digital silence
     *  until they first speak, so a caller who listened to the agent's
     *  greeting got VOICE_RECOGNITION retired after ~5 s and the call fell
     *  through to VOICE_CALL@16k and MIC, which carry nothing there — the
     *  agent never heard them (2026-10-23, three calls). */
    val silenceFallback: Boolean = true,
    /** 5 ms fade-in on the agent's audio after a silent stretch.  Off where
     *  the call is digital end to end and the owner wants it passed through
     *  untouched (S4 Mini, 2026-10-24). */
    val playbackFadeIn: Boolean = true,

    /** Consecutive silent frames before a capture source is abandoned.
     *
     *  The default is deliberately impatient, which is right when the fallback
     *  is a microphone that always works.  Where the digital source is the only
     *  acceptable one, bailing out after half a second throws it away during
     *  the ordinary gap before the far end starts talking.
     *
     *  25 frames was the old hard-coded value and is why in-call capture looked
     *  impossible on SM6150 for a long time: the source was working, and was
     *  being discarded during the pause before either party spoke.  The default
     *  is now 100 (~2s), which is still prompt enough to fall back off a source
     *  that is genuinely dead. */
    val captureSilenceFrames: Int = 100,

    /** Voice sessions to mark ACTIVE with the audio HAL, as hex VSIDs.
     *
     *  The Qualcomm HAL gates in-call recording — and the per-session voice
     *  mutes — on voice_is_call_state_active().  On this device that flag is
     *  never set: `voice_extn: update_call_states is_call_active:0, in_call:1,
     *  mode:2` with `cur_state=1` (CALL_INACTIVE) for every VSID, even while
     *  MODE_IN_CALL is set and the VoiceMMode2 ALSA session is running.  So the
     *  HAL never starts the record session, every in-call capture source
     *  returns silence, and Voice Tx Device Mute does nothing.
     *
     *  voice_extn accepts "vsid=<hex>;call_state=<n>" and implements an
     *  INACTIVE -> ACTIVE transition, which is what this announces. */
    val halCallActiveVsids: List<String> = emptyList(),

    /** Shell command run immediately after AudioRecord.startRecording().
     *
     *  Opening a capture stream resets that front-end's mixer state, so in-call
     *  capture routing set during bridge setup is already gone by the time the
     *  stream exists.  Measured on SM6150: AudioSource.VOICE_CALL opens
     *  MultiMedia9 (pcm27c), and that front-end's VOC_REC_DL/UL read Off once
     *  capture is running even though the ones set on MultiMedia1/4/8 stay On. */
    val mixerCaptureCmd: String = "",

    /** Carry the call over raw ALSA PCMs opened as root, not AudioRecord and
     *  AudioTrack.  For a device whose HAL cannot reach the call audio at all;
     *  null everywhere else.  See [RootPcmRoute]. */
    val rootPcm: RootPcmRoute? = null,


    /** Readback of the handful of controls this SoC's bridge depends on.
     *  Logged before and after [mixerSetupCmd] so a failed setup is visible in
     *  the call log.  The interesting controls are entirely SoC-specific, so
     *  each profile owns its own command; empty = skip the readback. */
    val mixerVerifyCmd: String,

    // ── Volume calibration ──

    /** STREAM_MUSIC volume as percent of max.  Controls incall_music
     *  injection level into the modem uplink. */
    val musicVolPercent: Int,

    /** Software gain for captured audio (before encoding). */
    val captureGain: Int,

    /** Software gain for playback audio (agent → caller). */
    val playbackGain: Int,

    // ── Echo/noise gate thresholds ──

    /** RMS below this = modem DSP noise, send silence. */
    val noiseGateThreshold: Int,

    /** RMS above this = playback active for echo gate. */
    val echoGateThreshold: Int,

    /** Capture must exceed expectedEcho * this to count as barge-in. */
    val doubleTalkRatio: Float,

    // ── Audio HAL behavior ──

    /** Speaker mode required for incall_music to work. */
    val requireSpeakerMode: Boolean,

    /** HAL parameter name for incall_music (set via AudioManager.setParameters). */
    val incallMusicParam: String,

    /** Whether VOICE_DOWNLINK returns real audio on this device. */
    val voiceDownlinkWorks: Boolean,

    /** Voice call stream volume as percentage of max (0=minimum non-zero).
     *  Controls the caller's voice volume on the speaker.
     *  MSM8930: 0 (muted via tinymix muteVoiceRx).
     *  Exynos 9820: higher value needed for mic-based acoustic capture. */
    val voiceCallVolPercent: Int = 0,

    /** AudioTrack usage attribute for playback (agent→caller).
     *  USAGE_MEDIA (default): Maps to STREAM_MUSIC.  Works with Qualcomm
     *  incall_music mixer (MultiMedia1/2) for digital modem TX injection.
     *  USAGE_VOICE_COMMUNICATION: Maps to STREAM_VOICE_CALL.  May trigger
     *  Samsung Exynos HAL to route playback into the voice call TX path.
     *  -1 = use USAGE_MEDIA (default). */
    val playbackUsage: Int = -1,

    // ── Timing ──

    /** Whether our own playback is audible to the capture source.
     *
     *  The double-talk/echo gate exists because on most devices the injected
     *  agent audio leaks back into capture.  Once playback is routed straight
     *  to Telephony Tx it never reaches the speaker, so the microphone cannot
     *  hear it — and the gate then does nothing but discard the caller's voice
     *  every time the agent talks. */
    val playbackLeaksIntoCapture: Boolean = true,

    /** Prefer UNPROCESSED/CAMCORDER over the voice-tuned capture sources.
     *
     *  When the caller is heard acoustically, the voice sources are the wrong
     *  tool: their echo canceller and noise suppression are built to remove
     *  exactly the loudspeaker signal we are trying to record, and their AGC
     *  pumps the room.  UNPROCESSED bypasses that chain. */
    val preferUnprocessedMic: Boolean = false,

    /** Put VOICE_RECOGNITION at the head of the capture list.
     *
     *  For handsets where VOICE_CALL opens and reports a plausible level but
     *  does not deliver frames at a steady 50/s.  That failure is invisible to
     *  the silence detector -- there *is* audio, it simply arrives in bursts --
     *  and the resulting gappy uplink reads to an AI agent's barge-in detector
     *  as the caller interrupting, so it stops mid-sentence.  Measured on the
     *  S4 Mini: VOICE_CALL@16k sent 1173 packets where 1676 arrived, while
     *  VOICE_RECOGNITION on the same call sent 1650 of 1675.
     *
     *  The rest of the list is kept as fallback rather than pinning this one
     *  source: if it ever fails to open, a call with no audio at all is worse
     *  than a call on a second-choice source. */
    val preferVoiceRecognition: Boolean = false,

    /** Ask AudioRecord to capture from the telephony RX device.
     *
     *  The mirror of [playbackToTelephonyTx].  Without it, capture on a device
     *  whose HAL refuses AudioSource.VOICE_CALL falls back to the microphone,
     *  which records the room rather than the call — the far end is only heard
     *  at all if the downlink happens to be audible on the speaker, and once
     *  the downlink is muted there the agent hears nothing but background. */
    val captureFromTelephonyRx: Boolean = false,

    /** Ask for the playback track to be routed to the telephony TX device.
     *
     *  Setting incall_music_enabled is not by itself enough to make
     *  AudioPolicyManager choose the incall_music_uplink output: the track is
     *  still attached to the speaker backend.  Naming the telephony device as
     *  the track's preferred device asks the policy manager for that route
     *  explicitly, which is something only a privileged app can get away with. */
    val playbackToTelephonyTx: Boolean = false,

    /** Turn incall_music on before the AudioTrack is created, not after.
     *
     *  This HAL decides the output usecase when the track is *created*:
     *  AudioPolicyManager only offers the incall_music_uplink mixPort — the one
     *  routed to Telephony Tx — while incall_music_enabled is already true, so
     *  setting the parameter after play() leaves the track bound to the speaker
     *  for its whole life.  MSM8930 wants the opposite order (its HAL starts
     *  the incall-music usecase only once a STREAM_MUSIC output exists), which
     *  is why this is per-profile rather than simply reordered. */
    val incallMusicBeforeTrack: Boolean = false,

    /** Open the playback track as stereo rather than mono.
     *
     *  Purely a routing key, not a quality choice: the HAL's
     *  "incall_music_uplink" mixPort — the one the audio policy routes to
     *  "Telephony Tx" — declares AUDIO_CHANNEL_OUT_STEREO and nothing else, so
     *  AudioPolicyManager cannot match a mono track to it and quietly hands the
     *  track to the speaker path instead.  Mono samples are duplicated into
     *  both channels on the way out. */
    val playbackStereo: Boolean = false,

    /** Request PERFORMANCE_MODE_LOW_LATENCY for the playback track.
     *
     *  Worth roughly 60ms: AudioTrack's ordinary minimum for 16 kHz stereo is
     *  about 80ms, and the low-latency path is a fraction of that.
     *
     *  Historically this was the thing NOT to do — low-latency made the HAL
     *  serve the track from low-latency-playback on MultiMedia5, a front-end
     *  wired to the speaker rather than to the modem, so the caller heard
     *  nothing from the agent.  That is why the code carries a warning against
     *  it.  Since routing is now requested explicitly through
     *  setPreferredDevice(TYPE_TELEPHONY) rather than inferred from the buffer
     *  size, the usecase may no longer follow the performance mode — but that
     *  has to be confirmed on the device, by checking that playback still
     *  reports routedTo=18 and the caller still hears the agent. */
    val playbackLowLatency: Boolean = false,

    /** Playback buffer size in milliseconds, or 0 for AudioTrack's minimum.
     *
     *  This decides which HAL output the track lands on, and therefore whether
     *  incall_music can reach the modem uplink at all.  A minimum-sized buffer
     *  qualifies for AudioFlinger's fast path, which the Qualcomm HAL serves
     *  with low-latency-playback on MultiMedia5; a larger request falls back to
     *  deep-buffer-playback on MultiMedia1.  On SM6150 the fast path left the
     *  agent audible on the phone's own speaker while the caller heard nothing
     *  from it. */
    val playbackBufferMs: Int = 0,

    /** Delay (ms) after speaker route change before mixer setup. */
    val routeChangeDelayMs: Long,

    /** Delay (ms) before opening AudioRecord/AudioTrack after call activation.
     *
     * Some Qualcomm builds destroy and recreate an AudioTrack while the
     * Telecom speaker route is still settling.  If that happens to the track
     * used for incall_music, Android may silently recreate it as an ordinary
     * output even though routedDevice later reports TELEPHONY. */
    val audioRouteSettleDelayMs: Long = 0,

    /** Delay (ms) after RTP release before reusing the Qualcomm voice output.
     *
     * Pixel 4a's deep-buffer incall_music output can remain blocked in
     * AudioFlinger for several seconds after the Java AudioTrack is released.
     * A following call must wait for that output to drain or it may inherit a
     * stale TELEPHONY_TX stream.
     */
    val audioStopSettleDelayMs: Long = 0,

    /** Delay (ms) for appops propagation on cold boot. */
    val appopsPropagationMs: Long,
) {
    companion object {
        private const val TAG = "DeviceProfile"

        /**
         * Resolved path to tinymix binary.  Empty string if not found.
         *
         * Checked paths (in order):
         *   /data/local/tmp/tinymix   (Magisk module installs here — permissive SELinux)
         *   /vendor/bin/tinymix
         *   /system/bin/tinymix       (Magisk overlay — may lack +x or SELinux context)
         *   /system/xbin/tinymix
         *
         * Falls back to `which tinymix` in the root shell PATH.
         */
        @Volatile private var cachedTinymix: String = ""
        @Volatile private var lastProbeMs: Long = 0L
        @Volatile private var missingReported = false

        /** Minimum gap between discovery attempts once one has come back empty. */
        private const val TINYMIX_REPROBE_MS = 5_000L

        /**
         * Resolved path to the tinymix binary, re-probed until one is found.
         *
         * Only a *successful* discovery is cached.  Discovery runs through the
         * root shell, so a shell that was denied — or had just died and not yet
         * been replaced — makes a perfectly good binary look absent.  This was
         * a `by lazy`, which cached that empty answer for the life of the
         * process: a single transient root failure disabled every mixer control
         * until the app was force-stopped, and the gateway went on answering
         * calls it could no longer bridge, silently.
         *
         * Re-probing is rate-limited to [TINYMIX_REPROBE_MS] so a genuinely
         * rootless device does not run four tinymix invocations per frame.
         */
        val tinymixBin: String
            get() {
                cachedTinymix.let { if (it.isNotEmpty()) return it }
                synchronized(this) {
                    cachedTinymix.let { if (it.isNotEmpty()) return it }
                    val now = SystemClock.elapsedRealtime()
                    if (lastProbeMs != 0L && now - lastProbeMs < TINYMIX_REPROBE_MS) return ""
                    lastProbeMs = now
                    val found = discoverTinymix()
                    if (found.isNotEmpty()) {
                        cachedTinymix = found
                        missingReported = false
                    }
                    return found
                }
            }

        private fun discoverTinymix(): String {
            val paths = listOf(
                "/data/local/tmp/tinymix",
                "/vendor/bin/tinymix",
                "/system/bin/tinymix",
                "/system/xbin/tinymix",
            )
            try {
                // Batch-check all paths in a single su call for speed.
                // The executable bit alone is not enough: a Magisk overlay can
                // put a binary of the wrong ABI on the path (an ARM64 tinymix
                // over a 32-bit ROM's own), and the shell then refuses every
                // invocation with "not executable: 64-bit ELF file" while the
                // file still looks perfectly runnable.  Run it once and keep
                // only a path that actually came back.
                val checks = paths.joinToString("; ") { p ->
                    "[ -x '$p' ] && '$p' >/dev/null 2>&1 && echo 'FOUND:$p'"
                }
                val result = RootShell.execForOutput(
                    "$checks; which tinymix 2>/dev/null | head -1",
                    timeoutMs = 3000
                )
                // Check explicit path matches first
                for (line in result.lines()) {
                    if (line.startsWith("FOUND:")) {
                        val path = line.removePrefix("FOUND:")
                        Log.i(TAG, "tinymix found: $path")
                        return path
                    }
                }
                // Check 'which' output (last line)
                val whichResult = result.lines().lastOrNull { it.startsWith("/") }?.trim()
                if (!whichResult.isNullOrEmpty()) {
                    Log.i(TAG, "tinymix found via which: $whichResult")
                    return whichResult
                }
            } catch (e: Exception) {
                Log.w(TAG, "tinymix discovery error: ${e.message}")
            }

            // Report the transition, not every probe: discovery now retries
            // for as long as it keeps failing, and logging on each attempt
            // would bury the call it is breaking.
            if (!missingReported) {
                missingReported = true
                Log.e(TAG, "no runnable tinymix on device! ABOX/ALSA mixer controls will not work. " +
                    "root=${RootShell.rootState()}. " +
                    "Push a static tinymix for ${Build.SUPPORTED_ABIS.firstOrNull()} to " +
                    "/data/local/tmp/tinymix (chmod 755)")
            }
            return ""
        }

        /**
         * Dump mixer state for diagnostics, once on the first audio bridge
         * setup.  The device-specific half comes from the profile's
         * [mixerVerifyCmd]; everything here is SoC-agnostic.
         */
        fun discoverMixerControls(profile: DeviceProfile): String {
            return try {
                val bin = tinymixBin
                if (bin.isEmpty()) {
                    return "=== tinymix not available ===\n" + RootShell.execForOutput(
                        "cat /proc/asound/cards 2>/dev/null", timeoutMs = 3000
                    )
                }
                val verify = resolveCmd(profile.mixerVerifyCmd)
                RootShell.execForOutput(
                    "echo '=== ALSA cards ==='; cat /proc/asound/cards 2>/dev/null; " +
                        (if (verify.isNotEmpty()) "echo '=== ${profile.name} ==='; $verify; " else "") +
                        "echo '=== total controls ==='; $bin 2>&1 | wc -l",
                    timeoutMs = 8000
                )
            } catch (e: Exception) {
                "Mixer discovery failed: ${e.message}"
            }
        }

        /**
         * Replace bare 'tinymix' in a command string with the resolved
         * full path.  Returns empty string if tinymix is not available.
         */
        fun resolveCmd(cmd: String): String {
            if (cmd.isEmpty()) return ""
            val bin = tinymixBin
            if (bin.isEmpty()) return ""
            return cmd.replace("tinymix", bin)
        }

        /** A system property, or "" — android.os.SystemProperties is hidden API. */
        private fun sysProp(key: String): String = try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, key) as? String ?: ""
        } catch (_: Throwable) { "" }

        /** A small sysfs file, or "" if it cannot be read. */
        private fun readSys(path: String): String = try {
            java.io.File(path).readText().trim()
        } catch (_: Throwable) { "" }

        /** Auto-detect the device and return the appropriate profile. */
        fun detect(): DeviceProfile {
            val hw = Build.HARDWARE.lowercase()
            val board = Build.BOARD.lowercase()
            val model = Build.MODEL.lowercase()
            val device = Build.DEVICE.lowercase()
            // The chip itself, whatever the ROM calls the phone.  Build.BOARD
            // is not it: LineageOS puts the platform there ("sm6150"), MIUI
            // the codename ("surya"), and a codename list never covers every
            // Xiaomi/Poco/Redmi model.  All four of these are readable without
            // root on MIUI 14 — /proc/device-tree and /proc/asound are not.
            val platform = sysProp("ro.board.platform").lowercase()
            val socModel = (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else sysProp("ro.soc.model")).lowercase()
            val socMachine = readSys("/sys/devices/soc0/machine").lowercase()
            val socId = readSys("/sys/devices/soc0/soc_id")
            Log.i(TAG, "Detecting device: hw=$hw board=$board model=${Build.MODEL} device=${Build.DEVICE} " +
                "platform=$platform soc=$socModel machine=$socMachine socId=$socId")
            val sm6150Family =
                listOf(board, hw, platform, socModel).any { it.contains("sm6150") || it.contains("sm7150") } ||
                // Qualcomm's own names: talos = SM6150, sdmmagpie = SM7150,
                // sdmmagpiep = SM7150-AC/P (Poco X3 NFC reports "SDMMAGPIE").
                socMachine in setOf("talos", "sdmmagpie", "sdmmagpiep", "magpie") ||
                socId in setOf("355", "365", "366")

            return when {
                // Samsung Galaxy S4 Mini (MSM8930 / WCD9304)
                board.contains("msm8930") || hw.contains("qcom") && model.contains("gt-i919") ->
                    msm8930()

                // Samsung Galaxy S10e Exynos (Exynos 9820)
                board.contains("exynos9820") || hw.contains("exynos") && model.contains("sm-g970") ->
                    exynos9820()

                // Snapdragon 7-series (SM6150/SM7150) with WCD9375 codec —
                // e.g. Poco X3 NFC.  Same incall_music path as MSM8930 but a
                // different codec, so the WCD9304 control names do not apply.
                // Pixel 4a (sunfish) reports the platform as sm6150 through
                // ro.board.platform, but Build.BOARD/Build.HARDWARE are both
                // "sunfish".  Match its codename (not the model name, which
                // could also match the differently-routed Pixel 4a 5G) so it
                // does not fall through to Generic and leave the mic live.
                //
                // MIUI reports Build.BOARD as the codename ("surya"), not the
                // platform ("sm6150") that LineageOS puts there, so on the
                // stock ROM the Poco fell through to Generic Qualcomm: agent
                // audio played on the loudspeaker instead of into the call and
                // the caller was never captured.  Match the codenames too —
                // surya (X3 NFC) and karna (X3, same SoC and codec).
                sm6150Family ||
                    device == "surya" || device == "karna" ||
                    device == "sunfish" || board == "sunfish" || hw == "sunfish" ->
                    sm6150()

                // Generic Qualcomm — try incall_music, skip WCD9304-specific controls
                hw.contains("qcom") || hw.contains("qualcomm") ->
                    genericQualcomm()

                // Generic Samsung Exynos
                hw.contains("exynos") || hw.contains("samsung") ->
                    genericExynos()

                // Unknown device — minimal mixer interaction
                else -> generic()
            }.also {
                Log.i(TAG, "Selected profile: ${it.name}")
            }
        }

        // ── Known device profiles ──

        /** Samsung Galaxy S4 Mini (MSM8930 / WCD9304 codec) */
        fun msm8930() = DeviceProfile(
            name = "MSM8930 (S4 Mini)",
            preferVoiceRecognition = true,
            mixerSetupCmd = buildString {
                // Voice Rx mute (silence speaker)
                append("tinymix 'Voice Rx Device Mute' 1 2>/dev/null; ")
                append("tinymix 'Voice Rx Mute' 1 2>/dev/null; ")
                append("tinymix 'Voip Rx Device Mute' 1 2>/dev/null; ")
                // Voice Tx: keep open for incall_music injection
                append("tinymix 'Voice Tx Mute' 0 2>/dev/null; ")
                // Full mic mute: disconnect DEC MUX, zero DEC/ADC volumes, cut MICBIAS
                append("tinymix 'DEC1 MUX' 'ZERO' 2>/dev/null; ")
                append("tinymix 'DEC2 MUX' 'ZERO' 2>/dev/null; ")
                append("tinymix 'DEC3 MUX' 'ZERO' 2>/dev/null; ")
                append("tinymix 'DEC1 Volume' 0 2>/dev/null; ")
                append("tinymix 'DEC2 Volume' 0 2>/dev/null; ")
                append("tinymix 'DEC3 Volume' 0 2>/dev/null; ")
                append("tinymix 'DEC4 Volume' 0 2>/dev/null; ")
                append("tinymix 'ADC1 Volume' 0 2>/dev/null; ")
                append("tinymix 'ADC2 Volume' 0 2>/dev/null; ")
                append("tinymix 'ADC3 Volume' 0 2>/dev/null; ")
                append("tinymix 'MICBIAS1 CAPLESS Switch' 0 2>/dev/null; ")
                append("tinymix 'MICBIAS2 CAPLESS Switch' 0 2>/dev/null; ")
                append("tinymix 'MICBIAS3 CAPLESS Switch' 0 2>/dev/null; ")
                append("tinymix 'Voice Tx Device Mute' 1 2>/dev/null; ")
                // Disable echo reference
                append("tinymix 291 0 2>/dev/null; ")
                // The agent's stream also feeds PRI_RX, the I2S output to the
                // PMIC speaker amp.  The WCD controls below (RX3, SPK DRV) are
                // a different speaker path and never silenced this one, which
                // is why STREAM_MUSIC had to be held at 14% (-47 dB) and the
                // agent reached the caller barely audible.  Cut it here; the
                // call gets the stream through Incall_Music only.
                append("tinymix 'PRI_RX Audio Mixer MultiMedia1' 0 2>/dev/null; ")
                append("tinymix 'PRI_RX Audio Mixer MultiMedia2' 0 2>/dev/null; ")
                // Enable incall_music mixer
                append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null; ")
                // Speaker codec-level mute
                append("tinymix 'RX3 Digital Volume' 0 2>/dev/null; ")
                append("tinymix 'SPK DRV Volume' 0 2>/dev/null; ")
                append("tinymix 'Speaker Boost Volume' 0 2>/dev/null; ")
                append("tinymix 'LINEOUT1 Volume' 0 2>/dev/null; ")
                append("tinymix 'LINEOUT2 Volume' 0 2>/dev/null")
            },
            mixerRestoreCmd = buildString {
                // Unmute voice RX (restore speaker audio)
                append("tinymix 'Voice Rx Device Mute' 0 2>/dev/null; ")
                append("tinymix 'Voice Rx Mute' 0 2>/dev/null; ")
                append("tinymix 'Voip Rx Device Mute' 0 2>/dev/null; ")
                // Unmute voice TX (was muted during bridge to block physical mic).
                // CRITICAL: must restore to 0 or subsequent calls can't set up
                // the voice TX path (S4 Mini: "second call never answered").
                append("tinymix 'Voice Tx Device Mute' 0 2>/dev/null; ")
                append("tinymix 'Voice Tx Mute' 0 2>/dev/null; ")
                // Reset incall_music mixer — prevents stale state on next call.
                // HAL may not reset these automatically on call end.
                append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 0 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 0 2>/dev/null; ")
                // Restore mic path: DEC MUX back to ADC, MICBIAS re-enabled
                append("tinymix 'DEC1 MUX' 'ADC1' 2>/dev/null; ")
                append("tinymix 'DEC2 MUX' 'ADC2' 2>/dev/null; ")
                append("tinymix 'DEC3 MUX' 'ADC3' 2>/dev/null; ")
                append("tinymix 'MICBIAS1 CAPLESS Switch' 1 2>/dev/null; ")
                append("tinymix 'MICBIAS2 CAPLESS Switch' 1 2>/dev/null; ")
                append("tinymix 'MICBIAS3 CAPLESS Switch' 1 2>/dev/null; ")
                // Restore volumes
                append("tinymix 'DEC1 Volume' 84 2>/dev/null; ")
                append("tinymix 'DEC2 Volume' 84 2>/dev/null; ")
                append("tinymix 'DEC3 Volume' 84 2>/dev/null; ")
                append("tinymix 'DEC4 Volume' 84 2>/dev/null; ")
                append("tinymix 'ADC1 Volume' 100 2>/dev/null; ")
                append("tinymix 'ADC2 Volume' 100 2>/dev/null; ")
                append("tinymix 'ADC3 Volume' 12 2>/dev/null; ")
                append("tinymix 'RX3 Digital Volume' 68 2>/dev/null; ")
                append("tinymix 'SPK DRV Volume' 8 2>/dev/null; ")
                append("tinymix 'Speaker Boost Volume' 5 2>/dev/null; ")
                append("tinymix 'LINEOUT1 Volume' 100 2>/dev/null; ")
                append("tinymix 'LINEOUT2 Volume' 100 2>/dev/null; ")
                // Media back on the speaker
                append("tinymix 'PRI_RX Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                // Restore echo reference
                append("tinymix 291 1 2>/dev/null")
            },
            mixerIncallMusicCmd = buildString {
                // Again here: the HAL re-routes MultiMedia1 to PRI_RX whenever
                // it reopens the output.
                append("tinymix 'PRI_RX Audio Mixer MultiMedia1' 0 2>/dev/null; ")
                append("tinymix 'PRI_RX Audio Mixer MultiMedia2' 0 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null; ")
                append("tinymix 'Voice Tx Mute' 0 2>/dev/null; ")
                append("sleep 1; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null; ")
                append("tinymix 'PRI_RX Audio Mixer MultiMedia1' 0 2>/dev/null; ")
                append("tinymix 'PRI_RX Audio Mixer MultiMedia2' 0 2>/dev/null")
            },
            mixerVerifyCmd = buildString {
                append("echo -n 'VoiceRxDevMute='; tinymix 'Voice Rx Device Mute' 2>&1; ")
                append("echo -n 'VoiceTxMute='; tinymix 'Voice Tx Mute' 2>&1; ")
                append("echo -n 'IncallMM1='; tinymix 'Incall_Music Audio Mixer MultiMedia1' 2>&1; ")
                append("echo -n 'IncallMM2='; tinymix 'Incall_Music Audio Mixer MultiMedia2' 2>&1; ")
                append("echo -n 'PriRxMM1='; tinymix 'PRI_RX Audio Mixer MultiMedia1' 2>&1")
            },
            // UNVERIFIED — the S4 Mini was not available to test against.
            // voice_extn is generic Qualcomm code, so the in-call recording
            // gate (voice_is_call_state_active) is very likely the same one
            // that kept SM6150 on acoustic capture.  This profile captures the
            // caller through the microphone today for what may be exactly that
            // reason.  The HAL rejects VSIDs it does not know, so listing the
            // full set is harmless; VOICE_SESSION (10c01000) is the one an
            // MSM8930-era HAL would actually use.
            halCallActiveVsids = listOf("10c01000", "10dc1000", "11c05000"),
            // Calibrated, not arbitrary: above roughly this the injected
            // stream clips.  Raising it to index 5/15 was tried on a live call
            // and made no audible difference at the far end — the incall_music
            // path does not take its level from the STREAM_MUSIC index — so
            // loudness has to come from playbackGain or the agent itself.
            // Full scale since PRI_RX is cut (above).  At 14% the mixer output
            // took -47 dB off the agent, measured mid-call 2026-10-23 -- the
            // September note that the index "made no audible difference" was
            // wrong; it was also what kept the speaker leak quiet.
            musicVolPercent = 100,
            // Unity both ways: the call is digital end to end here (modem DSP
            // in, incall_music out), so the gateway passes it through
            // untouched.  What made the agent mishear was the gate below
            // chopping words, not the level.
            captureGain = 1,
            playbackGain = 1,   // x2 only made up for the -47 dB above
            // Measured on live calls: the modem's silence floor sits at
            // rawCapRMS 6-174, while speech runs 471-4132.  At 500 the gate was
            // biting into speech — a frame at 471 was zeroed outright — and the
            // hard on/off at the threshold was audible as chatter at the starts
            // and ends of words.  100 keeps the gate almost entirely out of the
            // way, at the cost of relaying some of the modem's own hiss.
            //
            // On VOICE_RECOGNITION (VOC_REC_DL) the floor is far lower: the
            // line is digital silence, rawCapRMS 6-10.  At 100 the gate cut
            // the soft ends of words (-50..-60 dBFS), so the agent got clipped
            // fragments.  Even at 30 the hard cut to zero between words split
            // one sentence into several turns at the agent ("Я говорю как-то..."
            // / "Viva", 2026-10-24).  The line is already silent between words,
            // so there is nothing for a gate to remove: off.
            noiseGateThreshold = 0,
            echoGateThreshold = 300,
            doubleTalkRatio = 1.5f,
            // Nothing to cancel: capture is AudioSource.VOICE_CALL straight off
            // the modem and playback is injected digitally through
            // incall_music, so the handset's speaker and microphone are not in
            // the path and playback cannot reach the capture side.  Left on
            // (the default) the echo gate and double-talk logic ran on every
            // frame and did nothing but discard the caller whenever the agent
            // spoke — the counters bore this out, gates:echo climbing steadily
            // through a call where no acoustic echo was possible.
            playbackLeaksIntoCapture = false,
            // AudioSource.VOICE_CALL is uplink AND downlink mixed, and the
            // uplink is where incall_music injects the agent — so the agent's
            // own voice came straight back to it as if the caller had spoken,
            // and its barge-in detector cut every phrase in half while the
            // caller was silent.  Confirmed from the server's recordings.
            //
            // Downlink alone is the caller.  These are the two front-end
            // switches the HAL's incall-rec paths use, and they have to be set
            // after startRecording(): opening the capture stream reprograms
            // that front-end, discarding anything written earlier.
            mixerCaptureCmd = buildString {
                append("tinymix 'MultiMedia1 Mixer VOC_REC_DL' 1 2>/dev/null; ")
                append("tinymix 'MultiMedia1 Mixer VOC_REC_UL' 0 2>/dev/null; ")
                append("echo -n 'VOC_REC_DL='; tinymix 'MultiMedia1 Mixer VOC_REC_DL' 2>&1; ")
                append("echo -n 'VOC_REC_UL='; tinymix 'MultiMedia1 Mixer VOC_REC_UL' 2>&1")
            },
            requireSpeakerMode = true,
            incallMusicParam = "incall_music_enabled",
            voiceDownlinkWorks = false,
            primeCaptureBeforePlayback = false,
            silenceFallback = false,
            playbackFadeIn = false,
            routeChangeDelayMs = 700,
            appopsPropagationMs = 500,
        )

        /** Samsung Galaxy S10e (Exynos 9820), LineageOS: both directions digital,
         *  inside the ABOX DSP, through PCMs the audio HAL never opens.
         *
         *  The HAL offers nothing to work with — no telephony device in its
         *  audio policy, no incall_music, no in-call capture — so the bridge
         *  bypasses it and drives ABOX's own routing (card 0, all controls
         *  "ABOX ..."):
         *
         *    agent:  RDMA8 -> SPUS OUT8 -> SIFS2 -> NSRC0/NSRC1 -> VSS uplink.
         *            NSRC0/1 carry the microphone (UAIF0) in a stock call;
         *            pointing them at SIFS2 replaces it, so the room never
         *            reaches the caller and nothing needs muting.
         *    caller: SIFS0, the call's playback mix (downlink + media), ->
         *            NSRC5 -> WDMA6.
         *
         *  Three things this depends on, each found the hard way:
         *    - SIFS2 runs at 24 bit, the format NSRC1 gets from UAIF0.  The VSS
         *      resampler behind NSRC1 is configured for that when the call
         *      starts and is not reconfigured when its source changes; 16-bit
         *      audio came out as a buzz.
         *    - A SIFS is only clocked while a capture stream runs through it, so
         *      WDMA5 (NSRC4 = SIFS2) runs alongside the agent's playback as a
         *      pacer.
         *    - The ABOX firmware reports no position for WDMA5/6, so the vendor
         *      driver never moves those streams; the abox_pos kernel module
         *      (tools/abox-pos, loaded by the Magisk module) reports for it.
         *
         *  Do not mute the microphone through Android here: the HAL mutes the
         *  whole VSS uplink for that, the agent included. */
        /** The S10e's own earpiece, speaker and mics, off for a bridged call
         *  and back to the values the HAL's mixer paths use. */
        private const val S10E_MICS = "IN1L IN1R IN2L IN2R IN3L IN3R IN4L IN4R"
        private const val S10E_LOCAL_MUTE =
            "tinymix 'Left AMP Mute' Muted; tinymix 'Right AMP Mute' Muted; " +
            "for i in $S10E_MICS; do tinymix \"\$i Digital Volume\" 0; done"
        private const val S10E_LOCAL_UNMUTE =
            "tinymix 'Left AMP Mute' Unmuted; tinymix 'Right AMP Mute' Unmuted; " +
            "for i in $S10E_MICS; do tinymix \"\$i Digital Volume\" 128; done"

        fun exynos9820() = DeviceProfile(
            name = "Exynos 9820 (S10e, ABOX)",
            rootPcm = RootPcmRoute(
                card = 0,
                playDevice = 8,       // RDMA8
                pacerDevice = 17,     // WDMA5
                captureDevice = 18,   // WDMA6
                setupCmd = buildString {
                    append("[ -d /sys/module/abox_pos ] || insmod /data/local/tmp/abox_pos.ko; ")
                    append("echo 0x100 > /sys/module/abox_pos/parameters/rdma; ")
                    append("echo 0x60 > /sys/module/abox_pos/parameters/wdma; ")
                    append("tinymix 'ABOX SIFS2 Width' 24 >/dev/null; ")
                    append("tinymix 'ABOX SPUS OUT8' SIFS2 >/dev/null; ")
                    append("tinymix 'ABOX SIFS2' 'SPUS OUT8' >/dev/null; ")
                    append("tinymix 'ABOX NSRC4' SIFS2 >/dev/null; ")
                    append("tinymix 'ABOX NSRC5' SIFS0 >/dev/null; ")
                    append("echo -n 'abox_pos='; [ -d /sys/module/abox_pos ] && echo loaded || echo MISSING")
                },
                // The agent replaces the mic in the uplink, and the phone itself
                // goes quiet: both amps (Left is the earpiece, Right the
                // speaker) muted the way the HAL's spk-amp-mute path does it,
                // and the mic inputs turned down to -64 dB.  None of that is
                // in the gateway's path — the downlink is tapped at SIFS0,
                // before the codec, and NSRC0/1 no longer read the codec.
                connectCmd = buildString {
                    append("tinymix 'ABOX NSRC0' SIFS2; tinymix 'ABOX NSRC1' SIFS2; ")
                    append(S10E_LOCAL_MUTE)
                },
                // Only what is still ours: when the call ends first the HAL has
                // already put NSRC0/1 back to RESERVED, and that must stand.
                // The tap comes off SIFS0 before its capture stops, because a
                // consumer that stops stalls every other one on the same SIFS.
                disconnectCmd = buildString {
                    append("for n in NSRC0 NSRC1; do ")
                    append("tinymix \"ABOX \$n\" | grep -q SIFS2 && tinymix \"ABOX \$n\" UAIF0; done; ")
                    append("tinymix 'ABOX NSRC5' RESERVED; ")
                    append(S10E_LOCAL_UNMUTE)
                },
                teardownCmd = buildString {
                    append("tinymix 'ABOX NSRC4' RESERVED; ")
                    append("tinymix 'ABOX SPUS OUT8' SIFS0; ")
                    append("tinymix 'ABOX SIFS2' 'SPUS OUT6'; ")
                    append("tinymix 'ABOX SIFS2 Width' 16")
                },
            ),
            // The routing lives in rootPcm, around the streams it serves.
            mixerSetupCmd = "",
            // At call end: hand the call its own audio back, and undo the rest
            // only once no stream of ours is left on it — RtpSession tears down
            // on its own thread and may not have got there yet.
            mixerRestoreCmd = buildString {
                append("for n in NSRC0 NSRC1; do ")
                append("tinymix \"ABOX \$n\" | grep -q SIFS2 && tinymix \"ABOX \$n\" UAIF0; done; ")
                append(S10E_LOCAL_UNMUTE).append("; ")
                append("pgrep -f 'tinypcm play' >/dev/null || { ")
                append("tinymix 'ABOX NSRC5' RESERVED; tinymix 'ABOX NSRC4' RESERVED; ")
                append("tinymix 'ABOX SPUS OUT8' SIFS0; tinymix 'ABOX SIFS2' 'SPUS OUT6'; ")
                append("tinymix 'ABOX SIFS2 Width' 16; }")
            },
            mixerIncallMusicCmd = "",
            mixerVerifyCmd = buildString {
                append("echo -n 'NSRC0='; tinymix 'ABOX NSRC0' 2>&1; ")
                append("echo -n 'NSRC1='; tinymix 'ABOX NSRC1' 2>&1; ")
                append("echo -n 'NSRC4='; tinymix 'ABOX NSRC4' 2>&1; ")
                append("echo -n 'NSRC5='; tinymix 'ABOX NSRC5' 2>&1; ")
                append("echo -n 'OUT8='; tinymix 'ABOX SPUS OUT8' 2>&1; ")
                append("echo -n 'SIFS2W='; tinymix 'ABOX SIFS2 Width' 2>&1; ")
                append("echo -n 'AMP='; tinymix 'Left AMP Mute' 2>&1; tinymix 'Right AMP Mute' 2>&1; ")
                append("echo -n 'MIC='; tinymix 'IN1L Digital Volume' 2>&1")
            },
            musicVolPercent = 40,
            // Digital end to end: passed through untouched, as on the S4 Mini.
            captureGain = 1,
            playbackGain = 1,
            noiseGateThreshold = 0,
            echoGateThreshold = 300,
            doubleTalkRatio = 1.5f,
            // The agent goes to SIFS2 and the caller is read off SIFS0; the two
            // never meet.
            playbackLeaksIntoCapture = false,
            silenceFallback = false,
            playbackFadeIn = false,
            requireSpeakerMode = false,
            incallMusicParam = "",
            voiceDownlinkWorks = false,
            // The firmware applies the call volume ("ABOX Speech Volume") to
            // the downlink before SIFS0, so this sets the level the agent
            // hears.  The minimum the other profiles use would starve it.
            voiceCallVolPercent = 70,
            routeChangeDelayMs = 0,
            appopsPropagationMs = 300,
        )

        /** Qualcomm SM6150/SM7150 (Snapdragon 730/732G) with WCD9375 codec.
         *
         *  ALSA card: sm6150-wcd9375-snd-card (3436 controls).
         *
         *  Present and used here:
         *    Incall_Music Audio Mixer MultiMedia1/2/5/9  — digital injection
         *      of STREAM_MUSIC into the modem uplink, the same mechanism the
         *      S4 Mini uses and the one the Exynos S10e turned out to lack.
         *    MultiMedia{1,4,8,9} Mixer VOC_REC_DL/UL     — in-call capture.
         *      The HAL is supposed to set these when an app opens
         *      AudioSource.VOICE_CALL; enabling them explicitly costs nothing
         *      and covers the case where it does not.
         *    Voice Rx Device Mute / Voice Tx Mute / Voice Tx Device Mute
         *
         *  Absent (WCD9304-only, so nothing from the MSM8930 profile that
         *  touches them applies): MICBIAS* CAPLESS Switch, RX3 Digital Volume,
         *  SPK DRV Volume, LINEOUT* Volume, Voice Rx Mute, Voip Rx Device Mute.
         *
         *  Note the Voice* controls are write-only on this firmware — reading
         *  them returns "operation not permitted", which is expected and not a
         *  failure.
         *
         *  Levels: capture on this device comes in very quiet (rawCapRMS in the
         *  20-30 range), so the generic profile's 350 noise gate discarded
         *  every frame and the far end heard pure silence.
         */
        fun sm6150() = DeviceProfile(
            name = "SM6150/SM7150 (WCD9375)",
            mixerSetupCmd = buildString {
                // The downlink has to stay audible on the speaker: capture on
                // this device is acoustic (the HAL gives no in-call recording
                // and no telephony-RX capture), so the microphone hearing the
                // speaker is the only way the agent hears the caller at all.
                // Muting it is why the agent ended up hearing just the room.
                append("tinymix 'Voice Rx Device Mute' 0 4294967295 20 2>/dev/null; ")
                // Keep the uplink stream itself open — incall_music injects
                // into it — but mute the *device* (microphone) leg of it.
                // Without this the physical mic is mixed into the modem
                // uplink, so the caller hears the room and their own voice
                // coming back off the phone's speaker.  The two controls are
                // separate on purpose: 'Voice Tx Mute' would silence the whole
                // TX path including the injected agent audio, whereas
                // 'Voice Tx Device Mute' drops only the mic contribution.
                // AudioRecord keeps working — it reads the mic directly rather
                // than through the voice TX path.
                // These voice controls are 3-element arrays — the QCOM HAL
                // writes them as {mute, session_vsid, ramp_ms} with
                // ALL_SESSION_VSID = 0xFFFFFFFF.  "tinymix 'X' 1" only sets
                // element 0, leaving mute untouched, which is why the earlier
                // single-value writes silently did nothing.
                append("tinymix 'Voice Tx Mute' 0 4294967295 20 2>/dev/null; ")
                append("tinymix 'Voice Tx Device Mute' 1 4294967295 20 2>/dev/null; ")
                // Digital injection: agent audio -> modem uplink.
                // MultiMedia5 is the one that matters here: with a voice call
                // up, the AudioTrack lands on MultiMedia5 (visible as
                // "TERT_MI2S_RX Audio Mixer MultiMedia5: On"), not MultiMedia1/2
                // the way it does on MSM8930.  Opening only MM1/MM2 left the
                // agent's audio going to the speaker instead of the uplink, so
                // the caller heard nothing from the agent — only their own
                // voice echoing back off the mic.  All four available ports are
                // opened so this survives the HAL choosing another front-end.
                append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia5' 1 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia9' 1 2>/dev/null; ")
                // In-call record direction.  The driver accepts 0-2 only
                // (3/"both" is rejected as invalid argument), and it sits at 1
                // — uplink — by default.  Uplink is our own side of the call,
                // i.e. the audio incall_music injects, so capturing it fed the
                // agent its own echo and read rawCapRMS≈5.  Downlink is the
                // caller's voice, which is what the agent needs to hear.
                append("tinymix 'Voc Rec Config' 2 2>/dev/null; ")
                // In-call capture: DOWNLINK ONLY.
                // The uplink leg must stay off.  It carries what we inject via
                // incall_music, so routing it into the capture front-end feeds
                // the agent its own voice — an echo entirely inside this phone,
                // which is why it persisted even with the far end's microphone
                // muted.  VOC_REC_UL was enabled here while the uplink was
                // still silent and the mistake was invisible.
                append("tinymix 'MultiMedia1 Mixer VOC_REC_DL' 1 2>/dev/null; ")
                append("tinymix 'MultiMedia1 Mixer VOC_REC_UL' 0 2>/dev/null; ")
                append("tinymix 'MultiMedia4 Mixer VOC_REC_DL' 1 2>/dev/null; ")
                append("tinymix 'MultiMedia4 Mixer VOC_REC_UL' 0 2>/dev/null; ")
                append("tinymix 'MultiMedia8 Mixer VOC_REC_DL' 1 2>/dev/null; ")
                append("tinymix 'MultiMedia8 Mixer VOC_REC_UL' 0 2>/dev/null; ")
                append("tinymix 'MultiMedia9 Mixer VOC_REC_DL' 1 2>/dev/null; ")
                append("tinymix 'MultiMedia9 Mixer VOC_REC_UL' 0 2>/dev/null")
            },
            mixerRestoreCmd = buildString {
                append("tinymix 'Voice Rx Device Mute' 0 4294967295 20 2>/dev/null; ")
                append("tinymix 'Voice Tx Device Mute' 0 4294967295 20 2>/dev/null; ")
                append("tinymix 'Voice Tx Mute' 0 4294967295 20 2>/dev/null; ")
                append("tinymix 'Voc Rec Config' 1 2>/dev/null; ")
                append("tinymix 'DEC1 MUX' 'ADC1' 2>/dev/null; ")
                append("tinymix 'DEC2 MUX' 'ADC2' 2>/dev/null; ")
                append("tinymix 'DEC3 MUX' 'ADC3' 2>/dev/null; ")
                append("tinymix 'ADC1 Volume' 100 2>/dev/null; ")
                append("tinymix 'ADC2 Volume' 100 2>/dev/null; ")
                append("tinymix 'ADC3 Volume' 100 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 0 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 0 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia5' 0 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia9' 0 2>/dev/null; ")
                append("tinymix 'MultiMedia1 Mixer VOC_REC_DL' 0 2>/dev/null; ")
                append("tinymix 'MultiMedia1 Mixer VOC_REC_UL' 0 2>/dev/null; ")
                append("tinymix 'MultiMedia4 Mixer VOC_REC_DL' 0 2>/dev/null; ")
                append("tinymix 'MultiMedia4 Mixer VOC_REC_UL' 0 2>/dev/null; ")
                append("tinymix 'MultiMedia8 Mixer VOC_REC_DL' 0 2>/dev/null; ")
                append("tinymix 'MultiMedia8 Mixer VOC_REC_UL' 0 2>/dev/null; ")
                append("tinymix 'MultiMedia9 Mixer VOC_REC_DL' 0 2>/dev/null; ")
                append("tinymix 'MultiMedia9 Mixer VOC_REC_UL' 0 2>/dev/null")
            },
            mixerIncallMusicCmd = buildString {
                append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia5' 1 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia9' 1 2>/dev/null; ")
                append("tinymix 'Voice Tx Mute' 0 4294967295 20 2>/dev/null; ")
                // Re-assert the mic mute here as well as in mixerSetupCmd.  The
                // write is accepted either way (rc=0), but the HAL rewrites the
                // voice mutes while it brings the call up, so the one issued
                // during mixer setup is overwritten before the call is
                // established and the caller keeps hearing the room.  This
                // command runs once playback is going, i.e. after the HAL has
                // settled.
                append("tinymix 'Voice Tx Device Mute' 1 4294967295 20 2>/dev/null")
            },
            // VoiceMMode1 and VoiceMMode2 — VoiceMMode2 is the session
            // actually running here (pcm19c / TERT_MI2S_RX_Voice Mixer).
            halCallActiveVsids = listOf("11c05000", "11dc5000"),
            // ~6s: long enough to survive the silence before the agent speaks,
            // since the digital source is the only one that counts here.
            captureSilenceFrames = 300,
            silenceLocalAudio = true,
            // Re-assert in-call capture routing once the stream is open.
            // MultiMedia9 is the front-end VOICE_CALL lands on here, so it is
            // the one that actually has to carry VOC_REC.
            mixerCaptureCmd = buildString {
                // DIAGNOSTIC: silence everything local, so the only audio that
                // can reach the far end is the digital in-call path.  These
                // mutes are issued here rather than in mixerSetupCmd because
                // the voice mutes are gated on the HAL's is_call_active flag,
                // which RtpSession only sets on the way into initAudio — a
                // mute written before that is discarded, which is why every
                // earlier attempt at muting the mic did nothing.
                //   Voice Rx Device Mute -> downlink no longer reaches the
                //     speaker, so nothing is audible on the phone.
                //   Voice Tx Device Mute -> microphone leaves the GSM uplink,
                //     so the caller stops hearing the room and their own echo.
                //   DEC MUX / ADC volumes -> the microphone is dead at the
                //     codec, so AudioRecord cannot pick the room up either.
                // If the agent can still hear the caller with all of this on,
                // in-call digital capture is genuinely working.
                append("tinymix 'Voice Rx Device Mute' 1 4294967295 20 2>/dev/null; ")
                append("tinymix 'Voice Tx Device Mute' 1 4294967295 20 2>/dev/null; ")
                append("tinymix 'DEC1 MUX' 'ZERO' 2>/dev/null; ")
                append("tinymix 'DEC2 MUX' 'ZERO' 2>/dev/null; ")
                append("tinymix 'DEC3 MUX' 'ZERO' 2>/dev/null; ")
                append("tinymix 'ADC1 Volume' 0 2>/dev/null; ")
                append("tinymix 'ADC2 Volume' 0 2>/dev/null; ")
                append("tinymix 'ADC3 Volume' 0 2>/dev/null; ")
                // No VOC_REC / Voc Rec Config writes here any more.  Those
                // were added while the HAL considered the call inactive and was
                // doing nothing; now that is_call_active is set the HAL selects
                // SND_DEVICE_IN_INCALL_REC_* and programs these itself, and
                // overwriting them underneath it is more likely to break the
                // record session than to help.  Report what the HAL chose.
                // Kill the uplink leg again once the stream exists — opening
                // the capture front-end reprograms its mixers.
                append("tinymix 'MultiMedia9 Mixer VOC_REC_UL' 0 2>/dev/null; ")
                append("tinymix 'MultiMedia1 Mixer VOC_REC_UL' 0 2>/dev/null; ")
                append("echo -n 'MM9_DL='; tinymix 'MultiMedia9 Mixer VOC_REC_DL' 2>&1; ")
                append("echo -n 'MM9_UL='; tinymix 'MultiMedia9 Mixer VOC_REC_UL' 2>&1; ")
                append("echo -n 'VocRecCfg='; tinymix 'Voc Rec Config' 2>&1")
            },
            mixerVerifyCmd = buildString {
                append("echo -n 'IncallMM1='; tinymix 'Incall_Music Audio Mixer MultiMedia1' 2>&1; ")
                append("echo -n 'IncallMM2='; tinymix 'Incall_Music Audio Mixer MultiMedia2' 2>&1; ")
                append("echo -n 'IncallMM5='; tinymix 'Incall_Music Audio Mixer MultiMedia5' 2>&1; ")
                append("echo -n 'VocRecDL='; tinymix 'MultiMedia1 Mixer VOC_REC_DL' 2>&1; ")
                append("echo -n 'VocRecUL='; tinymix 'MultiMedia1 Mixer VOC_REC_UL' 2>&1; ")
                append("echo -n 'VocRecCfg='; tinymix 'Voc Rec Config' 2>&1")
            },
            musicVolPercent = 20,
            // Digital in-call capture arrives at full scale — measured
            // rawCapRMS 1000-5200 against 26-190 on the acoustic path — so it
            // needs no gain at all.  At 4x it clipped (capRMS ~20000 of 32767).
            captureGain = 1,
            // Unity: the agent leaves on an INCALL_MUSIC output at 0 dB, so
            // x2 was pure boost -- peaks at -0.5 dBFS, into the limiter, heard
            // as distortion (Poco, 2026-10-24).  Passed through untouched.
            playbackGain = 1,
            // 40-55 was measured against a loud recorded announcement; a real
            // caller coming through the speaker only reaches rawCapRMS 19-26,
            // and a gate of 30 discarded nearly all of it (noise=561 vs
            // fwd=481, capRMS=0) so the agent heard nothing at all.  Sits just
            // under the speech floor instead; voiceCallVolPercent below is what
            // buys back the headroom over room noise.
            //
            // On the digital VOICE_DOWNLINK capture the line between words is
            // digital silence (Poco, 2026-10-24), so a gate only chops word
            // tails: off, like the S4 Mini.
            noiseGateThreshold = 0,
            echoGateThreshold = 300,
            doubleTalkRatio = 1.5f,
            requireSpeakerMode = true,
            incallMusicParam = "incall_music_enabled",
            // Capture is acoustic, so the caller's voice has to be loud on the
            // speaker for the microphone to pick it out of the room.  Nothing
            // of ours plays locally any more — the agent's audio goes straight
            // to Telephony Tx — so there is no feedback cost to turning this up.
            voiceCallVolPercent = 100,
            // It reads silent only while the HAL believes the call is
            // inactive.  With call_state announced first the in-call record
            // session runs, and downlink-only is what we want: VOICE_CALL also
            // captures the uplink, which carries the agent's injected audio.
            voiceDownlinkWorks = true,
            // The echo this gate existed for was VOC_REC_UL folding our own
            // uplink into the capture, and that is fixed at the routing level
            // now.  Left on, it only destroys real audio: measured echo=601 and
            // noise=585 gated against fwd=166 forwarded, i.e. it was dropping
            // most of the caller, and its echoGainRatio estimate sits at 0.00
            // because it can only learn from frames it has already classified
            // as echo — a deadlock it cannot leave on its own.
            playbackLeaksIntoCapture = false,
            preferUnprocessedMic = true,
            // Measured: TYPE_TELEPHONY is offered as an input and
            // setPreferredDevice is accepted (routedFrom=18), but every source
            // then reads rawCapRMS=0 — this HAL provides no downlink capture,
            // and forcing the route takes the working mic fallback with it.
            captureFromTelephonyRx = false,
            playbackToTelephonyTx = true,
            // The HAL binds the usecase at track creation, so the parameter
            // has to be set first.
            incallMusicBeforeTrack = true,
            // The incall_music_uplink mixPort accepts stereo only.
            playbackStereo = true,
            // The low-latency output is unstable on this firmware when
            // Telecom changes the voice route: AudioTrack reports a dead
            // IAudioTrack and its native write can block for several seconds.
            // A deep-buffer track is still explicitly routed to TELEPHONY,
            // but survives the route transition and releases cleanly between
            // consecutive calls.
            playbackLowLatency = false,
            playbackBufferMs = 100,
            playbackFadeIn = false,
            routeChangeDelayMs = 500,
            audioRouteSettleDelayMs = 500,
            audioStopSettleDelayMs = 18_000,
            appopsPropagationMs = 300,
        )

        /** Generic Qualcomm device — tries incall_music, generic controls */
        fun genericQualcomm() = DeviceProfile(
            name = "Generic Qualcomm",
            mixerSetupCmd = buildString {
                append("tinymix 'Voice Rx Device Mute' 1 2>/dev/null; ")
                append("tinymix 'Voice Tx Mute' 0 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null")
            },
            mixerRestoreCmd = buildString {
                append("tinymix 'Voice Rx Device Mute' 0 2>/dev/null")
            },
            mixerIncallMusicCmd = buildString {
                append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null; ")
                append("tinymix 'Voice Tx Mute' 0 2>/dev/null")
            },
            mixerVerifyCmd = buildString {
                append("echo -n 'VoiceRxDevMute='; tinymix 'Voice Rx Device Mute' 2>&1; ")
                append("echo -n 'VoiceTxMute='; tinymix 'Voice Tx Mute' 2>&1; ")
                append("echo -n 'IncallMM1='; tinymix 'Incall_Music Audio Mixer MultiMedia1' 2>&1; ")
                append("echo -n 'IncallMM2='; tinymix 'Incall_Music Audio Mixer MultiMedia2' 2>&1")
            },
            musicVolPercent = 20,
            captureGain = 1,
            playbackGain = 2,
            noiseGateThreshold = 350,
            echoGateThreshold = 300,
            doubleTalkRatio = 1.5f,
            requireSpeakerMode = true,
            incallMusicParam = "incall_music_enabled",
            voiceDownlinkWorks = false,
            routeChangeDelayMs = 500,
            appopsPropagationMs = 300,
        )

        /** Generic Samsung Exynos — minimal mixer, rely on Android APIs */
        fun genericExynos() = DeviceProfile(
            name = "Generic Exynos",
            mixerSetupCmd = buildString {
                append("tinymix 'Main Mic Switch' 0 2>/dev/null; ")
                append("tinymix 'Sub Mic Switch' 0 2>/dev/null; ")
                append("tinymix 'Third Mic Switch' 0 2>/dev/null")
            },
            mixerRestoreCmd = buildString {
                append("tinymix 'Main Mic Switch' 1 2>/dev/null; ")
                append("tinymix 'Sub Mic Switch' 1 2>/dev/null")
            },
            mixerIncallMusicCmd = "",  // May not have incall_music
            mixerVerifyCmd = buildString {
                append("echo -n 'MainMic='; tinymix 'Main Mic Switch' 2>&1; ")
                append("echo -n 'SubMic='; tinymix 'Sub Mic Switch' 2>&1")
            },
            musicVolPercent = 20,
            captureGain = 1,
            playbackGain = 2,
            noiseGateThreshold = 300,
            echoGateThreshold = 300,
            doubleTalkRatio = 1.5f,
            requireSpeakerMode = true,
            incallMusicParam = "incall_music_enabled",
            voiceDownlinkWorks = true,
            routeChangeDelayMs = 500,
            appopsPropagationMs = 300,
        )

        /** Unknown device — no mixer hacks, pure Android APIs */
        fun generic() = DeviceProfile(
            name = "Generic",
            mixerSetupCmd = "",
            mixerRestoreCmd = "",
            mixerIncallMusicCmd = "",
            mixerVerifyCmd = "",
            musicVolPercent = 20,
            captureGain = 1,
            playbackGain = 2,
            noiseGateThreshold = 300,
            echoGateThreshold = 300,
            doubleTalkRatio = 1.5f,
            requireSpeakerMode = true,
            incallMusicParam = "incall_music_enabled",
            voiceDownlinkWorks = true,
            routeChangeDelayMs = 500,
            appopsPropagationMs = 300,
        )
    }
}

/**
 * Call audio through two PCMs the audio HAL never opens, streamed by tinypcm
 * running as root (installed to /data/local/tmp by the Magisk module).
 *
 * The commands run through [RootShell], with tinymix resolved as for the
 * other mixer commands, in this order around one RTP session:
 * [setupCmd], the PCMs open, [connectCmd] ... [disconnectCmd], the PCMs
 * close, [teardownCmd].  Each is safe to run twice.
 */
data class RootPcmRoute(
    val card: Int,
    /** Playback PCM the agent is written to. */
    val playDevice: Int,
    /** Capture PCM run alongside playback and discarded, for a path that is
     *  only clocked while something captures through it; -1 for none. */
    val pacerDevice: Int = -1,
    /** Capture PCM the caller is read from. */
    val captureDevice: Int,
    /** Channels the PCMs are opened with.  The pipe is always mono. */
    val channels: Int = 2,
    /** Routes the PCMs need to exist at all: a PCM with nothing behind it
     *  fails to prepare. */
    val setupCmd: String,
    /** Once both stream: give the call our audio instead of its own. */
    val connectCmd: String,
    /** Before they close: give the call its own audio back. */
    val disconnectCmd: String,
    /** After: undo the rest of [setupCmd]. */
    val teardownCmd: String,
)
