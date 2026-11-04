#define G_LOG_DOMAIN "bridge"
#include "bridge.h"
#include "gwutil.h"
#include <string.h>

#define SIP_CALL_TIMEOUT_MS 30000
/* a safety net once the server reports ringing: its own dial timeout
 * normally ends the call first */
#define SIP_RING_MAX_US (70 * G_USEC_PER_SEC)
#define GSM_DIAL_TIMEOUT_MS 45000
#define UNMUTE_DELAY_MS 1000
#define MAX_SIP_RETRIES 2
#define MIN_DIALABLE_DIGITS 3
#define SMS_RETRY_S 60
#define REPORT_MAX_ATTEMPTS 30
#define SEEN_IDS_MAX 5000

typedef struct {
	char *path, *number, *text, *received, *id;
	int parts, attempts;
} SmsOut;

typedef struct {
	char *id, *to, *event, *status, *reason, *encoding, *at;
	int parts, attempts;
	gboolean ok;
} SmsReport;

struct Bridge {
	char *own_number;	/* international form */
	gboolean sms_ascii;
	RtpAudioConfig audio;
	char *cap_dev, *pb_dev, *ctl_card, *uplink, *active, *mute;
	SipClient *sip;
	Modem *modem;

	BridgeState state;
	guint64 generation;
	GList *timers;		/* source ids, for bridge_free */
	guint unmute;		/* puts the amps back a moment after the call */

	SipCall *sip_call;
	int sip_attempt;
	gint64 sip_sent_at;
	int sip_retries;
	char *gsm_path;
	gboolean gsm_terminated, gsm_hangup_requested;
	char *gsm_caller;
	RtpSession *rtp;
	gboolean pending_rtp;	/* SIP answered; media starts when GSM goes active */

	GQueue sms_out;
	gboolean sms_flushing;
	GQueue reports;
	gboolean report_sending;
	guint sms_retry;
	GHashTable *seen;	/* X-SMS-Id of messages accepted from the server */
	GQueue seen_order;

	GwRecents *recents;
	BridgeStateNotify notify;
	gpointer notify_data;
	/* the call being listed: from when it arrived or was dialled, so an
	 * attempt that never bridged has a time to show */
	gint64 attempt_ms, bridged_us;
	gboolean attempt_incoming;
	char *attempt_number;
	gboolean agent_muted;	/* the app's Mute, for the call in progress */
};

const char *
bridge_state_name (BridgeState s)
{
	static const char *n[] = { "IDLE", "GSM_RINGING", "GSM_ANSWERED", "SIP_CALLING", "SIP_RINGING",
				   "BRIDGED", "GSM_DIALING", "TEARING_DOWN" };
	return n[s];
}

static gboolean
unmute_amps (gpointer data)
{
	Bridge *b = data;
	b->unmute = 0;
	rtp_mute_amps (&b->audio, FALSE);
	return G_SOURCE_REMOVE;
}

static void
set_state (Bridge *b, BridgeState s, const char *info)
{
	if (b->state == s)
		return;
	g_message ("%s -> %s%s%s", bridge_state_name (b->state), bridge_state_name (s), info ? ": " : "", info ? info : "");
	/* every state but IDLE has a GSM call, whose downlink the phone's own
	 * earpiece and speaker would play from the first ringback on; at IDLE
	 * the hangup is still on its way to the modem */
	if (s != BRIDGE_IDLE) {
		if (b->unmute) {
			g_source_remove (b->unmute);
			b->unmute = 0;
		}
		rtp_mute_amps (&b->audio, TRUE);
	} else if (!b->unmute) {
		b->unmute = g_timeout_add (UNMUTE_DELAY_MS, unmute_amps, b);
	}
	b->state = s;
	if (s == BRIDGE_BRIDGED && b->attempt_ms && !b->bridged_us)
		b->bridged_us = g_get_monotonic_time ();
	if (b->notify)
		b->notify (s, info, b->notify_data);
}

static void
attempt_begin (Bridge *b, gboolean incoming, const char *number)
{
	b->attempt_ms = g_get_real_time () / 1000;
	b->bridged_us = 0;
	b->attempt_incoming = incoming;
	g_free (b->attempt_number);
	b->attempt_number = g_strdup (number && *number ? number : "anonymous");
}

static void
attempt_end (Bridge *b)
{
	if (!b->attempt_ms)
		return;
	/* zero is how the list shows a call that never connected */
	gint64 dur = b->bridged_us ? (g_get_monotonic_time () - b->bridged_us) / G_USEC_PER_SEC : 0;
	if (b->recents)
		gw_recents_add_call (b->recents, b->attempt_incoming, b->attempt_number, b->attempt_ms, dur);
	b->attempt_ms = b->bridged_us = 0;
	g_clear_pointer (&b->attempt_number, g_free);
}

BridgeState bridge_state (Bridge *b) { return b->state; }

/* ---- generation-checked timers ----
 * A timer from a call that has already ended must not act on the next one:
 * a dial that failed at t+5 s once left its 45 s timeout to tear down a
 * different call that was answered at t+20 s. */

typedef void (*TimerFn) (Bridge *b, int arg);
typedef struct {
	Bridge *b;
	guint64 gen;
	TimerFn fn;
	int arg;
	guint id;
} BTimer;

