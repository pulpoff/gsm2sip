#include "sipmsg.h"
#include <string.h>
#include <stdlib.h>
#include <sys/random.h>

/* ---- parsing ---- */

void
sipmsg_free (SipMsg *m)
{
	if (!m)
		return;
	g_free (m->start_line);
	g_hash_table_unref (m->headers);
	g_free (m->body);
	g_free (m);
}

SipMsg *
sipmsg_parse (const char *data, gsize len)
{
	/* headers end at the first blank line, CRLF or bare LF */
	const char *end = NULL, *body = NULL;
	for (gsize i = 0; i + 1 < len; i++) {
		if (data[i] == '\n' && data[i + 1] == '\n') { end = data + i; body = data + i + 2; break; }
		if (i + 3 < len && !memcmp (data + i, "\r\n\r\n", 4)) { end = data + i; body = data + i + 4; break; }
		if (i + 2 < len && data[i] == '\n' && !memcmp (data + i + 1, "\r\n", 2)) { end = data + i; body = data + i + 3; break; }
	}
	if (!end)
		return NULL;
	g_autofree char *head = g_strndup (data, end - data);
	g_auto (GStrv) lines = g_regex_split_simple ("\\r?\\n", head, 0, 0);
	if (!lines || !lines[0])
		return NULL;
	const char *first = lines[0];
	/* a BOM or a leading space on the status line is harmless on the wire */
	if (g_str_has_prefix (first, "\xEF\xBB\xBF"))
		first += 3;
	while (*first == ' ' || *first == '\t')
		first++;
	if (!*first)
		return NULL;

	SipMsg *m = g_new0 (SipMsg, 1);
	m->start_line = g_strdup (first);
	g_strchomp (m->start_line);
	m->headers = g_hash_table_new_full (g_str_hash, g_str_equal, g_free, g_free);
	char *prev = NULL;
	for (int i = 1; lines[i]; i++) {
		const char *l = lines[i];
		if ((l[0] == ' ' || l[0] == '\t') && prev) {
			const char *old = g_hash_table_lookup (m->headers, prev);
			g_autofree char *t = g_strstrip (g_strdup (l));
			g_hash_table_insert (m->headers, g_strdup (prev), g_strdup_printf ("%s %s", old ? old : "", t));
			continue;
		}
		const char *colon = strchr (l, ':');
		if (!colon || colon == l)
			continue;
		g_autofree char *raw = g_strndup (l, colon - l);
		char *key = g_ascii_strdown (g_strstrip (raw), -1);
		char *val = g_strstrip (g_strdup (colon + 1));
		/* compact forms, which Asterisk does not send but a proxy might */
		static const char *compact[][2] = { { "i", "call-id" }, { "f", "from" }, { "t", "to" }, { "v", "via" },
						    { "m", "contact" }, { "c", "content-type" }, { "l", "content-length" } };
		for (guint k = 0; k < G_N_ELEMENTS (compact); k++)
			if (!strcmp (key, compact[k][0])) { g_free (key); key = g_strdup (compact[k][1]); break; }
		g_free (prev);
		prev = g_strdup (key);
		g_hash_table_insert (m->headers, key, val);
	}
	g_free (prev);
	m->body = g_strndup (body, len - (body - data));
	return m;
}

const char *
sipmsg_header (const SipMsg *m, const char *lname)
{
	return g_hash_table_lookup (m->headers, lname);
}

gboolean
sipmsg_is_request (const SipMsg *m)
{
	return g_ascii_strncasecmp (m->start_line, "SIP/", 4) != 0;
}

int
sipmsg_status (const SipMsg *m)
{
	if (sipmsg_is_request (m))
		return 0;
	const char *sp = strchr (m->start_line, ' ');
	return sp ? atoi (sp + 1) : 0;
}

static char *
start_field (const SipMsg *m, int idx)
{
	g_auto (GStrv) f = g_regex_split_simple ("\\s+", m->start_line, 0, 0);
	for (int i = 0; f[i]; i++)
		if (i == idx)
			return g_strdup (f[i]);
	return NULL;
}

char *
sipmsg_method (const SipMsg *m)
{
	return sipmsg_is_request (m) ? start_field (m, 0) : NULL;
}

