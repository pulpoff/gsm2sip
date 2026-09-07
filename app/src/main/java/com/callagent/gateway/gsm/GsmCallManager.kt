package com.callagent.gateway.gsm

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import com.callagent.gateway.DeviceProfile
import com.callagent.gateway.RootShell

/**
 * GSM call manager: answers/makes/hangs up GSM calls, tracks state.
 *
 * Calls are controlled through the InCallService (GsmCallService).
 * Audio routing uses device-specific mixer controls via [DeviceProfile].
 *
 * SIP→GSM: AudioTrack (USAGE_MEDIA / deep-buffer) → incall_music →
 * HAL injects STREAM_MUSIC digitally into voice TX (uplink).
 *
 * GSM→SIP: VOICE_CALL capture provides digital uplink+downlink audio.
 *
 * ALL tinymix commands are batched into a single su call to minimise
 * JVM spawning on low-end devices.
 */
object GsmCallManager {

    private const val TAG = "GsmCallManager"

    /** Active device profile — initialized on first use. */
    val profile: DeviceProfile by lazy { DeviceProfile.detect() }

    // Current active GSM call
    @Volatile var activeCall: Call? = null; private set
    @Volatile var activeCallState: Int = Call.STATE_NEW; private set
    @Volatile var inCallService: InCallService? = null; private set

    @Volatile var listener: Listener? = null

    /** Optional callback for routing important audio diagnostics to the
     *  app log viewer (Settings tab).  Set by GatewayService. */
    @Volatile var logCallback: ((String) -> Unit)? = null

