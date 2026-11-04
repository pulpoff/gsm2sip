#define G_LOG_DOMAIN "sip"
#include "sipclient.h"
#include "dsp.h"
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <sys/random.h>
#include <sys/timerfd.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <glib-unix.h>
#include <time.h>
#include <unistd.h>

#define REGISTER_TIMEOUT_MS 10000
#define MESSAGE_TIMEOUT_MS 10000
#define MAX_REGISTER_FAILURES 3
/* The REGISTER asks for 3600 s and chan_sip grants it; half of that is the
 * conventional refresh point.  A shorter grant is honoured below. */
#define REREGISTER_US (30 * 60 * G_USEC_PER_SEC)
/* Consumer NAT drops an idle UDP mapping somewhere after 30-60 s; the
 * server's 60 s OPTIONS only arrive while it is open. */
#define NAT_KEEPALIVE_US (25 * G_USEC_PER_SEC)
/* Must divide the keepalive interval, or 25 s becomes 30 on the wire. */
#define POLL_INTERVAL_S 5
/* Connect / resolve backoff: this server runs fail2ban, and a connection
 * attempt every 5 s while it is down gets the gateway banned. */
#define OPEN_BACKOFF_MIN_US (5 * G_USEC_PER_SEC)
#define OPEN_BACKOFF_MAX_US (300 * G_USEC_PER_SEC)
#define TLS_RX_LIMIT (256 * 1024)

/* ---- RegisterBackoff ----
 * Process-wide like the Kotlin companion object: a rebuilt transport picks
 * the cooldown up where the old one left off instead of flooding REGISTERs. */
static struct {
	char *target;
	gint64 delay_us, next_at;
} backoff = { NULL, 15 * G_USEC_PER_SEC, 0 };

/* Intervals here count the time the phone slept: a refresh due in 30 min is
 * due 30 min later on the clock, not after 30 min awake.  The monotonic clock
 * stops in suspend. */
static gint64
now_us (void)
{
	struct timespec ts;
	clock_gettime (CLOCK_BOOTTIME, &ts);
	return (gint64)ts.tv_sec * G_USEC_PER_SEC + ts.tv_nsec / 1000;
}

#define BACKOFF_MIN_US (15 * G_USEC_PER_SEC)
#define BACKOFF_MAX_US (300 * G_USEC_PER_SEC)

static gint64
backoff_hold (const char *server)
{
	if (g_strcmp0 (server, backoff.target)) {
		g_free (backoff.target);
		backoff.target = g_strdup (server);
		backoff.delay_us = BACKOFF_MIN_US;
		backoff.next_at = 0;
	}
	return MAX (0, backoff.next_at - now_us ());
}

static void
backoff_reset (void)
{
	backoff.delay_us = BACKOFF_MIN_US;
	backoff.next_at = 0;
}

static gint64
backoff_failure (void)
{
	/* jittered so several gateways on one server do not retry in step */
	gint64 wait = (gint64)(backoff.delay_us * g_random_double_range (0.8, 1.2));
	backoff.next_at = now_us () + wait;
	backoff.delay_us = MIN (backoff.delay_us * 2, BACKOFF_MAX_US);
	return wait;
}

/* ---- the client ---- */

typedef struct {
	SipClient *c;
	char *call_id, *target, *from_user, *body, *ctype, *from_tag;
	GStrv extra;
	int cseq;
	gboolean auth_tried;
	guint timer;
	SipMessageCallback cb;
	gpointer ud;
} MsgTxn;

struct SipClient {
	char *server, *user, *password, *codec, *ua, *public_ip, *cfg_public_ip;
	int port, local_port;
	gboolean tls, srtp;
	SipClientCallbacks cb;
	gpointer ud;
	SipBuildCtx bctx;
	GCancellable *cancel;

	gboolean running, registered, registering, reg_auth_sent;
	char *call_id_base;
	int cseq, reg_cseq;
	guint reg_timer, monitor;
	int alarm_fd;	/* wakes a sleeping phone for the next refresh */
	guint alarm_src;
	gint64 last_register, last_keepalive, reregister_us;
	int register_failures;

	gint64 last_open_attempt, open_backoff;
	char *server_ip;
	int adv_port;

	/* UDP */
	GSocket *udp;
	GSource *udp_src;
	GSocketAddress *server_sa;
	gboolean resolving;

	/* TLS */
	GSocketConnection *tls_conn;
	GCancellable *tls_cancel;
	gboolean tls_connecting, writing;
	guint tls_gen;
	GByteArray *rx;
	GQueue txq;

	GHashTable *calls;	/* Call-ID -> SipCall (a reference) */
	GHashTable *msgs;	/* Call-ID -> MsgTxn */
};

static void send_to (SipClient *c, const char *data, GSocketAddress *to);
static void register_attempt (SipClient *c);
static void tls_write_next (SipClient *c);
static void alarm_arm (SipClient *c);

static gboolean
transport_open (SipClient *c)
{
	return c->tls ? c->tls_conn != NULL : (c->udp && c->server_sa);
}

static gboolean
srtp_enabled (SipClient *c)
{
	return c->srtp && c->tls;
}

gboolean sip_client_srtp_enabled (SipClient *c) { return srtp_enabled (c); }
gboolean sip_client_registered (SipClient *c) { return c->registered; }
const char *sip_client_user (SipClient *c) { return c->user; }
const char *sip_client_server (SipClient *c) { return c->server; }
const char *sip_client_server_ip (SipClient *c) { return c->server_ip; }

static const char *
pub_ip (SipClient *c)
{
	return c->public_ip ? c->public_ip : "0.0.0.0";
}

static void
reset_call_id_base (SipClient *c)
{
	g_free (c->call_id_base);
	c->call_id_base = g_strdup_printf ("%" G_GINT64_FORMAT "@%s", g_get_real_time () / G_USEC_PER_SEC, pub_ip (c));
}

static char *
random_tag (void)
{
	return sip_tag ();
}

/* ---- SRTP keys (RFC 4568 SDES) ---- */

const char *
sip_srtp_suite_name (int bits)
{
	return bits == 32 ? "AES_CM_128_HMAC_SHA1_32" : "AES_CM_128_HMAC_SHA1_80";
}

void
sip_srtp_key_clear (SipSrtpKey *k)
{
	g_free (k->inline_b64);
	memset (k, 0, sizeof *k);
}

void
sip_srtp_key_generate (SipSrtpKey *k, int bits)
{
	sip_srtp_key_clear (k);
	if (getrandom (k->key, sizeof k->key, 0) != sizeof k->key)
		g_error ("getrandom failed: no entropy for an SRTP key");
	k->tag_bits = bits;
	k->inline_b64 = g_base64_encode (k->key, sizeof k->key);
	k->set = TRUE;
}

gboolean
sip_srtp_key_from_inline (SipSrtpKey *k, const char *suite, const char *inline_value)
{
	int bits;
	if (!g_ascii_strcasecmp (suite, "AES_CM_128_HMAC_SHA1_80"))
		bits = 80;
	else if (!g_ascii_strcasecmp (suite, "AES_CM_128_HMAC_SHA1_32"))
		bits = 32;
	else
		return FALSE;
	/* "key||salt|2^31|1:1": lifetime and MKI follow the base64 */
	g_autofree char *b64 = g_strndup (inline_value, strcspn (inline_value, "|"));
	gsize n = 0;
	g_autofree guchar *raw = g_base64_decode (b64, &n);
	if (!raw || n != SIP_SRTP_KEY_LEN)
		return FALSE;
	sip_srtp_key_clear (k);
	memcpy (k->key, raw, n);
	k->tag_bits = bits;
	k->inline_b64 = g_strdup (b64);
	k->set = TRUE;
	return TRUE;
}

gboolean
sip_call_srtp_active (const SipCall *call)
{
	return call->local_key.set && call->remote_key.set;
}

/* A crypto line on a non-SAVP stream is ignored deliberately: answering
 * plain RTP with encrypted audio gives a call where nobody hears anything. */
static gboolean
absorb_remote_srtp (SipCall *call, const SipMsg *msg)
{
	if (!sipmsg_sdp_is_savp (msg))
		return FALSE;
	g_autoptr (GPtrArray) lines = sipmsg_sdp_crypto (msg);
	for (guint i = 0; i < lines->len; i++) {
		SdpCrypto *cr = g_ptr_array_index (lines, i);
		if (sip_srtp_key_from_inline (&call->remote_key, cr->suite, cr->inline_key)) {
			call->srtp_tag = cr->tag;
			return TRUE;
		}
	}
	return FALSE;
}

static gboolean
local_sdp_srtp (const SipCall *call, SdpSrtp *out)
{
	if (!call->local_key.set)
		return FALSE;
	out->suite = sip_srtp_suite_name (call->local_key.tag_bits);
	out->inline_key = call->local_key.inline_b64;
	out->tag = call->srtp_tag;
	return TRUE;
}

/* ---- calls ---- */