char *
sipmsg_request_uri (const SipMsg *m)
{
	return sipmsg_is_request (m) ? start_field (m, 1) : NULL;
}

int
sipmsg_cseq_num (const SipMsg *m)
{
	const char *c = sipmsg_header (m, "cseq");
	return c ? atoi (c) : -1;
}

gboolean
sipmsg_cseq_is (const SipMsg *m, const char *method)
{
	const char *c = sipmsg_header (m, "cseq");
	if (!c)
		return FALSE;
	g_autofree char *up = g_ascii_strup (c, -1);
	g_autofree char *mu = g_ascii_strup (method, -1);
	return strstr (up, mu) != NULL;
}

char *
sip_extract_user (const char *v)
{
	if (!v)
		return NULL;
	const char *s = strstr (v, "sip:");
	if (!s) {
		s = strstr (v, "sips:");
		if (!s)
			return NULL;
		s++;
	}
	const char *at = strchr (s, '@');
	if (!at)
		return NULL;
	return g_strndup (s + 4, at - (s + 4));
}

char *
sip_extract_tag (const char *v)
{
	if (!v)
		return NULL;
	const char *t = strstr (v, ";tag=");
	if (!t)
		return NULL;
	t += 5;
	return g_strstrip (g_strndup (t, strcspn (t, ";")));
}

char *sipmsg_from_tag (const SipMsg *m) { return sip_extract_tag (sipmsg_header (m, "from")); }
char *sipmsg_to_tag (const SipMsg *m) { return sip_extract_tag (sipmsg_header (m, "to")); }

static char *
identity_user (const char *v)
{
	char *u = sip_extract_user (v);
	if (u)
		return u;
	if (!v)
		return NULL;
	const char *t = strcasestr (v, "tel:");
	if (!t)
		return NULL;
	t += 4;
	char *r = g_strstrip (g_strndup (t, strcspn (t, ">;")));
	if (!*r) { g_free (r); return NULL; }
	return r;
}

char *
sipmsg_caller_number (const SipMsg *m)
{
	const char *src[] = { sipmsg_header (m, "p-asserted-identity"), sipmsg_header (m, "remote-party-id"),
			      sipmsg_header (m, "from") };
	for (guint i = 0; i < G_N_ELEMENTS (src); i++) {
		char *u = identity_user (src[i]);
		if (u)
			return u;
	}
	return NULL;
}

char *
sipmsg_caller_display (const SipMsg *m)
{
	const char *f = sipmsg_header (m, "from");
	if (!f)
		return NULL;
	const char *q1 = strchr (f, '"');
	if (!q1)
		return NULL;
	const char *q2 = strchr (q1 + 1, '"');
	if (!q2)
		return NULL;
	char *r = g_strstrip (g_strndup (q1 + 1, q2 - q1 - 1));
	if (!*r) { g_free (r); return NULL; }
	return r;
}

char *
sipmsg_contact_uri (const SipMsg *m)
{
	const char *c = sipmsg_header (m, "contact");
	if (!c)
		return NULL;
	const char *s = strchr (c, '<');
	const char *e = s ? strchr (s + 1, '>') : NULL;
	if (s && e)
		return g_strndup (s + 1, e - s - 1);
	return g_strstrip (g_strndup (c, strcspn (c, ";")));
}

gboolean
sipmsg_contact_addr (const SipMsg *m, char **host, int *port)
{
	g_autofree char *uri = sipmsg_contact_uri (m);
	if (!uri)
		return FALSE;
	const char *p = uri;
	if (g_str_has_prefix (p, "sip:")) p += 4;
	const char *at = strchr (p, '@');
	if (at) p = at + 1;
	g_autofree char *hp = g_strndup (p, strcspn (p, ";>"));
	char *colon = strchr (hp, ':');
	*port = colon ? atoi (colon + 1) : 5060;
	if (colon) *colon = 0;
	*host = g_strdup (hp);
	return TRUE;
}

/* ---- SDP ---- */

