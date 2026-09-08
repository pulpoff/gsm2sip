#!/system/bin/sh
# service.sh — runs late in boot (after data is decrypted & mounted)
#
# Keeps the priv-app APK in sync when the user updates via 'adb install -r'.
# The updated APK goes to /data/app/ but the priv-app base in the Magisk
# overlay becomes stale.  This script copies the latest APK so the overlay
# is correct on the NEXT reboot.
#
# Also logs CAPTURE_AUDIO_OUTPUT grant status for debugging.

MODDIR="${0%/*}"
TAG="GatewayMagisk"

MOD_VER=$(grep '^version=' "$MODDIR/module.prop" 2>/dev/null | cut -d= -f2)
log -t "$TAG" "SIP-GSM Gateway Magisk Module ${MOD_VER:-unknown} — service.sh running"

PRIV_DIR="$MODDIR/system/priv-app/Gateway"
PRIV_APK="$PRIV_DIR/Gateway.apk"

# ── Sync APK ──────────────────────────────────────────
# pm path returns the currently-active APK (may be /data/app/ update)
APK_PATH=$(pm path com.callagent.gateway 2>/dev/null | head -1 | sed 's/^package://')

if [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ]; then
    if [ ! -f "$PRIV_APK" ]; then
        # No priv-app APK yet — copy it
        mkdir -p "$PRIV_DIR"
        cp "$APK_PATH" "$PRIV_APK"
        chmod 644 "$PRIV_APK"
        log -t "$TAG" "Created priv-app APK from $APK_PATH (reboot needed)"
    elif ! cmp -s "$APK_PATH" "$PRIV_APK" 2>/dev/null; then
        # APK was updated via adb install — sync it
        cp "$APK_PATH" "$PRIV_APK"
        chmod 644 "$PRIV_APK"
        log -t "$TAG" "Synced updated APK from $APK_PATH (reboot needed for priv-app refresh)"
    else
        log -t "$TAG" "Priv-app APK is up to date"
    fi
else
    log -t "$TAG" "Gateway app not installed — nothing to sync"
fi

# ── Wait for PackageManager ───────────────────────────
# service.sh runs in late_start, which is still early enough that `pm` is not
# answering yet: every grant below silently did nothing on a cold boot, which
# is how RECEIVE_SMS came to be ungranted while the log claimed otherwise.
# Backgrounded so the wait does not hold up Magisk's service stage.
wait_for_pm() {
    i=0
    while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 150 ]; do
        sleep 2
        i=$((i + 1))
    done
    i=0
    while ! pm path android >/dev/null 2>&1 && [ $i -lt 30 ]; do
        sleep 2
        i=$((i + 1))
    done
}

# ── Keep the app's Magisk su policy on "allow" ────────
# The gateway is useless without root: it drives the ALSA mixer through
# tinymix to route agent audio into the GSM uplink, and grants itself
# RECORD_AUDIO via appops.  Denied, it still answers calls and bridges them
# with no audio in either direction, which is a much worse failure than not
# answering at all.
#
# A superuser prompt that nobody is there to answer — this is a headless
# gateway — writes policy=1 (deny) permanently, and that is exactly how a
# working device went silent on 2026-09-09.  Seed policy=2 (allow) on every
# boot so a stray prompt or a reinstall cannot leave it denied.
#
# Note this deliberately overrides a manual deny: on a dedicated gateway that
# is the intent.  Remove this module to take the grant away.
seed_su_policy() {
    # The uid is assigned when the app is installed, so it cannot be baked in
    # at flash time and can change across a reinstall.  Read it back instead.
    SU_UID=$(stat -c %u "/data/user/0/$PKG" 2>/dev/null)
    case "$SU_UID" in
        ''|*[!0-9]*)
            SU_UID=$(dumpsys package "$PKG" 2>/dev/null | grep -m1 -oE 'userId=[0-9]+' | cut -d= -f2)
            ;;
    esac
    case "$SU_UID" in
        ''|*[!0-9]*)
            log -t "$TAG" "su policy: could not resolve uid for $PKG — not seeded"
            return
            ;;
    esac

    # REPLACE/upsert syntax varies with the schema Magisk ships, so branch on
    # whether the row exists rather than relying on a constraint being there.
    if magisk --sqlite "SELECT policy FROM policies WHERE uid=$SU_UID" 2>/dev/null | grep -q policy; then
        magisk --sqlite "UPDATE policies SET policy=2, until=0 WHERE uid=$SU_UID" >/dev/null 2>&1
    else
        magisk --sqlite "INSERT INTO policies (uid,policy,until,logging,notification) VALUES ($SU_UID,2,0,1,1)" >/dev/null 2>&1
    fi

    if magisk --sqlite "SELECT policy FROM policies WHERE uid=$SU_UID" 2>/dev/null | grep -q "policy=2"; then
        log -t "$TAG" "su policy: uid $SU_UID allowed"
    else
        log -t "$TAG" "su policy: FAILED to allow uid $SU_UID — gateway will bridge calls with no audio"
    fi
}