static SipCall *
call_new (SipClient *c, const char *call_id, SipDir dir)
{
	SipCall *call = g_new0 (SipCall, 1);
	call->refcount = 1;
	call->client = c;
	call->call_id = g_strdup (call_id);
	call->dir = dir;
	call->state = SIP_CALL_TRYING;
	call->local_tag = random_tag ();
	call->local_cseq = 1;
	call->pt = 9;
	call->te_pt = 101;
	call->srtp_tag = 1;
	return call;
}

SipCall *
sip_call_ref (SipCall *call)
{
	call->refcount++;
	return call;
}

static void
call_stop_timers (SipCall *call)
{
	if (call->timer_a) {
		g_source_remove (call->timer_a);
		call->timer_a = 0;
	}
	if (call->timer_g) {
		g_source_remove (call->timer_g);
		call->timer_g = 0;
	}
}

void
sip_call_unref (SipCall *call)
{
	if (!call || --call->refcount > 0)
		return;
	call_stop_timers (call);
	g_free (call->call_id);
	g_free (call->local_tag);
	g_free (call->remote_tag);
	g_free (call->from_hdr);
	g_free (call->to_hdr);
	g_free (call->remote_contact_uri);
	g_free (call->remote_rtp_addr);
	sip_srtp_key_clear (&call->local_key);
	sip_srtp_key_clear (&call->remote_key);
	g_free (call->caller_number);
	g_free (call->caller_display);
	g_free (call->gsm_forward);
	g_free (call->cid_number);
	g_free (call->cid_name);
	if (call->invite)
		sipmsg_free (call->invite);
	g_free (call->invite_raw);
	g_clear_object (&call->src);
	g_free (call->last_ok);
	g_free (call);
}

void
sip_client_remove_call (SipClient *c, SipCall *call)
{
	SipCall *held = g_hash_table_lookup (c->calls, call->call_id);
	if (held == call) {
		call_stop_timers (call);
		g_hash_table_remove (c->calls, call->call_id);
	}
}

static void
notify_terminated (SipCall *call)
{
	SipClient *c = call->client;
	if (call->notified)
		return;
	call->notified = TRUE;
	call_stop_timers (call);
	if (c->cb.call_terminated)
		c->cb.call_terminated (c->ud, call);
}

static GSocketAddress *
reply_addr (SipCall *call, GSocketAddress *from)
{
	return from ? from : call->src;
}

static void
send_ok_plain (SipCall *call, const SipMsg *req, GSocketAddress *to)
{
	SipClient *c = call->client;
	g_autofree char *ok = sip_build_ok (&c->bctx, req, c->user, pub_ip (c), c->adv_port, 0, NULL, NULL);
	send_to (c, ok, to);
}

/* The ACK to a 200: a new transaction, sent to the Contact. */
static void
send_ack_2xx (SipCall *call, int cseq)
{
	SipClient *c = call->client;
	g_autofree char *fallback = g_strdup_printf ("sip:%s:%d", c->server, c->port);
	const char *uri = call->remote_contact_uri ? call->remote_contact_uri : fallback;
	g_autofree char *ack = sip_build_ack (&c->bctx, uri, call->to_hdr, call->from_hdr, call->call_id, cseq,
					      c->user, pub_ip (c), c->adv_port);
	send_to (c, ack, NULL);
}

static void
send_ack_error (SipClient *c, const char *request_uri, const SipMsg *resp)
{
	g_autofree char *ack = sip_build_ack_for_error (&c->bctx, request_uri, resp);
	send_to (c, ack, NULL);
}

static void
resend_invite_with_auth (SipCall *call, const SipAuthParams *p, gboolean proxy)
{
	SipClient *c = call->client;
	const char *to = call->to_hdr ? call->to_hdr : "";
	const char *s = strstr (to, "sip:");
	if (!s)
		return;
	g_autofree char *uri = g_strndup (s, strcspn (s, ">"));
	g_autofree char *auth = sip_auth_header ("INVITE", uri, c->user, c->password, p);
	g_autofree char *hdr = proxy ? g_strconcat ("Proxy-", auth, NULL) : g_strdup (auth);
	SdpSrtp srtp;
	gboolean has = local_sdp_srtp (call, &srtp);
	char *invite = sip_build_invite (&c->bctx, uri, c->user, c->server, pub_ip (c), c->adv_port, call->call_id,
					 call->local_cseq++, call->local_rtp_port, call->local_tag, call->cid_number,
					 call->cid_name, hdr, has ? &srtp : NULL);
	/* a later CANCEL has to cancel this transaction, not the challenged one */
	if (call->invite)
		sipmsg_free (call->invite);
	call->invite = sipmsg_parse (invite, strlen (invite));
	g_free (call->invite_raw);
	call->invite_raw = invite;
	send_to (c, invite, NULL);
}

/* Timer A (RFC 3261 §17.1.1.2): UDP only, until any response arrives. */
static gboolean
timer_a_fire (gpointer data)
{
	SipCall *call = data;
	SipClient *c = call->client;
	if (call->response_received || g_hash_table_lookup (c->calls, call->call_id) != call || !call->invite_raw) {
		call->timer_a = 0;
		return G_SOURCE_REMOVE;
	}
	call->retrans++;
	if (call->retrans == 1)
		g_message ("No response to INVITE — retransmitting");
	g_debug ("INVITE retransmit #%d for %s (%ums)", call->retrans, call->call_id, call->retrans_delay);
	send_to (c, call->invite_raw, NULL);
	if (call->retrans >= 7) {
		call->timer_a = 0;
		g_message ("Server never answered the INVITE (7 sends)");
		return G_SOURCE_REMOVE;
	}
	call->retrans_delay = MIN (call->retrans_delay * 2, 4000);
	call->timer_a = g_timeout_add (call->retrans_delay, timer_a_fire, call);
	return G_SOURCE_REMOVE;
}

static void
start_timer_a (SipCall *call)
{
	if (call->client->tls)
		return;
	if (call->timer_a)
		g_source_remove (call->timer_a);
	call->retrans = 0;
	call->retrans_delay = 500;
	call->timer_a = g_timeout_add (call->retrans_delay, timer_a_fire, call);
}

/* Timer G for our 200 to an inbound INVITE: UDP loses it as easily as any
 * other datagram, and an unacknowledged 200 never becomes a call. */
static gboolean
timer_g_fire (gpointer data)
{
	SipCall *call = data;
	SipClient *c = call->client;
	if (!call->last_ok || call->state != SIP_CALL_ANSWERED || ++call->retrans > 10) {
		call->timer_g = 0;
		return G_SOURCE_REMOVE;
	}
	g_debug ("200 OK retransmit #%d for %s", call->retrans, call->call_id);
	send_to (c, call->last_ok, call->src);
	call->retrans_delay = MIN (call->retrans_delay * 2, 4000);
	call->timer_g = g_timeout_add (call->retrans_delay, timer_g_fire, call);
	return G_SOURCE_REMOVE;
}

static void
answer_in_dialog (SipCall *call, const SipMsg *msg, const char *what, GSocketAddress *from)
{
	SipClient *c = call->client;
	gboolean has_sdp = strstr (msg->body, "m=audio") != NULL;
	g_autofree char *method = sipmsg_method (msg);
	if (has_sdp) {
		g_autofree char *addr = sipmsg_sdp_addr (msg);
		int port = sipmsg_sdp_port (msg);
		if ((addr && g_strcmp0 (addr, call->remote_rtp_addr)) || (port > 0 && port != call->remote_rtp_port))
			g_message ("%s tried to move media to %s:%d (was %s:%d) — not followed; set direct_media=no",
				   what, addr ? addr : "?", port, call->remote_rtp_addr ? call->remote_rtp_addr : "?",
				   call->remote_rtp_port);
		int te = sipmsg_sdp_te_pt (msg);
		if (te >= 0)
			call->te_pt = te;
	}
	/* a re-INVITE without SDP is a delayed offer and the 200 must carry one;
	 * an UPDATE without SDP is answered without */
	int rtp = (has_sdp || !g_strcmp0 (method, "INVITE")) && call->local_rtp_port > 0 ? call->local_rtp_port : 0;
	SdpSrtp srtp;
	gboolean has = sip_call_srtp_active (call) && local_sdp_srtp (call, &srtp);
	g_autofree char *ok = sip_build_ok (&c->bctx, msg, c->user, pub_ip (c), c->adv_port, rtp, call->local_tag,
					    has ? &srtp : NULL);
	send_to (c, ok, reply_addr (call, from));
	g_message ("Answered %s for call %s (sdp=%s)", what, call->call_id, has_sdp ? "yes" : "no");
}

