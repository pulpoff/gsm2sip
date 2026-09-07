package com.callagent.gateway

import android.media.AudioAttributes
import android.os.Build
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
        val tinymixBin: String by lazy { discoverTinymix() }

        private fun discoverTinymix(): String {
            val paths = listOf(
                "/data/local/tmp/tinymix",
                "/vendor/bin/tinymix",
                "/system/bin/tinymix",
                "/system/xbin/tinymix",
            )
            try {
                // Batch-check all paths in a single su call for speed
                val checks = paths.joinToString("; ") { p ->
                    "[ -x '$p' ] && echo 'FOUND:$p'"
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

            Log.e(TAG, "tinymix NOT FOUND on device! ABOX/ALSA mixer controls will not work. " +
                "Push a static arm64 tinymix binary to /data/local/tmp/tinymix (chmod 755)")
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

        /** Auto-detect the device and return the appropriate profile. */
        fun detect(): DeviceProfile {
            val hw = Build.HARDWARE.lowercase()
            val board = Build.BOARD.lowercase()
            val model = Build.MODEL.lowercase()
            Log.i(TAG, "Detecting device: hw=$hw board=$board model=${Build.MODEL} device=${Build.DEVICE}")

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
                board.contains("sm6150") || board.contains("sm7150") ->
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
                // Restore echo reference
                append("tinymix 291 1 2>/dev/null")
            },
            mixerIncallMusicCmd = buildString {
                append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null; ")
                append("tinymix 'Voice Tx Mute' 0 2>/dev/null; ")
                append("sleep 1; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null")
            },
            mixerVerifyCmd = buildString {
                append("echo -n 'VoiceRxDevMute='; tinymix 'Voice Rx Device Mute' 2>&1; ")
                append("echo -n 'VoiceTxMute='; tinymix 'Voice Tx Mute' 2>&1; ")
                append("echo -n 'IncallMM1='; tinymix 'Incall_Music Audio Mixer MultiMedia1' 2>&1; ")
                append("echo -n 'IncallMM2='; tinymix 'Incall_Music Audio Mixer MultiMedia2' 2>&1")
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
            musicVolPercent = 14,
            captureGain = 2,
            playbackGain = 2,
            noiseGateThreshold = 500,
            echoGateThreshold = 300,
            doubleTalkRatio = 1.5f,
            requireSpeakerMode = true,
            incallMusicParam = "incall_music_enabled",
            voiceDownlinkWorks = false,
            routeChangeDelayMs = 700,
            appopsPropagationMs = 500,
        )

        /** Samsung Galaxy S10e (Exynos 9820 / Cirrus Logic CS47L93 Madera codec)
         *
         *  Audio HAL: Samsung audio_hw_proxy (ABOX DSP)
         *  ALSA cards:
         *    0: beyondmadera (CS47L93 Madera + ALL ABOX controls, 1267 total)
         *    1: aboxvdma (ABOX Virtual DMA — ZERO mixer controls!)
         *    2: aboxdump (debug)
         *
         *  ABOX routing during voice call (v2.8.40-41 diagnostics):
         *    NSRC0=UAIF0(8) Bridge=On  — mic/codec → modem TX
         *    NSRC1=UAIF0(8) Bridge=On  — mic/codec → modem TX (redundant)
         *    NSRC2=SIFS0     Bridge=Off — playback mixer (not bridged)
         *    SIFS1 → UAIF1 → CS35L41 speaker amp (speaker playback)
         *    RDMA0 → VPCMOUT_DAI0 (modem downlink playback)
         *    VPCMIN_DAI0 → WDMA1 (modem uplink capture)
         *    ABOX Sound Type=VOICE, Audio Mode=IN_CALL
         *
         *  UAIF interfaces:
         *    UAIF0 = CODEC (Madera CS47L93) — mic/codec I2S
         *    UAIF1 = Speaker AMP (CS35L41)  — speaker playback
         *    UAIF2 = FM Radio, UAIF3 = Bluetooth
         *
         *  v2.8.42 TX injection strategy — NSRC bridge rerouting:
         *    Reroute NSRC0 from UAIF0 (mic) to SIFS0 (playback mixer).
         *    Bridge is already On (set by telephony HAL), so the bridged
         *    audio source changes from mic to AudioTrack playback.
         *    AudioTrack uses USAGE_MEDIA (routes through SPUS → SIFS0).
         *    This replaces physical mic with SIP agent audio in modem TX.
         *    v2.8.42 confirmed: caller heard audio (overdriven loud noise).
         *    Enum names work: 'SIFS0' sets correctly, 'UAIF0' restores.
         *
         *  v2.8.43-44: Only rerouted NSRC0 — caller silent (modem uses NSRC1).
         *  v2.8.45: Reroute NSRC0+NSRC1→SIFS0 (both to playback mixer).
         *    SIFS0=AudioTrack only (no modem downlink → no feedback).
         *    v2.8.42 noise was NSRC1→SIFS1 (speaker=modem downlink feedback).
         */
        fun exynos9820() = DeviceProfile(
            name = "Exynos 9820 (S10e)",
            // ABOX mixer controls — exact names from /vendor/etc/mixer_paths.xml.
            // The control names are CASE SENSITIVE — "Bridge" not "BRIDGE".
            // ALL ABOX controls are on card 0 (default) — no -D flag needed.
            // v2.8.49: Attempted speaker mute via CS35L41/Madera/ABOX controls.
            // v2.8.50: SPK_SCAN confirmed NO speaker amp controls exist on this
            // LineageOS firmware.  Only HPOUT* (headphone) volumes are exposed.
            // ABOX UAIF1 SPK=RESERVED disconnected the speaker amp I2S bus which
            // KILLED the entire voice path (caller heard nothing).  The CS35L41
            // amp is controlled through missing kernel control (ABOX Spk AmpL Power).
            // No ALSA-level speaker mute is possible on this firmware.
            // Keep discovery-only scan for diagnostics.
            mixerSetupCmd = buildString {
                append("echo 'SPK_SCAN:'; tinymix 2>&1 | grep -iE '(spk|speaker|amp.*(switch|gain|vol)|out[1-6][lr].*(switch|vol))' | head -20")
            },
            // Restore NSRC0 and NSRC1 to UAIF0 (mic/codec → modem TX) on call end.
            // v2.8.42 confirmed enum names work — 'SIFS0' sets correctly.
            mixerRestoreCmd = buildString {
                append("tinymix 'ABOX NSRC0' 'UAIF0' 2>/dev/null; ")
                append("tinymix 'ABOX NSRC1' 'UAIF0' 2>/dev/null")
            },
            // NSRC bridge rerouting: change NSRC0+NSRC1 source from UAIF0 (mic)
            // to SIFS0 (playback mixer).  Bridge is already On (set by HAL),
            // so this injects AudioTrack audio into modem TX.
            // v2.8.42: NSRC0→SIFS0 + NSRC1→SIFS1 = loud noise (SIFS1 has modem
            //   downlink via speaker path → feedback loop).
            // v2.8.43-44: NSRC0→SIFS0 only = silent (modem reads NSRC1, not NSRC0).
            // v2.8.45: NSRC0+NSRC1→SIFS0 = clean injection, no feedback.
            //   SIFS0 has only playback mixer (AudioTrack), NOT modem downlink
            //   (which goes through SIFS1→UAIF1→speaker amp).
            mixerIncallMusicCmd = buildString {
                // Reroute NSRC0+NSRC1 → SIFS0 (playback mixer → modem TX bridge)
                append("tinymix 'ABOX NSRC0' 'SIFS0' 2>/dev/null; ")
                append("tinymix 'ABOX NSRC1' 'SIFS0' 2>/dev/null; ")
                append("sleep 0.1; ")
                // Verify readback
                append("echo 'NSRC_REROUTE:'; ")
                append("echo -n 'NSRC0='; tinymix 'ABOX NSRC0' 2>/dev/null; ")
                append("echo -n 'NSRC1='; tinymix 'ABOX NSRC1' 2>/dev/null; ")
                append("echo -n 'NSRC0_Bridge='; tinymix 'ABOX NSRC0 Bridge' 2>/dev/null; ")
                append("echo -n 'NSRC1_Bridge='; tinymix 'ABOX NSRC1 Bridge' 2>/dev/null")
            },
            // NSRC/bridge state — the controls the TX injection depends on.
            // These names exist only on Samsung's ABOX DSP; on any other SoC
            // tinymix answers "control not found", which is why this readback
            // belongs to the profile rather than the shared call path.
            mixerVerifyCmd = buildString {
                append("echo -n 'NSRC0='; tinymix 'ABOX NSRC0' 2>&1; ")
                append("echo -n 'NSRC1='; tinymix 'ABOX NSRC1' 2>&1; ")
                append("echo -n 'NSRC0B='; tinymix 'ABOX NSRC0 Bridge' 2>&1; ")
                append("echo -n 'NSRC1B='; tinymix 'ABOX NSRC1 Bridge' 2>&1; ")
                append("echo -n 'NSRC2B='; tinymix 'ABOX NSRC2 Bridge' 2>&1; ")
                append("echo -n 'SPUS0='; tinymix 'ABOX SPUS OUT0' 2>&1")
            },
            // Deliberately NOT set: there is nothing on this device to route
            // to.  Checked on the hardware — /vendor/etc/audio_policy_configuration.xml
            // declares three mixPorts (deep, fast, primary) and contains no
            // telephony device at all, and audio.primary.universal9820.so has
            // no TELEPHONY_TX, no incall_rec usecases and no voice_extn
            // call_state/vsid handling.  Against SM6150, where
            // incall_music_uplink -> Telephony Tx is what carries injected
            // audio into the modem, this device simply has no equivalent.
            // Neither direction can be done digitally here; that is why the
            // gateway moved to a Qualcomm device.
            musicVolPercent = 40,  // v2.8.45@30%+2x=audible but quiet. Raise for clarity.
            captureGain = 10,      // VOICE_RECOGNITION captures very quietly (rawCapRMS~2)
            playbackGain = 2,      // 40%+2x = moderate, SIFS0 only = no feedback
            noiseGateThreshold = 20,  // Lower gate: VOICE_RECOGNITION raw level is ~2-50
            echoGateThreshold = 300,
            doubleTalkRatio = 1.5f,
            // Speaker mode REQUIRED for capture on this device.  In earpiece
            // mode, ALL AudioRecord sources return rawCapRMS=0.
            requireSpeakerMode = true,
            // Samsung incall_music HAL param.  Set via enableIncallMusic()
            // AFTER capture is established (not in configureAudioBridge).
            // This gives AudioRecord time to lock onto its PCM device before
            // the HAL re-routes for incall_music.
            incallMusicParam = "incall_music_enabled",
            voiceDownlinkWorks = true,
            voiceCallVolPercent = 0,   // Minimum — vol=12 caused acoustic echo
            // USAGE_MEDIA: Routes through SPUS → SIFS0/SIFS1 (normal playback).
            // NSRC bridge rerouting captures from SIFS into modem TX.
            // v2.8.41 proved USAGE_VOICE_COMMUNICATION does NOT inject into
            // modem TX — HAL ignores it.  USAGE_MEDIA is correct because we
            // need audio on SIFS where the rerouted NSRC bridge can capture it.
            playbackUsage = -1,  // default = USAGE_MEDIA
            routeChangeDelayMs = 500,
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
            playbackGain = 2,
            // 40-55 was measured against a loud recorded announcement; a real
            // caller coming through the speaker only reaches rawCapRMS 19-26,
            // and a gate of 30 discarded nearly all of it (noise=561 vs
            // fwd=481, capRMS=0) so the agent heard nothing at all.  Sits just
            // under the speech floor instead; voiceCallVolPercent below is what
            // buys back the headroom over room noise.
            noiseGateThreshold = 12,
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
            // No override: routing is decided by setPreferredDevice(TELEPHONY)
            // rather than by buffer size now, so the deep-buffer trick that
            // forced the track off MultiMedia5 is no longer load-bearing, and
            // 100ms of playback buffer is 100ms of latency on a live call.
            // AudioTrack's own minimum for 16 kHz stereo is already ~80ms.
            playbackBufferMs = 0,
            routeChangeDelayMs = 500,
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