static char *
sdp_line (const SipMsg *m, const char *prefix)
{
	g_auto (GStrv) lines = g_strsplit (m->body, "\n", -1);
	for (int i = 0; lines[i]; i++) {
		g_strchomp (lines[i]);
		if (g_str_has_prefix (lines[i], prefix))
			return g_strdup (lines[i]);
	}
	return NULL;
}

int
sipmsg_sdp_port (const SipMsg *m)
{
	g_autofree char *l = sdp_line (m, "m=audio");
	if (!l)
		return -1;
	g_auto (GStrv) f = g_strsplit (l, " ", -1);
	return f[0] && f[1] ? atoi (f[1]) : -1;
}

char *
sipmsg_sdp_addr (const SipMsg *m)
{
	g_autofree char *l = sdp_line (m, "c=IN IP4");
	if (!l)
		return NULL;
	g_auto (GStrv) f = g_strsplit (l, " ", -1);
	return f[0] && f[1] && f[2] ? g_strstrip (g_strdup (f[2])) : NULL;
}

int
sipmsg_sdp_preferred_pt (const SipMsg *m, const char *codec_mode)
{
	g_autofree char *l = sdp_line (m, "m=audio");
	if (!l)
		return 8;
	g_auto (GStrv) f = g_strsplit (l, " ", -1);
	gboolean has0 = FALSE, has8 = FALSE, has9 = FALSE;
	for (int i = 3; f[0] && f[1] && f[2] && f[i]; i++) {
		int pt = atoi (f[i]);
		if (!strcmp (f[i], "0")) has0 = TRUE;
		if (pt == 8) has8 = TRUE;
		if (pt == 9) has9 = TRUE;
	}
	/* follow the preference, never fail a call over it */
	int g711 = has8 ? 8 : has0 ? 0 : -1;
	if (!g_strcmp0 (codec_mode, "g711"))
		return g711 >= 0 ? g711 : has9 ? 9 : 8;
	return has9 ? 9 : (g711 >= 0 ? g711 : 9);
}

int
sipmsg_sdp_te_pt (const SipMsg *m)
{
	g_auto (GStrv) lines = g_strsplit (m->body, "\n", -1);
	int any = -1;
	for (int i = 0; lines[i]; i++) {
		g_strchomp (lines[i]);
		if (!g_str_has_prefix (lines[i], "a=rtpmap:"))
			continue;
		const char *p = lines[i] + 9;
		int pt = atoi (p);
		const char *sp = strchr (p, ' ');
		if (!sp)
			continue;
		if (!g_ascii_strncasecmp (sp + 1, "telephone-event/8000", 20))
			return pt;
		if (!g_ascii_strncasecmp (sp + 1, "telephone-event", 15) && any < 0)
			any = pt;
	}
	return any;
}

gboolean
sipmsg_sdp_is_savp (const SipMsg *m)
{
	g_autofree char *l = sdp_line (m, "m=audio");
	return l && strstr (l, "RTP/SAVP");
}

static void
crypto_free (gpointer p)
{
	SdpCrypto *c = p;
	g_free (c->suite);
	g_free (c->inline_key);
	g_free (c);
}

GPtrArray *
sipmsg_sdp_crypto (const SipMsg *m)
{
	GPtrArray *a = g_ptr_array_new_with_free_func (crypto_free);
	g_auto (GStrv) lines = g_strsplit (m->body, "\n", -1);
	for (int i = 0; lines[i]; i++) {
		g_strchomp (lines[i]);
		if (!g_str_has_prefix (lines[i], "a=crypto:"))
			continue;
		g_auto (GStrv) f = g_regex_split_simple ("\\s+", lines[i] + 9, 0, 0);
		if (!f[0] || !f[1] || !f[2])
			continue;
		const char *key = NULL;
		for (int k = 2; f[k]; k++)
			if (g_str_has_prefix (f[k], "inline:")) { key = f[k] + 7; break; }
		if (!key)
			continue;
		SdpCrypto *c = g_new0 (SdpCrypto, 1);
		c->tag = atoi (f[0]);
		c->suite = g_strdup (f[1]);
		c->inline_key = g_strdup (key);
		g_ptr_array_add (a, c);
	}
	return a;
}

/* ---- digest auth ---- */