static gboolean
call_handle (SipCall *call, SipMsg *msg, GSocketAddress *from)
{
	SipClient *c = call->client;
	gboolean req = sipmsg_is_request (msg);
	int code = sipmsg_status (msg);
	g_autofree char *method = sipmsg_method (msg);

	if (!req)
		call->response_received = TRUE;

	if (!req && code == 200 && sipmsg_cseq_is (msg, "INVITE")) {
		g_free (call->remote_tag);
		call->remote_tag = sipmsg_to_tag (msg);
		g_free (call->to_hdr);
		call->to_hdr = g_strdup (sipmsg_header (msg, "to"));
		char *contact = sipmsg_contact_uri (msg);
		if (contact) {
			g_free (call->remote_contact_uri);
			call->remote_contact_uri = contact;
		}
		int port = sipmsg_sdp_port (msg);
		if (port > 0)
			call->remote_rtp_port = port;
		char *addr = sipmsg_sdp_addr (msg);
		if (addr) {
			g_free (call->remote_rtp_addr);
			call->remote_rtp_addr = addr;
		}
		call->pt = sipmsg_sdp_preferred_pt (msg, c->codec);
		int te = sipmsg_sdp_te_pt (msg);
		if (te >= 0)
			call->te_pt = te;
		if (call->local_key.set && call->state != SIP_CALL_ANSWERED) {
			if (absorb_remote_srtp (call, msg)) {
				g_message ("SRTP active (%s)", sip_srtp_suite_name (call->remote_key.tag_bits));
			} else {
				/* a downgrade: said out loud, and our key dropped so the media
				 * path does not protect a stream the far end reads as plain */
				sip_srtp_key_clear (&call->local_key);
				g_message ("SRTP declined by server — audio NOT encrypted");
			}
		}
		/* the ACK carries the CSeq of the INVITE it acknowledges */
		int ack_cseq = sipmsg_cseq_num (msg);
		send_ack_2xx (call, ack_cseq > 0 ? ack_cseq : call->local_cseq);
		if (call->state == SIP_CALL_ANSWERED) {
			g_debug ("Duplicate 200 OK for %s, ACKed", call->call_id);
			return TRUE;
		}
		call->state = SIP_CALL_ANSWERED;
		call_stop_timers (call);
		g_message ("200 OK: RTP %s:%d pt=%d te=%d", call->remote_rtp_addr ? call->remote_rtp_addr : "?",
			   call->remote_rtp_port, call->pt, call->te_pt);
		if (call->remote_rtp_port > 0 && c->cb.call_rtp_ready)
			c->cb.call_rtp_ready (c->ud, call);
		else if (call->remote_rtp_port <= 0)
			g_warning ("200 OK carries no RTP port — cannot bridge");
		if (c->cb.call_answered)
			c->cb.call_answered (c->ud, call);
		return TRUE;
	}
	if (!req && code == 100) {
		return TRUE;
	}
	if (!req && (code == 180 || code == 183)) {
		if (call->state == SIP_CALL_TRYING)
			call->state = SIP_CALL_RINGING;
		g_free (call->remote_tag);
		call->remote_tag = sipmsg_to_tag (msg);
		return TRUE;
	}
	if (!req && (code == 401 || code == 407) && sipmsg_cseq_is (msg, "INVITE")) {
		g_autofree char *ruri = call->invite ? sipmsg_request_uri (call->invite) : NULL;
		send_ack_error (c, ruri ? ruri : "sip:invalid", msg);
		if (call->auth_handled) {
			/* a retransmission of the challenge: a second authenticated
			 * INVITE would get 491 Request Pending */
			g_debug ("Ignoring duplicate %d — credentials already sent", code);
			return TRUE;
		}
		SipAuthParams *p = sip_auth_parse (msg);
		if (p) {
			call->auth_handled = TRUE;
			g_message ("INVITE %d challenge, re-sending with credentials", code);
			resend_invite_with_auth (call, p, code == 407);
			call->response_received = FALSE;
			start_timer_a (call);
			sip_auth_free (p);
			return TRUE;
		}
		/* fall through: an unanswerable challenge ends the call */
	}
	if (req && !g_strcmp0 (method, "BYE")) {
		g_message ("Received BYE for call %s", call->call_id);
		send_ok_plain (call, msg, reply_addr (call, from));
		call->state = SIP_CALL_TERMINATED;
		notify_terminated (call);
		return TRUE;
	}
	if (req && !g_strcmp0 (method, "CANCEL")) {
		/* acknowledge the CANCEL, then end the INVITE with 487 */
		g_message ("Received CANCEL for call %s", call->call_id);
		send_ok_plain (call, msg, reply_addr (call, from));
		if (call->state != SIP_CALL_ANSWERED && call->state != SIP_CALL_TERMINATED) {
			sip_call_reject (call, 487, "Request Terminated");
			notify_terminated (call);
		}
		return TRUE;
	}
	if (!req && code >= 300) {
		if (sipmsg_cseq_is (msg, "INVITE")) {
			g_autofree char *ruri = call->invite ? sipmsg_request_uri (call->invite) : NULL;
			send_ack_error (c, ruri ? ruri : "sip:invalid", msg);
		}
		/* A 491 for a duplicate INVITE can land after the 200 that set the
		 * dialog up; it must not tear the call down. */
		if (call->state == SIP_CALL_ANSWERED) {
			g_debug ("Ignoring %d — call already answered", code);
			return TRUE;
		}
		g_message ("INVITE rejected: %d (call %s)", code, call->call_id);
		call->final_status = code;
		call->state = SIP_CALL_TERMINATED;
		notify_terminated (call);
		return TRUE;
	}
	if (req && !g_strcmp0 (method, "INFO")) {
		send_ok_plain (call, msg, reply_addr (call, from));
		int ms = 0;
		char d = sip_info_dtmf_parse (sipmsg_header (msg, "content-type"), msg->body, &ms);
		if (d) {
			g_message ("INFO DTMF '%c' (%d ms)", d, ms);
			if (c->cb.call_dtmf)
				c->cb.call_dtmf (c->ud, call, d, ms);
		}
		return TRUE;
	}
	if (req && !g_strcmp0 (method, "INVITE")) {
		g_autofree char *tt = sipmsg_to_tag (msg);
		if (call->state == SIP_CALL_ANSWERED && tt) {
			answer_in_dialog (call, msg, "re-INVITE", from);
			return TRUE;
		}
		/* a retransmission of the initial INVITE */
		if (call->state == SIP_CALL_ANSWERED && call->last_ok)
			send_to (c, call->last_ok, reply_addr (call, from));
		return TRUE;
	}
	if (req && !g_strcmp0 (method, "UPDATE")) {
		answer_in_dialog (call, msg, "UPDATE", from);
		return TRUE;
	}
	if (req && !g_strcmp0 (method, "ACK")) {
		if (call->timer_g) {
			g_source_remove (call->timer_g);
			call->timer_g = 0;
		}
		return TRUE;
	}
	return FALSE;
}

/* ---- public call operations ---- */

void
sip_call_accept (SipCall *call, int local_rtp_port)
{
	SipClient *c = call->client;
	call->local_rtp_port = local_rtp_port;
	if (!call->invite)
		return;
	/* the tag in our 200 becomes part of the dialog id: the BYE must carry
	 * it too, or Asterisk answers 481 and the SIP caller stays up */
	const char *to = sipmsg_header (call->invite, "to");
	g_free (call->to_hdr);
	call->to_hdr = to && strstr (to, ";tag=") ? g_strdup (to) : g_strdup_printf ("%s;tag=%s", to ? to : "", call->local_tag);
	SdpSrtp srtp;
	gboolean has = local_sdp_srtp (call, &srtp);
	g_free (call->last_ok);
	call->last_ok = sip_build_ok (&c->bctx, call->invite, c->user, pub_ip (c), c->adv_port, local_rtp_port,
				      call->local_tag, has ? &srtp : NULL);
	send_to (c, call->last_ok, call->src);
	call->state = SIP_CALL_ANSWERED;
	if (!c->tls) {
		call->retrans = 0;
		call->retrans_delay = 500;
		call->timer_g = g_timeout_add (call->retrans_delay, timer_g_fire, call);
	}
	g_message ("Sent 200 OK for inbound call %s (RTP port %d%s)", call->call_id, local_rtp_port,
		   has ? ", SRTP" : "");
}

void
sip_call_reject (SipCall *call, int code, const char *reason)
{
	SipClient *c = call->client;
	if (call->state == SIP_CALL_TERMINATED || !call->invite)
		return;
	g_autofree char *r = sip_build_reject (&c->bctx, call->invite, code, reason, call->local_tag);
	send_to (c, r, call->src);
	call->state = SIP_CALL_TERMINATED;
	call_stop_timers (call);
	g_message ("Rejected inbound call %s with %d %s", call->call_id, code, reason);
}

void
sip_call_ringing (SipCall *call)
{
	SipClient *c = call->client;
	if (!call->invite || call->dir != SIP_DIR_INBOUND)
		return;
	g_autofree char *r = sip_build_ringing (&c->bctx, call->invite, call->local_tag);
	send_to (c, r, call->src);
	call->state = SIP_CALL_RINGING;
}