# ── Grant runtime permissions automatically ───────────
# These normally require user approval via UI prompts.
# Granting them here avoids manual setup on a headless gateway.
PKG="com.callagent.gateway"
(
wait_for_pm
seed_su_policy
for PERM in \
    android.permission.RECORD_AUDIO \
    android.permission.READ_PHONE_STATE \
    android.permission.READ_PHONE_NUMBERS \
    android.permission.READ_CALL_LOG \
    android.permission.RECEIVE_SMS \
    android.permission.SEND_SMS \
    android.permission.ACCESS_FINE_LOCATION \
    android.permission.ACCESS_COARSE_LOCATION \
    android.permission.CALL_PHONE \
    android.permission.ANSWER_PHONE_CALLS \
    android.permission.POST_NOTIFICATIONS \
; do
    pm grant "$PKG" "$PERM" 2>/dev/null && \
        log -t "$TAG" "Granted: $PERM" || \
        log -t "$TAG" "Skip (already granted or N/A): $PERM"
done

# ── No outgoing SMS rate limit ────────────────────────
# SmsUsageMonitor stops an app that is not the default SMS app after 30
# messages in 30 minutes and asks the user to confirm — a dialog nobody is
# there to answer on a gateway.  It reads these two globals before falling
# back to the framework defaults, so setting them lifts the cap.
settings put global sms_outgoing_check_interval_ms 1000 2>/dev/null && \
    log -t "$TAG" "Outgoing SMS rate limit lifted" || \
    log -t "$TAG" "Could not lift outgoing SMS rate limit"
settings put global sms_outgoing_check_max_count 1000000 2>/dev/null

# ── Keep SMS traffic silent ───────────────────────────
# The gateway forwards messages; it does not need the device to announce them,
# and nobody is looking at this screen.  Google Messages stays the default SMS
# app - it stores the messages and its copy is a useful independent record -
# but it is not allowed to notify.  Revoking POST_NOTIFICATIONS is what
# actually silences it; the appop is set too, for anything that checks it.
MSGS="com.google.android.apps.messaging"
silence_messages() {
    pm path "$MSGS" >/dev/null 2>&1 || return 1
    pm revoke "$MSGS" android.permission.POST_NOTIFICATIONS 2>/dev/null
    appops set --uid "$MSGS" POST_NOTIFICATION ignore 2>/dev/null
    appops set "$MSGS" POST_NOTIFICATION ignore 2>/dev/null
}

silence_messages && log -t "$TAG" "Silenced notifications: $MSGS"

# Again once the SMS role has settled.  POST_NOTIFICATIONS is granted to the
# default SMS app *by the role*, and the role is re-evaluated during a package
# scan — a fresh module install, for one — which put the permission straight
# back after the first pass revoked it.
(
    sleep 45
    silence_messages && log -t "$TAG" "Silenced notifications (second pass): $MSGS"
) &
) &

# ── PermissionController: hidden by Magisk overlay ────
# The module's filesystem overlay hides PermissionController's APK
# (system/priv-app/PermissionController/.replace), so Android cannot
# start it at all.  Previous approaches all failed:
#   - killall: auto-restarts in ~3s
#   - appops set --uid: overridden immediately
#   - pm disable-user: Android still started it for service binding
#   - Activity launch: can't get TOP state with screen locked
#
# With the APK hidden at the filesystem level, PermissionController
# never runs, never sets MODE_FOREGROUND, and appops stay as set.
#
# Kill any instance that might have started before module mounted.
killall com.google.android.permissioncontroller 2>/dev/null
killall com.android.permissioncontroller 2>/dev/null

# Verify PermissionController is actually gone
if pm list packages 2>/dev/null | grep -q permissioncontroller; then
    log -t "$TAG" "WARNING: PermissionController still visible to pm!"
    # Fallback: force-disable it
    pm disable com.android.permissioncontroller 2>/dev/null
    pm disable com.google.android.permissioncontroller 2>/dev/null
else
    log -t "$TAG" "PermissionController: hidden by Magisk overlay"
fi

# ── Force-allow RECORD_AUDIO via appops ───────────────
# With PermissionController gone, this setting persists permanently.
# Set both UID-level and package-level modes for maximum compatibility.
appops set --uid "$PKG" RECORD_AUDIO allow 2>/dev/null
appops set "$PKG" RECORD_AUDIO allow 2>/dev/null && \
    log -t "$TAG" "appops RECORD_AUDIO: forced allow (--uid + pkg)" || \
    log -t "$TAG" "appops RECORD_AUDIO: failed to set"

