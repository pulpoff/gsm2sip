#!/system/bin/sh
# Magisk module installation script
# Installs the gateway app as a system priv-app with elevated permissions
# (required for CAPTURE_AUDIO_OUTPUT — telephony audio capture)

SKIPUNZIP=1

# Legacy-module control variables.  Magisk's install_module() reads these after
# sourcing this script and gives them no defaults of its own, so leaving one
# unset is not the same as false: `$SKIPMOUNT && touch $MODPATH/skip_mount`
# expands to an empty command, which exits 0, and the touch then runs.  An
# unset SKIPMOUNT therefore silently produces a skip_mount file and Magisk
# mounts nothing — no priv-app APK, no /system/bin overlay.
#
# The other three are copy-backs from the installer's temp dir.  Everything
# this module ships is already unzipped into $MODPATH below, so they stay
# false and the files we extract ourselves are the ones that count.
SKIPMOUNT=false
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=false

# Show version
MOD_VER=$(grep '^version=' "$MODPATH/../module.prop" 2>/dev/null | cut -d= -f2)
[ -z "$MOD_VER" ] && MOD_VER=$(unzip -p "$ZIPFILE" module.prop 2>/dev/null | grep '^version=' | cut -d= -f2)
ui_print "- SIP-GSM Gateway Magisk Module ${MOD_VER:-unknown}"
ui_print ""

# Extract module files
ui_print "- Extracting module files"
unzip -o "$ZIPFILE" -x 'META-INF/*' -d $MODPATH

# ── Ensure APK is in priv-app ────────────────────────
# build.sh includes the APK in the zip, but if the module was built
# from git without build.sh, or if the user wants to update the APK
# independently, we fall back to copying the already-installed APK.

PRIV_DIR="$MODPATH/system/priv-app/Gateway"
PRIV_APK="$PRIV_DIR/Gateway.apk"

if [ -f "$PRIV_APK" ]; then
    ui_print "- APK found in module (from build.sh)"
else
    ui_print "- APK not in module, searching installed apps..."
    # Find the APK installed via 'adb install' or Play Store
    APK_PATH=$(pm path com.callagent.gateway 2>/dev/null | head -1 | sed 's/^package://')
    if [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ]; then
        mkdir -p "$PRIV_DIR"
        cp "$APK_PATH" "$PRIV_APK"
        ui_print "- Copied installed APK to priv-app: $APK_PATH"
    else
        ui_print "! WARNING: Gateway APK not found!"
        ui_print "! Install the APK first (adb install gateway.apk),"
        ui_print "! then reinstall this Magisk module."
    fi
fi

# ── Install the tinymix build that matches this device ─
# tinymix ships as two static builds.  The ALSA control ioctls encode the size
# of structs that contain `long`, so an ARM64 binary and an armeabi-v7a one
# speak different ioctl ABIs, and only the matching one works.
#
# Getting this wrong is quiet, not loud.  The module used to overlay an ARM64
# tinymix unconditionally, which on the 32-bit Galaxy S4 Mini shadowed the
# ROM's own working copy with a binary the linker refuses to load —
# "not executable: 64-bit ELF file" — while the app, which only tested the
# executable bit, went on believing it had tinymix and failed every mixer
# command in silence.
DEVICE_ABI=$(getprop ro.product.cpu.abi 2>/dev/null)
case "$DEVICE_ABI" in
    arm64*|aarch64*) TINYMIX_SRC="$MODPATH/tinymix" ;;
    arm*)            TINYMIX_SRC="$MODPATH/tinymix32" ;;
    *)               TINYMIX_SRC="" ;;
esac

rm -f "$MODPATH/system/bin/tinymix"
if [ -n "$TINYMIX_SRC" ] && [ -f "$TINYMIX_SRC" ]; then
    mkdir -p "$MODPATH/system/bin"
    cp "$TINYMIX_SRC" "$MODPATH/system/bin/tinymix"
    ui_print "- tinymix: installed the $DEVICE_ABI build"
else
    # Nothing to offer — leave /system/bin alone so that if the ROM ships its
    # own tinymix it stays reachable.
    rmdir "$MODPATH/system/bin" 2>/dev/null
    ui_print "! tinymix: no build for $DEVICE_ABI, leaving the ROM's in place"
fi

# ── Hide PermissionController ─────────────────────────
# PermissionController sets RECORD_AUDIO appop to MODE_FOREGROUND which
# denies AudioRecord for foreground services on cold boot (no Activity
# in TOP state).  Hiding the APK via Magisk overlay prevents it from
# running at all.  On a dedicated gateway device, permission management
# UI is never used — the module grants all permissions in service.sh.
PC_HIDDEN=false
for PC_PATH in \
    /system/priv-app/PermissionController \
    /system/product/priv-app/PermissionController \
    /system/system_ext/priv-app/PermissionController \
    /system/priv-app/GooglePermissionController \
    /system/product/priv-app/GooglePermissionController \
; do
    if [ -d "$PC_PATH" ]; then
        # Create overlay directory with .replace to hide the real one
        PC_OVERLAY="$MODPATH${PC_PATH}"
        mkdir -p "$PC_OVERLAY"
        touch "$PC_OVERLAY/.replace"
        ui_print "- Hiding PermissionController: $PC_PATH"
        PC_HIDDEN=true
    fi
done
if [ "$PC_HIDDEN" = "false" ]; then
    # Fallback: create the common AOSP path anyway
    mkdir -p "$MODPATH/system/priv-app/PermissionController"
    touch "$MODPATH/system/priv-app/PermissionController/.replace"
    ui_print "- PermissionController path not found, using default overlay"
fi

# ── Set permissions ───────────────────────────────────
set_perm_recursive $MODPATH 0 0 0755 0644
if [ -d "$PRIV_DIR" ]; then
    set_perm_recursive $MODPATH/system/priv-app 0 0 0755 0644
fi
# tinymix/tinycap need execute permission
if [ -f "$MODPATH/tinymix" ]; then
    chmod 755 "$MODPATH/tinymix"
fi
if [ -f "$MODPATH/tinymix32" ]; then
    chmod 755 "$MODPATH/tinymix32"
fi
if [ -f "$MODPATH/tinycap" ]; then
    chmod 755 "$MODPATH/tinycap"
fi
if [ -f "$MODPATH/system/bin/tinymix" ]; then
    chmod 755 "$MODPATH/system/bin/tinymix"
fi

# Ensure module mount is never skipped
rm -f "$MODPATH/skip_mount"

ui_print ""
ui_print "- SIP-GSM Gateway installed as priv-app"
ui_print "- Privileged permissions configured:"
ui_print "    CAPTURE_AUDIO_OUTPUT, MODIFY_PHONE_STATE,"
ui_print "    READ_PRIVILEGED_PHONE_STATE, CALL_PRIVILEGED"
ui_print "- PermissionController hidden (cold boot audio fix)"
ui_print "- Runtime permissions will be auto-granted on boot"
ui_print ""
ui_print "- Reboot required to activate"
