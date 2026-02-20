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

# ── Grant runtime permissions automatically ───────────
# These normally require user approval via UI prompts.
# Granting them here avoids manual setup on a headless gateway.
PKG="com.callagent.gateway"
for PERM in \
    android.permission.RECORD_AUDIO \
    android.permission.READ_PHONE_STATE \
    android.permission.READ_PHONE_NUMBERS \
    android.permission.READ_CALL_LOG \
    android.permission.CALL_PHONE \
    android.permission.ANSWER_PHONE_CALLS \
    android.permission.ACCESS_FINE_LOCATION \
    android.permission.ACCESS_COARSE_LOCATION \
    android.permission.POST_NOTIFICATIONS \
; do
    pm grant "$PKG" "$PERM" 2>/dev/null && \
        log -t "$TAG" "Granted: $PERM" || \
        log -t "$TAG" "Skip (already granted or N/A): $PERM"
done

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
# The bundled binary is ARM64 only — skip on 32-bit devices (e.g. S4 Mini).
DEVICE_ABI=$(getprop ro.product.cpu.abi 2>/dev/null)
if [ -f "$MODDIR/tinymix" ]; then
    case "$DEVICE_ABI" in
        arm64*|aarch64*)
            cp "$MODDIR/tinymix" /data/local/tmp/tinymix
            chmod 755 /data/local/tmp/tinymix
            chown root:root /data/local/tmp/tinymix
            log -t "$TAG" "tinymix: installed ARM64 binary to /data/local/tmp/tinymix"
            ;;
        *)
            log -t "$TAG" "tinymix: skipped (device ABI=$DEVICE_ABI, bundled binary is ARM64)"
            ;;
    esac
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