static gboolean
btimer_fire (gpointer data)
{
	BTimer *t = data;
	Bridge *b = t->b;
	b->timers = g_list_remove (b->timers, GUINT_TO_POINTER (t->id));
	if (t->gen == b->generation)
		t->fn (b, t->arg);
	return G_SOURCE_REMOVE;
}

static void
schedule (Bridge *b, guint ms, TimerFn fn, int arg)
{
	BTimer *t = g_new0 (BTimer, 1);
	t->b = b;
	t->gen = b->generation;
	t->fn = fn;
	t->arg = arg;
	t->id = g_timeout_add_full (G_PRIORITY_DEFAULT, ms, btimer_fire, t, g_free);
	b->timers = g_list_prepend (b->timers, GUINT_TO_POINTER (t->id));
}

/* ---- teardown ---- */

static void
tear_down (Bridge *b, const char *reason, int code, const char *phrase)
{
	if (b->state == BRIDGE_IDLE || b->state == BRIDGE_TEARING_DOWN)
		return;
	set_state (b, BRIDGE_TEARING_DOWN, reason);
	if (b->rtp) {
		rtp_session_free (b->rtp);
		b->rtp = NULL;
	}
	if (b->sip_call) {
		SipCall *c = b->sip_call;
		if (c->state != SIP_CALL_TERMINATED) {
			/* an INVITE we never answered gets a final response, which is
			 * the only way the server learns why the GSM leg did not come up */
			if (c->dir == SIP_DIR_INBOUND && c->state != SIP_CALL_ANSWERED)
				sip_call_reject (c, code ? code : 480, phrase ? phrase : "Temporarily Unavailable");
			else
				sip_call_hangup (c);
		}
		sip_client_remove_call (b->sip, c);
		sip_call_unref (c);
		b->sip_call = NULL;
	}
	if (b->gsm_path) {
		/* always, ringing, dialling or active: a ringing GSM call left behind
		 * was "the second call never answered" on the S4 Mini */
		if (!b->gsm_terminated) {
			b->gsm_hangup_requested = TRUE;
			modem_call_hangup (b->modem, b->gsm_path);
		} else {
			modem_call_delete (b->modem, b->gsm_path);
		}
		g_clear_pointer (&b->gsm_path, g_free);
	}
	g_clear_pointer (&b->gsm_caller, g_free);
	b->pending_rtp = FALSE;
	b->agent_muted = FALSE;
	b->gsm_terminated = FALSE;
	b->generation++;
	attempt_end (b);
	set_state (b, BRIDGE_IDLE, reason);
}

/* the SIP final response that says why a GSM call we placed did not connect */
static void
gsm_end_status (Bridge *b, guint reason, int *code, const char **phrase)
{
	switch (reason) {
	case MM_REASON_REFUSED_OR_BUSY: *code = 486; *phrase = "Busy Here"; return;
	case MM_REASON_ERROR:
	case MM_REASON_AUDIO_SETUP_FAILED: *code = 503; *phrase = "Service Unavailable"; return;
	default:
		if (b->gsm_hangup_requested) {
			*code = 487;
			*phrase = "Request Terminated";
		} else {
			*code = 480;
			*phrase = "Temporarily Unavailable";
		}
	}
}

/* ---- media ---- */

static void
on_rtp_timeout (gpointer ud)
{
	tear_down (ud, "RTP timeout — no audio from the server", 0, NULL);
}

static gboolean
start_rtp (Bridge *b)
{
	SipCall *c = b->sip_call;
	g_autoptr (GError) err = NULL;
	if (!c)
		return FALSE;
	if (!b->rtp && !(b->rtp = rtp_session_new (&err))) {
		tear_down (b, err->message, 503, "Service Unavailable");
		return FALSE;
	}
	/* keyed before the first packet: a call that agreed to be protected
	 * never sends or accepts one in clear */
	if (sip_call_srtp_active (c) &&
	    !rtp_session_set_srtp (b->rtp, c->local_key.tag_bits, c->local_key.key, c->remote_key.key, &err)) {
		g_autofree char *why = g_strdup_printf ("SRTP setup failed: %s", err->message);
		tear_down (b, why, 488, "Not Acceptable Here");
		return FALSE;
	}
	RtpCallbacks cb = { .timeout = on_rtp_timeout };
	rtp_session_set_agent_muted (b->rtp, b->agent_muted);
	if (!rtp_session_start (b->rtp, &b->audio, c->remote_rtp_addr, sip_client_server_ip (b->sip), c->remote_rtp_port,
				c->pt, c->te_pt, &cb, b, &err)) {
		tear_down (b, err->message, 503, "Service Unavailable");
		return FALSE;
	}
	return TRUE;
}

/* ---- inbound: GSM -> SIP ---- */

static const char *
inbound_target (Bridge *b)
{
	if (b->own_number && *b->own_number)
		return b->own_number;
	/* addressing our own account makes the server see account N calling
	 * account N and ring us straight back */
	g_warning ("No own_number configured — addressing our own extension, which loops back");
	return sip_client_user (b->sip);
}

static void sip_timeout_check (Bridge *b, int attempt);

