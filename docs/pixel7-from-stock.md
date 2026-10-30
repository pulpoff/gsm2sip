# Pixel 7 gateway from stock

A start-to-finish setup for a **Google Pixel 7 (`panther`)**: from a stock or
GrapheneOS phone to LineageOS, root, and a working gsm2sip gateway with both
call directions digital.

What was used when this was written (October 2026) — newer builds should work
the same way, but check the LineageOS firmware requirement each time:

| | |
|---|---|
| Stock firmware | Android 17, `CP3A.261005.002.A1` |
| ROM | LineageOS 24.0 nightly |
| Root | Magisk 31.1 |
| Host | Linux with Docker, `adb`/`fastboot` from Google's platform-tools |

> **Everything on the phone is erased** — unlocking the bootloader wipes it,
> and so do the stock and LineageOS installs.  Use a phone dedicated to the
> gateway.

Why the Pixel 7 needs more than the README's generic steps, in short: its
audio is on Google's AoC DSP, not a Qualcomm codec, so it needs the
`Tensor GS201` device profile; capture is read straight from an ALSA PCM over
root; and on Android 17 Magisk needs an extra SELinux rule before the app can
use root at all.  The README's *Supported Devices* section has the detail.

---

## 1. Host tools

`adb` and `fastboot` must be recent — Debian/Ubuntu's packaged 29.x
`fastboot` is too old for current Pixel factory images (the flash script wants
33.0.1 or newer).  Either install Google's platform-tools, or run them from a
container:

```dockerfile
# Dockerfile
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates unzip usbutils && rm -rf /var/lib/apt/lists/*
RUN curl -sSL -o /tmp/pt.zip https://dl.google.com/android/repository/platform-tools-latest-linux.zip \
    && unzip -q /tmp/pt.zip -d /opt && rm /tmp/pt.zip
ENV PATH=/opt/platform-tools:$PATH
WORKDIR /work
```

```bash
docker build -t android-tools .
mkdir -p adb-keys
docker run -d --name android-tools --privileged \
  -v /dev/bus/usb:/dev/bus/usb \
  -v "$PWD/adb-keys:/root/.android" \
  -v "$PWD:/work" \
  android-tools sleep infinity
alias adb='docker exec -it android-tools adb'
alias fastboot='docker exec -it android-tools fastboot'
```

- Mounting `adb-keys` keeps the adb key across container restarts, so the
  phone only asks to trust the computer once per wipe.
- The phone re-enumerates on USB every time it reboots or changes mode
  (system → bootloader → recovery → sideload).  The adb server in the
  container often keeps a stale handle: if `adb devices` comes back empty
  while the phone is clearly connected, run `adb kill-server; adb start-server`.
- On Linux hosts, install your distribution's Android udev rules
  (e.g. `android-udev` on Arch) so the device nodes are accessible.

## 2. Developer options and OEM unlocking

On stock Android or GrapheneOS:

1. **Settings → About phone →** tap **Build number** seven times, confirm with
   your PIN.
2. **Settings → System → Developer options:** enable **OEM unlocking** and
   **USB debugging**.  If OEM unlocking is greyed out, connect to the internet
   once and check again.
3. Plug the phone in, accept the **Allow USB debugging?** prompt with
   *Always allow from this computer*, and check `adb devices` lists it as
   `device`.

Optional but worth it: before wiping, `tools/check-device.sh` (no root needed
for the first checks) confirms the audio policy has the routes the gateway
uses.

## 3. Unlock the bootloader

```bash
adb reboot bootloader
fastboot devices                 # should list the phone; product: panther
fastboot flashing unlock
```

Confirm on the phone with the volume keys and power.  The phone wipes itself.
`fastboot getvar unlocked` should now say `yes`.

## 4. Flash Android 17 stock firmware

