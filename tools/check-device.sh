#!/bin/bash
#
# Decide whether a phone can run the gateway with a fully digital audio path.
#
# Support is a property of the vendor image, not of the chip.  The Qualcomm
# audio HAL family carries this code almost universally, and Google's Tensor
# phones have it on their AoC audio DSP, but whether the OEM built it in, and
# whether the audio policy exposes a route to the modem uplink, varies per
# device and per ROM.
#
# The audio-policy check needs no root, so a candidate phone can be vetted
# before rooting it.  The HAL and mixer checks need root (Magisk, with
# Superuser access set to "Apps and ADB").
#
# Usage:  tools/check-device.sh [adb-serial]

set -u
SERIAL="${1:-}"
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

sh_()  { "${ADB[@]}" shell "$@" 2>/dev/null | tr -d '\r'; }
su_()  { "${ADB[@]}" shell "su -c '$1'" 2>/dev/null | tr -d '\r'; }
have_root() { [ "$(su_ 'id -u')" = "0" ]; }

POL=/vendor/etc/audio_policy_configuration.xml
# Does the audio policy contain this (grep -E) pattern?  No root needed.
pol_has() { sh_ "grep -qE '$1' $POL && echo y" | grep -q y; }
# Path of a working tinymix, or empty.  The executable bit alone is not
# enough: an overlay can put a wrong-ABI binary on the path.
find_tinymix() {
    for p in /data/local/tmp/tinymix /vendor/bin/tinymix /system/bin/tinymix; do
        su_ "[ -x $p ] && echo yes" | grep -q yes && { echo "$p"; return; }
    done
}

pass=0; fail=0; unknown=0
ok()  { echo "  [ok]      $1"; pass=$((pass+1)); }
no()  { echo "  [MISSING] $1"; fail=$((fail+1)); }
unk() { echo "  [?]       $1"; unknown=$((unknown+1)); }

echo "=== Device ==="
echo "  model    : $(sh_ getprop ro.product.model)"
echo "  board    : $(sh_ getprop ro.board.platform)"
echo "  hardware : $(sh_ getprop ro.hardware)"
echo "  android  : $(sh_ getprop ro.build.version.release)"
echo "  vendor   : $(sh_ getprop ro.vendor.build.fingerprint)"
echo

HW=$(sh_ getprop ro.hardware)
BOARD=$(sh_ getprop ro.board.platform)

# The audio HAL family decides which names to look for below.
case "$HW:$BOARD" in
    qcom:*|qualcomm:*|*:sm6150|*:sm7150|*:sm[5678]*)
        FAMILY=qualcomm
        echo "Qualcomm audio HAL ($BOARD)"
        ;;
    *:gs201)
        FAMILY=tensor
        echo "Google Tensor / AoC audio DSP ($BOARD) — profile: Tensor GS201"
        ;;
    *)
        echo "Neither a Qualcomm nor a Google Tensor device — stop here."
        echo "Injection needs an output the policy routes to the modem uplink and"
        echo "capture needs an in-call record stream; the Qualcomm HAL and the AoC"
        echo "DSP have both.  A Samsung Exynos S10e was checked and has no"
        echo "telephony route in its audio policy at all: three mixPorts (deep,"
        echo "fast, primary) and nothing else."
        exit 1
        ;;
esac

if sh_ 'pm list features' | grep -q 'feature:android.hardware.telephony'; then
    ok "telephony hardware present"
else
    no "no telephony — this device cannot place GSM calls (WiFi-only?)"
fi

# ── 1. audio policy (no root needed) — the decisive one ──────────────
echo
echo "=== 1. Audio policy: is there a route to the modem uplink? ==="
case $FAMILY in
qualcomm)
    if pol_has incall_music_uplink; then
        ok "incall_music_uplink mixPort declared"
        sh_ "grep -A4 'mixPort name=\"incall_music_uplink\"' $POL" | grep -i channelmasks | sed 's/^/            /'
        sh_ "grep -i 'sink=\"Telephony Tx\"' $POL" | head -1 | sed 's/^/            /'
    else
        no "no incall_music_uplink mixPort — nothing to inject the agent into"
    fi
    ;;
tensor)
    pol_has 'mixPort name="incall playback"' \
        && ok "incall playback mixPort declared" \
        || no "no incall playback mixPort — nothing to inject the agent into"
    pol_has 'sink="Telephony Tx" sources="incall playback' \
        && ok "incall playback routed to Telephony Tx" \
        || no "incall playback is not routed to Telephony Tx"
    pol_has 'mixPort name="incall capture"' \
        && ok "incall capture mixPort declared" \
        || no "no incall capture mixPort — caller can only be heard acoustically"
    ;;