static void
inbound_flow (Bridge *b)
{
	g_autoptr (GError) err = NULL;
	set_state (b, BRIDGE_SIP_CALLING, NULL);
	if (!b->rtp && !(b->rtp = rtp_session_new (&err))) {
		tear_down (b, err->message, 0, NULL);
		return;
	}
	/* the caller as the carrier delivered it; withheld goes as anonymous */
	const char *num = b->gsm_caller && *b->gsm_caller ? b->gsm_caller : NULL;
	b->sip_call = sip_client_make_call (b->sip, inbound_target (b), rtp_session_port (b->rtp),
					    num ? num : "anonymous", num ? num : "Unknown");
	b->sip_call->user_data = b;
	b->sip_sent_at = g_get_monotonic_time ();
	b->sip_attempt++;
	schedule (b, SIP_CALL_TIMEOUT_MS, sip_timeout_check, b->sip_attempt);
}

static void
sip_timeout_check (Bridge *b, int attempt)
{
	if ((b->state != BRIDGE_SIP_CALLING && b->state != BRIDGE_SIP_RINGING) || attempt != b->sip_attempt || !b->sip_call)
		return;
	gint64 elapsed = g_get_monotonic_time () - b->sip_sent_at;
	/* answered and waiting for the GSM pickup counts as ringing: the 30 s
	 * mark must not cut a call the agent has just taken */
	gboolean ringing = b->sip_call->state == SIP_CALL_RINGING || b->sip_call->state == SIP_CALL_ANSWERED;
	/* once the server says it is ringing, its own dial timeout decides;
	 * cutting at 30 s regardless gave a push-woken softphone 20 s to answer */
	if (ringing && elapsed < SIP_RING_MAX_US) {
		schedule (b, 5000, sip_timeout_check, attempt);
		return;
	}
	g_message ("SIP call timeout — %s %" G_GINT64_FORMAT " s", ringing ? "rang" : "no ringing from the server in",
		   elapsed / G_USEC_PER_SEC);
	tear_down (b, ringing ? "No answer" : "Server not answering", 0, NULL);
}

static void
retry_inbound (Bridge *b, int arg)
{
	(void)arg;
	if (b->state != BRIDGE_SIP_CALLING && b->state != BRIDGE_SIP_RINGING)
		return;
	if (!b->gsm_path) {
		g_message ("SIP retry: GSM call gone, aborting");
		tear_down (b, "GSM call gone", 0, NULL);
		return;
	}
	inbound_flow (b);
}

static void
on_incoming_gsm (Bridge *b, const char *path, const char *number)
{
	if (b->state != BRIDGE_IDLE) {
		if (!g_strcmp0 (path, b->gsm_path))
			return;
		g_message ("Busy (%s) — rejecting GSM call from %s", bridge_state_name (b->state), number ? number : "?");
		modem_call_hangup (b->modem, path);
		return;
	}
	b->sip_retries = 0;
	b->gsm_path = g_strdup (path);
	b->gsm_caller = g_strdup (number);
	b->gsm_terminated = b->gsm_hangup_requested = FALSE;
	attempt_begin (b, TRUE, number);
	g_autofree char *info = g_strdup_printf ("GSM call from %s", number && *number ? number : "anonymous");
	set_state (b, BRIDGE_GSM_RINGING, info);
	/* not answered yet: the SIP side goes first, and GSM is picked up only
	 * once the agent has answered, so the caller hears ringing until then */
	inbound_flow (b);
}

static void
on_gsm_active (Bridge *b)
{
	switch (b->state) {
	case BRIDGE_SIP_CALLING:
	case BRIDGE_SIP_RINGING:
		if (b->pending_rtp) {
			b->pending_rtp = FALSE;
			if (start_rtp (b))
				set_state (b, BRIDGE_BRIDGED, "inbound");
		} else {
			/* picked up before the SIP side was ready */
			set_state (b, BRIDGE_GSM_ANSWERED, "GSM active, SIP not answered yet");
		}
		break;
	case BRIDGE_GSM_DIALING: {
		/* the GSM party answered: now the SIP caller gets its 200 */
		g_autoptr (GError) err = NULL;
		if (!b->rtp && !(b->rtp = rtp_session_new (&err))) {
			tear_down (b, err->message, 503, "Service Unavailable");
			return;
		}
		sip_call_accept (b->sip_call, rtp_session_port (b->rtp));
		if (b->sip_call->remote_rtp_port > 0 && !start_rtp (b))
			return;
		set_state (b, BRIDGE_BRIDGED, "outbound");
		break;
	}
	default:
		break;
	}
}

/* ---- SIP callbacks ---- */

static void sms_flush (Bridge *b);
static void reports_flush (Bridge *b);

static void
on_sip_registered (gpointer ud)
{
	Bridge *b = ud;
	/* anything the modem still holds goes out now, then whatever queued */
	if (b->modem)
		modem_sms_rescan (b->modem);
	sms_flush (b);
	reports_flush (b);
}

static void
on_sip_registration_failed (gpointer ud)
{
	(void)ud;
}

static gboolean
outbound_digits_match_own (Bridge *b, const char *user)
{
	g_autofree char *d1 = gw_digits_of (user);
	g_autofree char *d2 = gw_digits_of (inbound_target (b));
	return !g_strcmp0 (d1, d2);
}

/* X-GSM-Forward first, then a number in the R-URI -- the order the SMS path
 * resolves a recipient in.  Our account name and our own MSISDN in the R-URI
 * are the server addressing us, not a number to dial: both would loop. */