void
sip_call_hangup (SipCall *call)
{
	SipClient *c = call->client;
	if (call->state == SIP_CALL_TERMINATED)
		return;
	if (call->state != SIP_CALL_ANSWERED) {
		if (call->dir == SIP_DIR_INBOUND) {
			/* a UAS cannot CANCEL; it turns the INVITE down */
			sip_call_reject (call, 480, "Temporarily Unavailable");
			return;
		}
		/* No dialog yet: BYE is invalid and Asterisk keeps ringing the
		 * group.  CANCEL reuses the INVITE's Via branch and CSeq. */
		const char *via = call->invite ? sipmsg_header (call->invite, "via") : NULL;
		int cseq = call->invite ? sipmsg_cseq_num (call->invite) : -1;
		if (via && cseq > 0) {
			g_autofree char *ruri = sipmsg_request_uri (call->invite);
			g_autofree char *cancel = sip_build_cancel (&c->bctx, ruri, via, sipmsg_header (call->invite, "from"),
								     sipmsg_header (call->invite, "to"), call->call_id, cseq);
			send_to (c, cancel, NULL);
			g_message ("Sent CANCEL for early call %s (CSeq %d)", call->call_id, cseq);
		} else {
			g_warning ("Cannot build CANCEL for %s: INVITE headers unavailable", call->call_id);
		}
		call->state = SIP_CALL_TERMINATED;
		call_stop_timers (call);
		return;
	}
	g_autofree char *fallback = g_strdup_printf ("sip:%s:%d", c->server, c->port);
	const char *uri = call->remote_contact_uri ? call->remote_contact_uri : fallback;
	/* as UAS the gateway swaps From and To; one order for both directions
	 * gives a well-formed BYE that Asterisk rejects with 481 */
	const char *from = call->dir == SIP_DIR_INBOUND ? call->to_hdr : call->from_hdr;
	const char *to = call->dir == SIP_DIR_INBOUND ? call->from_hdr : call->to_hdr;
	g_autofree char *bye = sip_build_bye (&c->bctx, uri, from, to, call->call_id, call->local_cseq++, c->user,
					      pub_ip (c), c->adv_port);
	send_to (c, bye, NULL);
	call->state = SIP_CALL_TERMINATED;
	call_stop_timers (call);
	g_message ("Sent BYE for call %s", call->call_id);
}

SipCall *
sip_client_make_call (SipClient *c, const char *target, int rtp_port, const char *cid_number, const char *cid_name)
{
	g_autofree char *call_id = g_strdup_printf ("%" G_GINT64_FORMAT "call@%s", g_get_real_time () / 1000, pub_ip (c));
	SipCall *call = call_new (c, call_id, SIP_DIR_OUTBOUND);
	call->local_rtp_port = rtp_port;
	call->cid_number = g_strdup (cid_number);
	call->cid_name = g_strdup (cid_name);
	g_autofree char *disp = cid_name ? g_strdup_printf ("\"%s\" ", cid_name) : g_strdup ("");
	call->from_hdr = g_strdup_printf ("%s<sip:%s@%s>;tag=%s", disp, cid_number ? cid_number : c->user, c->server,
					  call->local_tag);
	call->to_hdr = g_strdup_printf ("<sip:%s@%s>", target, c->server);
	/* generated once and reused for every retry and re-INVITE of this call */
	if (srtp_enabled (c))
		sip_srtp_key_generate (&call->local_key, 80);
	g_autofree char *uri = g_strdup_printf ("sip:%s@%s", target, c->server);
	SdpSrtp srtp;
	gboolean has = local_sdp_srtp (call, &srtp);
	call->invite_raw = sip_build_invite (&c->bctx, uri, c->user, c->server, pub_ip (c), c->adv_port, call_id,
					     call->local_cseq++, rtp_port, call->local_tag, cid_number, cid_name, NULL,
					     has ? &srtp : NULL);
	/* the exact transaction headers, for a CANCEL before the answer */
	call->invite = sipmsg_parse (call->invite_raw, strlen (call->invite_raw));
	g_hash_table_insert (c->calls, g_strdup (call_id), sip_call_ref (call));
	send_to (c, call->invite_raw, NULL);
	/* both halves the server routes on: the R-URI (EXTEN) and the From user (caller ID) */
	g_message ("Sent INVITE RURI=%s From=<sip:%s@%s>%s (call-id=%s)", uri, cid_number ? cid_number : c->user, c->server,
		   has ? " SAVP" : "", call_id);
	start_timer_a (call);
	return call;
}

/* ---- inbound ---- */

static void
handle_incoming_invite (SipClient *c, SipMsg *msg, GSocketAddress *from)
{
	const char *call_id = sipmsg_header (msg, "call-id");
	if (!call_id) {
		sipmsg_free (msg);
		return;
	}
	g_autofree char *trying = sip_build_trying (&c->bctx, msg);
	send_to (c, trying, from);

	SipCall *call = call_new (c, call_id, SIP_DIR_INBOUND);
	call->invite = msg;
	call->src = from ? g_object_ref (from) : NULL;
	call->caller_number = sipmsg_caller_number (msg);
	call->caller_display = sipmsg_caller_display (msg);
	call->remote_contact_uri = sipmsg_contact_uri (msg);
	call->remote_cseq = MAX (1, sipmsg_cseq_num (msg));
	call->from_hdr = g_strdup (sipmsg_header (msg, "from"));
	call->to_hdr = g_strdup (sipmsg_header (msg, "to"));
	call->remote_tag = sipmsg_from_tag (msg);
	call->remote_rtp_port = MAX (0, sipmsg_sdp_port (msg));
	call->remote_rtp_addr = sipmsg_sdp_addr (msg);
	/* answered, never volunteered; and our key is fresh, never theirs
	 * reflected back -- SDES keys each direction separately */
	if (srtp_enabled (c) && absorb_remote_srtp (call, msg)) {
		sip_srtp_key_generate (&call->local_key, call->remote_key.tag_bits);
		g_message ("SRTP offered by server (%s) — answering SAVP", sip_srtp_suite_name (call->remote_key.tag_bits));
	} else if (sipmsg_sdp_is_savp (msg)) {
		g_message (!c->tls ? "Server offered SRTP but signalling is UDP — not accepting keys over cleartext"
				   : "Server offered SRTP but it is switched off here");
	}
	call->pt = sipmsg_sdp_preferred_pt (msg, c->codec);
	int te = sipmsg_sdp_te_pt (msg);
	if (te >= 0)
		call->te_pt = te;
	const char *fwd = sipmsg_header (msg, "x-gsm-forward");
	call->gsm_forward = fwd ? g_strstrip (g_strdup (fwd)) : NULL;
	g_message ("Incoming INVITE call-id=%s from=%s pt=%d te=%d%s", call_id,
		   call->caller_number ? call->caller_number : "?", call->pt, call->te_pt,
		   call->gsm_forward ? " (X-GSM-Forward)" : "");
	g_hash_table_insert (c->calls, g_strdup (call_id), call);
	if (c->cb.incoming_call)
		c->cb.incoming_call (c->ud, call);
}

static const char *
message_reason (int code)
{
	switch (code) {
	case 200: return "OK";
	case 202: return "Accepted";
	case 400: return "Bad Request";
	case 404: return "Not Found";
	case 405: return "Method Not Allowed";
	case 415: return "Unsupported Media Type";
	case 503: return "Service Unavailable";
	default: return "Error";
	}
}

/* ---- registration ---- */

static void
send_register (SipClient *c, const char *auth)
{
	c->reg_cseq = c->cseq++;
	g_autofree char *m = sip_build_register (&c->bctx, c->user, c->server, c->port, pub_ip (c), c->adv_port,
						 c->call_id_base, c->reg_cseq, auth);
	send_to (c, m, NULL);
}

static void drop_transport (SipClient *c, const char *why);

static void
register_done (SipClient *c, gboolean ok, const char *why)
{
	c->registering = FALSE;
	if (c->reg_timer) {
		g_source_remove (c->reg_timer);
		c->reg_timer = 0;
	}
	if (ok) {
		c->registered = TRUE;
		c->last_register = now_us ();
		c->register_failures = 0;
		backoff_reset ();
		g_message ("Registered with %s", c->server);
		alarm_arm (c);
		if (c->cb.registered)
			c->cb.registered (c->ud);
		return;
	}
	c->registered = FALSE;
	if (!c->running)
		return;
	gint64 wait = backoff_failure ();
	alarm_arm (c);
	c->register_failures++;
	g_message ("REGISTER failed (%s) — next attempt in %" G_GINT64_FORMAT "s", why, wait / G_USEC_PER_SEC);
	if (c->cb.registration_failed)
		c->cb.registration_failed (c->ud);
	if (c->register_failures >= MAX_REGISTER_FAILURES) {
		c->register_failures = 0;
		/* the local address or the path may have changed under us; but a
		 * call in progress keeps the socket it is signalling on */
		if (g_hash_table_size (c->calls) == 0) {
			g_message ("Registration failing repeatedly, rebuilding the connection");
			drop_transport (c, "registration failing");
		}
		if (c->cb.connection_lost)
			c->cb.connection_lost (c->ud);
	}
}

static gboolean
on_reg_timeout (gpointer data)
{
	SipClient *c = data;
	c->reg_timer = 0;
	register_done (c, FALSE, "no response");
	return G_SOURCE_REMOVE;
}