esac

# ── 2. HAL (root) ────────────────────────────────────────────────────
echo
echo "=== 2. Audio HAL ==="
if ! have_root; then
    unk "needs root — enable Magisk Superuser access = 'Apps and ADB', then"
    echo "            run 'adb shell su -c id' once and grant the prompt"
elif [ $FAMILY = tensor ]; then
    # The AoC HAL is not string-probed: its routes are all in the policy and
    # the mixer, which sections 1 and 3 cover.
    echo "            (nothing to check on this family)"
else
    HAL=$(su_ 'ls /vendor/lib64/hw/audio.primary.*.so /vendor/lib/hw/audio.primary.*.so 2>/dev/null' | grep -v default | head -1)
    if [ -z "$HAL" ]; then
        unk "no vendor audio HAL found"
    else
        echo "            $HAL"
        S=$(su_ "strings $HAL")
        echo "$S" | grep -q "AUDIO_OUTPUT_FLAG_INCALL_MUSIC" \
            && ok "incall_music output flag   — agent audio into the uplink" \
            || no "incall_music output flag   — no digital path to the caller"
        echo "$S" | grep -q "USECASE_INCALL_REC" \
            && ok "in-call record usecases    — caller into the agent" \
            || no "in-call record usecases    — capture would be acoustic only"
        echo "$S" | grep -qx "vsid" \
            && ok "voice_extn vsid/call_state — needed to start that session" \
            || no "voice_extn vsid/call_state — cannot mark the call active"
    fi
fi

# ── 3. mixer controls (root + tinymix) ───────────────────────────────
echo
echo "=== 3. Mixer controls ==="
if ! have_root; then
    unk "needs root"
else
    TM=$(find_tinymix)
    if [ -z "$TM" ]; then
        unk "tinymix not present — push magisk/tinymix to /data/local/tmp/ (chmod 755)"
    else
        M=$(su_ "$TM")
        case $FAMILY in
        qualcomm)
            n=$(echo "$M" | grep -c 'Incall_Music Audio Mixer')
            [ "$n" -gt 0 ] && ok "Incall_Music Audio Mixer ($n ports)" || no "Incall_Music Audio Mixer"
            echo "$M" | grep -q VOC_REC_DL && ok "VOC_REC_DL / VOC_REC_UL" || no "VOC_REC_* capture routing"
            echo "$M" | grep -q "Voc Rec Config" && ok "Voc Rec Config" || no "Voc Rec Config"
            ;;
        tensor)
            echo "$M" | grep -q "Incall Playback Stream0" && ok "Incall Playback Stream0/1 — agent audio into the uplink" || no "Incall Playback Stream*"
            echo "$M" | grep -q "Incall Capture Stream0" && ok "Incall Capture Stream0-3   — caller into the agent" || no "Incall Capture Stream*"
            echo "$M" | grep -q "Incall Mic Mute" && ok "Incall Mic/Sink Mute       — handset out of the call" || no "Incall Mic/Sink Mute"
            ;;
        esac
    fi
fi

# ── verdict ──────────────────────────────────────────────────────────
echo
echo "=== Verdict ==="
if [ "$fail" -eq 0 ] && [ "$unknown" -eq 0 ]; then
    echo "  Fully supported on paper: $pass/$pass checks passed."
    if [ $FAMILY = tensor ]; then
        echo "  Matches the Tensor GS201 profile (verified on a Pixel 7)."
    else
        echo "  A DeviceProfile entry will still be needed — the mixer names are"
        echo "  generic, but which front-end the playback track lands on is not."
        echo "  The 'Mixer BEFORE/AFTER' lines logged around each call show it."
    fi
elif [ "$fail" -eq 0 ]; then
    echo "  Promising: $pass checks passed, $unknown could not be checked."
    echo "  Root the device and re-run for a definite answer."
elif [ "$pass" -eq 0 ]; then
    echo "  Not supported: nothing required is present."
else
    echo "  Partial: $pass present, $fail missing, $unknown unchecked."
    echo "  Without an output routed to the modem uplink there is no digital"
    echo "  path to the caller; without an in-call record stream the agent can"
    echo "  only hear the caller acoustically, through the phone's own microphone."
fi