static char *
outbound_destination (Bridge *b, SipCall *call)
{
	if (call->gsm_forward && *call->gsm_forward)
		return g_strdup (call->gsm_forward);
	g_autofree char *ruri = call->invite ? sipmsg_request_uri (call->invite) : NULL;
	g_autofree char *user = ruri ? sip_extract_user (ruri) : NULL;
	if (user)
		g_strstrip (user);
	if (!user || !*user)
		return NULL;
	if (!g_ascii_strcasecmp (user, sip_client_user (b->sip)) || outbound_digits_match_own (b, user) ||
	    !gw_looks_dialable (user, MIN_DIALABLE_DIGITS))
		return NULL;
	g_message ("No X-GSM-Forward — destination taken from the Request-URI");
	return g_steal_pointer (&user);
}

typedef struct {
	Bridge *b;
	guint64 gen;
} GenCtx;

static void
on_dialed (const char *path, const char *error, gpointer ud)
{
	GenCtx *g = ud;
	Bridge *b = g->b;
	gboolean current = g->gen == b->generation && b->state == BRIDGE_GSM_DIALING;
	g_free (g);
	if (!current) {
		/* the SIP side gave up while the modem was still dialling */
		if (path) {
			g_message ("Dial completed for a call that has ended — hanging it up");
			modem_call_hangup (b->modem, path);
		}
		return;
	}
	if (path && !b->gsm_path)
		b->gsm_path = g_strdup (path);
	if (error) {
		g_autofree char *why = g_strdup_printf ("Cannot dial GSM: %s", error);
		tear_down (b, why, 503, "Service Unavailable");
	}
}

static void
dial_timeout (Bridge *b, int arg)
{
	(void)arg;
	if (b->state == BRIDGE_GSM_DIALING)
		tear_down (b, "GSM dial timeout", 0, NULL);
}

static void
on_sip_incoming (gpointer ud, SipCall *call)
{
	Bridge *b = ud;
	if (b->state != BRIDGE_IDLE) {
		g_message ("Busy (%s) — rejecting SIP call 486", bridge_state_name (b->state));
		sip_call_reject (call, 486, "Busy Here");
		sip_client_remove_call (b->sip, call);
		return;
	}
	g_autofree char *dest = outbound_destination (b, call);
	if (!dest) {
		/* a 200 here would leave the server bridged to silence */
		g_message ("SIP INVITE names no GSM destination — rejecting 488");
		sip_call_reject (call, 488, "Not Acceptable Here");
		sip_client_remove_call (b->sip, call);
		return;
	}
	if (!b->modem || !modem_present (b->modem)) {
		g_message ("No modem — rejecting SIP call 503");
		sip_call_reject (call, 503, "Service Unavailable");
		sip_client_remove_call (b->sip, call);
		return;
	}
	b->sip_call = sip_call_ref (call);
	call->user_data = b;
	b->gsm_terminated = b->gsm_hangup_requested = FALSE;
	attempt_begin (b, FALSE, dest);
	g_autofree char *info = g_strdup_printf ("Dialing %s", dest);
	set_state (b, BRIDGE_GSM_DIALING, info);
	sip_call_ringing (call);
	GenCtx *g = g_new0 (GenCtx, 1);
	g->b = b;
	g->gen = b->generation;
	modem_dial (b->modem, dest, on_dialed, g);
	schedule (b, GSM_DIAL_TIMEOUT_MS, dial_timeout, 0);
}

static void
on_sip_answered (gpointer ud, SipCall *call)
{
	(void)ud;
	g_message ("SIP call answered: %s", call->call_id);
}

static void
on_sip_rtp_ready (gpointer ud, SipCall *call)
{
	Bridge *b = ud;
	if (call != b->sip_call)
		return;
	if (b->state == BRIDGE_SIP_CALLING || b->state == BRIDGE_SIP_RINGING) {
		if (modem_call_state (b->modem, b->gsm_path) == MM_CALL_ACTIVE) {
			if (start_rtp (b))
				set_state (b, BRIDGE_BRIDGED, "GSM already active");
			return;
		}
		/* the agent is there: pick the GSM call up now, and start the
		 * media the moment it is active */
		b->pending_rtp = TRUE;
		g_message ("SIP answered (pt=%d) — answering the GSM call", call->pt);
		modem_call_accept (b->modem, b->gsm_path);
	} else if (b->state == BRIDGE_GSM_ANSWERED) {
		if (start_rtp (b))
			set_state (b, BRIDGE_BRIDGED, "inbound");
	} else {
		g_message ("RTP ready ignored in %s", bridge_state_name (b->state));
	}
}

static void
on_sip_terminated (gpointer ud, SipCall *call)
{
	Bridge *b = ud;
	if (call != b->sip_call)
		return;
	/* retry only what may be transient; a busy or a decline must end the
	 * GSM call, or the softphone rings again after the user said no */
	int st = call->final_status;
	gboolean transient = st == 0 || st == 408 || st == 500 || st == 503 || st == 504;
	if ((b->state == BRIDGE_SIP_CALLING || b->state == BRIDGE_SIP_RINGING) && transient &&
	    b->sip_retries < MAX_SIP_RETRIES && b->gsm_path) {
		b->sip_retries++;
		g_message ("SIP call failed (%d) while GSM rings — retrying (%d/%d)", st, b->sip_retries, MAX_SIP_RETRIES);
		sip_client_remove_call (b->sip, call);
		sip_call_unref (call);
		b->sip_call = NULL;
		schedule (b, 1000, retry_inbound, 0);
		return;
	}
	if (st >= 300)
		g_message ("SIP side answered %d — not retrying, ending the GSM call", st);
	tear_down (b, "SIP call ended", 0, NULL);
}