    /** Log to both Android logcat AND the app log viewer. */
    private fun appLog(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    interface Listener {
        /** Incoming GSM call ringing — caller number provided */
        fun onIncomingGsmCall(call: Call, number: String)
        /** GSM call connected (active) */
        fun onGsmCallActive(call: Call)
        /** GSM call state changed */
        fun onGsmCallStateChanged(call: Call, state: Int)
        /** GSM call ended */
        fun onGsmCallEnded(call: Call)
    }

    // ── InCallService callbacks ─────────────────────────

    fun onCallAdded(call: Call, service: InCallService) {
        inCallService = service
        activeCall = call
        activeCallState = call.state

        val number = call.details?.handle?.schemeSpecificPart ?: "unknown"

        when (call.state) {
            Call.STATE_RINGING -> {
                Log.i(TAG, "Incoming GSM call from $number")
                // Silence the ringtone immediately — this is a gateway device,
                // not a user-facing phone.  The call will be auto-answered
                // once the SIP leg is established.
                try {
                    val am = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Ringer silence failed: ${e.message}")
                }
                listener?.onIncomingGsmCall(call, number)
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                Log.i(TAG, "Outgoing GSM call to $number")
            }
            Call.STATE_ACTIVE -> {
                Log.i(TAG, "GSM call active: $number")
                configureAudioBridge()
                listener?.onGsmCallActive(call)
            }
        }
    }

    fun onCallRemoved(call: Call) {
        Log.i(TAG, "GSM call removed")
        if (activeCall == call) {
            activeCall = null
            activeCallState = Call.STATE_DISCONNECTED
        }
        restoreAudio()
        listener?.onGsmCallEnded(call)
    }

    fun onCallStateChanged(call: Call, state: Int) {
        activeCallState = state

        when (state) {
            Call.STATE_RINGING -> {
                // Handle calls that arrive as STATE_NEW in onCallAdded and
                // transition to RINGING via the callback.  Without this,
                // the orchestrator never learns about the incoming call.
                val number = call.details?.handle?.schemeSpecificPart ?: "unknown"
                Log.i(TAG, "GSM call ringing: $number (via state change)")
                listener?.onIncomingGsmCall(call, number)
            }
            Call.STATE_ACTIVE -> {
                Log.i(TAG, "GSM call active")
                configureAudioBridge()
                listener?.onGsmCallActive(call)
            }
            Call.STATE_DISCONNECTED -> {
                Log.i(TAG, "GSM call disconnected")
                listener?.onGsmCallEnded(call)
                if (activeCall == call) {
                    activeCall = null
                }
            }
        }
        listener?.onGsmCallStateChanged(call, state)
    }

    // ── Call control ────────────────────────────────────

    /** Answer a ringing GSM call */
    fun answerCall(call: Call? = activeCall) {
        call?.let {
            Log.i(TAG, "Answering GSM call")
            it.answer(it.details.videoState)
        }
    }

    /** Reject a ringing GSM call */
    fun rejectCall(call: Call? = activeCall) {
        call?.let {
            Log.i(TAG, "Rejecting GSM call")
            it.reject(false, "")
        }
    }

    /** Hang up active GSM call */
    fun hangupCall(call: Call? = activeCall) {
        call?.let {
            Log.i(TAG, "Hanging up GSM call")
            it.disconnect()
        }
    }

    /** Place outgoing GSM call via the SIM */
    fun makeCall(context: Context, destination: String) {
        Log.i(TAG, "Making GSM call to $destination")
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$destination"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Music volume percent — from device profile. */
    val MUSIC_VOL_PERCENT: Int get() = profile.musicVolPercent

    /** Run mixer discovery once on first audio bridge setup. */
    @Volatile private var discoveryDone = false

    private fun runMixerDiscovery() {
        if (discoveryDone) return
        discoveryDone = true
        Thread({
            try {
                val discovery = DeviceProfile.discoverMixerControls()
                for (line in discovery.lines()) {
                    if (line.isNotBlank()) Log.i(TAG, "MixerDiscovery: $line")
                }
                // Send summary to app log viewer (not the full dump)
                val cards = discovery.lines()
                    .filter { it.contains("[") && it.contains("]") && it.contains(":") }
                    .joinToString(", ") { it.trim() }
                val tinymix = if (DeviceProfile.tinymixBin.isNotEmpty())
                    DeviceProfile.tinymixBin else "NOT FOUND"
                appLog("ALSA: tinymix=$tinymix cards=[$cards]")
            } catch (e: Exception) {
                appLog("Mixer discovery failed: ${e.message}")
            }
        }, "MixerDiscovery").start()
    }

    /** Configure audio for GSM↔SIP bridge using the active device profile. */
    private fun configureAudioBridge() {
        try {
            // Run ABOX/ALSA discovery on first call for diagnostics
            runMixerDiscovery()

            inCallService?.let { service ->
                val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

                // Samsung HAL params (incall_music_enabled, g_call_path, etc.)
                // are NOT set here — they fire in RtpSession.enableIncallMusic()
                // AFTER the AudioRecord is running.  Setting them before capture
                // kills VOICE_CALL capture (confirmed v2.8.33).

                if (profile.requireSpeakerMode) {
                    service.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                }

                audioManager?.let { am ->
                    // Do NOT set isMicrophoneMute = true here!
                    // v2.8.50: Samsung Exynos HAL interprets mic mute as "mute
                    // entire voice uplink to modem", which blocks NSRC-injected
                    // AudioTrack audio from reaching the caller.
                    // MSM8930: mic muting is handled at ALSA level (DEC MUX=ZERO,
                    // MICBIAS=0) in mixerSetupCmd — no need for API-level mute.
                    am.isMicrophoneMute = false
                    enforceVolumes(am)

                    // Delay mixer/volume setup until speaker route change settles.
                    Thread({
                        try {
                            Thread.sleep(profile.routeChangeDelayMs)
                            enforceVolumes(am)
                            batchMixerSetup()
                        } catch (_: Exception) {}
                    }, "VolEnforce").start()

                    // Samsung Exynos re-route dance REMOVED (v2.8.39):
                    // v2.8.38 tried earpiece→speaker re-route at t=3s to force HAL
                    // voice path recreation with incall_music ausage.  Results:
                    //   - Audio moved from speaker to earpiece and STAYED there
                    //   - 300ms delay was insufficient for route to settle
                    //   - No incall_music mixer controls exist on Exynos 9820 anyway
                    //     (confirmed: 1267 tinymix controls, zero match incall/inject)
                    //   - No ausage config files on this firmware
                    // The re-route served no purpose and broke speaker mode.

                    val tinymixStatus = if (DeviceProfile.tinymixBin.isNotEmpty()) "available" else "NOT FOUND"
                    val route = if (profile.requireSpeakerMode) "speaker" else "earpiece"
                    appLog("Audio bridge: $route, mode=${am.mode}, tinymix=$tinymixStatus, profile=${profile.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure audio: ${e.message}")
        }
    }

    /** Set audio stream volumes for the GSM↔SIP bridge.
     *  Called multiple times: immediately, after delayed route change,
     *  and from RtpSession as a secondary safeguard. */
    fun enforceVolumes(am: AudioManager) {
        // Clear any stale ADJUST_MUTE flag from a previous call.
        // CRITICAL: Do NOT use ADJUST_MUTE on STREAM_VOICE_CALL — on
        // MSM8930 it kills the incall_music injection path, preventing
        // the agent's audio from reaching the GSM caller.  Speaker
        // silencing is handled by muteVoiceRx() at the ALSA mixer level.
        try {
            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: SecurityException) {}
        // Voice call volume: controls caller's voice on speaker.
        // MSM8930: minimum (1) — speaker silenced by muteVoiceRx via tinymix.
        // Exynos 9820: 80% — no muteVoiceRx, need loud speaker for mic capture.
        // Volume=0 can confuse audio policy into treating call as inactive.
        try {
            val vcVol = if (profile.voiceCallVolPercent > 0) {
                val maxVc = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                (maxVc * profile.voiceCallVolPercent / 100).coerceAtLeast(1)
            } else {
                1
            }
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, vcVol, 0)
        } catch (_: SecurityException) {}
        // Music stream controls incall_music injection level into
        // the modem uplink.  Lower value = quieter speaker + quieter
        // agent voice for the GSM caller.
        val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val musicVol = (maxMusic * MUSIC_VOL_PERCENT / 100).coerceAtLeast(1)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, musicVol, 0)
        // Read back actual values to confirm they stuck
        val actualVoice = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val actualMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val muted = am.isStreamMute(AudioManager.STREAM_VOICE_CALL)
        appLog("Vol: voice=$actualVoice(m=$muted), music=$actualMusic/$maxMusic(target=$musicVol)")
    }

    /** Restore audio state when call ends */
    private fun restoreAudio() {
        try {
            // Single su call to restore all mixer controls
            batchMixerRestore()

            inCallService?.let { service ->
                service.setAudioRoute(CallAudioState.ROUTE_EARPIECE)

                val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.let { am ->
                    am.isMicrophoneMute = false
                    // Clear incall_music HAL parameter for clean state on next call
                    if (profile.incallMusicParam.isNotEmpty()) {
                        am.setParameters("${profile.incallMusicParam}=false")
                    }

                    // Unmute voice call stream and restore volume for normal phone use
                    try {
                        am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
                    } catch (_: SecurityException) {}
                    try {
                        val maxVc = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVc * 2 / 3).coerceAtLeast(1), 0)
                    } catch (_: SecurityException) {}
                    Log.i(TAG, "Audio restored: earpiece, VoiceRx unmuted, echoRef=SLIM_RX, incall_music=false")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore audio: ${e.message}")
        }
    }

    /**
     * Set up mixer controls for audio bridge using the device profile.
     * All commands batched into a single su call for efficiency.
     *
     * Commands reference bare 'tinymix' — [DeviceProfile.resolveCmd] replaces
     * it with the discovered full path at runtime.
     */
    fun batchMixerSetup() {
        if (profile.mixerSetupCmd.isEmpty()) {
            appLog("Mixer: no commands for ${profile.name}")
            return
        }
        val resolvedSetup = DeviceProfile.resolveCmd(profile.mixerSetupCmd)
        if (resolvedSetup.isEmpty()) {
            appLog("Mixer: tinymix NOT FOUND — cannot set controls for ${profile.name}")
            return
        }
        try {
            val bin = DeviceProfile.tinymixBin
            // Step 1: Readback BEFORE — see what HAL set during call setup
            val before = RootShell.execForOutput(buildString {
                append("echo 'NSRC0B:'; $bin 'ABOX NSRC0 Bridge' 2>&1; ")
                append("echo 'NSRC1B:'; $bin 'ABOX NSRC1 Bridge' 2>&1; ")
                append("echo 'NSRC0:'; $bin 'ABOX NSRC0' 2>&1; ")
                append("echo 'NSRC1:'; $bin 'ABOX NSRC1' 2>&1; ")
                append("echo 'SPUS0:'; $bin 'ABOX SPUS OUT0' 2>&1")
            }, timeoutMs = 8000)
            appLog("Mixer BEFORE: $before")

            // Step 2: Run mixer setup commands (all ABOX controls on card 0)
            // Use execForOutput to capture discovery/diagnostic output from setup commands
            val setupOutput = RootShell.execForOutput(resolvedSetup, timeoutMs = 8000)
            if (setupOutput.isNotBlank()) appLog("Mixer setup: $setupOutput")

            // Step 3: Readback AFTER — verify controls were actually changed
            val readback = RootShell.execForOutput(buildString {
                append("echo 'NSRC0B:'; $bin 'ABOX NSRC0 Bridge' 2>&1; ")
                append("echo 'NSRC1B:'; $bin 'ABOX NSRC1 Bridge' 2>&1; ")
                append("echo 'NSRC2B:'; $bin 'ABOX NSRC2 Bridge' 2>&1; ")
                append("echo 'NSRC0:'; $bin 'ABOX NSRC0' 2>&1; ")
                append("echo 'NSRC1:'; $bin 'ABOX NSRC1' 2>&1; ")
                append("echo 'SPUS0:'; $bin 'ABOX SPUS OUT0' 2>&1")
            }, timeoutMs = 10000)
            appLog("Mixer AFTER: $readback")
        } catch (e: Exception) {
            appLog("Mixer setup FAILED: ${e.message}")
        }
    }

    /** Restore mixer state when call ends using the device profile. */
    fun batchMixerRestore() {
        if (profile.mixerRestoreCmd.isEmpty()) {
            Log.i(TAG, "batchMixerRestore: no mixer commands for ${profile.name}")
            return
        }
        val resolvedRestore = DeviceProfile.resolveCmd(profile.mixerRestoreCmd)
        if (resolvedRestore.isEmpty()) {
            Log.i(TAG, "batchMixerRestore: tinymix not found, skipping")
            return
        }
        try {
            RootShell.exec(resolvedRestore)
            appLog("Mixer restored")
        } catch (e: Exception) {
            appLog("Mixer restore FAILED: ${e.message}")
        }
    }

    /** Check if a GSM call is currently active */
    val isCallActive: Boolean
        get() = activeCall != null && activeCallState == Call.STATE_ACTIVE

    /** Get current call number */
    val currentNumber: String?
        get() = activeCall?.details?.handle?.schemeSpecificPart
}
