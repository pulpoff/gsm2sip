#!/system/bin/sh
# Magisk module installation script
# Installs the gateway app as a system priv-app with elevated permissions
# (required for CAPTURE_AUDIO_OUTPUT — telephony audio capture)

SKIPUNZIP=1

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
# tinymix needs execute permission
if [ -f "$MODPATH/tinymix" ]; then
    chmod 755 "$MODPATH/tinymix"
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