static void
on_sip_dtmf (gpointer ud, SipCall *call, char digit, int ms)
{
	Bridge *b = ud;
	if (call != b->sip_call || !b->rtp) {
		g_message ("INFO DTMF '%c' for a call that is not bridged — ignored", digit);
		return;
	}
	rtp_session_dtmf_pulse (b->rtp, digit, ms);
}

/* ---- SMS: SIP -> GSM ---- */

typedef struct {
	Bridge *b;
	char *id, *to, *encoding;
	int parts;
} SendJob;

static void
report_free (SmsReport *r)
{
	g_free (r->id);
	g_free (r->to);
	g_free (r->event);
	g_free (r->status);
	g_free (r->reason);
	g_free (r->encoding);
	g_free (r->at);
	g_free (r);
}

static void
on_sms_sent (gboolean ok, const char *error, gpointer ud)
{
	SendJob *j = ud;
	Bridge *b = j->b;
	SmsReport *r = g_new0 (SmsReport, 1);
	r->id = g_steal_pointer (&j->id);
	r->to = g_steal_pointer (&j->to);
	r->encoding = g_steal_pointer (&j->encoding);
	r->parts = MAX (j->parts, 1);
	r->ok = ok;
	r->event = g_strdup (ok ? "sent" : "failed");
	r->status = g_strdup (r->event);
	r->reason = g_strdup (error);
	r->at = gw_iso_utc (NULL);
	g_message ("SMS %s to %s %s%s%s", r->id, r->to, r->event, error ? ": " : "", error ? error : "");
	if (b->recents)
		gw_recents_update_sms (b->recents, r->id, r->event, error);
	g_free (j);
	g_queue_push_tail (&b->reports, r);
	reports_flush (b);
}

static void
remember_id (Bridge *b, const char *id)
{
	char *k = g_strdup (id);
	g_hash_table_add (b->seen, k);
	g_queue_push_tail (&b->seen_order, k);
	while (g_queue_get_length (&b->seen_order) > SEEN_IDS_MAX) {
		char *old = g_queue_pop_head (&b->seen_order);
		g_hash_table_remove (b->seen, old);
		g_free (old);
	}
}

static int
on_sip_message (gpointer ud, const SipMsg *msg, GPtrArray *extra)
{
	Bridge *b = ud;
	const char *ct = sipmsg_header (msg, "content-type");
	g_autofree char *type = ct ? g_ascii_strdown (ct, -1) : g_strdup ("");
	if (*type && !g_str_has_prefix (type, "text/plain")) {
		g_message ("SMS send refused: unsupported Content-Type '%s'", type);
		return 415;
	}
	const char *hto = sipmsg_header (msg, "x-sms-to");
	g_autofree char *ruri = sipmsg_request_uri (msg);
	g_autofree char *target = hto ? g_strdup (hto) : NULL;
	if (!target && ruri)
		target = sip_extract_user (ruri);
	if (!target)
		target = sip_extract_user (sipmsg_header (msg, "to"));
	if (target)
		g_strstrip (target);
	/* folded before anything measures or lists it, so the part count and
	 * the log describe what actually goes out */
	g_autofree char *body = NULL;
	if (msg->body && *msg->body && b->sms_ascii) {
		body = gw_sms_ascii_or_null (msg->body);
		if (!body)
			g_message ("SMS send: no ASCII spelling for this text — sending it unchanged as UCS-2");
		else if (strcmp (body, msg->body))
			g_message ("SMS send: transliterated to ASCII (%ld -> %ld chars)", g_utf8_strlen (msg->body, -1),
				   (long)strlen (body));
	}
	if (!body)
		body = g_strdup (msg->body);
	if (!target || !*target || !body || !*body) {
		g_message ("SMS send refused: missing recipient or body");
		return 400;
	}
	if (!b->modem || !modem_present (b->modem)) {
		/* 503, not 4xx: the server should try this one again later */
		g_message ("SMS send refused: no modem");
		return 503;
	}
	const char *hid = sipmsg_header (msg, "x-sms-id");
	g_autofree char *id = hid && *hid ? g_strstrip (g_strdup (hid)) : gw_uuid_random ();
	/* what it will cost, told before the server commits: one character
	 * outside GSM-7 turns 160-character parts into 70-character ones */
	GwSmsMeasure m = gw_sms_measure (body);
	g_ptr_array_add (extra, g_strdup_printf ("X-SMS-Parts: %d", m.parts));
	g_ptr_array_add (extra, g_strdup_printf ("X-SMS-Encoding: %s", m.encoding));
	if (g_hash_table_contains (b->seen, id)) {
		/* the server repeated a request whose 202 it did not see */
		g_message ("SMS send: %s already accepted — not sending twice", id);
		return 202;
	}
	remember_id (b, id);
	g_message ("SMS send: %s to %s accepted (%ld chars, %d part(s), %s)", id, target,
		   g_utf8_strlen (body, -1), m.parts, m.encoding);
	if (b->recents)
		gw_recents_add_sms (b->recents, FALSE, target, g_get_real_time () / 1000, body, id, m.encoding, m.parts,
				    "pending");
	SendJob *j = g_new0 (SendJob, 1);
	j->b = b;
	j->id = g_strdup (id);
	j->to = g_strdup (target);
	j->encoding = g_strdup (m.encoding);
	j->parts = m.parts;
	modem_sms_send (b->modem, target, body, on_sms_sent, j);
	return 202;
}