static void
register_attempt (SipClient *c)
{
	if (!c->running || c->registering || !transport_open (c))
		return;
	gint64 hold = backoff_hold (c->server);
	if (hold > 0) {
		g_debug ("REGISTER held off for %" G_GINT64_FORMAT "s more (backoff)", hold / G_USEC_PER_SEC);
		return;
	}
	g_message ("REGISTER → %s", c->server);
	c->registering = TRUE;
	c->reg_auth_sent = FALSE;
	send_register (c, NULL);
	c->reg_timer = g_timeout_add (REGISTER_TIMEOUT_MS, on_reg_timeout, c);
}

void
sip_client_retry_now (SipClient *c)
{
	backoff_reset ();
	c->register_failures = 0;
	g_message ("Manual retry requested");
	register_attempt (c);
}

static void
handle_register_response (SipClient *c, const SipMsg *msg)
{
	int code = sipmsg_status (msg);
	if (!c->registering || sipmsg_cseq_num (msg) != c->reg_cseq || code < 200)
		return;
	if (code == 200) {
		/* a shorter grant than we asked for is refreshed at half of it */
		const char *e = sipmsg_header (msg, "expires");
		int granted = e ? atoi (e) : 0;
		c->reregister_us = granted > 0 ? MIN (REREGISTER_US, (gint64)granted * G_USEC_PER_SEC / 2) : REREGISTER_US;
		register_done (c, TRUE, NULL);
		return;
	}
	if ((code == 401 || code == 407) && !c->reg_auth_sent) {
		SipAuthParams *p = sip_auth_parse (msg);
		if (!p) {
			register_done (c, FALSE, "unparseable challenge");
			return;
		}
		g_debug ("REGISTER %d challenge, sending auth", code);
		g_autofree char *uri = g_strdup_printf ("sip:%s:%d", c->server, c->port);
		g_autofree char *auth = sip_auth_header ("REGISTER", uri, c->user, c->password, p);
		g_autofree char *hdr = code == 407 ? g_strconcat ("Proxy-", auth, NULL) : g_strdup (auth);
		sip_auth_free (p);
		c->reg_auth_sent = TRUE;
		send_register (c, hdr);
		return;
	}
	/* a second 401 means the credentials were refused: answering it again
	 * would loop at network speed, which is exactly what fail2ban counts */
	g_autofree char *why = g_strdup_printf ("%d%s", code, code == 401 || code == 407 ? ", credentials refused" : "");
	register_done (c, FALSE, why);
}

/* ---- outbound MESSAGE ---- */

static void
msg_txn_free (gpointer p)
{
	MsgTxn *t = p;
	if (t->timer)
		g_source_remove (t->timer);
	g_free (t->call_id);
	g_free (t->target);
	g_free (t->from_user);
	g_free (t->body);
	g_free (t->ctype);
	g_free (t->from_tag);
	g_strfreev (t->extra);
	g_free (t);
}

static void
msg_txn_finish (MsgTxn *t, int status)
{
	SipClient *c = t->c;
	SipMessageCallback cb = t->cb;
	gpointer ud = t->ud;
	g_hash_table_remove (c->msgs, t->call_id);	/* frees t */
	if (cb)
		cb (status, ud);
}

static gboolean
msg_timeout (gpointer data)
{
	MsgTxn *t = data;
	t->timer = 0;
	g_debug ("MESSAGE %s: no final response", t->call_id);
	msg_txn_finish (t, 0);
	return G_SOURCE_REMOVE;
}

static void
msg_send (MsgTxn *t, const char *auth)
{
	SipClient *c = t->c;
	g_autofree char *m = sip_build_message (&c->bctx, t->target, t->from_user, c->server, pub_ip (c), c->adv_port,
						t->call_id, t->cseq, t->body, t->ctype, t->extra, t->from_tag, auth);
	send_to (c, m, NULL);
	if (t->timer)
		g_source_remove (t->timer);
	t->timer = g_timeout_add (MESSAGE_TIMEOUT_MS, msg_timeout, t);
}

typedef struct {
	SipMessageCallback cb;
	gpointer ud;
} DeferredStatus;

static gboolean
deferred_zero (gpointer data)
{
	DeferredStatus *d = data;
	d->cb (0, d->ud);
	g_free (d);
	return G_SOURCE_REMOVE;
}

void
sip_client_send_message (SipClient *c, const char *target_uri, const char *from_user, const char *body,
			 const char *const *extra, const char *content_type, SipMessageCallback cb, gpointer ud)
{
	if (!c->running || !transport_open (c)) {
		if (cb) {
			DeferredStatus *d = g_new (DeferredStatus, 1);
			d->cb = cb;
			d->ud = ud;
			g_idle_add (deferred_zero, d);
		}
		return;
	}
	MsgTxn *t = g_new0 (MsgTxn, 1);
	t->c = c;
	t->call_id = g_strdup_printf ("%" G_GINT64_FORMAT "msg@%s", g_get_real_time () / 1000, pub_ip (c));
	/* two messages in the same millisecond must not share a Call-ID */
	while (g_hash_table_contains (c->msgs, t->call_id)) {
		g_free (t->call_id);
		t->call_id = g_strdup_printf ("%" G_GINT64_FORMAT "%04xmsg@%s", g_get_real_time () / 1000,
					      g_random_int_range (0, 0xffff), pub_ip (c));
	}
	t->target = g_strdup (target_uri);
	t->from_user = g_strdup (from_user);
	t->body = g_strdup (body);
	t->ctype = g_strdup (content_type ? content_type : "text/plain;charset=UTF-8");
	t->extra = g_strdupv ((char **)extra);
	t->from_tag = random_tag ();
	t->cseq = 1;
	t->cb = cb;
	t->ud = ud;
	g_hash_table_insert (c->msgs, g_strdup (t->call_id), t);
	msg_send (t, NULL);
}

static gboolean
handle_message_response (SipClient *c, const SipMsg *msg)
{
	const char *cid = sipmsg_header (msg, "call-id");
	MsgTxn *t = cid ? g_hash_table_lookup (c->msgs, cid) : NULL;
	if (!t)
		return FALSE;
	int code = sipmsg_status (msg);
	if (code < 200 || sipmsg_cseq_num (msg) != t->cseq)
		return TRUE;
	if ((code == 401 || code == 407) && !t->auth_tried) {
		SipAuthParams *p = sip_auth_parse (msg);
		if (p) {
			/* same Call-ID, next CSeq: a fresh Call-ID would read as
			 * an unrelated message to the server */
			t->auth_tried = TRUE;
			t->cseq++;
			g_autofree char *auth = sip_auth_header ("MESSAGE", t->target, c->user, c->password, p);
			g_autofree char *hdr = code == 407 ? g_strconcat ("Proxy-", auth, NULL) : g_strdup (auth);
			sip_auth_free (p);
			msg_send (t, hdr);
			return TRUE;
		}
	}
	msg_txn_finish (t, code);
	return TRUE;
}

/* ---- dispatch ---- */

static void
stray_request_answer (SipClient *c, const SipMsg *msg, const char *method, GSocketAddress *from)
{
	int code;
	const char *reason;
	if (!g_strcmp0 (method, "BYE") || !g_strcmp0 (method, "NOTIFY")) {
		g_autofree char *ok = sip_build_ok (&c->bctx, msg, c->user, pub_ip (c), c->adv_port, 0, NULL, NULL);
		send_to (c, ok, from);
		return;
	}
	if (!g_strcmp0 (method, "CANCEL") || !g_strcmp0 (method, "INFO") || !g_strcmp0 (method, "UPDATE") ||
	    !g_strcmp0 (method, "PRACK")) {
		code = 481;
		reason = "Call/Transaction Does Not Exist";
	} else {
		code = 405;
		reason = "Method Not Allowed";
	}
	g_autofree char *r = sip_build_status (&c->bctx, msg, code, reason, NULL, NULL);
	send_to (c, r, from);
}

