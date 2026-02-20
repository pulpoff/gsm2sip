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

    /** Grep pattern for diagnostic tinymix dump. */
    val mixerDiagGrep: String,

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
         * Dump available ALSA mixer controls for diagnostics.
         * Called once on first audio bridge setup.  Returns the dump for logging.
         */
        fun discoverMixerControls(): String {
            return try {
                val sb = StringBuilder()
                val bin = tinymixBin
                if (bin.isNotEmpty()) {
                    // Compact discovery: NSRC/bridge state + ALSA cards only.
                    // Full routing map established in v2.8.42 — no need for
                    // bulk control dumps, enum lists, or mixer_paths.xml parsing.
                    val result = RootShell.execForOutput(
                        "echo '=== ALSA cards ==='; cat /proc/asound/cards 2>/dev/null; " +
                        "echo '=== NSRC/Bridge state ==='; " +
                        "for i in 0 1 2 3 4; do " +
                        "  echo -n \"NSRC\${i}=\"; $bin \"ABOX NSRC\${i}\" 2>/dev/null || echo 'N/A'; " +
                        "  echo -n \"NSRC\${i}_Bridge=\"; $bin \"ABOX NSRC\${i} Bridge\" 2>/dev/null || echo 'N/A'; " +
                        "done; " +
                        "echo -n 'SoundType='; $bin 'ABOX Sound Type' 2>/dev/null || echo 'N/A'; " +
                        "echo '=== total controls ==='; $bin 2>&1 | wc -l",
                        timeoutMs = 8000
                    )
                    sb.appendLine(result)
                } else {
                    sb.appendLine("=== tinymix not available ===")
                    val info = RootShell.execForOutput(
                        "cat /proc/asound/card0/id 2>/dev/null; " +
                        "cat /proc/asound/card1/id 2>/dev/null",
                        timeoutMs = 3000
                    )
                    sb.appendLine(info)
                }
                sb.toString()
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
            mixerDiagGrep = "tinymix 2>&1 | grep -iE '(Voice Rx|Voice Tx|Incall_Music|EC_REF|DEC[1-4] Vol|DEC[1-4] MUX|ADC[1-3] Vol|MICBIAS|RX[0-9].*[Vv]ol|SPK DRV|LINEOUT[12] Vol|SLIM TX[0-9])'",
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
            // Diagnostic: NSRC/bridge state only (routing map fully understood).
            mixerDiagGrep = buildString {
                append("echo '=== NSRC/Bridge ==='; ")
                append("tinymix 2>&1 | grep -iE '(nsrc|bridge)' | head -20")
            },
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
            mixerDiagGrep = "tinymix 2>&1 | grep -iE '(voice|incall|music|multimedia)'",
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
            mixerDiagGrep = "tinymix 2>&1 | grep -iE '(mic|spk|voice|abox)'",
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
            mixerDiagGrep = "tinymix 2>&1 | head -30",
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