/* ---- SMS reports, back to the server ---- */

static const char *
own_or_user (Bridge *b)
{
	return b->own_number && *b->own_number ? b->own_number : sip_client_user (b->sip);
}

static void
schedule_sms_retry (Bridge *b);

static void
on_report_sent (int status, gpointer ud)
{
	Bridge *b = ud;
	SmsReport *r = g_queue_peek_head (&b->reports);
	b->report_sending = FALSE;
	if (!r)
		return;
	if (status == 200 || status == 202) {
		g_message ("SMS report %s for %s acknowledged", r->event, r->id);
		report_free (g_queue_pop_head (&b->reports));
		reports_flush (b);
		return;
	}
	r->attempts++;
	g_message ("SMS report %s for %s not acknowledged (%s)%s", r->event, r->id,
		   status ? "status" : "no response", r->attempts >= REPORT_MAX_ATTEMPTS ? " — giving up" : "");
	if (r->attempts >= REPORT_MAX_ATTEMPTS) {
		report_free (g_queue_pop_head (&b->reports));
		reports_flush (b);
		return;
	}
	schedule_sms_retry (b);
}

static void
reports_flush (Bridge *b)
{
	SmsReport *r = g_queue_peek_head (&b->reports);
	if (!r || b->report_sending || !b->sip || !sip_client_registered (b->sip))
		return;
	GString *j = g_string_new ("{");
	g_string_append (j, "\"id\":");
	gw_json_append_string (j, r->id);
	g_string_append (j, ",\"event\":");
	gw_json_append_string (j, r->event);
	g_string_append (j, ",\"to\":");
	gw_json_append_string (j, r->to);
	g_string_append_printf (j, ",\"parts\":%d,\"sentOk\":%d,\"sentFailed\":%d,\"deliveredOk\":0,\"deliveredFailed\":0",
				r->parts, r->ok ? r->parts : 0, r->ok ? 0 : r->parts);
	g_string_append (j, ",\"status\":");
	gw_json_append_string (j, r->status);
	if (r->reason && *r->reason) {
		g_string_append (j, ",\"reason\":");
		gw_json_append_string (j, r->reason);
	}
	g_string_append (j, ",\"encoding\":");
	gw_json_append_string (j, r->encoding);
	g_string_append (j, ",\"at\":");
	gw_json_append_string (j, r->at);
	g_string_append_c (j, '}');

	GPtrArray *h = g_ptr_array_new_with_free_func (g_free);
	g_ptr_array_add (h, g_strdup_printf ("X-SMS-Id: %s", r->id));
	g_ptr_array_add (h, g_strdup_printf ("X-SMS-Event: %s", r->event));
	g_ptr_array_add (h, g_strdup_printf ("X-SMS-To: %s", r->to));
	g_ptr_array_add (h, g_strdup_printf ("X-SMS-Parts: %d", r->parts));
	g_ptr_array_add (h, g_strdup_printf ("X-SMS-At: %s", r->at));
	g_ptr_array_add (h, g_strdup_printf ("X-SMS-Status: %s", r->status));
	if (r->reason && *r->reason) {
		/* one header line: a modem error can carry a newline */
		g_autofree char *flat = g_strdelimit (g_strdup (r->reason), "\r\n", ' ');
		g_ptr_array_add (h, g_strdup_printf ("X-SMS-Reason: %s", flat));
	}
	g_ptr_array_add (h, g_strdup_printf ("X-SMS-Encoding: %s", r->encoding));
	g_ptr_array_add (h, NULL);
	g_autofree char *uri = g_strdup_printf ("sip:%s@%s", own_or_user (b), sip_client_server (b->sip));
	b->report_sending = TRUE;
	/* text/plain, not application/json: chan_sip answers 415 to anything
	 * else on an out-of-dialog MESSAGE.  Every field is a header as well. */
	sip_client_send_message (b->sip, uri, own_or_user (b), j->str, (const char *const *)h->pdata, NULL,
				 on_report_sent, b);
	g_ptr_array_unref (h);
	g_string_free (j, TRUE);
}

/* ---- SMS: GSM -> SIP ---- */

static void
sms_out_free (SmsOut *s)
{
	g_free (s->path);
	g_free (s->number);
	g_free (s->text);
	g_free (s->received);
	g_free (s->id);
	g_free (s);
}

static gboolean
sms_retry_fire (gpointer data)
{
	Bridge *b = data;
	b->sms_retry = 0;
	sms_flush (b);
	reports_flush (b);
	return G_SOURCE_REMOVE;
}

static void
schedule_sms_retry (Bridge *b)
{
	if (!b->sms_retry)
		b->sms_retry = g_timeout_add_seconds (SMS_RETRY_S, sms_retry_fire, b);
}

