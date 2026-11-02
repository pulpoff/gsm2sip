/* GSM <-> SIP: the port of bridge/CallOrchestrator.kt, plus the SMS relay
 * GatewayService.kt did on Android.
 *
 *   inbound   GSM rings -> INVITE to our own number -> SIP 200 -> answer GSM
 *             -> GSM active -> media.  The caller hears ringing, then the
 *             agent, with no dead air in between.
 *   outbound  INVITE (X-GSM-Forward or a number in the R-URI) -> 180 -> dial
 *             -> GSM active -> 200 with SDP -> media.
 *   SMS       received SMS -> MESSAGE to sip:<own>@<server>, deleted from the
 *             modem once the server took it; MESSAGE from the server -> 202
 *             -> sent by the modem -> a report MESSAGE. */
#pragma once
#include "modem.h"
#include "rtp.h"
#include "sipclient.h"

typedef struct Bridge Bridge;

typedef enum {
	BRIDGE_IDLE,
	BRIDGE_GSM_RINGING,	/* incoming GSM, not answered yet */
	BRIDGE_GSM_ANSWERED,
	BRIDGE_SIP_CALLING,	/* INVITE out, waiting for the server */
	BRIDGE_SIP_RINGING,
	BRIDGE_BRIDGED,
	BRIDGE_GSM_DIALING,	/* outbound: dialling the GSM number */
	BRIDGE_TEARING_DOWN,
} BridgeState;

typedef struct {
	const char *own_number;	/* the SIM's MSISDN, the inbound INVITE's target */
	RtpAudioConfig audio;
} BridgeConfig;

Bridge *bridge_new (const BridgeConfig *cfg);
/* Callbacks to hand to sip_client_new / modem_new with the bridge as data. */
const SipClientCallbacks *bridge_sip_callbacks (void);
const ModemCallbacks *bridge_modem_callbacks (void);
void bridge_attach (Bridge *b, SipClient *sip, Modem *modem);
BridgeState bridge_state (Bridge *b);
/* End any call in progress, both legs. */
void bridge_shutdown (Bridge *b);
void bridge_free (Bridge *b);