SipAuthParams *
sip_auth_parse (const SipMsg *m)
{
	const char *h = sipmsg_header (m, "www-authenticate");
	if (!h)
		h = sipmsg_header (m, "proxy-authenticate");
	if (!h || g_ascii_strncasecmp (h, "digest", 6))
		return NULL;
	g_autoptr (GHashTable) p = g_hash_table_new_full (g_str_hash, g_str_equal, g_free, g_free);
	g_auto (GStrv) pairs = g_strsplit (h + 6, ",", -1);
	for (int i = 0; pairs[i]; i++) {
		char *eq = strchr (pairs[i], '=');
		if (!eq)
			continue;
		g_autofree char *raw = g_strndup (pairs[i], eq - pairs[i]);
		char *k = g_ascii_strdown (g_strstrip (raw), -1);
		char *v = g_strstrip (g_strdup (eq + 1));
		gsize n = strlen (v);
		if (n >= 2 && v[0] == '"' && v[n - 1] == '"') { memmove (v, v + 1, n - 2); v[n - 2] = 0; }
		g_hash_table_insert (p, k, v);
	}
	const char *realm = g_hash_table_lookup (p, "realm"), *nonce = g_hash_table_lookup (p, "nonce");
	if (!realm || !nonce)
		return NULL;
	SipAuthParams *a = g_new0 (SipAuthParams, 1);
	a->realm = g_strdup (realm);
	a->nonce = g_strdup (nonce);
	a->opaque = g_strdup (g_hash_table_lookup (p, "opaque"));
	a->qop = g_strdup (g_hash_table_lookup (p, "qop"));
	return a;
}

void
sip_auth_free (SipAuthParams *p)
{
	if (!p)
		return;
	g_free (p->realm); g_free (p->nonce); g_free (p->opaque); g_free (p->qop);
	g_free (p);
}

static char *
md5hex (const char *s)
{
	return g_compute_checksum_for_string (G_CHECKSUM_MD5, s, -1);
}

char *
sip_auth_header (const char *method, const char *uri, const char *user, const char *pass, const SipAuthParams *p)
{
	g_autofree char *a1 = g_strdup_printf ("%s:%s:%s", user, p->realm, pass);
	g_autofree char *a2 = g_strdup_printf ("%s:%s", method, uri);
	g_autofree char *ha1 = md5hex (a1), *ha2 = md5hex (a2);
	g_autofree char *r = g_strdup_printf ("%s:%s:%s", ha1, p->nonce, ha2);
	g_autofree char *resp = md5hex (r);
	GString *s = g_string_new (NULL);
	g_string_append_printf (s, "Authorization: Digest username=\"%s\", realm=\"%s\", nonce=\"%s\", uri=\"%s\", "
			       "response=\"%s\", algorithm=MD5", user, p->realm, p->nonce, uri, resp);
	if (p->opaque)
		g_string_append_printf (s, ", opaque=\"%s\"", p->opaque);
	g_string_append (s, "\r\n");
	return g_string_free (s, FALSE);
}

/* ---- builders ---- */

static guint32
rnd9 (void)
{
	guint32 r;
	if (getrandom (&r, sizeof r, 0) != sizeof r)
		r = g_random_int ();
	return 100000000 + r % 900000000;
}

char *sip_branch (void) { return g_strdup_printf ("z9hG4bK%u", rnd9 ()); }
char *sip_tag (void) { return g_strdup_printf ("gw%u", rnd9 ()); }

static const char *
contact_param (const SipBuildCtx *c)
{
	return !g_strcmp0 (c->transport, "TLS") ? ";transport=tls" : "";
}

static const char *
payloads (const SipBuildCtx *c)
{
	if (!g_strcmp0 (c->codec_mode, "g711")) return "8 0 101";
	if (!g_strcmp0 (c->codec_mode, "both")) return "9 8 0 101";
	return "9 101";
}

