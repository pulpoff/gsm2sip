# gsm2sipd — the gsm2sip gateway on Linux phones

The Android gateway's job, done by a daemon on a phone running mainline Linux
(Mobian/Debian with ModemManager): the SIM's calls and SMS are bridged to a
SIP server, exactly as the app does it, so a server that already speaks to
the app needs no change.

- **Inbound call**: the SIM rings → INVITE to `sip:<own_number>@server` →
  the server answers → the GSM call is answered → audio. The caller hears
  ringing until the agent is there, not dead air.
- **Outbound call**: an INVITE with `X-GSM-Forward: <number>`, or a number as
  the Request-URI user → `180` → the modem dials → `200` with SDP once the
  GSM side answers.
- **SMS**: a received SMS becomes a MESSAGE to `sip:<own_number>@server`
  (the same `X-SMS-*` headers as the app) and is deleted from the modem once
  the server accepted it; a MESSAGE from the server is answered `202`, sent
  by the modem, and reported back (`X-SMS-Event: submitted` / `failed`).

Signalling is the app's, byte for byte where it matters: the same REGISTER,
digest auth, User-Agent (`gsm2sip v1.4.17 <hostname>`), SDP, `X-SMS-*`
headers, status codes and retry rules. G.722 by default, G.711 A-law/µ-law
when configured, SDES-SRTP over TLS, DTMF from SIP as RFC 4733 events or
SIP INFO, STUN for the public address.

It is a port of the Kotlin in `app/src/main/java/com/callagent/gateway/`
(`sip/`, `rtp/`, `bridge/CallOrchestrator.kt`, `net/StunClient.kt`) onto one
GLib main loop plus three media threads. Nothing of Android is needed: the
modem is ModemManager over D-Bus, the call audio is two ALSA PCMs.

## Layout

| file | what |
|---|---|
| `src/sipmsg.[ch]` | SIP parsing and the message builders (SipMessage.kt, SipAuth.kt) |
| `src/sipclient.[ch]` | registration, transactions, dialogs, UDP and TLS transport (SipClient.kt, SipCall.kt, SipTransport.kt) |
| `src/bridge.[ch]` | the call state machine and the SMS relay (CallOrchestrator.kt, GatewayService.kt) |
| `src/modem.[ch]` | ModemManager: calls, dialling, SMS |
| `src/rtp.[ch]` | RTP/SRTP, ALSA capture and playback, jitter queue |
| `src/dsp.[ch]` | resamplers, G.711, RFC 4733, the DTMF tone generator |
| `src/g722.[ch]` | G.722 (G722Codec.kt) |
| `src/stun.[ch]` | STUN binding requests (StunClient.kt) |
| `src/gwutil.[ch]` | number and SMS helpers, ids, timestamps, the ASCII fold (Transliterate.kt) |
| `src/recents.[ch]` | the recent calls and messages (CallLogStore.kt), in `/var/lib/gsm2sip/recents` |
| `src/service.[ch]` | the D-Bus interface the app talks to |
| `app/` | the GNOME app (C, GTK 4, libadwaita): the Android app's screens |
| `tests/test_main.c` | unit tests (no modem, ALSA or network needed) |
| `tools/conf-from-android-xml.py` | the app's settings → `gateway.conf` |

## Build

Needs GLib/GIO ≥ 2.70, ALSA, and libsrtp2 for SRTP (optional: without it
`srtp=true` is ignored with a warning and calls use plain RTP).

Native, with the unit tests:

```
meson setup build-native -Dsrtp=disabled
ninja -C build-native && meson test -C build-native
```

Cross, for the S10e (aarch64), with clang and lld against the phone's own
rootfs plus a small overlay holding the ALSA and libsrtp2 headers and
libraries (paths in `cross-arm64.ini`):

```
meson setup build-arm64 --cross-file cross-arm64.ini
ninja -C build-arm64
```

The unit tests can also run on aarch64 under qemu-user, against the phone's
GLib — the run that catches `char` signedness slips, since `char` is
unsigned there:

```
meson setup build-arm64-test --cross-file cross-arm64.ini --cross-file cross-arm64-qemu.ini
meson test -C build-arm64-test
```

## Configure

`/etc/gsm2sip/gateway.conf`, mode 600 (it holds the SIP password; the daemon
warns at start if group or others can read it). See
`data/gateway.conf.example` for every key.

To carry over a phone's Android settings, pull the app's
`shared_prefs/gateway.xml` and convert it. The tool prints only which
settings it found, never their values, writes the file mode 600, and
refuses to replace an existing one without `--force`:

```
tools/conf-from-android-xml.py gateway.xml gateway.conf
```

## Install on the S10e

```
# on the phone
sudo apt install libsrtp2-1            # 2.8 in forky, what the build links against
sudo install -m 755 gsm2sipd /usr/sbin/gsm2sipd
sudo install -d -m 700 /etc/gsm2sip
sudo install -m 600 gateway.conf /etc/gsm2sip/gateway.conf
sudo install -m 644 gsm2sipd.service /usr/lib/systemd/system/gsm2sipd.service
sudo systemctl daemon-reload
sudo systemctl enable --now gsm2sipd
journalctl -u gsm2sipd -f
```

`SIGUSR1` re-registers at once (after the network came back, say);
`SIGHUP` takes up an edited `gateway.conf` (once no call is up);
`SIGTERM` ends any call on both legs before the daemon exits. `-v` logs the
first line of every SIP message.

