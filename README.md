# SIP-GSM Gateway

Android app that bridges GSM calls (Israeli SIM) with the CallAgent SIP/Asterisk server.

## How It Works

A dedicated rooted Android phone with an Israeli SIM card acts as a SIP-to-GSM gateway:

- **Inbound**: Someone calls the Israeli number → phone auto-answers → bridges to Asterisk via SIP → AI agent handles the call
- **Outbound**: Asterisk sends SIP INVITE with `X-GSM-Forward: +972...` header → phone dials the destination via GSM SIM → bridges audio back to SIP

Audio flows through shared speaker/mic — both GSM and SIP audio run concurrently on the same hardware, enabled by a Magisk module that disables Android's audio concurrency restrictions.

## Audio Codec

G.722 wideband (16 kHz, 64 kbps) for high quality voice. Falls back to G.711 A-law if needed.

## Requirements

- **Device**: Samsung Galaxy S10e (or similar) with LineageOS + Magisk root
- **SIM**: Israeli SIM card with voice plan
- **Network**: Stable WiFi connection
- **Power**: Always connected to charger
- **Build host**: Linux with JDK 17+

## Build

```bash
chmod +x build.sh
./build.sh          # debug build
./build.sh release  # release build
```

Outputs:
- `gateway.apk` — the Android app
- `gateway-magisk.zip` — Magisk module for audio permissions

## Device Setup

1. **Install Magisk module**: Copy `gateway-magisk.zip` to device, install via Magisk Manager → Modules
2. **Reboot** the device
3. **Install APK**: `adb install gateway.apk`
4. **Grant permissions**: Open the app, grant all requested permissions
5. **Set as default phone app**: Settings → Apps → Default apps → Phone app → SIP-GSM Gateway
6. **Disable battery optimization**: The app requests this on first launch
7. **Configure SIP**: Enter your Asterisk server address, port, username, and password
8. **Start**: Tap START — the app registers with Asterisk and begins bridging calls

## Asterisk Configuration

### 1. Create a SIP account for the gateway

Add to `sip.conf` or create via the realtime database:

```ini
[gateway-il](agent-template)
secret = <strong-password>
context = gateway-incoming
```

### 2. Add gateway dialplan context

Add to `extensions.conf`:

```ini
; Gateway incoming calls (GSM → SIP → Agent)
[gateway-incoming]
exten => _X.,1,NoOp(Gateway call from ${CALLERID(num)} via Israeli SIM)
same => n,Set(CDR(destination)=${EXTEN})
same => n,Set(CDR(userfield)=gateway-il)
; Route to AI agent (same logic as incoming-calls)
same => n,Set(AgentToUse=${ODBC_AGENT_LOOKUP(gateway-il)})
same => n,GotoIf($["${AgentToUse}" = ""]?default_agent:route_to_agent)
same => n(route_to_agent),MixMonitor(/var/spool/asterisk/monitor/${STRFTIME(${EPOCH},,%Y%m%d-%H%M%S)}-${UNIQUEID}.wav)
same => n,Dial(SIP/${AgentToUse},60,tT)
same => n,Hangup()
same => n(default_agent),MixMonitor(/var/spool/asterisk/monitor/${STRFTIME(${EPOCH},,%Y%m%d-%H%M%S)}-${UNIQUEID}.wav)
same => n,Dial(SIP/100,60,tT)
same => n,Hangup()

; Outbound: Agent calls Israeli number via gateway
; The agent context already allows outbound calls:
;   Dial(SIP/gateway-il,,X-GSM-Forward: +972xxxxxxxxx)
; Or use a custom AGI/ARI to set the header.
```

### 3. Making outbound calls through the gateway

From Asterisk dialplan, to call an Israeli number via the gateway:

```ini
exten => _972X.,1,NoOp(Outbound to Israel: ${EXTEN})
same => n,SIPAddHeader(X-GSM-Forward: +${EXTEN})
same => n,Dial(SIP/gateway-il,60)
same => n,Hangup()
```

## Architecture

```
┌─────────────────┐     GSM      ┌──────────────────┐
│  Remote Caller   │◄───────────►│  Android Phone    │
│  (Israeli #)     │   voice     │  (S10e + SIM)     │
└─────────────────┘              │                    │
                                 │  ┌──────────────┐ │
                                 │  │ InCallService │ │  GSM call control
                                 │  └──────┬───────┘ │
                                 │         │         │
                                 │  ┌──────▼───────┐ │
                                 │  │ Orchestrator  │ │  Bridges GSM ↔ SIP
                                 │  └──────┬───────┘ │
                                 │         │         │
                                 │  ┌──────▼───────┐ │
                                 │  │  SIP Client   │ │  Registration + calls
                                 │  │  RTP Session  │ │  G.722 audio stream
                                 │  └──────┬───────┘ │
                                 └─────────┼─────────┘
                                           │ SIP/RTP
                                           │ (WiFi)
                                 ┌─────────▼─────────┐
                                 │  Asterisk Server   │
                                 │  (CallAgent SIP)   │
                                 └─────────┬─────────┘
                                           │
                                 ┌─────────▼─────────┐
                                 │  AI Voice Agent    │
                                 └───────────────────┘
```

## Magisk Module

The `gateway-magisk.zip` module does two critical things:

1. **Disables audio concurrency restrictions** (`system.prop`):
   - `voice.voip.conc.disabled=false` — allows VoIP audio during GSM calls
   - `voice.record.conc.disabled=false` — allows audio recording during calls
   - `voice.playback.conc.disabled=false` — allows audio playback during calls

2. **Grants system-level permissions** (`privapp-permissions-gateway.xml`):
   - `CAPTURE_AUDIO_OUTPUT` — capture audio from other sources
   - `MODIFY_PHONE_STATE` — control telephony
   - `READ_PRECISE_PHONE_STATE` — detailed call state info

## Troubleshooting

- **One-way audio**: Ensure the Magisk module is installed and device is rebooted
- **Echo**: The app uses Android's AcousticEchoCanceler + VOICE_COMMUNICATION mode
- **SIP not registering**: Check WiFi connectivity, server address, and credentials
- **Calls not auto-answering**: Ensure the app is set as the default phone app
- **Audio drops**: Check WiFi stability; the app holds a WiFi lock but poor signal will cause issues
# gsm2sip