static void
rtpmaps (GString *s, const SipBuildCtx *c)
{
	if (g_strcmp0 (c->codec_mode, "g711"))
		g_string_append (s, "a=rtpmap:9 G722/8000\r\n");
	if (g_strcmp0 (c->codec_mode, "g722")) {
		g_string_append (s, "a=rtpmap:8 PCMA/8000\r\n");
		g_string_append (s, "a=rtpmap:0 PCMU/8000\r\n");
	}
	g_string_append (s, "a=rtpmap:101 telephone-event/8000\r\n");
	g_string_append (s, "a=fmtp:101 0-16\r\n");
	g_string_append (s, "a=ptime:20\r\n");
	g_string_append (s, "a=sendrecv\r\n");
}

char *
sip_build_sdp (const SipBuildCtx *c, const char *ip, int rtp_port, const SdpSrtp *srtp)
{
	GString *s = g_string_new (NULL);
	g_string_append (s, "v=0\r\n");
	g_string_append_printf (s, "o=gateway 0 0 IN IP4 %s\r\n", ip);
	g_string_append (s, "s=SIP Call\r\n");
	g_string_append_printf (s, "c=IN IP4 %s\r\n", ip);
	g_string_append (s, "t=0 0\r\n");
	g_string_append_printf (s, "m=audio %d %s %s\r\n", rtp_port, srtp ? "RTP/SAVP" : "RTP/AVP", payloads (c));
	if (srtp)
		g_string_append_printf (s, "a=crypto:%d %s inline:%s\r\n", srtp->tag, srtp->suite, srtp->inline_key);
	rtpmaps (s, c);
	return g_string_free (s, FALSE);
}

static void
extra (GString *s, char **h)
{
	for (int i = 0; h && h[i]; i++)
		g_string_append_printf (s, "%s\r\n", h[i]);
}

char *
sip_build_register (const SipBuildCtx *c, const char *user, const char *domain, int server_port, const char *ip,
		    int port, const char *call_id, int cseq, const char *auth)
{
	g_autofree char *br = sip_branch (), *tg = sip_tag ();
	GString *s = g_string_new (NULL);
	g_string_append_printf (s, "REGISTER sip:%s:%d SIP/2.0\r\n", domain, server_port);
	g_string_append_printf (s, "Via: SIP/2.0/%s %s:%d;branch=%s;rport\r\n", c->transport, ip, port, br);
	g_string_append (s, "Max-Forwards: 70\r\n");
	g_string_append_printf (s, "User-Agent: %s\r\n", c->user_agent);
	g_string_append_printf (s, "To: <sip:%s@%s>\r\n", user, domain);
	g_string_append_printf (s, "From: <sip:%s@%s>;tag=%s\r\n", user, ip, tg);
	g_string_append_printf (s, "Call-ID: %s\r\n", call_id);
	g_string_append_printf (s, "CSeq: %d REGISTER\r\n", cseq);
	g_string_append_printf (s, "Contact: <sip:%s@%s:%d%s>;expires=3600\r\n", user, ip, port, contact_param (c));
	if (auth)
		g_string_append (s, auth);
	g_string_append (s, "Content-Length: 0\r\n\r\n");
	return g_string_free (s, FALSE);
}

char *
sip_build_invite (const SipBuildCtx *c, const char *target_uri, const char *user, const char *domain, const char *ip,
		  int port, const char *call_id, int cseq, int rtp_port, const char *from_tag, const char *cid_number,
		  const char *cid_name, const char *auth, const SdpSrtp *srtp)
{
	g_autofree char *br = sip_branch ();
	g_autofree char *sdp = sip_build_sdp (c, ip, rtp_port, srtp);
	g_autofree char *disp = cid_name ? g_strdup_printf ("\"%s\" ", cid_name) : g_strdup ("");
	GString *s = g_string_new (NULL);
	g_string_append_printf (s, "INVITE %s SIP/2.0\r\n", target_uri);
	g_string_append_printf (s, "Via: SIP/2.0/%s %s:%d;branch=%s;rport\r\n", c->transport, ip, port, br);
	g_string_append (s, "Max-Forwards: 70\r\n");
	g_string_append_printf (s, "User-Agent: %s\r\n", c->user_agent);
	g_string_append_printf (s, "To: <%s>\r\n", target_uri);
	g_string_append_printf (s, "From: %s<sip:%s@%s>;tag=%s\r\n", disp, cid_number ? cid_number : user, domain, from_tag);
	g_string_append_printf (s, "Call-ID: %s\r\n", call_id);
	g_string_append_printf (s, "CSeq: %d INVITE\r\n", cseq);
	g_string_append_printf (s, "Contact: <sip:%s@%s:%d%s>\r\n", user, ip, port, contact_param (c));
	if (auth)
		g_string_append (s, auth);
	g_string_append (s, "Content-Type: application/sdp\r\n");
	g_string_append_printf (s, "Content-Length: %zu\r\n\r\n", strlen (sdp));
	g_string_append (s, sdp);
	return g_string_free (s, FALSE);
}

