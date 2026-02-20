#!/system/bin/sh
# Remove skip_mount if it exists — this script runs even when skip_mount
# is present, so it guarantees the module's filesystem overlay is active
# on the next boot.
MODDIR="${0%/*}"
rm -f "$MODDIR/skip_mount"
