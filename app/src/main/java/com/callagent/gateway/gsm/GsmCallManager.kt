package com.callagent.gateway.gsm

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
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

    /**
     * Why the last GSM call ended.
     *
     * Read at STATE_DISCONNECTED and kept, because the bridge tears down
     * afterwards and the Call object may be invalidated by then — and the
     * cause is what decides the SIP status the server is told.
     */
    @Volatile var lastDisconnectCause: DisconnectCause? = null
        private set
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
        lastDisconnectCause = null
        // Release the previous call's object; the dedupe only needs to span
        // one call's own disconnect.
        endedCall = null

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
                silenceDialTone()
            }
            Call.STATE_ACTIVE -> {
                Log.i(TAG, "GSM call active: $number")
                configureAudioBridge()
                listener?.onGsmCallActive(call)
            }
        }
    }

    /** The call already reported as ended, so it is reported exactly once.
     *
     *  A disconnect arrives twice: once as a STATE_DISCONNECTED callback and
     *  again as onCallRemoved.  Both used to fire onGsmCallEnded, so the
     *  orchestrator tore the bridge down twice for one call — harmless only
     *  because tearDown happens to be state-guarded, which is not a property
     *  worth depending on. */
    @Volatile private var endedCall: Call? = null

    /**
     * Silence the ringback the handset plays while an outgoing call is set up.
     *
     * The ALSA voice mutes cannot do this one: they are gated on the HAL's
     * is_call_active flag and only take effect once capture is running, which
     * is well after the network has started sending ringback — so the room
     * hears the first of it.  This goes through AudioManager instead, the same
     * way the incoming ringtone is silenced in onCallAdded.  A gateway should
     * be quiet whichever direction the call goes.
     *
     * Only for profiles that silence the handset anyway.  The ones that need
     * the speaker up to capture audio must not have the voice stream muted out
     * from under them, and on MSM8930 muting this stream kills the
     * incall_music injection path outright (see enforceVolumes).
     */
    private fun silenceDialTone() {
        if (!profile.silenceLocalAudio) return
        val service = inCallService ?: return
        try {
            val am = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
            appLog("Outgoing ringback silenced")
        } catch (e: Exception) {
            Log.w(TAG, "Ringback silence failed: ${e.message}")
        }
    }

    private fun notifyCallEnded(call: Call) {
        if (endedCall === call) return
        endedCall = call
        listener?.onGsmCallEnded(call)
    }

    /**
     * Telecom has unbound the InCallService.
     *
     * [inCallService] is a static reference to a Service — a full Context —
     * and nothing ever cleared it, so every unbind/rebind cycle (dialer role
     * change, service restart) left the previous instance pinned for the life
     * of the process.
     */
    fun onServiceUnbound(service: InCallService) {
        if (inCallService === service) {
            inCallService = null
            Log.i(TAG, "InCallService unbound")
        }
    }

    fun onCallRemoved(call: Call) {
        Log.i(TAG, "GSM call removed")
        if (activeCall == call) {
            activeCall = null
            activeCallState = Call.STATE_DISCONNECTED
        }
        restoreAudio()
        notifyCallEnded(call)
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
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                silenceDialTone()
            }
            Call.STATE_ACTIVE -> {
                Log.i(TAG, "GSM call active")
                configureAudioBridge()
                listener?.onGsmCallActive(call)
            }
            Call.STATE_DISCONNECTED -> {
                lastDisconnectCause = try {
                    call.details?.disconnectCause
                } catch (e: Exception) {
                    Log.w(TAG, "Disconnect cause unavailable: ${e.message}")
                    null
                }
                Log.i(TAG, "GSM call disconnected (cause=${lastDisconnectCause?.code})")
                notifyCallEnded(call)
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
    /**
     * Place an outgoing GSM call.
     *
     * Uses TelecomManager.placeCall rather than startActivity(ACTION_CALL).
     * The gateway dials from a foreground service with no visible Activity,
     * and Android 15+ blocks background activity launches, so the intent form
     * never reaches Telecom at all — it is dropped with BAL_BLOCK and the call
     * simply never happens.  placeCall is a binder call into Telecom, needs no
     * Activity, and we hold CALL_PHONE (plus CALL_PRIVILEGED as a priv-app);
     * as the default dialer, Telecom hands the call back to our InCallService.
     *
     * The ACTION_CALL path is kept as a fallback for the case where Telecom
     * refuses the direct call — it still works whenever an Activity is up.
     */
    /**
     * True for dial strings that are MMI/USSD codes rather than phone numbers
     * — anything containing '*' or '#', e.g. *132# (balance) or *#06# (IMEI).
     *
     * These must never reach placeCall: Telecom rejects them with
     * "Connection is null, DIALED_MMI", and on this build that took
     * TelephonyConnectionService down with it.
     */
    fun isMmiCode(dialString: String): Boolean {
        val s = dialString.trim()
        return s.isNotEmpty() && (s.contains('*') || s.contains('#'))
    }

    /**
     * Run an MMI/USSD code and report the network's answer via [onResult].
     *
     * USSD codes (those ending in '#') go through sendUssdRequest, which hands
     * the reply back to us so it can be logged — useful on a headless gateway
     * where nobody is watching for a system dialog.  Anything else (IMEI
     * lookups, call-forwarding shortcuts) goes to Telecom's own MMI handling.
     */
    @SuppressLint("MissingPermission")
    fun sendMmi(context: Context, dialString: String, onResult: (String) -> Unit) {
        val code = dialString.trim()
        if (code.endsWith("#")) {
            try {
                val telephony =
                    context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                telephony.sendUssdRequest(
                    code,
                    object : TelephonyManager.UssdResponseCallback() {
                        override fun onReceiveUssdResponse(
                            tm: TelephonyManager, request: String, response: CharSequence
                        ) {
                            Log.i(TAG, "USSD $request → $response")
                            onResult(response.toString())
                        }

                        override fun onReceiveUssdResponseFailed(
                            tm: TelephonyManager, request: String, failureCode: Int
                        ) {
                            Log.w(TAG, "USSD $request failed (code $failureCode)")
                            onResult("USSD $request failed (code $failureCode)")
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
                return
            } catch (e: Exception) {
                Log.w(TAG, "sendUssdRequest failed (${e.message}) — falling back to Telecom")
            }
        }
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val handled = telecom.handleMmi(code)
            onResult(if (handled) "MMI $code sent" else "MMI $code not recognised")
        } catch (e: Exception) {
            onResult("MMI $code failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun makeCall(context: Context, destination: String) {
        Log.i(TAG, "Making GSM call to $destination")
        val uri = Uri.fromParts("tel", destination, null)
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(uri, Bundle())
            return
        } catch (e: Exception) {
            Log.w(TAG, "placeCall failed (${e.message}) — falling back to ACTION_CALL")
        }
        try {
            context.startActivity(
                Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_CALL fallback failed: ${e.message}")
        }
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
                val discovery = DeviceProfile.discoverMixerControls(profile)
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
            // Run ALSA mixer discovery on first call for diagnostics
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

                if (profile.silenceLocalAudio) {
                    // Mute through Telecom as well as AudioManager.  Telecom
                    // owns the call's mute state and re-applies it whenever the
                    // audio route changes, so an AudioManager-only mute can be
                    // quietly undone underneath us.
                    try {
                        service.setMuted(true)
                        appLog("Telecom mute requested")
                    } catch (e: Exception) {
                        Log.w(TAG, "Telecom setMuted failed: ${e.message}")
                    }
                }

                audioManager?.let { am ->
                    // Do NOT set isMicrophoneMute = true here!
                    // v2.8.50: Samsung Exynos HAL interprets mic mute as "mute
                    // entire voice uplink to modem", which blocks NSRC-injected
                    // AudioTrack audio from reaching the caller.
                    // MSM8930: mic muting is handled at ALSA level (DEC MUX=ZERO,
                    // MICBIAS=0) in mixerSetupCmd — no need for API-level mute.
                    // Profiles that silence the handset re-mute in
                    // enforceVolumes() immediately below.
                    if (!profile.silenceLocalAudio) {
                        am.isMicrophoneMute = false
                    }
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

        if (profile.silenceLocalAudio) {
            // Digital bridge: silence the handset itself.  Done through
            // AudioManager because it goes via the HAL, unlike the ALSA voice
            // mutes which the HAL overwrites when it programs the call path.
            try {
                am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
            } catch (_: SecurityException) {}
            try {
                am.isMicrophoneMute = true
            } catch (e: Exception) {
                Log.w(TAG, "Mic mute failed: ${e.message}")
            }
            appLog("Local audio silenced: speaker muted, mic muted (digital only)")
            return
        }

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
                        try { am.isMicrophoneMute = false } catch (_: Exception) {}
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
            // Readback before/after so a setup that silently did nothing is
            // visible in the call log.  Which controls matter is SoC-specific,
            // so the profile supplies the command — the ABOX names this used
            // to hardcode exist only on Samsung's DSP and answered "control
            // not found" on every other device.
            val verify = DeviceProfile.resolveCmd(profile.mixerVerifyCmd)
            if (verify.isNotEmpty()) {
                appLog("Mixer BEFORE: ${RootShell.execForOutput(verify, timeoutMs = 8000)}")
            }

            val setupOutput = RootShell.execForOutput(resolvedSetup, timeoutMs = 8000)
            if (setupOutput.isNotBlank()) appLog("Mixer setup: $setupOutput")

            if (verify.isNotEmpty()) {
                appLog("Mixer AFTER: ${RootShell.execForOutput(verify, timeoutMs = 10000)}")
            }
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