static void
handle_packet (SipClient *c, const char *data, gsize len, GSocketAddress *from)
{
	SipMsg *msg = sipmsg_parse (data, len);
	if (!msg)
		return;
	g_debug ("<< %s", msg->start_line);
	gboolean req = sipmsg_is_request (msg);
	g_autofree char *method = sipmsg_method (msg);
	const char *call_id = sipmsg_header (msg, "call-id");
	/* RFC 3261 §8.1.1: every message carries these five; without them
	 * there is no transaction to answer and no dialog to find */
	if (!call_id || !sipmsg_header (msg, "via") || !sipmsg_header (msg, "from") || !sipmsg_header (msg, "to") ||
	    sipmsg_cseq_num (msg) < 0) {
		g_debug ("Dropping a SIP message without Via/From/To/Call-ID/CSeq: %s", msg->start_line);
		goto out;
	}

	if (req && !g_strcmp0 (method, "OPTIONS")) {
		g_autofree char *r = sip_build_options_ok (&c->bctx, msg, c->user, pub_ip (c), c->adv_port);
		send_to (c, r, from);
		goto out;
	}
	if (!req && sipmsg_cseq_is (msg, "REGISTER")) {
		handle_register_response (c, msg);
		goto out;
	}
	/* before call routing: a page-mode MESSAGE has no dialog */
	if (!req && sipmsg_cseq_is (msg, "MESSAGE") && handle_message_response (c, msg))
		goto out;
	if (!req && sipmsg_cseq_is (msg, "OPTIONS"))
		goto out;

	SipCall *call = call_id ? g_hash_table_lookup (c->calls, call_id) : NULL;
	if (call) {
		g_autoptr (SipCall) hold = sip_call_ref (call);
		if (!req && sipmsg_status (msg) != 100)
			g_debug ("SIP %d for call %s", sipmsg_status (msg), call_id);
		if (!call_handle (call, msg, from))
			g_debug ("Unhandled SIP message for call %s: %s", call_id, msg->start_line);
		if (call->state == SIP_CALL_TERMINATED) {
			sip_client_remove_call (c, call);
			notify_terminated (call);
		}
		goto out;
	}

	if (req && !g_strcmp0 (method, "MESSAGE")) {
		g_autoptr (GPtrArray) extra = g_ptr_array_new_with_free_func (g_free);
		int code = c->cb.message_request ? c->cb.message_request (c->ud, msg, extra) : 405;
		g_ptr_array_add (extra, NULL);
		g_autofree char *r = sip_build_status (&c->bctx, msg, code, message_reason (code), (char **)extra->pdata, NULL);
		send_to (c, r, from);
		goto out;
	}
	if (req && !g_strcmp0 (method, "INVITE")) {
		g_autofree char *tt = sipmsg_to_tag (msg);
		if (tt) {
			/* a re-INVITE for a dialog we do not have */
			g_autofree char *r = sip_build_status (&c->bctx, msg, 481, "Call/Transaction Does Not Exist", NULL, NULL);
			send_to (c, r, from);
			goto out;
		}
		handle_incoming_invite (c, g_steal_pointer (&msg), from);
		return;
	}
	if (!req) {
		int code = sipmsg_status (msg);
		/* a call we already ended: its final answer still has to be ACKed,
		 * or the server retransmits it for half a minute; a 200 that
		 * crossed our CANCEL also gets a BYE, or the server keeps a call up
		 * that nobody is on */
		if (sipmsg_cseq_is (msg, "INVITE") && code >= 200) {
			const char *to = sipmsg_header (msg, "to");
			const char *s = to ? strstr (to, "sip:") : NULL;
			g_autofree char *uri = s ? g_strndup (s, strcspn (s, ">;")) : g_strdup_printf ("sip:%s", c->server);
			if (code >= 300) {
				send_ack_error (c, uri, msg);
			} else {
				g_autofree char *contact = sipmsg_contact_uri (msg);
				const char *target = contact ? contact : uri;
				g_autofree char *ack = sip_build_ack (&c->bctx, target, to, sipmsg_header (msg, "from"), call_id,
								      sipmsg_cseq_num (msg), c->user, pub_ip (c), c->adv_port);
				send_to (c, ack, NULL);
				g_autofree char *bye = sip_build_bye (&c->bctx, target, sipmsg_header (msg, "from"), to, call_id,
								      sipmsg_cseq_num (msg) + 1, c->user, pub_ip (c), c->adv_port);
				send_to (c, bye, NULL);
				g_message ("200 OK for a call already ended (%s) — ACK + BYE", call_id);
			}
		} else {
			g_debug ("Stray SIP response %d for unknown call-id=%s", code, call_id);
		}
		goto out;
	}
	if (!g_strcmp0 (method, "ACK"))
		goto out;
	stray_request_answer (c, msg, method, from);
out:
	if (msg)
		sipmsg_free (msg);
}

/* ---- transport: send ---- */

typedef struct {
	SipClient *c;
	GBytes *bytes;
	guint gen;
} WriteCtx;

static void
on_tls_written (GObject *src, GAsyncResult *res, gpointer data)
{
	WriteCtx *w = data;
	g_autoptr (GError) err = NULL;
	gboolean ok = g_output_stream_write_all_finish (G_OUTPUT_STREAM (src), res, NULL, &err);
	g_bytes_unref (w->bytes);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED)) {
		g_free (w);
		return;
	}
	SipClient *c = w->c;
	gboolean current = w->gen == c->tls_gen;
	g_free (w);
	if (!current)
		return;
	c->writing = FALSE;
	if (!ok) {
		drop_transport (c, err->message);
		return;
	}
	tls_write_next (c);
}

static void
tls_write_next (SipClient *c)
{
	if (c->writing || !c->tls_conn || g_queue_is_empty (&c->txq))
		return;
	WriteCtx *w = g_new0 (WriteCtx, 1);
	w->c = c;
	w->bytes = g_queue_pop_head (&c->txq);
	w->gen = c->tls_gen;
	c->writing = TRUE;
	gsize n;
	const void *p = g_bytes_get_data (w->bytes, &n);
	GOutputStream *out = g_io_stream_get_output_stream (G_IO_STREAM (c->tls_conn));
	g_output_stream_write_all_async (out, p, n, G_PRIORITY_DEFAULT, c->tls_cancel, on_tls_written, w);
}

static void
send_to (SipClient *c, const char *data, GSocketAddress *to)
{
	if (g_log_writer_default_would_drop (G_LOG_LEVEL_DEBUG, G_LOG_DOMAIN) == FALSE) {
		g_autofree char *first = g_strndup (data, strcspn (data, "\r\n"));
		if (*first)
			g_debug (">> %s", first);
	}
	gsize n = strlen (data);
	if (c->tls) {
		if (!c->tls_conn) {
			g_debug ("TLS not connected — message dropped");
			return;
		}
		g_queue_push_tail (&c->txq, g_bytes_new (data, n));
		tls_write_next (c);
		return;
	}
	GSocketAddress *dst = to ? to : c->server_sa;
	if (!c->udp || !dst)
		return;
	g_autoptr (GError) err = NULL;
	if (g_socket_send_to (c->udp, dst, data, n, NULL, &err) < 0)
		g_message ("Send error: %s", err->message);
}

/* ---- transport: receive ---- */

GBytes *
sip_stream_take_message (GByteArray *rx)
{
	/* RFC 5626 keepalives are bare CRLFs between messages, not messages */
	guint skip = 0;
	while (skip < rx->len && (rx->data[skip] == '\r' || rx->data[skip] == '\n'))
		skip++;
	if (skip)
		g_byte_array_remove_range (rx, 0, skip);
	const guint8 *hit = NULL;
	for (guint i = 0; i + 3 < rx->len; i++)
		if (!memcmp (rx->data + i, "\r\n\r\n", 4)) {
			hit = rx->data + i;
			break;
		}
	if (!hit)
		return NULL;
	gsize head = hit - rx->data;
	/* framed by Content-Length, in bytes: the blank line alone would cut
	 * every SDP offer and every SMS in half */
	g_autofree char *h = g_strndup ((const char *)rx->data, head);
	long body = 0;
	g_auto (GStrv) lines = g_strsplit (h, "\r\n", -1);
	for (int i = 1; lines[i]; i++) {
		char *colon = strchr (lines[i], ':');
		if (!colon)
			continue;
		g_autofree char *name = g_strstrip (g_strndup (lines[i], colon - lines[i]));
		if (!g_ascii_strcasecmp (name, "content-length") || !g_ascii_strcasecmp (name, "l")) {
			body = strtol (colon + 1, NULL, 10);
			if (body < 0)
				body = 0;
		}
	}
	gsize total = head + 4 + (gsize)body;
	if (rx->len < total)
		return NULL;
	GBytes *b = g_bytes_new (rx->data, total);
	g_byte_array_remove_range (rx, 0, total);
	return b;
}

typedef struct {
	SipClient *c;
	guint gen;
} ReadCtx;

static void tls_read (SipClient *c);

static void
on_tls_read (GObject *src, GAsyncResult *res, gpointer data)
{
	ReadCtx *r = data;
	g_autoptr (GError) err = NULL;
	g_autoptr (GBytes) b = g_input_stream_read_bytes_finish (G_INPUT_STREAM (src), res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED)) {
		g_free (r);
		return;
	}
	SipClient *c = r->c;
	gboolean current = r->gen == c->tls_gen;
	guint gen = r->gen;
	g_free (r);
	if (!current)
		return;
	if (!b) {
		drop_transport (c, err->message);
		return;
	}
	if (g_bytes_get_size (b) == 0) {
		drop_transport (c, "TLS connection closed by server");
		return;
	}
	gsize n;
	const guint8 *p = g_bytes_get_data (b, &n);
	g_byte_array_append (c->rx, p, n);
	GBytes *m;
	while (gen == c->tls_gen && (m = sip_stream_take_message (c->rx))) {
		gsize ml;
		const char *md = g_bytes_get_data (m, &ml);
		handle_packet (c, md, ml, NULL);
		g_bytes_unref (m);
	}
	if (gen != c->tls_gen)
		return;
	if (c->rx->len > TLS_RX_LIMIT) {
		drop_transport (c, "unframeable data on the TLS stream");
		return;
	}
	tls_read (c);
}