char *
sip_build_message (const SipBuildCtx *c, const char *target_uri, const char *from_user, const char *domain,
		   const char *ip, int port, const char *call_id, int cseq, const char *body, const char *content_type,
		   char **extra_headers, const char *from_tag, const char *auth)
{
	g_autofree char *br = sip_branch ();
	GString *s = g_string_new (NULL);
	g_string_append_printf (s, "MESSAGE %s SIP/2.0\r\n", target_uri);
	g_string_append_printf (s, "Via: SIP/2.0/%s %s:%d;branch=%s;rport\r\n", c->transport, ip, port, br);
	g_string_append (s, "Max-Forwards: 70\r\n");
	g_string_append_printf (s, "User-Agent: %s\r\n", c->user_agent);
	g_string_append_printf (s, "To: <%s>\r\n", target_uri);
	g_string_append_printf (s, "From: <sip:%s@%s>;tag=%s\r\n", from_user, domain, from_tag);
	g_string_append_printf (s, "Call-ID: %s\r\n", call_id);
	g_string_append_printf (s, "CSeq: %d MESSAGE\r\n", cseq);
	extra (s, extra_headers);
	if (auth)
		g_string_append (s, auth);
	g_string_append_printf (s, "Content-Type: %s\r\n", content_type ? content_type : "text/plain;charset=UTF-8");
	/* bytes, not characters: an SMS with one umlaut would otherwise be truncated by the server */
	g_string_append_printf (s, "Content-Length: %zu\r\n\r\n", strlen (body));
	g_string_append (s, body);
	return g_string_free (s, FALSE);
}

static char *
to_with_tag (const SipMsg *req, const char *to_tag)
{
	const char *to = sipmsg_header (req, "to");
	if (!to) to = "";
	if (strstr (to, ";tag="))
		return g_strdup (to);
	return g_strdup_printf ("%s;tag=%s", to, to_tag);
}

/* A header copied into an answer: empty when the request lacked it, as the
 * Kotlin's `headers[x] ?: ""` -- never a NULL handed to printf. */
static const char *
hdr_or_empty (const SipMsg *m, const char *lname)
{
	const char *v = sipmsg_header (m, lname);
	return v ? v : "";
}

static void
resp_head (GString *s, const SipMsg *req, const char *to)
{
	g_string_append_printf (s, "Via: %s\r\n", hdr_or_empty (req, "via"));
	g_string_append_printf (s, "To: %s\r\n", to ? to : "");
	g_string_append_printf (s, "From: %s\r\n", hdr_or_empty (req, "from"));
	g_string_append_printf (s, "Call-ID: %s\r\n", hdr_or_empty (req, "call-id"));
	g_string_append_printf (s, "CSeq: %s\r\n", hdr_or_empty (req, "cseq"));
}

char *
sip_build_status (const SipBuildCtx *c, const SipMsg *req, int code, const char *reason, char **extra_headers,
		  const char *to_tag)
{
	g_autofree char *tg = to_tag ? NULL : sip_tag ();
	g_autofree char *to = to_with_tag (req, to_tag ? to_tag : tg);
	GString *s = g_string_new (NULL);
	g_string_append_printf (s, "SIP/2.0 %d %s\r\n", code, reason);
	resp_head (s, req, to);
	g_string_append_printf (s, "User-Agent: %s\r\n", c->user_agent);
	extra (s, extra_headers);
	g_string_append (s, "Content-Length: 0\r\n\r\n");
	return g_string_free (s, FALSE);
}

