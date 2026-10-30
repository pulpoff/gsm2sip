# A simple Asterisk for the gateway, in Docker

A small PJSIP Asterisk on your LAN that the gateway registers to, plus any
number of SIP clients (softphones, desk phones):

| | |
|---|---|
| A call to the SIM | rings **every registered client** at once; the first to answer gets it |
| A client dials a number | the call goes out through the **gateway's SIM** |
| A client dials `600` | echo test — checks the client on its own, no gateway involved |
| An SMS to the SIM | delivered to **every registered client** as a chat message |
| A client sends a chat message to a number | sent as an **SMS** through the gateway |

It is meant for a home or office LAN: one gateway, a few clients, nothing
exposed to the internet.  For anything bigger (several SIMs, routing per
number, AI agents) the README's *The longer way* section is the place to
start; the dialplan here is a working base for it.

Everything lives in [`docker/asterisk/`](../docker/asterisk/):

| File | |
|---|---|
| `Dockerfile` | Alpine + Asterisk 20 + English prompts |
| `docker-compose.yml` | host networking, a volume for undelivered SMS |
| `.env.example` | the only file you edit (as `.env`) |
| `entrypoint.sh` | renders the accounts (`pjsip.conf`) from `.env` at start |
| `conf/extensions.conf` | the dialplan described above |

## 1. Prepare `.env`

```bash
cd docker/asterisk
cp .env.example .env
chmod 600 .env
```

Fill in:

| Variable | |
|---|---|
| `SERVER_IP` | this host's LAN address; Asterisk listens only there, UDP and TCP 5060 |
| `LOCAL_NET` | your LAN, e.g. `192.168.1.0/24` |
| `GATEWAY_IP` | the gateway phone's address — give it a DHCP reservation so it never changes |
| `GATEWAY_USER` / `GATEWAY_PASSWORD` | the account the gateway registers with |
| `CLIENTS` | space-separated `name:password` pairs, one per client |

Names and passwords may only use letters, digits, `.`, `_` and `-`, and
passwords must be at least 12 characters — the container refuses to start
otherwise.  `openssl rand -hex 16` makes a good password.

`.env` is git-ignored.  Keep it that way: anyone holding a client password
can make calls and send SMS on your SIM.

## 2. Start it

```bash
docker compose up -d --build
docker logs gsm2sip-asterisk | grep entrypoint
# entrypoint: gateway 'gateway' from 192.168.1.20, clients: alice,bob
```

Lines such as `Unable to load config file 'cdr.conf'` are modules this
minimal setup does not configure; they are harmless.

Without Compose:

```bash
docker build -t gsm2sip-asterisk .
docker run -d --name gsm2sip-asterisk --network host --env-file .env \
  -v gsm2sip-astdb:/var/lib/asterisk-db --restart unless-stopped gsm2sip-asterisk
```

Host networking keeps Asterisk directly on the LAN address, so there is no
NAT between it and the phones for SIP or RTP (UDP 10000–10200).  If the host
runs a firewall, allow UDP/TCP 5060 and UDP 10000–10200 **from the LAN only**.
Do not forward these ports from your router.

## 3. Point the gateway at it

In gsm2sip **Settings**:

| Field | Value |
|---|---|
| Server | `SERVER_IP` |
| Port | `5060` (UDP) |
| Username / Password | `GATEWAY_USER` / `GATEWAY_PASSWORD` |
| Own Number | the SIM's number in international format, e.g. `+15551234567` |

The globe badge turns green once it is registered.  On the server:

```bash
docker exec gsm2sip-asterisk asterisk -rx 'pjsip show contacts'
```

## 4. Add clients

Each client registers with its `CLIENTS` name and password, domain
`SERVER_IP`.  Up to five devices may share one client account.  Clients should
offer **G.722** (wideband) — the gateway speaks G.722 by default, and a client
that only offers G.711 gets a narrowband, muffled call.

**Linphone desktop** wants UDP port 5060 for itself and fails with
`udp bind() failed ... Address already in use` (shown only as *Error during
connection*) when it runs on the same host as Asterisk.  Choose **TCP** as
the transport in its account form; Asterisk listens on TCP 5060 too.  Enable
G722 under its audio codecs.

Checks, in order:

1. Dial `600` from the client — echo test, client ↔ Asterisk only.
2. Call the SIM from another phone — every registered client rings.
3. Dial a number from the client in international format — the gateway
   places a GSM call on the SIM (at your normal rates).
4. Text the SIM — the message appears on every registered client, as a
   conversation with the sender's number; replying in it sends an SMS.

## SMS details

The gateway forwards every received SMS as a SIP MESSAGE with its id in
`X-SMS-Id`.  PJSIP answers the gateway `202 Accepted` before the dialplan
runs, so the gateway considers the message delivered at that point and will
not retry it.  The dialplan therefore stores each message in Asterisk's
database first and removes it once at least one client has it.  A message
that arrives while no client is registered stays there:

```bash
docker exec gsm2sip-asterisk asterisk -rx 'database show sms-in'
```

Delivery reports for messages a client sent (`submitted`, `delivered`,
`failed`, ...) are logged, not forwarded:

```bash
docker logs gsm2sip-asterisk 2>&1 | grep 'SMS '
```

Clients also send typing notifications and read receipts as SIP MESSAGEs with
XML bodies; the dialplan drops those instead of sending them as SMS.

## Changing things

- Accounts: edit `.env`, then `docker compose up -d` (the entrypoint renders
  `pjsip.conf` again on every start).
- Routing: edit `conf/extensions.conf`, then `docker compose up -d --build`,
  or for a quick try `docker exec gsm2sip-asterisk asterisk -rx 'dialplan reload'`
  after copying the file in.
- Watch calls live: `docker exec -it gsm2sip-asterisk asterisk -rvvv`.