static void
tls_read (SipClient *c)
{
	ReadCtx *r = g_new0 (ReadCtx, 1);
	r->c = c;
	r->gen = c->tls_gen;
	GInputStream *in = g_io_stream_get_input_stream (G_IO_STREAM (c->tls_conn));
	g_input_stream_read_bytes_async (in, 8192, G_PRIORITY_DEFAULT, c->tls_cancel, on_tls_read, r);
}

static gboolean
on_udp_readable (GSocket *s, GIOCondition cond, gpointer data)
{
	SipClient *c = data;
	(void)cond;
	char buf[8192];
	for (;;) {
		GSocketAddress *from = NULL;
		gssize n = g_socket_receive_from (s, &from, buf, sizeof buf, NULL, NULL);
		if (n <= 0) {
			g_clear_object (&from);
			break;
		}
		handle_packet (c, buf, n, from);
		g_clear_object (&from);
		if (c->udp != s)	/* dropped from inside the handler */
			break;
	}
	return G_SOURCE_CONTINUE;
}

/* ---- transport: open / drop ---- */

static void
learn_local_ip (SipClient *c, GSocketAddress *local)
{
	if (c->cfg_public_ip || !G_IS_INET_SOCKET_ADDRESS (local))
		return;
	g_autofree char *ip = g_inet_address_to_string (g_inet_socket_address_get_address (G_INET_SOCKET_ADDRESS (local)));
	if (g_strcmp0 (ip, c->public_ip)) {
		g_message ("No public address known — advertising the local one, %s", ip);
		g_free (c->public_ip);
		c->public_ip = g_strdup (ip);
		reset_call_id_base (c);
	}
}

static void
open_failed (SipClient *c, const char *what, const char *msg)
{
	g_message ("%s failed (%s), next try in %" G_GINT64_FORMAT "s", what, msg, c->open_backoff / G_USEC_PER_SEC);
	c->open_backoff = MIN (c->open_backoff * 2, OPEN_BACKOFF_MAX_US);
}

static void
on_udp_resolved (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GError) err = NULL;
	GList *addrs = g_resolver_lookup_by_name_finish (G_RESOLVER (src), res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED))
		return;
	SipClient *c = data;
	c->resolving = FALSE;
	if (!addrs) {
		open_failed (c, "Resolving the server", err->message);
		return;
	}
	GInetAddress *v4 = NULL;
	for (GList *l = addrs; l; l = l->next)
		if (g_inet_address_get_family (l->data) == G_SOCKET_FAMILY_IPV4) { v4 = l->data; break; }
	if (!v4) {
		g_resolver_free_addresses (addrs);
		open_failed (c, "Resolving the server", "no IPv4 address");
		return;
	}
	g_clear_object (&c->server_sa);
	c->server_sa = g_inet_socket_address_new (v4, c->port);
	g_free (c->server_ip);
	c->server_ip = g_inet_address_to_string (v4);
	g_resolver_free_addresses (addrs);

	if (!c->udp) {
		GSocket *s = g_socket_new (G_SOCKET_FAMILY_IPV4, G_SOCKET_TYPE_DATAGRAM, G_SOCKET_PROTOCOL_UDP, &err);
		if (!s) {
			open_failed (c, "UDP socket", err->message);
			return;
		}
		g_autoptr (GInetAddress) any = g_inet_address_new_any (G_SOCKET_FAMILY_IPV4);
		g_autoptr (GSocketAddress) la = g_inet_socket_address_new (any, c->local_port);
		if (!g_socket_bind (s, la, TRUE, &err)) {
			g_object_unref (s);
			open_failed (c, "Binding the SIP port", err->message);
			return;
		}
		g_socket_set_blocking (s, FALSE);
		c->udp = s;
		c->udp_src = g_socket_create_source (s, G_IO_IN, NULL);
		g_source_set_callback (c->udp_src, G_SOURCE_FUNC (on_udp_readable), c, NULL);
		g_source_attach (c->udp_src, NULL);
	}
	c->adv_port = c->local_port;
	/* which local address the kernel would use toward the server: a
	 * connected datagram socket answers that without sending anything */
	g_autoptr (GSocket) probe = g_socket_new (G_SOCKET_FAMILY_IPV4, G_SOCKET_TYPE_DATAGRAM, G_SOCKET_PROTOCOL_UDP, NULL);
	if (probe && g_socket_connect (probe, c->server_sa, NULL, NULL)) {
		g_autoptr (GSocketAddress) local = g_socket_get_local_address (probe, NULL);
		if (local)
			learn_local_ip (c, local);
	}
	c->open_backoff = OPEN_BACKOFF_MIN_US;
	g_message ("Socket bound to UDP port %d, server %s:%d", c->local_port, c->server_ip, c->port);
	register_attempt (c);
}

static void
on_tls_connected (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GError) err = NULL;
	GSocketConnection *conn = g_socket_client_connect_to_host_finish (G_SOCKET_CLIENT (src), res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED))
		return;
	SipClient *c = data;
	c->tls_connecting = FALSE;
	if (!conn) {
		if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_TIMED_OUT)) {
			/* almost always TLS pointed at a plaintext port */
			g_autofree char *m = g_strdup_printf ("%s — is %d the TLS port? (plaintext SIP is usually 5060, TLS 5061)",
							      err->message, c->port);
			open_failed (c, "TLS connect", m);
		} else {
			open_failed (c, "TLS connect", err->message);
		}
		return;
	}
	c->tls_conn = conn;
	c->tls_gen++;
	g_byte_array_set_size (c->rx, 0);
	c->writing = FALSE;
	GSocket *sock = g_socket_connection_get_socket (conn);
	g_socket_set_option (sock, IPPROTO_TCP, TCP_NODELAY, 1, NULL);
	/* the client's 10 s timeout stays on the socket it made: the idle read
	 * then failed 10 s after every REGISTER and the gateway was offline a
	 * third of the time. Keepalive probes take its place: they hold the NAT
	 * mapping between the 30 min refreshes and find a dead server in ~1 min. */
	g_socket_set_timeout (sock, 0);
	g_socket_set_keepalive (sock, TRUE);
	g_socket_set_option (sock, IPPROTO_TCP, TCP_KEEPIDLE, 25, NULL);
	g_socket_set_option (sock, IPPROTO_TCP, TCP_KEEPINTVL, 10, NULL);
	g_socket_set_option (sock, IPPROTO_TCP, TCP_KEEPCNT, 4, NULL);
	g_autoptr (GSocketAddress) local = g_socket_connection_get_local_address (conn, NULL);
	g_autoptr (GSocketAddress) remote = g_socket_connection_get_remote_address (conn, NULL);
	if (local && G_IS_INET_SOCKET_ADDRESS (local)) {
		c->adv_port = g_inet_socket_address_get_port (G_INET_SOCKET_ADDRESS (local));
		learn_local_ip (c, local);
	}
	if (remote && G_IS_INET_SOCKET_ADDRESS (remote)) {
		g_free (c->server_ip);
		c->server_ip = g_inet_address_to_string (g_inet_socket_address_get_address (G_INET_SOCKET_ADDRESS (remote)));
	}
	const char *cipher = "?";
	if (G_IS_TCP_WRAPPER_CONNECTION (conn)) {
		GIOStream *base = g_tcp_wrapper_connection_get_base_io_stream (G_TCP_WRAPPER_CONNECTION (conn));
		if (G_IS_TLS_CONNECTION (base)) {
			const char *n = g_tls_connection_get_ciphersuite_name (G_TLS_CONNECTION (base));
			if (n)
				cipher = n;
		}
	}
	c->open_backoff = OPEN_BACKOFF_MIN_US;
	g_message ("TLS connected to %s:%d (%s), local port %d", c->server, c->port, cipher, c->adv_port);
	tls_read (c);
	register_attempt (c);
}

static void
maybe_open_transport (SipClient *c)
{
	gint64 now = now_us ();
	if (c->resolving || c->tls_connecting || now - c->last_open_attempt < c->open_backoff)
		return;
	c->last_open_attempt = now;
	if (c->tls) {
		c->tls_connecting = TRUE;
		g_clear_object (&c->tls_cancel);
		c->tls_cancel = g_cancellable_new ();
		/* system CAs, hostname check and SNI all come from connecting by
		 * name; there is deliberately no way to skip them -- a REGISTER
		 * carries the account password */
		g_autoptr (GSocketClient) sc = g_socket_client_new ();
		g_socket_client_set_tls (sc, TRUE);
		g_socket_client_set_timeout (sc, 10);
		g_socket_client_connect_to_host_async (sc, c->server, (guint16)c->port, c->cancel, on_tls_connected, c);
	} else {
		c->resolving = TRUE;
		g_autoptr (GResolver) r = g_resolver_get_default ();
		g_resolver_lookup_by_name_async (r, c->server, c->cancel, on_udp_resolved, c);
	}
}

