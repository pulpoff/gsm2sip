#!/bin/bash
#
# Decide whether a phone can run the gateway with a fully digital audio path.
#
# Support is a property of the vendor image, not of the chip.  The Qualcomm
# audio HAL family carries this code almost universally, but whether the OEM
# built it in, and whether the audio policy exposes a route to the modem
# uplink, varies per device and per ROM.
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

if [ "$(sh_ getprop ro.hardware)" != "qcom" ]; then
    echo "Not a Qualcomm device — stop here."
    echo "Injection needs incall_music and capture needs the in-call record"
    echo "session; neither exists outside the Qualcomm audio HAL.  A Samsung"
    echo "Exynos S10e was checked and has no telephony route in its audio"
    echo "policy at all: three mixPorts (deep, fast, primary) and nothing else."
    exit 1
fi

if sh_ 'pm list features' | grep -q 'feature:android.hardware.telephony'; then
    ok "telephony hardware present"
else
    no "no telephony — this device cannot place GSM calls (WiFi-only?)"
fi

# ── 1. audio policy (no root needed) — the decisive one ──────────────
echo
echo "=== 1. Audio policy: is there a route to the modem uplink? ==="
POL=/vendor/etc/audio_policy_configuration.xml
if sh_ "grep -q incall_music_uplink $POL && echo y" | grep -q y; then
    ok "incall_music_uplink mixPort declared"
    sh_ "grep -A4 'mixPort name=\"incall_music_uplink\"' $POL" | grep -i channelmasks | sed 's/^/            /'
    sh_ "grep -i 'sink=\"Telephony Tx\"' $POL" | head -1 | sed 's/^/            /'
else
    no "no incall_music_uplink mixPort — nothing to inject the agent into"
fi

# ── 2. HAL (root) ────────────────────────────────────────────────────
echo
echo "=== 2. Audio HAL ==="
if ! have_root; then
    unk "needs root — enable Magisk Superuser access = 'Apps and ADB', then"
    echo "            run 'adb shell su -c id' once and grant the prompt"
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
    TM=""
    for p in /data/local/tmp/tinymix /vendor/bin/tinymix /system/bin/tinymix; do
        su_ "[ -x $p ] && echo yes" | grep -q yes && { TM=$p; break; }
    done
    if [ -z "$TM" ]; then
        unk "tinymix not present — push magisk/tinymix to /data/local/tmp/ (chmod 755)"
    else
        M=$(su_ "$TM")
        n=$(echo "$M" | grep -c 'Incall_Music Audio Mixer')
        [ "$n" -gt 0 ] && ok "Incall_Music Audio Mixer ($n ports)" || no "Incall_Music Audio Mixer"
        echo "$M" | grep -q VOC_REC_DL && ok "VOC_REC_DL / VOC_REC_UL" || no "VOC_REC_* capture routing"
        echo "$M" | grep -q "Voc Rec Config" && ok "Voc Rec Config" || no "Voc Rec Config"
    fi
fi

# ── verdict ──────────────────────────────────────────────────────────
echo
echo "=== Verdict ==="
if [ "$fail" -eq 0 ] && [ "$unknown" -eq 0 ]; then
    echo "  Fully supported on paper: $pass/$pass checks passed."
    echo "  A DeviceProfile entry will still be needed — the mixer names are"
    echo "  generic, but which front-end the playback track lands on is not."
    echo "  The 'Mixer BEFORE/AFTER' lines logged around each call show it."
elif [ "$fail" -eq 0 ]; then
    echo "  Promising: $pass checks passed, $unknown could not be checked."
    echo "  Root the device and re-run for a definite answer."
elif [ "$pass" -eq 0 ]; then
    echo "  Not supported: nothing required is present."
else
    echo "  Partial: $pass present, $fail missing, $unknown unchecked."
    echo "  Without incall_music there is no digital path to the caller;"
    echo "  without the in-call record usecases the agent can only hear the"
    echo "  caller acoustically, through the phone's own microphone."
fi
