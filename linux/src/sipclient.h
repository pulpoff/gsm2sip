/* SIP user agent: transport, registration, call dialogs and page-mode
 * MESSAGE.  A port of sip/SipClient.kt, SipCall.kt and SipTransport.kt onto
 * one GMainLoop: no thread here, every callback runs on the main context. */
#pragma once
#include <gio/gio.h>
#include "sipmsg.h"

typedef struct SipClient SipClient;

typedef enum { SIP_DIR_INBOUND, SIP_DIR_OUTBOUND } SipDir;
typedef enum { SIP_CALL_TRYING, SIP_CALL_RINGING, SIP_CALL_ANSWERED, SIP_CALL_TERMINATED } SipCallState;

#define SIP_SRTP_KEY_LEN 30	/* 16-byte master key || 14-byte salt */

typedef struct {
	gboolean set;
	int tag_bits;		/* 80 or 32: AES_CM_128_HMAC_SHA1_80 / _32 */
	guint8 key[SIP_SRTP_KEY_LEN];
	char *inline_b64;
} SipSrtpKey;

typedef struct SipCall {
	int refcount;
	SipClient *client;
	char *call_id;
	SipDir dir;
	SipCallState state;
	gboolean response_received;	/* stops Timer A */
	gboolean auth_handled;		/* one authenticated re-INVITE, never two */
	gboolean notified;		/* call_terminated delivered */
	int final_status;		/* >= 300 that ended it, 0 for none */

	char *local_tag, *remote_tag, *from_hdr, *to_hdr, *remote_contact_uri;
	int local_cseq, remote_cseq;

	int local_rtp_port, remote_rtp_port;
	char *remote_rtp_addr;
	int pt;				/* negotiated payload type */
	int te_pt;			/* the peer's telephone-event PT, 101 if none named */

	SipSrtpKey local_key, remote_key;	/* ours protects what we send, theirs what we receive */
	int srtp_tag;

	char *caller_number, *caller_display, *gsm_forward;
	char *cid_number, *cid_name;	/* outbound caller ID, kept for the authenticated re-INVITE */

	SipMsg *invite;		/* the INVITE received (inbound) or the last one sent (outbound) */
	char *invite_raw;	/* outbound: what Timer A retransmits */
	GSocketAddress *src;	/* inbound: where the INVITE came from (UDP); responses go there */
	char *last_ok;		/* inbound: our 200, retransmitted until the ACK (UDP) */
	guint timer_a, timer_g;
	int retrans;
	guint retrans_delay;
	gpointer user_data;	/* the bridge's */
} SipCall;

typedef void (*SipMessageCallback) (int status, gpointer user_data);	/* 0: no final response */

typedef struct {
	void (*registered) (gpointer ud);
	void (*registration_failed) (gpointer ud);
	/* Registration keeps failing: the client has dropped its transport and
	 * will rebuild it; the owner may refresh the public address meanwhile. */
	void (*connection_lost) (gpointer ud);
	void (*incoming_call) (gpointer ud, SipCall *call);
	void (*call_answered) (gpointer ud, SipCall *call);
	void (*call_rtp_ready) (gpointer ud, SipCall *call);
	/* The far end ended it (BYE, CANCEL, a final error).  Never fired for
	 * a hangup or reject this side asked for. */
	void (*call_terminated) (gpointer ud, SipCall *call);
	void (*call_dtmf) (gpointer ud, SipCall *call, char digit, int duration_ms);
	/* An out-of-dialog MESSAGE.  Returns the status to answer with and may
	 * add header lines (g_strdup'd) to `extra`.  Unset: 405. */
	int (*message_request) (gpointer ud, const SipMsg *msg, GPtrArray *extra);
} SipClientCallbacks;

typedef struct {
	const char *server;
	int port;
	const char *user;
	const char *password;
	gboolean tls;
	gboolean srtp;		/* honoured only with tls: SDES over cleartext protects nothing */
	const char *codec;	/* g722 | g711 | both */
	int local_port;		/* UDP; TLS advertises its ephemeral port */
	const char *public_ip;	/* NULL: the local address toward the server */
	const char *user_agent;
} SipConfig;

SipClient *sip_client_new (const SipConfig *cfg, const SipClientCallbacks *cb, gpointer ud);
void sip_client_start (SipClient *c);
/* Hang up every call and stop registering.  The transport stays up until
 * sip_client_free so the BYEs and CANCELs actually leave. */
void sip_client_stop (SipClient *c);
void sip_client_free (SipClient *c);
void sip_client_set_public_ip (SipClient *c, const char *ip);
/* Register now, ignoring the backoff (SIGUSR1). */
void sip_client_retry_now (SipClient *c);

gboolean sip_client_registered (SipClient *c);
const char *sip_client_user (SipClient *c);
const char *sip_client_server (SipClient *c);
const char *sip_client_server_ip (SipClient *c);	/* NULL until resolved / connected */
gboolean sip_client_srtp_enabled (SipClient *c);

/* Returns a new reference; the client keeps its own until the call ends. */
SipCall *sip_client_make_call (SipClient *c, const char *target_user, int local_rtp_port,
			       const char *cid_number, const char *cid_name);
void sip_client_remove_call (SipClient *c, SipCall *call);

void sip_client_send_message (SipClient *c, const char *target_uri, const char *from_user, const char *body,
			      const char *const *extra_headers, const char *content_type,
			      SipMessageCallback cb, gpointer ud);

SipCall *sip_call_ref (SipCall *call);
void sip_call_unref (SipCall *call);
G_DEFINE_AUTOPTR_CLEANUP_FUNC (SipCall, sip_call_unref)

void sip_call_accept (SipCall *call, int local_rtp_port);
void sip_call_reject (SipCall *call, int code, const char *reason);
void sip_call_ringing (SipCall *call);
void sip_call_hangup (SipCall *call);
gboolean sip_call_srtp_active (const SipCall *call);

/* For the tests. */
gboolean sip_srtp_key_from_inline (SipSrtpKey *k, const char *suite, const char *inline_value);
void sip_srtp_key_generate (SipSrtpKey *k, int tag_bits);
const char *sip_srtp_suite_name (int tag_bits);
void sip_srtp_key_clear (SipSrtpKey *k);
/* Pull one Content-Length-framed message off a TLS byte stream; NULL when
 * none is complete yet.  Leading CRLF keepalives are consumed. */
GBytes *sip_stream_take_message (GByteArray *rx);