# Verification: wait 5 seconds and confirm the mode stuck.
(
    sleep 5
    MODE=$(appops get "$PKG" RECORD_AUDIO 2>/dev/null)
    log -t "$TAG" "appops RECORD_AUDIO verify: $MODE"
    if echo "$MODE" | grep -qi "foreground\|ignore\|deny"; then
        # Something re-revoked — kill and re-assert
        killall com.google.android.permissioncontroller 2>/dev/null
        killall com.android.permissioncontroller 2>/dev/null
        appops set --uid "$PKG" RECORD_AUDIO allow 2>/dev/null
        appops set "$PKG" RECORD_AUDIO allow 2>/dev/null
        log -t "$TAG" "appops RECORD_AUDIO: re-asserted after revert"
    fi
) &

# ── Ensure tinymix is available ────────────────────────
# tinymix is needed to control ABOX/ALSA mixer for incall_music injection.
# /system/bin/tinymix via Magisk overlay can hit SELinux "Permission denied"
# on some devices, so we install to /data/local/tmp/ which has a permissive
# context.  The app prefers /data/local/tmp/ in its discovery order.
# tinymix is bundled once per ABI: the ALSA control ioctls encode the size of
# structs holding `long`, so an ARM64 build and an armeabi-v7a build speak
# different ioctl ABIs and neither works on the other's kernel.
DEVICE_ABI=$(getprop ro.product.cpu.abi 2>/dev/null)
case "$DEVICE_ABI" in
    arm64*|aarch64*) TINYMIX_SRC="$MODDIR/tinymix" ;;
    arm*)            TINYMIX_SRC="$MODDIR/tinymix32" ;;
    *)               TINYMIX_SRC="" ;;
esac
if [ -n "$TINYMIX_SRC" ] && [ -f "$TINYMIX_SRC" ]; then
    cp "$TINYMIX_SRC" /data/local/tmp/tinymix
    chmod 755 /data/local/tmp/tinymix
    chown root:root /data/local/tmp/tinymix
    log -t "$TAG" "tinymix: installed $DEVICE_ABI binary to /data/local/tmp/tinymix"
else
    log -t "$TAG" "tinymix: no bundled build for ABI=$DEVICE_ABI"
fi
TINYMIX_FOUND=false
for TPATH in /data/local/tmp/tinymix /vendor/bin/tinymix /system/bin/tinymix /system/xbin/tinymix; do
    if [ -x "$TPATH" ]; then
        TINYMIX_FOUND=true
        log -t "$TAG" "tinymix: using $TPATH"
        break
    fi
done
if [ "$TINYMIX_FOUND" = "false" ]; then
    log -t "$TAG" "tinymix: NOT FOUND — ABOX mixer controls will not work"
fi

# ── Ensure tinycap is available ───────────────────────
# tinycap is needed to probe ALSA capture PCMs for modem downlink audio.
# Same deployment strategy as tinymix: /data/local/tmp/ for SELinux compat.
# The bundled tinycap is an ARM64 C build with no source in this tree, so on a
# 32-bit device fall back to the ROM's own — LineageOS ships tinyplay/tinycap/
# tinypcminfo on the msm8960 devices, and the app only ever looks for tinycap
# at the /data/local/tmp path.
TINYCAP_SRC=""
case "$DEVICE_ABI" in
    arm64*|aarch64*) [ -f "$MODDIR/tinycap" ] && TINYCAP_SRC="$MODDIR/tinycap" ;;
    *)               [ -x /system/bin/tinycap ] && TINYCAP_SRC=/system/bin/tinycap ;;
esac
if [ -n "$TINYCAP_SRC" ]; then
    cp "$TINYCAP_SRC" /data/local/tmp/tinycap
    chmod 755 /data/local/tmp/tinycap
    chown root:root /data/local/tmp/tinycap
    log -t "$TAG" "tinycap: installed $TINYCAP_SRC to /data/local/tmp/tinycap"
else
    log -t "$TAG" "tinycap: none available for ABI=$DEVICE_ABI — PCM probe will be skipped"
fi

# ── Log ALSA card info for diagnostics ────────────────
ALSA_CARDS=$(cat /proc/asound/cards 2>/dev/null)
if [ -n "$ALSA_CARDS" ]; then
    log -t "$TAG" "ALSA cards: $ALSA_CARDS"
fi

# ── Log privileged permission status ──────────────────
PERM_DUMP=$(dumpsys package "$PKG" 2>/dev/null)
for PERM in CAPTURE_AUDIO_OUTPUT MODIFY_PHONE_STATE READ_PRIVILEGED_PHONE_STATE CALL_PRIVILEGED; do
    if echo "$PERM_DUMP" | grep -q "$PERM.*granted=true"; then
        log -t "$TAG" "$PERM: GRANTED"
    else
        log -t "$TAG" "$PERM: NOT GRANTED — check priv-app install, reboot may be needed"
    fi
done

# Log install location for debugging
log -t "$TAG" "APK path: $APK_PATH"
log -t "$TAG" "Priv-app: $(ls -la $PRIV_APK 2>/dev/null || echo 'missing')"