char *
sip_build_ok (const SipBuildCtx *c, const SipMsg *req, const char *user, const char *ip, int port, int rtp_port,
	      const char *to_tag, const SdpSrtp *srtp)
{
	g_autofree char *tg = to_tag ? NULL : sip_tag ();
	g_autofree char *to = to_with_tag (req, to_tag ? to_tag : tg);
	g_autofree char *sdp = rtp_port > 0 ? sip_build_sdp (c, ip, rtp_port, srtp) : NULL;
	GString *s = g_string_new ("SIP/2.0 200 OK\r\n");
	resp_head (s, req, to);
	g_string_append_printf (s, "Contact: <sip:%s@%s:%d%s>\r\n", user, ip, port, contact_param (c));
	if (sdp) {
		g_string_append (s, "Content-Type: application/sdp\r\n");
		g_string_append_printf (s, "Content-Length: %zu\r\n\r\n", strlen (sdp));
		g_string_append (s, sdp);
	} else {
		g_string_append (s, "Content-Length: 0\r\n\r\n");
	}
	return g_string_free (s, FALSE);
}

char *
sip_build_trying (const SipBuildCtx *c, const SipMsg *req)
{
	GString *s = g_string_new ("SIP/2.0 100 Trying\r\n");
	resp_head (s, req, sipmsg_header (req, "to"));
	g_string_append_printf (s, "Server: %s\r\n", c->user_agent);
	g_string_append (s, "Content-Length: 0\r\n\r\n");
	return g_string_free (s, FALSE);
}

char *
sip_build_reject (const SipBuildCtx *c, const SipMsg *req, int code, const char *reason, const char *to_tag)
{
	g_autofree char *tg = to_tag ? NULL : sip_tag ();
	g_autofree char *to = to_with_tag (req, to_tag ? to_tag : tg);
	GString *s = g_string_new (NULL);
	g_string_append_printf (s, "SIP/2.0 %d %s\r\n", code, reason);
	resp_head (s, req, to);
	g_string_append_printf (s, "Server: %s\r\n", c->user_agent);
	g_string_append (s, "Content-Length: 0\r\n\r\n");
	return g_string_free (s, FALSE);
}

char *
sip_build_ringing (const SipBuildCtx *c, const SipMsg *req, const char *to_tag)
{
	return sip_build_reject (c, req, 180, "Ringing", to_tag);
}

char *
sip_build_ack (const SipBuildCtx *c, const char *target_uri, const char *to, const char *from, const char *call_id,
	       int cseq, const char *user, const char *ip, int port)
{
	g_autofree char *br = sip_branch ();
	GString *s = g_string_new (NULL);
	g_string_append_printf (s, "ACK %s SIP/2.0\r\n", target_uri);
	g_string_append_printf (s, "Via: SIP/2.0/%s %s:%d;branch=%s;rport\r\n", c->transport, ip, port, br);
	g_string_append (s, "Max-Forwards: 70\r\n");
	g_string_append_printf (s, "User-Agent: %s\r\n", c->user_agent);
	g_string_append_printf (s, "To: %s\r\n", to);
	g_string_append_printf (s, "From: %s\r\n", from);
	g_string_append_printf (s, "Call-ID: %s\r\n", call_id);
	g_string_append_printf (s, "CSeq: %d ACK\r\n", cseq);
	g_string_append_printf (s, "Contact: <sip:%s@%s:%d%s>\r\n", user, ip, port, contact_param (c));
	g_string_append (s, "Content-Length: 0\r\n\r\n");
	return g_string_free (s, FALSE);
}

char *
sip_build_ack_for_error (const SipBuildCtx *c, const char *request_uri, const SipMsg *resp)
{
	GString *s = g_string_new (NULL);
	g_string_append_printf (s, "ACK %s SIP/2.0\r\n", request_uri);
	g_string_append_printf (s, "Via: %s\r\n", hdr_or_empty (resp, "via"));
	g_string_append (s, "Max-Forwards: 70\r\n");
	g_string_append_printf (s, "User-Agent: %s\r\n", c->user_agent);
	g_string_append_printf (s, "From: %s\r\n", hdr_or_empty (resp, "from"));
	g_string_append_printf (s, "To: %s\r\n", hdr_or_empty (resp, "to"));
	g_string_append_printf (s, "Call-ID: %s\r\n", hdr_or_empty (resp, "call-id"));
	g_string_append_printf (s, "CSeq: %d ACK\r\n", sipmsg_cseq_num (resp));
	g_string_append (s, "Content-Length: 0\r\n\r\n");
	return g_string_free (s, FALSE);
}