static void
on_sms_forwarded (int status, gpointer ud)
{
	Bridge *b = ud;
	SmsOut *s = g_queue_peek_head (&b->sms_out);
	b->sms_flushing = FALSE;
	if (!s)
		return;
	if (status == 200 || status == 202) {
		g_message ("SMS %s from %s accepted (%d)", s->id, s->number, status);
		modem_sms_delete (b->modem, s->path);	/* only now: the server has it */
		sms_out_free (g_queue_pop_head (&b->sms_out));
		sms_flush (b);
		return;
	}
	s->attempts++;
	g_message ("SMS %s from %s not accepted (%s) — queued, retry in %d s", s->id, s->number,
		   status ? "rejected" : "no response", SMS_RETRY_S);
	/* the rest wait too: keep the order, a later one is no likelier to land */
	schedule_sms_retry (b);
}

static void
sms_flush (Bridge *b)
{
	SmsOut *s = g_queue_peek_head (&b->sms_out);
	if (!s || b->sms_flushing || !b->sip || !sip_client_registered (b->sip))
		return;
	const char *target = own_or_user (b);
	g_autofree char *uri = g_strdup_printf ("sip:%s@%s", target, sip_client_server (b->sip));
	GPtrArray *h = g_ptr_array_new_with_free_func (g_free);
	g_ptr_array_add (h, g_strdup_printf ("X-SMS-Id: %s", s->id));
	g_ptr_array_add (h, g_strdup_printf ("X-SMS-From: %s", s->number));
	g_ptr_array_add (h, g_strdup_printf ("X-SMS-To: %s", target));
	g_ptr_array_add (h, g_strdup_printf ("X-SMS-Received: %s", s->received));
	g_ptr_array_add (h, g_strdup_printf ("X-SMS-Parts: %d", s->parts));
	if (s->attempts > 0)
		g_ptr_array_add (h, g_strdup_printf ("X-SMS-Attempt: %d", s->attempts + 1));
	g_ptr_array_add (h, NULL);
	b->sms_flushing = TRUE;
	sip_client_send_message (b->sip, uri, *s->number ? s->number : "unknown", s->text,
				 (const char *const *)h->pdata, NULL, on_sms_forwarded, b);
	g_ptr_array_unref (h);
}

static void
on_sms_received (gpointer ud, const char *path, const char *number, const char *text, const char *ts)
{
	Bridge *b = ud;
	for (GList *l = b->sms_out.head; l; l = l->next)
		if (!strcmp (((SmsOut *)l->data)->path, path))
			return;
	SmsOut *s = g_new0 (SmsOut, 1);
	s->path = g_strdup (path);
	s->number = g_strdup (number);
	s->text = g_strdup (text);
	s->received = gw_iso_utc_from_mm (ts);
	/* stable across restarts, so the server can tell a re-forward from a
	 * new message: the modem keeps the SMS until the server has taken it */
	s->id = gw_sms_stable_id (number, ts, text);
	s->parts = gw_sms_measure (text).parts;
	if (b->recents) {
		/* on arrival, not on forward: it happened whether or not the server
		 * is reachable */
		g_autoptr (GDateTime) at = ts ? g_date_time_new_from_iso8601 (ts, NULL) : NULL;
		gint64 ms = at ? g_date_time_to_unix (at) * 1000 : g_get_real_time () / 1000;
		gw_recents_add_sms (b->recents, TRUE, number, ms, text, s->id, NULL, s->parts, NULL);
	}
	g_queue_push_tail (&b->sms_out, s);
	sms_flush (b);
}

/* ---- modem callbacks ---- */

static void
on_modem_present (gpointer ud, gboolean present)
{
	Bridge *b = ud;
	if (!present && b->gsm_path)
		tear_down (b, "Modem went away", 503, "Service Unavailable");
}

static void
on_call_added (gpointer ud, const char *path, gboolean incoming, const char *number, int state)
{
	Bridge *b = ud;
	if (incoming) {
		if (state == MM_CALL_RINGING_IN || state == MM_CALL_WAITING) {
			on_incoming_gsm (b, path, number);
		} else if (state == MM_CALL_TERMINATED) {
			modem_call_delete (b->modem, path);
		} else if (g_strcmp0 (path, b->gsm_path)) {
			g_message ("Incoming call %s already in state %d — not ours to bridge", path, state);
		}
		return;
	}
	/* ours, from modem_dial: track it before the dial reply lands so a
	 * fast failure is not missed */
	if (b->state == BRIDGE_GSM_DIALING && !b->gsm_path)
		b->gsm_path = g_strdup (path);
}

static void
on_call_state (gpointer ud, const char *path, int old_state, int new_state, guint reason)
{
	Bridge *b = ud;
	(void)old_state;
	if (!b->gsm_path && b->state == BRIDGE_GSM_DIALING &&
	    (new_state == MM_CALL_DIALING || new_state == MM_CALL_RINGING_OUT))
		b->gsm_path = g_strdup (path);
	if (g_strcmp0 (path, b->gsm_path)) {
		if (new_state == MM_CALL_TERMINATED)
			modem_call_delete (b->modem, path);
		return;
	}
	if (new_state == MM_CALL_ACTIVE) {
		on_gsm_active (b);
	} else if (new_state == MM_CALL_TERMINATED) {
		int code;
		const char *phrase;
		gsm_end_status (b, reason, &code, &phrase);
		b->gsm_terminated = TRUE;
		g_autofree char *why = g_strdup_printf ("GSM call ended (reason %u)", reason);
		tear_down (b, why, code, phrase);	/* deletes the call object too */
	}
}

