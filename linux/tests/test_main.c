/* Unit tests for the parts of gsm2sipd that need no modem, no ALSA and no
 * network: the SIP parser and builders, digest auth, the codecs and
 * resamplers, DTMF, STUN parsing, TLS framing and the SMS helpers. */
#include "dsp.h"
#include "g722.h"
#include "gwutil.h"
#include "sipclient.h"
#include "sipmsg.h"
#include "stun.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

static const SipBuildCtx ctx = { "gsm2sip vTEST box", "UDP", "g722" };

static SipMsg *
parse (const char *s)
{
	return sipmsg_parse (s, strlen (s));
}

/* ---- sipmsg ---- */

static void
test_invite_round_trip (void)
{
	SdpSrtp srtp = { "AES_CM_128_HMAC_SHA1_80", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwd", 1 };
	g_autofree char *inv = sip_build_invite (&ctx, "sip:+491511234567@pbx.example", "gw1", "pbx.example",
						 "203.0.113.7", 5062, "123call@203.0.113.7", 1, 30042, "gw123456789",
						 "+4930111", "+4930111", NULL, &srtp);
	g_autoptr (SipMsg) m = parse (inv);
	g_assert_nonnull (m);
	g_assert_true (sipmsg_is_request (m));
	g_autofree char *method = sipmsg_method (m);
	g_autofree char *ruri = sipmsg_request_uri (m);
	g_assert_cmpstr (method, ==, "INVITE");
	g_assert_cmpstr (ruri, ==, "sip:+491511234567@pbx.example");
	g_assert_cmpstr (sipmsg_header (m, "call-id"), ==, "123call@203.0.113.7");
	g_assert_cmpint (sipmsg_cseq_num (m), ==, 1);
	g_assert_true (sipmsg_cseq_is (m, "INVITE"));
	g_autofree char *ft = sipmsg_from_tag (m);
	g_assert_cmpstr (ft, ==, "gw123456789");
	g_autofree char *fu = sip_extract_user (sipmsg_header (m, "from"));
	g_assert_cmpstr (fu, ==, "+4930111");
	g_assert_cmpint (sipmsg_sdp_port (m), ==, 30042);
	g_autofree char *addr = sipmsg_sdp_addr (m);
	g_assert_cmpstr (addr, ==, "203.0.113.7");
	g_assert_true (sipmsg_sdp_is_savp (m));
	g_assert_cmpint (sipmsg_sdp_preferred_pt (m, "g722"), ==, 9);
	g_assert_cmpint (sipmsg_sdp_te_pt (m), ==, 101);
	g_autoptr (GPtrArray) cr = sipmsg_sdp_crypto (m);
	g_assert_cmpuint (cr->len, ==, 1);
	SdpCrypto *c = g_ptr_array_index (cr, 0);
	g_assert_cmpint (c->tag, ==, 1);
	g_assert_cmpstr (c->suite, ==, "AES_CM_128_HMAC_SHA1_80");
	g_assert_cmpstr (c->inline_key, ==, srtp.inline_key);
	/* Content-Length is the body's byte count */
	const char *cl = sipmsg_header (m, "content-length");
	g_assert_cmpint (atoi (cl), ==, (int)strlen (m->body));

	g_autofree char *ok = sip_build_ok (&ctx, m, "gw1", "203.0.113.7", 5062, 30044, "gwTOTAG", NULL);
	g_autoptr (SipMsg) r = parse (ok);
	g_assert_false (sipmsg_is_request (r));
	g_assert_cmpint (sipmsg_status (r), ==, 200);
	g_autofree char *tt = sipmsg_to_tag (r);
	g_assert_cmpstr (tt, ==, "gwTOTAG");
	g_assert_true (sipmsg_cseq_is (r, "INVITE"));
	g_assert_cmpstr (sipmsg_header (r, "via"), ==, sipmsg_header (m, "via"));
	g_assert_cmpint (sipmsg_sdp_port (r), ==, 30044);
	g_assert_false (sipmsg_sdp_is_savp (r));
	g_autofree char *cu = sipmsg_contact_uri (r);
	g_assert_cmpstr (cu, ==, "sip:gw1@203.0.113.7:5062");
	char *host = NULL;
	int port = 0;
	g_assert_true (sipmsg_contact_addr (r, &host, &port));
	g_assert_cmpstr (host, ==, "203.0.113.7");
	g_assert_cmpint (port, ==, 5062);
	g_free (host);

	/* CANCEL reuses the INVITE's Via and CSeq number */
	g_autofree char *cancel = sip_build_cancel (&ctx, ruri, sipmsg_header (m, "via"), sipmsg_header (m, "from"),
						     sipmsg_header (m, "to"), "123call@203.0.113.7", 1);
	g_autoptr (SipMsg) cm = parse (cancel);
	g_assert_cmpstr (sipmsg_header (cm, "via"), ==, sipmsg_header (m, "via"));
	g_assert_cmpint (sipmsg_cseq_num (cm), ==, 1);
	g_assert_true (sipmsg_cseq_is (cm, "CANCEL"));

	/* the ACK for a 486 carries the response's Via, i.e. the INVITE's branch */
	g_autofree char *busy = sip_build_reject (&ctx, m, 486, "Busy Here", "gwX");
	g_autoptr (SipMsg) bm = parse (busy);
	g_autofree char *ack = sip_build_ack_for_error (&ctx, ruri, bm);
	g_autoptr (SipMsg) am = parse (ack);
	g_assert_cmpstr (sipmsg_header (am, "via"), ==, sipmsg_header (m, "via"));
	g_assert_true (sipmsg_cseq_is (am, "ACK"));
	g_assert_cmpint (sipmsg_cseq_num (am), ==, 1);
	g_autofree char *amethod = sipmsg_method (am);
	g_assert_cmpstr (amethod, ==, "ACK");
}

static void
test_parse_variants (void)
{
	/* bare LF line ends, a BOM, a compact form, a folded header */
	const char *lf = "\xEF\xBB\xBF" "OPTIONS sip:gw@x SIP/2.0\n"
			 "i: abc@h\n"
			 "f: <sip:a@b>;tag=1\n"
			 "Subject: one\n"
			 "  two\n"
			 "l: 0\n\nBODY";
	g_autoptr (SipMsg) m = parse (lf);
	g_assert_nonnull (m);
	g_autofree char *method = sipmsg_method (m);
	g_assert_cmpstr (method, ==, "OPTIONS");
	g_assert_cmpstr (sipmsg_header (m, "call-id"), ==, "abc@h");
	g_assert_cmpstr (sipmsg_header (m, "subject"), ==, "one two");
	g_assert_cmpstr (sipmsg_header (m, "content-length"), ==, "0");
	g_assert_cmpstr (m->body, ==, "BODY");

	/* LF then CRLF as the blank line */
	g_autoptr (SipMsg) m2 = parse ("SIP/2.0 200 OK\nCSeq: 7 REGISTER\n\r\nX");
	g_assert_nonnull (m2);
	g_assert_cmpint (sipmsg_status (m2), ==, 200);
	g_assert_cmpint (sipmsg_cseq_num (m2), ==, 7);
	g_assert_cmpstr (m2->body, ==, "X");

	/* repeated header: the last one wins, as in the Kotlin map */
	g_autoptr (SipMsg) m3 = parse ("SIP/2.0 401 Unauthorized\r\nX-A: 1\r\nX-A: 2\r\n\r\n");
	g_assert_cmpstr (sipmsg_header (m3, "x-a"), ==, "2");

	/* not a message: keepalives and junk */
	g_assert_null (parse ("\r\n\r\n"));
	g_assert_null (parse ("\r\n"));
	g_assert_null (parse ("INVITE sip:x SIP/2.0\r\nVia: y\r\n"));
	g_assert_null (parse ("\xEF"));

	/* identity: P-Asserted-Identity wins, tel: URIs work */
	g_autoptr (SipMsg) m4 = parse ("INVITE sip:1@x SIP/2.0\r\n"
				       "From: \"Alice\" <sip:anon@x>;tag=9\r\n"
				       "P-Asserted-Identity: <tel:+4930123;cpc=x>\r\n\r\n");
	g_autofree char *num = sipmsg_caller_number (m4);
	g_autofree char *disp = sipmsg_caller_display (m4);
	g_assert_cmpstr (num, ==, "+4930123");
	g_assert_cmpstr (disp, ==, "Alice");

	/* a reject with no tag given gets one, never ";tag=(null)" */
	g_autofree char *rj = sip_build_reject (&ctx, m4, 486, "Busy Here", NULL);
	g_assert_null (strstr (rj, "(null)"));
	g_autoptr (SipMsg) rm = parse (rj);
	g_autofree char *tag = sipmsg_to_tag (rm);
	g_assert_true (g_str_has_prefix (tag, "gw"));
	g_assert_cmpint (strlen (tag), ==, 11);
}

static void
test_sdp_choice (void)
{
	struct { const char *m; const char *mode; int pt; } cases[] = {
		{ "m=audio 4000 RTP/AVP 0 101", "g722", 0 },
		{ "m=audio 4000 RTP/AVP 9 8 0", "g711", 8 },
		{ "m=audio 4000 RTP/AVP 8 9", "g722", 9 },
		{ "m=audio 4000 RTP/AVP 8 9", "both", 9 },
		{ "m=audio 4000 RTP/AVP 9", "g711", 9 },
	};
	for (guint i = 0; i < G_N_ELEMENTS (cases); i++) {
		g_autofree char *s = g_strdup_printf ("SIP/2.0 200 OK\r\nCSeq: 1 INVITE\r\n\r\nv=0\r\nc=IN IP4 1.2.3.4\r\n%s\r\n"
						      "a=rtpmap:96 telephone-event/8000\r\n", cases[i].m);
		g_autoptr (SipMsg) m = parse (s);
		g_assert_cmpint (sipmsg_sdp_preferred_pt (m, cases[i].mode), ==, cases[i].pt);
		g_assert_cmpint (sipmsg_sdp_te_pt (m), ==, 96);
	}
}

static void
test_message_builder_bytes (void)
{
	const char *hdr[] = { "X-SMS-Id: 1", NULL };
	g_autofree char *m = sip_build_message (&ctx, "sip:+49@x", "+4930", "x", "1.2.3.4", 5062, "1msg@1.2.3.4", 1,
						"Grüße", NULL, (char **)hdr, "gw1", NULL);
	g_autoptr (SipMsg) p = parse (m);
	g_assert_cmpint (atoi (sipmsg_header (p, "content-length")), ==, 7);	/* bytes, not characters */
	g_assert_cmpstr (sipmsg_header (p, "x-sms-id"), ==, "1");
	g_assert_cmpstr (p->body, ==, "Grüße");
}

static void
test_digest (void)
{
	/* RFC 2617's HA1 (939e7578...) with the no-qop response of RFC 2069;
	 * the response printed in RFC 2069 itself is a known erratum */
	g_autoptr (SipMsg) ch = parse ("SIP/2.0 401 Unauthorized\r\nCSeq: 1 REGISTER\r\n"
				       "WWW-Authenticate: Digest realm=\"testrealm@host.com\", "
				       "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"\r\n\r\n");
	SipAuthParams *p = sip_auth_parse (ch);
	g_assert_nonnull (p);
	g_assert_cmpstr (p->realm, ==, "testrealm@host.com");
	g_assert_cmpstr (p->opaque, ==, "5ccc069c403ebaf9f0171e9517f40e41");
	g_autofree char *h = sip_auth_header ("GET", "/dir/index.html", "Mufasa", "Circle Of Life", p);
	g_assert_nonnull (strstr (h, "response=\"670fd8c2df070c60b045671b8b24ff02\""));
	g_assert_nonnull (strstr (h, "opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""));
	g_assert_true (g_str_has_suffix (h, "\r\n"));
	g_autofree char *ha1 = g_compute_checksum_for_string (G_CHECKSUM_MD5, "Mufasa:testrealm@host.com:Circle Of Life", -1);
	g_assert_cmpstr (ha1, ==, "939e7578ed9e3c518a452acee763bce9");
	sip_auth_free (p);
}

/* ---- codecs and resamplers ---- */

/* least-squares fit of a sine at f: the power of that component, and of
 * what is left over */
static double
snr_db (const int16_t *x, int n, double f, double rate, double *amp)
{
	double sc = 0, cc = 0, ss = 0, xs = 0, xc = 0;
	for (int i = 0; i < n; i++) {
		double s = sin (2 * G_PI * f * i / rate), c = cos (2 * G_PI * f * i / rate);
		ss += s * s;
		cc += c * c;
		sc += s * c;
		xs += x[i] * s;
		xc += x[i] * c;
	}
	double det = ss * cc - sc * sc;
	double a = (xs * cc - xc * sc) / det, b = (xc * ss - xs * sc) / det;
	double sig = 0, noise = 0;
	for (int i = 0; i < n; i++) {
		double fit = a * sin (2 * G_PI * f * i / rate) + b * cos (2 * G_PI * f * i / rate);
		sig += fit * fit;
		noise += (x[i] - fit) * (x[i] - fit);
	}
	if (amp)
		*amp = sqrt (a * a + b * b);
	return 10 * log10 (sig / MAX (noise, 1e-9));
}

static void
test_g722_tone (void)
{
	enum { N = 16000 };
	static int16_t in[N], out[N];
	static uint8_t code[N / 2];
	for (int i = 0; i < N; i++)
		in[i] = (int16_t)lrint (10000 * sin (2 * G_PI * 1000 * i / 16000.0));
	G722State enc, dec;
	g722_init (&enc);
	g722_init (&dec);
	g722_encode (&enc, in, N, code);
	g722_decode (&dec, code, N / 2, out);
	double amp;
	double snr = snr_db (out + 800, N - 800, 1000, 16000, &amp);
	g_test_message ("G.722 1 kHz: SNR %.1f dB, amplitude %.0f of 10000", snr, amp);
	g_assert_cmpfloat (snr, >, 30);
	g_assert_cmpfloat (fabs (amp - 10000), <, 1000);
}

static void
test_g711 (void)
{
	for (int v = -32768; v <= 32767; v += 7) {
		int16_t a = alaw_decode (alaw_encode ((int16_t)v));
		int16_t u = ulaw_decode (ulaw_encode ((int16_t)v));
		int tol = MAX (16, abs (v) / 16);
		g_assert_cmpint (abs (a - v), <=, tol);
		g_assert_cmpint (abs (u - v), <=, tol);
	}
	/* the quiet-sample bug the Kotlin fixed: 150 must not come back as 2240 */
	g_assert_cmpint (abs (alaw_decode (alaw_encode (150)) - 150), <, 16);
	g_assert_cmpint (alaw_encode (0), ==, 0xD5);
	g_assert_cmpint (ulaw_encode (0), ==, 0xFF);
}

static double
tone_through (int in_rate, int out_rate, double f, double *snr)
{
	int n_in = in_rate;	/* one second */
	int16_t *in = g_new (int16_t, n_in), *out = g_new (int16_t, (gsize)out_rate + 64);
	for (int i = 0; i < n_in; i++)
		in[i] = (int16_t)lrint (10000 * sin (2 * G_PI * f * i / in_rate));
	Resampler *r = resampler_new (in_rate, out_rate);
	int n = 0;
	/* in 20 ms blocks, as the media threads feed it */
	for (int off = 0; off < n_in; off += in_rate / 50)
		n += resampler_process (r, in + off, in_rate / 50, out + n);
	resampler_free (r);
	g_assert_cmpint (abs (n - out_rate), <=, 2);
	double amp = 0;
	double s = snr_db (out + out_rate / 10, n - out_rate / 10, f, out_rate, &amp);
	if (snr)
		*snr = s;
	/* total output power, for stop-band checks */
	double pw = 0;
	for (int i = out_rate / 10; i < n; i++)
		pw += (double)out[i] * out[i];
	pw = sqrt (pw / (n - out_rate / 10)) * sqrt (2);
	g_free (in);
	g_free (out);
	return f < MIN (in_rate, out_rate) / 2.0 ? amp : pw;
}

static void
test_resamplers (void)
{
	struct { int in, out; double pass, stop; } c[] = {
		{ 32000, 16000, 1000, 9000 },	/* capture -> G.722 */
		{ 32000, 8000, 1000, 4500 },	/* capture -> G.711 */
		{ 16000, 48000, 1000, 0 },	/* G.722 -> playback */
		{ 8000, 48000, 1000, 0 },	/* G.711 -> playback */
	};
	for (guint i = 0; i < G_N_ELEMENTS (c); i++) {
		double snr;
		double amp = tone_through (c[i].in, c[i].out, c[i].pass, &snr);
		g_test_message ("%d -> %d: 1 kHz amplitude %.0f, SNR %.1f dB", c[i].in, c[i].out, amp, snr);
		g_assert_cmpfloat (fabs (20 * log10 (amp / 10000)), <, 0.3);
		g_assert_cmpfloat (snr, >, 55);
		if (c[i].stop > 0) {
			/* above the new Nyquist: must not alias back in */
			double left = tone_through (c[i].in, c[i].out, c[i].stop, NULL);
			/* below half an LSB the output rounds to exact zero */
			double db = 20 * log10 (MAX (left, 1e-3) / 10000);
			g_test_message ("%d -> %d: %.0f Hz leaks %.1f dB", c[i].in, c[i].out, c[i].stop, db);
			g_assert_cmpfloat (db, <, -50);
		}
	}
	/* the interpolator's image: 1 kHz at 16 kHz must not reappear at 15 kHz */
	int16_t in[320], out[1000];
	Resampler *r = resampler_new (16000, 48000);
	double img_s = 0, img_c = 0;
	int idx = 0;
	for (int blk = 0; blk < 50; blk++) {
		for (int i = 0; i < 320; i++)
			in[i] = (int16_t)lrint (10000 * sin (2 * G_PI * 1000 * (blk * 320 + i) / 16000.0));
		int n = resampler_process (r, in, 320, out);
		for (int i = 0; i < n; i++, idx++)
			if (blk >= 5) {
				img_s += out[i] * sin (2 * G_PI * 15000 * idx / 48000.0);
				img_c += out[i] * cos (2 * G_PI * 15000 * idx / 48000.0);
			}
	}
	resampler_free (r);
	double img = 2 * sqrt (img_s * img_s + img_c * img_c) / (45 * 960);
	g_test_message ("16 -> 48 kHz image at 15 kHz: %.1f dB", 20 * log10 (MAX (img, 1e-3) / 10000));
	g_assert_cmpfloat (20 * log10 (MAX (img, 1e-3) / 10000), <, -50);
}

/* ---- DTMF ---- */

typedef struct {
	GString *log;
} DtmfLog;

static void
log_begin (char d, gpointer ud)
{
	g_string_append_printf (((DtmfLog *)ud)->log, "+%c", d);
}

static void
log_end (char d, int ms, gpointer ud)
{
	g_string_append_printf (((DtmfLog *)ud)->log, "-%c%d ", d, ms);
}

static void
pkt (DtmfDetector *d, guint32 ts, int ev, gboolean end, int dur)
{
	uint8_t p[4] = { (uint8_t)ev, (uint8_t)((end ? 0x80 : 0) | 10), (uint8_t)(dur >> 8), (uint8_t)dur };
	dtmf_detector_packet (d, ts, p, 4);
}

static void
test_rfc4733 (void)
{
	TelephoneEvent ev;
	const uint8_t p[] = { 11, 0x8a, 0x03, 0x20 };
	g_assert_true (rfc4733_parse (p, 4, &ev));
	g_assert_cmpint (ev.event, ==, 11);
	g_assert_true (ev.end);
	g_assert_cmpint (ev.volume, ==, 10);
	g_assert_cmpint (ev.duration, ==, 800);
	g_assert_false (rfc4733_parse (p, 3, &ev));
	g_assert_cmpint (rfc4733_to_char (11), ==, '#');
	g_assert_cmpint (rfc4733_to_char (10), ==, '*');
	g_assert_cmpint (rfc4733_to_char (15), ==, 'D');
	g_assert_cmpint (rfc4733_to_char (16), ==, 0);
	g_assert_cmpint (rfc4733_from_char ('b'), ==, 13);

	DtmfLog l = { g_string_new (NULL) };
	DtmfDetector d;
	dtmf_detector_init (&d, log_begin, log_end, &l);
	/* one press: start repeated, end three times */
	pkt (&d, 1000, 5, FALSE, 160);
	pkt (&d, 1000, 5, FALSE, 320);
	pkt (&d, 1000, 5, FALSE, 480);
	pkt (&d, 1000, 5, TRUE, 800);
	pkt (&d, 1000, 5, TRUE, 800);
	pkt (&d, 1000, 5, TRUE, 800);
	/* the same key again is a new press (new timestamp) */
	pkt (&d, 3000, 5, FALSE, 160);
	pkt (&d, 3000, 5, TRUE, 400);
	/* a late copy of the first press's end must not replay it */
	pkt (&d, 1000, 5, TRUE, 800);
	/* a press whose end was lost is closed by the next one */
	pkt (&d, 5000, 10, FALSE, 160);
	pkt (&d, 7000, 11, FALSE, 160);
	dtmf_detector_flush (&d);
	g_assert_cmpstr (l.log->str, ==, "+5-5100 +5-550 +*-*20 +#-#20 ");
	g_string_free (l.log, TRUE);
}

static void
test_info_dtmf (void)
{
	int ms;
	g_assert_cmpint (sip_info_dtmf_parse ("application/dtmf-relay", "Signal=5\r\nDuration=250\r\n", &ms), ==, '5');
	g_assert_cmpint (ms, ==, 250);
	g_assert_cmpint (sip_info_dtmf_parse ("Application/DTMF-Relay; x=y", "signal = #\n", &ms), ==, '#');
	g_assert_cmpint (ms, ==, 160);
	g_assert_cmpint (sip_info_dtmf_parse ("application/dtmf-relay", "Signal=10\r\n", &ms), ==, '*');
	g_assert_cmpint (sip_info_dtmf_parse ("application/dtmf", " 7 \r\n", &ms), ==, '7');
	g_assert_cmpint (sip_info_dtmf_parse ("application/dtmf", "x", &ms), ==, 0);
	g_assert_cmpint (sip_info_dtmf_parse ("text/plain", "Signal=5", &ms), ==, 0);
	g_assert_cmpint (sip_info_dtmf_parse (NULL, "Signal=5", &ms), ==, 0);
}

static void
test_dtmf_tone (void)
{
	DtmfTone t;
	dtmf_tone_init (&t);
	/* a short press is held to the minimum */
	dtmf_tone_begin (&t, '1', 1000);
	dtmf_tone_end (&t, '1', 1030);
	g_assert_cmpint (dtmf_tone_at (&t, 1000), ==, '1');
	g_assert_cmpint (dtmf_tone_at (&t, 1119), ==, '1');
	g_assert_cmpint (dtmf_tone_at (&t, 1120), ==, 0);
	/* "11": the second starts only after the gap */
	dtmf_tone_begin (&t, '1', 1125);
	g_assert_cmpint (dtmf_tone_at (&t, 1150), ==, 0);
	g_assert_cmpint (dtmf_tone_at (&t, 1180), ==, '1');
	/* no end ever: the watchdog stops it at 2 s */
	g_assert_cmpint (dtmf_tone_at (&t, 1180 + 1999), ==, '1');
	g_assert_cmpint (dtmf_tone_at (&t, 1180 + 2000), ==, 0);
	/* an end with no begin plays as a pulse */
	dtmf_tone_init (&t);
	dtmf_tone_end (&t, '9', 5000);
	g_assert_cmpint (dtmf_tone_at (&t, 5000), ==, '9');
	g_assert_cmpint (dtmf_tone_at (&t, 5120), ==, 0);
	/* INFO pulses, clamped */
	dtmf_tone_pulse (&t, '#', 50, 6000);
	g_assert_cmpint (dtmf_tone_at (&t, 6119), ==, '#');
	g_assert_cmpint (dtmf_tone_at (&t, 6120), ==, 0);
	dtmf_tone_pulse (&t, '#', 9000, 7000);
	g_assert_cmpint (dtmf_tone_at (&t, 8999), ==, '#');
	g_assert_cmpint (dtmf_tone_at (&t, 9000), ==, 0);
	dtmf_tone_cancel (&t, 7100);
	g_assert_cmpint (dtmf_tone_at (&t, 7101), ==, 0);

	/* and the tone itself is the right pair of frequencies */
	int16_t pcm[4800];
	dtmf_tone_init (&t);
	dtmf_tone_render (&t, '5', 48000, pcm, 4800);
	double lo = 0, hi = 0;
	g_assert_true (dtmf_freqs ('5', &lo, &hi));
	g_assert_cmpfloat (lo, ==, 770);
	g_assert_cmpfloat (hi, ==, 1336);
	double amp_lo, amp_hi;
	snr_db (pcm, 4800, lo, 48000, &amp_lo);
	snr_db (pcm, 4800, hi, 48000, &amp_hi);
	g_assert_cmpfloat (fabs (amp_lo - 7000), <, 100);
	g_assert_cmpfloat (fabs (amp_hi - 8800), <, 100);
}

/* ---- STUN ---- */

static void
test_stun (void)
{
	guint8 tx[12];
	for (int i = 0; i < 12; i++)
		tx[i] = (guint8)(0xa0 + i);
	guint8 req[20];
	stun_build_request (req, tx);
	g_assert_cmpint (req[0], ==, 0);
	g_assert_cmpint (req[1], ==, 1);
	g_assert_cmpint (req[4], ==, 0x21);
	g_assert_cmpmem (req + 8, 12, tx, 12);

	/* Binding Success with a SOFTWARE attribute first, then
	 * XOR-MAPPED-ADDRESS 192.0.2.1:32853 (the RFC 5769 example address) */
	guint8 r[20 + 12 + 12];
	memset (r, 0, sizeof r);
	r[0] = 0x01; r[1] = 0x01; r[3] = 24;
	r[4] = 0x21; r[5] = 0x12; r[6] = 0xA4; r[7] = 0x42;
	memcpy (r + 8, tx, 12);
	r[20] = 0x80; r[21] = 0x22; r[23] = 5;		/* SOFTWARE, 5 bytes, padded to 8 */
	memcpy (r + 24, "test", 4);
	guint8 *a = r + 32;
	a[1] = 0x20; a[3] = 8;
	a[5] = 0x01;
	a[6] = (32853 >> 8) ^ 0x21; a[7] = (32853 & 0xff) ^ 0x12;
	a[8] = 192 ^ 0x21; a[9] = 0 ^ 0x12; a[10] = 2 ^ 0xA4; a[11] = 1 ^ 0x42;
	char ip[16];
	int port = 0;
	g_assert_true (stun_parse_response (r, sizeof r, tx, ip, &port));
	g_assert_cmpstr (ip, ==, "192.0.2.1");
	g_assert_cmpint (port, ==, 32853);
	tx[0] ^= 1;
	g_assert_false (stun_parse_response (r, sizeof r, tx, ip, &port));
	g_assert_false (stun_parse_response (r, 19, tx, ip, &port));
}

/* ---- TLS stream framing ---- */

static void
test_stream_framing (void)
{
	const char *m1 = "SIP/2.0 200 OK\r\nCSeq: 1 REGISTER\r\nContent-Length: 0\r\n\r\n";
	const char *m2 = "MESSAGE sip:a@b SIP/2.0\r\nl: 7\r\n\r\nGrüße";
	GByteArray *rx = g_byte_array_new ();
	g_byte_array_append (rx, (const guint8 *)"\r\n\r\n", 4);	/* keepalive */
	g_byte_array_append (rx, (const guint8 *)m1, strlen (m1));
	g_byte_array_append (rx, (const guint8 *)m2, strlen (m2) - 3);	/* body incomplete */
	GBytes *b = sip_stream_take_message (rx);
	g_assert_nonnull (b);
	g_assert_cmpmem (g_bytes_get_data (b, NULL), g_bytes_get_size (b), m1, strlen (m1));
	g_bytes_unref (b);
	g_assert_null (sip_stream_take_message (rx));
	g_byte_array_append (rx, (const guint8 *)m2 + strlen (m2) - 3, 3);
	b = sip_stream_take_message (rx);
	g_assert_nonnull (b);
	g_assert_cmpmem (g_bytes_get_data (b, NULL), g_bytes_get_size (b), m2, strlen (m2));
	g_bytes_unref (b);
	g_assert_cmpuint (rx->len, ==, 0);
	g_byte_array_unref (rx);
}

static void
test_srtp_keys (void)
{
	SipSrtpKey k = { 0 };
	g_assert_true (sip_srtp_key_from_inline (&k, "AES_CM_128_HMAC_SHA1_80", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwd|2^31|1:1"));
	g_assert_cmpint (k.tag_bits, ==, 80);
	for (int i = 0; i < 30; i++)
		g_assert_cmpint (k.key[i], ==, i);
	g_assert_true (sip_srtp_key_from_inline (&k, "aes_cm_128_hmac_sha1_32", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwd"));
	g_assert_cmpint (k.tag_bits, ==, 32);
	g_assert_false (sip_srtp_key_from_inline (&k, "AES_CM_128_HMAC_SHA1_80", "AAECAwQF"));
	g_assert_false (sip_srtp_key_from_inline (&k, "AEAD_AES_256_GCM", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwd"));
	sip_srtp_key_generate (&k, 80);
	g_assert_true (k.set);
	g_assert_cmpint (strlen (k.inline_b64), ==, 40);
	sip_srtp_key_clear (&k);
}

/* ---- SMS and number helpers ---- */

static void
test_numbers (void)
{
	g_autofree char *a = gw_to_international ("0049 151-123/45");
	g_autofree char *b = gw_to_international ("+49 151 12345");
	g_autofree char *c = gw_to_international ("015112345");
	g_assert_cmpstr (a, ==, "+4915112345");
	g_assert_cmpstr (b, ==, "+4915112345");
	g_assert_cmpstr (c, ==, "015112345");
	g_assert_true (gw_looks_dialable ("+49(30)123-45", 3));
	g_assert_false (gw_looks_dialable ("+49 30 123", 3));	/* as strict as the Kotlin */
	g_assert_false (gw_looks_dialable ("gateway1", 3));
	g_assert_false (gw_looks_dialable ("12", 3));
	g_assert_false (gw_looks_dialable ("", 3));
}

static void
test_sms_measure (void)
{
	GString *s = g_string_new (NULL);
	for (int i = 0; i < 160; i++)
		g_string_append_c (s, 'a');
	GwSmsMeasure m = gw_sms_measure (s->str);
	g_assert_cmpstr (m.encoding, ==, "GSM7");
	g_assert_cmpint (m.parts, ==, 1);
	g_string_append_c (s, 'a');
	g_assert_cmpint (gw_sms_measure (s->str).parts, ==, 2);
	/* the euro sign takes two septets */
	g_string_truncate (s, 159);
	g_string_append (s, "€");
	g_assert_cmpint (gw_sms_measure (s->str).parts, ==, 2);
	g_assert_cmpstr (gw_sms_measure ("Grüße aus München").encoding, ==, "GSM7");
	g_assert_cmpstr (gw_sms_measure ("Привет").encoding, ==, "UCS2");
	g_string_truncate (s, 0);
	for (int i = 0; i < 70; i++)
		g_string_append (s, "ж");
	g_assert_cmpint (gw_sms_measure (s->str).parts, ==, 1);
	g_string_append (s, "ж");
	g_assert_cmpint (gw_sms_measure (s->str).parts, ==, 2);
	g_assert_cmpstr (gw_sms_measure ("a`b").encoding, ==, "UCS2");
	g_string_free (s, TRUE);
}

static void
test_sms_ids_and_time (void)
{
	g_autofree char *a = gw_sms_stable_id ("+4930", "2026-11-01T03:12:45+01:00", "hi");
	g_autofree char *b = gw_sms_stable_id ("+4930", "2026-11-01T03:12:45+01:00", "hi");
	g_autofree char *c = gw_sms_stable_id ("+4930", "2026-11-01T03:12:46+01:00", "hi");
	g_assert_cmpstr (a, ==, b);
	g_assert_cmpstr (a, !=, c);
	g_assert_true (g_uuid_string_is_valid (a));
	g_assert_cmpint (a[14], ==, '5');
	g_autofree char *t1 = gw_iso_utc_from_mm ("2026-11-01T03:12:45+01:00");
	g_autofree char *t2 = gw_iso_utc_from_mm ("2026-11-01T03:12:45+01");
	g_assert_cmpstr (t1, ==, "2026-11-01T02:12:45Z");
	g_assert_cmpstr (t2, ==, "2026-11-01T02:12:45Z");
	g_autofree char *t3 = gw_iso_utc_from_mm ("garbage");
	g_assert_cmpint (strlen (t3), ==, 20);
	GString *j = g_string_new (NULL);
	gw_json_append_string (j, "a\"b\\c\nd\x01");
	g_assert_cmpstr (j->str, ==, "\"a\\\"b\\\\c\\nd\\u0001\"");
	g_string_free (j, TRUE);
}

int
main (int argc, char **argv)
{
	g_test_init (&argc, &argv, NULL);
	g_test_add_func ("/sipmsg/invite-round-trip", test_invite_round_trip);
	g_test_add_func ("/sipmsg/parse-variants", test_parse_variants);
	g_test_add_func ("/sipmsg/sdp-choice", test_sdp_choice);
	g_test_add_func ("/sipmsg/message-bytes", test_message_builder_bytes);
	g_test_add_func ("/sipmsg/digest", test_digest);
	g_test_add_func ("/codec/g722-tone", test_g722_tone);
	g_test_add_func ("/codec/g711", test_g711);
	g_test_add_func ("/dsp/resamplers", test_resamplers);
	g_test_add_func ("/dtmf/rfc4733", test_rfc4733);
	g_test_add_func ("/dtmf/info", test_info_dtmf);
	g_test_add_func ("/dtmf/tone", test_dtmf_tone);
	g_test_add_func ("/stun/parse", test_stun);
	g_test_add_func ("/sip/stream-framing", test_stream_framing);
	g_test_add_func ("/sip/srtp-keys", test_srtp_keys);
	g_test_add_func ("/util/numbers", test_numbers);
	g_test_add_func ("/util/sms-measure", test_sms_measure);
	g_test_add_func ("/util/sms-ids-time", test_sms_ids_and_time);
	return g_test_run ();
}
