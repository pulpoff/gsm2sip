#!/system/bin/sh
#
# Keep ADB-over-network on across reboots.
#
# Not installed by the module — copy it to /data/adb/service.d/ on a gateway
# phone you want to reach without the cable:
#
#   adb push tools/adb-over-network.sh /data/local/tmp/
#   adb shell su -c 'cp /data/local/tmp/adb-over-network.sh /data/adb/service.d/ \
#                    && chmod 755 /data/adb/service.d/adb-over-network.sh'
#
# It is opt-in on purpose.  A phone running this accepts ADB from anything that
# can reach it on the LAN, with no prompt and no key check beyond the usual
# adb one, so it belongs on a gateway on a network you trust and nowhere else.
#
# Why it is needed at all: LineageOS turns the Developer Options "ADB over
# network" toggle off at every boot.  The toggle sets service.adb.tcp.port and
# restarts adbd, and nothing re-applies it afterwards — so setting
# persist.adb.tcp.port is not enough on its own.  Both properties survive the
# reboot and adbd still comes up listening on USB only, because nothing
# restarted it once the property was there.  This does the missing half.
#
# Magisk runs everything in /data/adb/service.d/ as root in late_start service
# mode.  The delay keeps the adbd restart clear of the rest of boot; on a
# serranolte (Android 9) 30s is comfortable.
sleep 30
setprop service.adb.tcp.port 5555
stop adbd
start adbd