static void
on_call_deleted (gpointer ud, const char *path)
{
	Bridge *b = ud;
	if (!g_strcmp0 (path, b->gsm_path)) {
		b->gsm_terminated = TRUE;
		tear_down (b, "GSM call gone", 480, "Temporarily Unavailable");
	}
}

/* ---- lifecycle ---- */

static const SipClientCallbacks sip_cbs = {
	.registered = on_sip_registered,
	.registration_failed = on_sip_registration_failed,
	.incoming_call = on_sip_incoming,
	.call_answered = on_sip_answered,
	.call_rtp_ready = on_sip_rtp_ready,
	.call_terminated = on_sip_terminated,
	.call_dtmf = on_sip_dtmf,
	.message_request = on_sip_message,
};

static const ModemCallbacks modem_cbs = {
	.present = on_modem_present,
	.call_added = on_call_added,
	.call_state = on_call_state,
	.call_deleted = on_call_deleted,
	.sms_received = on_sms_received,
};

const SipClientCallbacks *bridge_sip_callbacks (void) { return &sip_cbs; }
const ModemCallbacks *bridge_modem_callbacks (void) { return &modem_cbs; }

Bridge *
bridge_new (const BridgeConfig *cfg)
{
	Bridge *b = g_new0 (Bridge, 1);
	b->own_number = gw_to_international (cfg->own_number);
	b->sms_ascii = cfg->sms_ascii;
	b->audio = cfg->audio;
	b->cap_dev = g_strdup (cfg->audio.capture_dev);
	b->pb_dev = g_strdup (cfg->audio.playback_dev);
	b->ctl_card = g_strdup (cfg->audio.control_card);
	b->uplink = g_strdup (cfg->audio.uplink_switch);
	b->active = g_strdup (cfg->audio.call_active);
	b->mute = g_strdup (cfg->audio.mute);
	b->audio.capture_dev = b->cap_dev;
	b->audio.playback_dev = b->pb_dev;
	b->audio.control_card = b->ctl_card;
	b->audio.uplink_switch = b->uplink;
	b->audio.call_active = b->active;
	b->audio.mute = b->mute;
	g_queue_init (&b->sms_out);
	g_queue_init (&b->reports);
	g_queue_init (&b->seen_order);
	b->seen = g_hash_table_new_full (g_str_hash, g_str_equal, NULL, NULL);
	if (*b->own_number)
		g_message ("Own number: %s", b->own_number);
	return b;
}

void
bridge_attach (Bridge *b, SipClient *sip, Modem *modem)
{
	b->sip = sip;
	b->modem = modem;
}

void
bridge_set_recents (Bridge *b, GwRecents *recents)
{
	b->recents = recents;
}

void
bridge_set_state_notify (Bridge *b, BridgeStateNotify fn, gpointer user_data)
{
	b->notify = fn;
	b->notify_data = user_data;
}

const char *
bridge_call_number (Bridge *b)
{
	return b->attempt_ms ? b->attempt_number : NULL;
}

gboolean
bridge_call_incoming (Bridge *b)
{
	return b->attempt_incoming;
}

gint64
bridge_call_bridged_at (Bridge *b)
{
	return b->bridged_us ? g_get_real_time () / 1000 - (g_get_monotonic_time () - b->bridged_us) / 1000 : 0;
}

gboolean
bridge_hangup (Bridge *b)
{
	if (b->state == BRIDGE_IDLE || b->state == BRIDGE_TEARING_DOWN)
		return FALSE;
	tear_down (b, "Ended from the app", 480, "Temporarily Unavailable");
	return TRUE;
}

void
bridge_set_agent_muted (Bridge *b, gboolean muted)
{
	if (b->state == BRIDGE_IDLE || b->agent_muted == muted)
		return;
	b->agent_muted = muted;
	if (b->rtp)
		rtp_session_set_agent_muted (b->rtp, muted);
	g_message ("%s", muted ? "Agent muted to the caller" : "Agent unmuted");
	if (b->notify)
		b->notify (b->state, NULL, b->notify_data);
}

gboolean
bridge_agent_muted (Bridge *b)
{
	return b->agent_muted;
}

void
bridge_shutdown (Bridge *b)
{
	tear_down (b, "Gateway stopping", 503, "Service Unavailable");
}

void
bridge_free (Bridge *b)
{
	if (!b)
		return;
	bridge_shutdown (b);
	if (b->unmute) {
		g_source_remove (b->unmute);
		unmute_amps (b);
	}
	for (GList *l = b->timers; l; l = l->next)
		g_source_remove (GPOINTER_TO_UINT (l->data));
	g_list_free (b->timers);
	if (b->sms_retry)
		g_source_remove (b->sms_retry);
	g_queue_clear_full (&b->sms_out, (GDestroyNotify)sms_out_free);
	g_queue_clear_full (&b->reports, (GDestroyNotify)report_free);
	g_queue_clear_full (&b->seen_order, g_free);
	g_hash_table_unref (b->seen);
	g_free (b->own_number);
	g_free (b->attempt_number);
	g_free (b->cap_dev);
	g_free (b->pb_dev);
	g_free (b->ctl_card);
	g_free (b->uplink);
	g_free (b->active);
	g_free (b->mute);
	g_free (b);
}