static void
drop_transport (SipClient *c, const char *why)
{
	gboolean was = transport_open (c);
	c->registered = FALSE;
	if (c->registering) {
		c->registering = FALSE;
		if (c->reg_timer) {
			g_source_remove (c->reg_timer);
			c->reg_timer = 0;
		}
	}
	if (c->tls) {
		if (!c->tls_conn)
			return;
		g_message ("TLS connection lost: %s", why);
		c->tls_gen++;
		if (c->tls_cancel)
			g_cancellable_cancel (c->tls_cancel);
		g_io_stream_close_async (G_IO_STREAM (c->tls_conn), G_PRIORITY_DEFAULT, NULL, NULL, NULL);
		g_clear_object (&c->tls_conn);
		g_queue_clear_full (&c->txq, (GDestroyNotify)g_bytes_unref);
		c->writing = FALSE;
		g_byte_array_set_size (c->rx, 0);
	} else {
		if (was)
			g_message ("Dropping the UDP socket: %s", why);
		if (c->udp_src) {
			g_source_destroy (c->udp_src);
			g_source_unref (c->udp_src);
			c->udp_src = NULL;
		}
		g_clear_object (&c->udp);
		g_clear_object (&c->server_sa);
	}
}

/* ---- monitor ---- */

static gboolean
monitor_tick (gpointer data)
{
	SipClient *c = data;
	if (!c->running)
		return G_SOURCE_CONTINUE;
	if (!transport_open (c)) {
		maybe_open_transport (c);
		return G_SOURCE_CONTINUE;
	}
	gint64 now = now_us ();
	if (!c->registered) {
		register_attempt (c);	/* rate-limits itself */
		return G_SOURCE_CONTINUE;
	}
	/* a bare CRLF refreshes the NAT mapping; the server neither answers
	 * nor logs it, unlike OPTIONS (which fail2ban counted as auth failures).
	 * TLS needs none: TCP keepalive holds its mapping (on_tls_connected). */
	if (!c->tls && now - c->last_keepalive >= NAT_KEEPALIVE_US) {
		send_to (c, "\r\n\r\n", NULL);
		c->last_keepalive = now;
		alarm_arm (c);
	}
	if (now - c->last_register > c->reregister_us && !c->registering) {
		g_message ("Refreshing registration");
		register_attempt (c);
	}
	return G_SOURCE_CONTINUE;
}

/* The 5 s monitor only runs while the phone is awake.  What it must do on
 * time while the phone sleeps (the refresh, a UDP keepalive, a retry) gets an
 * alarm that wakes the phone for it: CLOCK_BOOTTIME_ALARM, so root, or
 * without the capability, an alarm that fires only while awake. */
#define UNREGISTERED_RETRY_US (60 * G_USEC_PER_SEC)

static void
alarm_arm (SipClient *c)
{
	if (c->alarm_fd < 0)
		return;
	gint64 now = now_us (), due = 0;
	if (c->running && c->registered) {
		due = c->last_register + c->reregister_us;
		if (!c->tls)
			due = MIN (due, c->last_keepalive + NAT_KEEPALIVE_US);
	} else if (c->running) {
		due = backoff.next_at > now ? backoff.next_at : now + UNREGISTERED_RETRY_US;
	}
	struct itimerspec its = { 0 };
	if (due) {
		/* never in the past: an overdue refresh in flight would fire at once, forever */
		due = MAX (due, now + G_USEC_PER_SEC);
		its.it_value.tv_sec = due / G_USEC_PER_SEC;
		its.it_value.tv_nsec = due % G_USEC_PER_SEC * 1000;
	}
	timerfd_settime (c->alarm_fd, TFD_TIMER_ABSTIME, &its, NULL);
}

static gboolean
alarm_fired (gint fd, GIOCondition cond, gpointer data)
{
	(void)cond;
	guint64 n;
	if (read (fd, &n, sizeof n) == sizeof n)
		monitor_tick (data);
	alarm_arm (data);
	return G_SOURCE_CONTINUE;
}

/* ---- lifecycle ---- */

SipClient *
sip_client_new (const SipConfig *cfg, const SipClientCallbacks *cb, gpointer ud)
{
	SipClient *c = g_new0 (SipClient, 1);
	c->server = g_strdup (cfg->server);
	c->port = cfg->port;
	c->user = g_strdup (cfg->user);
	c->password = g_strdup (cfg->password);
	c->tls = cfg->tls;
	c->srtp = cfg->srtp;
#ifndef HAVE_SRTP
	/* offering a key we cannot use would answer the call and then drop it */
	if (c->srtp) {
		g_warning ("srtp=true, but this build has no libsrtp2 — calls use plain RTP");
		c->srtp = FALSE;
	}
#endif
	c->codec = g_strdup (cfg->codec ? cfg->codec : "g722");
	c->local_port = cfg->local_port;
	c->cfg_public_ip = cfg->public_ip && *cfg->public_ip ? g_strdup (cfg->public_ip) : NULL;
	c->public_ip = g_strdup (c->cfg_public_ip);
	c->ua = g_strdup (cfg->user_agent);
	c->cb = *cb;
	c->ud = ud;
	c->bctx.user_agent = c->ua;
	c->bctx.transport = c->tls ? "TLS" : "UDP";
	c->bctx.codec_mode = c->codec;
	c->cancel = g_cancellable_new ();
	c->cseq = 1;
	c->alarm_fd = -1;
	c->reregister_us = REREGISTER_US;
	c->open_backoff = OPEN_BACKOFF_MIN_US;
	c->last_open_attempt = G_MININT64 / 2;
	c->adv_port = c->local_port;
	c->rx = g_byte_array_new ();
	g_queue_init (&c->txq);
	c->calls = g_hash_table_new_full (g_str_hash, g_str_equal, g_free, (GDestroyNotify)sip_call_unref);
	c->msgs = g_hash_table_new_full (g_str_hash, g_str_equal, g_free, msg_txn_free);
	if (c->srtp && !c->tls)
		g_message ("SRTP is on but signalling is UDP: SDES keys would travel in clear, so SRTP stays off");
	return c;
}

void
sip_client_set_public_ip (SipClient *c, const char *ip)
{
	g_free (c->cfg_public_ip);
	c->cfg_public_ip = ip && *ip ? g_strdup (ip) : NULL;
	if (c->cfg_public_ip && g_strcmp0 (c->cfg_public_ip, c->public_ip)) {
		g_free (c->public_ip);
		c->public_ip = g_strdup (c->cfg_public_ip);
		reset_call_id_base (c);
		g_message ("Public address %s", c->public_ip);
	}
}

void
sip_client_start (SipClient *c)
{
	if (c->running)
		return;
	c->running = TRUE;
	reset_call_id_base (c);
	maybe_open_transport (c);
	c->monitor = g_timeout_add_seconds (POLL_INTERVAL_S, monitor_tick, c);
	c->alarm_fd = timerfd_create (CLOCK_BOOTTIME_ALARM, TFD_CLOEXEC | TFD_NONBLOCK);
	if (c->alarm_fd < 0) {
		g_message ("No wake alarm (%s): registration refreshes wait for the phone to be awake",
			   g_strerror (errno));
		c->alarm_fd = timerfd_create (CLOCK_BOOTTIME, TFD_CLOEXEC | TFD_NONBLOCK);
	}
	if (c->alarm_fd >= 0) {
		c->alarm_src = g_unix_fd_add (c->alarm_fd, G_IO_IN, alarm_fired, c);
		alarm_arm (c);
	}
}

void
sip_client_stop (SipClient *c)
{
	if (!c->running)
		return;
	c->running = FALSE;
	c->registered = FALSE;
	if (c->monitor) {
		g_source_remove (c->monitor);
		c->monitor = 0;
	}
	if (c->alarm_src) {
		g_source_remove (c->alarm_src);
		c->alarm_src = 0;
	}
	if (c->alarm_fd >= 0) {
		close (c->alarm_fd);
		c->alarm_fd = -1;
	}
	GList *calls = g_hash_table_get_values (c->calls);
	for (GList *l = calls; l; l = l->next)
		sip_call_hangup (l->data);
	g_list_free (calls);
	g_hash_table_remove_all (c->calls);
	/* nobody is left to see a response, so every pending MESSAGE is
	 * reported unsent now and stays queued by whoever sent it */
	GList *ids = g_hash_table_get_keys (c->msgs);
	for (GList *l = ids; l; l = l->next) {
		MsgTxn *t = g_hash_table_lookup (c->msgs, l->data);
		if (t)
			msg_txn_finish (t, 0);
	}
	g_list_free (ids);
}

void
sip_client_free (SipClient *c)
{
	if (!c)
		return;
	sip_client_stop (c);
	g_cancellable_cancel (c->cancel);
	if (c->reg_timer)
		g_source_remove (c->reg_timer);
	drop_transport (c, "shutting down");
	g_clear_object (&c->tls_cancel);
	g_clear_object (&c->cancel);
	g_hash_table_unref (c->calls);
	g_hash_table_unref (c->msgs);
	g_byte_array_unref (c->rx);
	g_free (c->server);
	g_free (c->user);
	if (c->password)
		memset (c->password, 0, strlen (c->password));
	g_free (c->password);
	g_free (c->codec);
	g_free (c->ua);
	g_free (c->public_ip);
	g_free (c->cfg_public_ip);
	g_free (c->call_id_base);
	g_free (c->server_ip);
	g_free (c);
}
