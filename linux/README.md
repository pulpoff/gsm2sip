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
digest auth, User-Agent (`gsm2sip v1.4.15 <hostname>`), SDP, `X-SMS-*`
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
| `src/gwutil.[ch]` | number and SMS helpers, ids, timestamps |
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
`SIGTERM` ends any call on both legs before the daemon exits. `-v` logs the
first line of every SIP message.

**Turn the phone's own dialler off first.** GNOME Calls (and callaudiod)
see the same ModemManager calls the gateway does: left running, they ring
the phone, may answer, and switch the call audio routing under the
gateway. In gateway mode the phone is not a phone.

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
  monitor and snoop features, SMS transliteration, the on-disk SMS store,
  and calls placed from the phone's own dialler.