**The phone's own dialler has to be off while the gateway runs.** GNOME
Calls (and callaudiod) see the same ModemManager calls the gateway does:
left running, they ring the phone, may answer, and switch the call audio
routing under the gateway; Chatty takes the SMS off the modem. The app's
Enabled switch does this both ways (below). By hand it is
`systemctl --user mask --now calls-daemon.service sm.puri.Chatty-daemon.service`,
and `unmask` + `start` to get the phone back.

## The app and the D-Bus interface

`app/` is the GNOME app, the Android app's Home, Settings and Logs screens.
It runs as the phone's user and talks to the daemon over the system bus:
`com.callagent.Gsm2sipd`, object `/com/callagent/Gsm2sipd`, interface
`com.callagent.Gsm2sipd1`. Only root and the `dialout` group (the phone's
user on Mobian) may call it — `data/com.callagent.Gsm2sipd.conf` goes in
`/usr/share/dbus-1/system.d/`. The SIP password is write-only: nothing on
the bus carries it back out.

| | |
|---|---|
| properties | `Version`, `Enabled`, `State` (`unconfigured` `disabled` `connecting` `registered` `failed`), `OnlineSince`, `Server`, `User`, `CallState` (the bridge's, lower case), `CallInfo`, `CallNumber`, `CallIncoming`, `CallBridgedAt` (ms), `AgentMuted`, `RestartPending` |
| methods | `GetRecents` → `aa{sv}` (newest first, Android's keys), `ClearRecents`, `GetLog` → `as`, `ClearLog`, `GetConfig` → `a{sv}` (`has_password`, never the password), `SetConfig(a{sv})`, `SetEnabled(b)`, `Reconnect`, `Hangup`, `SetAgentMuted(b)` |
| signals | `RecentsChanged`, `LogLines(as)` |

`SetConfig` and `SetEnabled` rewrite `gateway.conf` (mode 600) and restart
the daemon in place (a re-exec, same PID) — a call in progress is not
dropped for `SetConfig`, the restart waits for it; switching off ends it.

**Enabled** (`[gateway] enabled`, default true): off, the daemon runs but
leaves the modem alone, so the phone's own apps take its calls and SMS. The
app hands those apps the modem back when it switches the gateway off, and
masks and stops them before switching it on. Without a complete `[sip]`
section the daemon also waits, `unconfigured`, for the app's settings.

## Sleep

A gateway must hear the SIP server while the phone sleeps. Where the Wi-Fi
can wake the phone (the device's `power/wakeup` in sysfs reads `enabled`;
on the S10e, brcmfmac with `host_wake=gpa0:7`), the daemon lets it sleep:
an INVITE, a MESSAGE or the server's OPTIONS wakes it, and so does a GSM
call or SMS. Elsewhere it holds a logind `sleep` block inhibitor while the
gateway is on, so the phone stays up; it looks again every 30 s. Its SIP
timers run on `CLOCK_BOOTTIME`, and the next re-REGISTER (or UDP keepalive,
or retry) has a `CLOCK_BOOTTIME_ALARM` timer that wakes the phone for it.

## Call audio on the S10e

The exynos9820-abox card exposes the voice path directly:

| PCM | direction | format |
|---|---|---|
| `hw:0,2` | capture: the GSM downlink (the caller) | S16_LE, 2 ch, 32 kHz |
| `hw:0,3` | playback: into the GSM uplink (to the caller) | S32_LE, 2 ch, 48 kHz |

The mixer switch `Call Uplink Switch` on `hw:0` connects the playback PCM to
the uplink; the daemon turns it on when it opens playback and off when the
call ends. The capture is mixed to mono (or one channel,
`capture_channel=left|right`), resampled to 16 kHz for G.722 or 8 kHz for
G.711; what comes back is decoded and resampled ×3/×6 to 48 kHz. The PCMs
are opened as `hw:` devices, so nothing else (PipeWire, PulseAudio) may
hold them during a call. Another device needs only the `[audio]` section
changed.

## Known gaps

- **DTMF to GSM is in-band only.** The S10e modem path (cpd) has no `+VTS`,
  so ModemManager cannot send DTMF; digits from SIP are generated as tones
  into the uplink instead (120 ms minimum, 60 ms gap, 2 s watchdog), as the
  app does on most phones. DTMF from the GSM side reaches SIP only as
  audio, as in the app.
- **No country guessing.** Android looked up the SIM's country to turn a
  national `own_number` into international form; here `00…` becomes `+…`
  and anything else is sent as written. Configure `own_number` as `+…`.
- **No delivery reports.** Sent SMS are reported `submitted` or `failed`;
  the `delivered` report the app sends is not implemented.
- **Binary SMS are ignored** (left on the modem, not forwarded), as are
  SMS-SUBMIT entries in the modem's store.
- **All signalling goes to the configured server address**, responses to
  where the request came from. A Contact or Record-Route pointing elsewhere
  is not followed — fine for one Asterisk/FreeSWITCH, not for a proxy chain.
- **One modem, one call.** A second GSM call while one is bridged is
  rejected busy; dual-SIM is not handled.
- **Not ported from the app**: the Android audio-HAL workarounds, the call
  monitor and snoop features, the on-disk SMS store, and calls placed from
  the phone's own dialler.