LineageOS for `panther` requires a **specific stock firmware version** (the
bootloader, radio and vendor images that only the stock OS updates).  At the
time of writing that is **Android 17**; check the current requirement on the
[LineageOS install page for panther](https://wiki.lineageos.org/devices/panther/install/)
— being on another ROM (GrapheneOS included) does not count, even if its
Android version matches.

1. Download the newest Android 17 **`panther`** image from
   [developers.google.com/android/images](https://developers.google.com/android/images#panther)
   (you have to accept Google's terms there).  Make sure it says `panther` —
   `bluejay` (Pixel 6a) and the other codenames are different phones.
2. Check its SHA-256 against the value on the page, unzip it, and run the
   flash script from inside the extracted folder while the phone is in
   fastboot mode:

   ```bash
   unzip panther-*-factory-*.zip
   cd panther-*/
   ./flash-all.sh       # bootloader, radio, then the system image with -w
   ```

   `Erase successful, but not automatically formatting` and
   `wipe task partition not found: cache` are normal.
3. Let it boot once, go through setup **without adding a Google account**
   (factory reset protection), and if you can, check a call and an SMS work
   on stock.
4. Enable developer options and USB debugging again (step 2).  The phone has
   forgotten your computer, so accept the debugging prompt again.

`adb shell getprop ro.build.version.release` should say `17`, and
`ro.bootloader` should match the bootloader version in the factory image.

## 5. Install LineageOS

From the [LineageOS downloads for panther](https://download.lineageos.org/devices/panther/builds),
fetch the newest build's `boot.img`, `dtbo.img`, `vendor_kernel_boot.img`,
`vendor_boot.img` (Lineage Recovery) and the `lineage-*-panther-signed.zip`,
and check each against its published SHA-256.

```bash
adb reboot bootloader
fastboot flash boot boot.img
fastboot flash dtbo dtbo.img
fastboot flash vendor_kernel_boot vendor_kernel_boot.img
fastboot reboot bootloader
fastboot flash vendor_boot vendor_boot.img
fastboot reboot recovery
```

In Lineage Recovery (it shows the LineageOS logo — if not, start this section
again):

1. **Factory reset → Format data / factory reset**, confirm.
2. Back to the main menu, **Apply update → Apply from ADB**.
3. On the host:

   ```bash
   adb sideload lineage-*-panther-signed.zip
   ```

   Stopping at ~47% with `Total xfer: 1.00x` is a success.
4. Answer **No** to *reboot to recovery to install additional packages* — the
   gateway needs no Google apps, and Magisk is installed later from the
   running system.
5. **Reboot system now.**  First boot can take several minutes.

Set LineageOS up, then enable developer options and USB debugging again.

## 6. Root with Magisk

The Pixel 7 boots through `init_boot`, so that is the image Magisk patches.
Use the `init_boot.img` from the **same** LineageOS build you installed.

```bash
adb install Magisk-v*.apk                  # from github.com/topjohnwu/Magisk/releases
adb push init_boot.img /sdcard/Download/
```

1. Open **Magisk**, let it finish any setup it asks for.
2. **Install → Select and Patch a File →** `Download/init_boot.img` → **Let's go**.
3. Flash the result:

   ```bash
   adb pull /sdcard/Download/magisk_patched-*.img
   adb reboot bootloader
   fastboot flash init_boot magisk_patched-*.img
   fastboot reboot
   ```

**Root for adb.**  `tools/check-device.sh` and the debugging steps below use
`adb shell su`.  If `adb shell su -c id` says `Permission denied` without a
prompt, open **Magisk → Superuser**: a *Shell* entry that is switched off is
a saved deny — switch it on.

## 7. Build and install the gateway module

Until a release includes the Pixel 7 profile, build the module from source
(see *Build* in the README — JDK 17 and an Android SDK with platform 34):

```bash
./build.sh release      # produces gateway-magisk.zip
```

`build.sh release` signs with `~/.android/debug.keystore`; create one with
`keytool` if the build host has none.  If the phone already runs the gateway
signed with a different key, Android clears the app's data when the new one
replaces it — re-enter the SIP settings afterwards.

```bash
adb push gateway-magisk.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/gateway-magisk.zip'
adb reboot
```

The install log should end with `Installing custom sepolicy rules` — that is
the `priv_app_36` rule; without it the app cannot use root on Android 17.
`PermissionController path not found` is expected: on Android 17 the
permission controller lives in an APEX and cannot be hidden by an overlay.
Capture on this profile does not go through AudioRecord, so the cold-boot
RECORD_AUDIO problem that hiding it works around does not apply.

Then, on the phone:

1. **Settings → Apps → Default apps → Phone app → gsm2sip** (the module may
   already have taken the role — `adb shell cmd role get-role-holders android.app.role.DIALER`).
2. In gsm2sip **Settings**: SIP server, port, username, password, and the
   SIM's **Own Number** in international format.  The globe badge turns green
   once registered.

## 8. Check it

```bash
tools/check-device.sh        # with the gateway module installed and root for adb
```

All six Tensor checks should pass.  During a call, the mixer should read:

```bash
adb shell su -c /data/local/tmp/tinymix | grep -E 'Incall (Capture|Playback) Stream|Incall (Mic|Sink) Mute'
```

| Control | During a call | Meaning |
|---|---|---|
| `Incall Playback Stream1` | `On` | agent audio injected into the uplink |
| `Incall Capture Stream0` | `DL` | caller read from the telephony-rx PCM |
| `Incall Mic Mute` / `Incall Sink Mute` | `On` | handset mic and earpiece out of the call |

All four return to `Off` after hang-up.  In the app log (`adb logcat -s
RtpSession RootPcmCapture`), a good call shows
`Selected profile: Tensor GS201 (Pixel 7)` and
`Capture started: source=PCM0:20@48k capRate=16000`.

## Troubleshooting

| Symptom | Cause |
|---|---|
| Caller hears the agent from the phone's speaker, not in the call; agent hears the room | Profile is `Generic` — the build does not have the Pixel 7 profile, or `ro.board.platform` is not `gs201` |
| `appops re-assert: [] ok=false`, every mixer command silent, logcat `avc: denied { connectto } ... scontext=u:r:priv_app_36` | The `sepolicy.rule` is missing — reinstall the module from a build that has it |
| Caller → agent distorted and very loud | Something besides `tinycap` wrote to the capture pipe (an odd byte count shifts every sample) |
| Caller hears their own voice, muffled | Handset mic still in the call — check `Incall Mic Mute` |
| `adb: no devices/emulators found` after a reboot, phone still plugged in | Stale adb server: `adb kill-server; adb start-server` |
| Magisk denies Shell without asking | Saved deny under **Magisk → Superuser → Shell** |