char *
sip_build_bye (const SipBuildCtx *c, const char *target_uri, const char *from, const char *to, const char *call_id,
	       int cseq, const char *user, const char *ip, int port)
{
	g_autofree char *br = sip_branch ();
	GString *s = g_string_new (NULL);
	g_string_append_printf (s, "BYE %s SIP/2.0\r\n", target_uri);
	g_string_append_printf (s, "Via: SIP/2.0/%s %s:%d;branch=%s;rport\r\n", c->transport, ip, port, br);
	g_string_append (s, "Max-Forwards: 70\r\n");
	g_string_append_printf (s, "User-Agent: %s\r\n", c->user_agent);
	g_string_append_printf (s, "From: %s\r\n", from);
	g_string_append_printf (s, "To: %s\r\n", to);
	g_string_append_printf (s, "Call-ID: %s\r\n", call_id);
	g_string_append_printf (s, "CSeq: %d BYE\r\n", cseq);
	g_string_append_printf (s, "Contact: <sip:%s@%s:%d%s>\r\n", user, ip, port, contact_param (c));
	g_string_append (s, "Content-Length: 0\r\n\r\n");
	return g_string_free (s, FALSE);
}

char *
sip_build_cancel (const SipBuildCtx *c, const char *target_uri, const char *via, const char *from, const char *to,
		  const char *call_id, int cseq)
{
	GString *s = g_string_new (NULL);
	g_string_append_printf (s, "CANCEL %s SIP/2.0\r\n", target_uri);
	g_string_append_printf (s, "Via: %s\r\n", via);
	g_string_append (s, "Max-Forwards: 70\r\n");
	g_string_append_printf (s, "User-Agent: %s\r\n", c->user_agent);
	g_string_append_printf (s, "From: %s\r\n", from);
	g_string_append_printf (s, "To: %s\r\n", to);
	g_string_append_printf (s, "Call-ID: %s\r\n", call_id);
	g_string_append_printf (s, "CSeq: %d CANCEL\r\n", cseq);
	g_string_append (s, "Content-Length: 0\r\n\r\n");
	return g_string_free (s, FALSE);
}

char *
sip_build_options_ok (const SipBuildCtx *c, const SipMsg *req, const char *user, const char *ip, int port)
{
	/* a capability description, not an offer: no port, no key */
	GString *sdp = g_string_new (NULL);
	g_string_append (sdp, "v=0\r\n");
	g_string_append_printf (sdp, "o=gateway 0 0 IN IP4 %s\r\n", ip);
	g_string_append (sdp, "s=SIP Gateway capabilities\r\n");
	g_string_append_printf (sdp, "c=IN IP4 %s\r\n", ip);
	g_string_append (sdp, "t=0 0\r\n");
	g_string_append_printf (sdp, "m=audio 0 RTP/AVP %s\r\n", payloads (c));
	rtpmaps (sdp, c);
	GString *s = g_string_new ("SIP/2.0 200 OK\r\n");
	resp_head (s, req, sipmsg_header (req, "to"));
	g_string_append_printf (s, "Contact: <sip:%s@%s:%d%s>\r\n", user, ip, port, contact_param (c));
	g_string_append_printf (s, "User-Agent: %s\r\n", c->user_agent);
	g_string_append (s, "Allow: INVITE, ACK, CANCEL, OPTIONS, BYE, MESSAGE\r\n");
	g_string_append (s, "Accept: application/sdp, text/plain\r\n");
	g_string_append (s, "Accept-Encoding: identity\r\n");
	g_string_append (s, "Content-Type: application/sdp\r\n");
	g_string_append_printf (s, "Content-Length: %zu\r\n\r\n", sdp->len);
	g_string_append (s, sdp->str);
	g_string_free (sdp, TRUE);
	return g_string_free (s, FALSE);
}
