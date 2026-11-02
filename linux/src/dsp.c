#include "dsp.h"
#include <math.h>
#include <string.h>
#include <stdlib.h>

/* ---- resampler ---- */

static int
gcd (int a, int b)
{
	while (b) {
		int t = a % b;
		a = b;
		b = t;
	}
	return a;
}

static double
bessel_i0 (double x)
{
	double sum = 1, term = 1;
	for (int k = 1; k < 50; k++) {
		term *= (x / (2 * k)) * (x / (2 * k));
		sum += term;
		if (term < sum * 1e-12)
			break;
	}
	return sum;
}

Resampler *
resampler_new (int in_rate, int out_rate)
{
	Resampler *r = g_new0 (Resampler, 1);
	int g = gcd (in_rate, out_rate);
	r->L = out_rate / g;
	r->M = in_rate / g;
	int mx = MAX (r->L, r->M);
	if (mx == 1) {
		r->tpp = 1;
		r->h = g_new (float, 1);
		r->h[0] = 1;
	} else {
		/* 48 taps per unit of the larger factor: about 1.4 kHz of transition
		 * band whichever way the rate goes, and -70 dB beyond it */
		int n = 48 * mx + 1;
		r->tpp = (n + r->L - 1) / r->L;
		n = r->tpp * r->L;
		double fc = 0.5 / mx * 0.9, beta = 7.0, centre = (n - 1) / 2.0, sum = 0;
		double *proto = g_new (double, n);
		for (int j = 0; j < n; j++) {
			double x = j - centre;
			double s = fabs (x) < 1e-9 ? 2 * fc : sin (2 * G_PI * fc * x) / (G_PI * x);
			double u = 2.0 * j / (n - 1) - 1;
			proto[j] = s * bessel_i0 (beta * sqrt (MAX (0, 1 - u * u))) / bessel_i0 (beta);
			sum += proto[j];
		}
		r->h = g_new (float, n);
		for (int p = 0; p < r->L; p++)
			for (int k = 0; k < r->tpp; k++)
				r->h[p * r->tpp + k] = (float)(proto[p + k * r->L] * r->L / sum);
		g_free (proto);
	}
	resampler_reset (r);
	return r;
}

void
resampler_reset (Resampler *r)
{
	if (r->x)
		memset (r->x, 0, sizeof (float) * r->xcap);
	r->t = 0;
}

void
resampler_free (Resampler *r)
{
	if (!r)
		return;
	g_free (r->h);
	g_free (r->x);
	g_free (r);
}

static inline int16_t
sat16f (float v)
{
	if (v > 32767.f) return 32767;
	if (v < -32768.f) return -32768;
	return (int16_t)lrintf (v);
}

int
resampler_process (Resampler *r, const int16_t *in, int n, int16_t *out)
{
	int hist = r->tpp - 1;
	if (hist + n > r->xcap) {
		int cap = hist + n;
		float *nx = g_new0 (float, cap);
		if (r->x)
			memcpy (nx, r->x, sizeof (float) * hist);
		g_free (r->x);
		r->x = nx;
		r->xcap = cap;
	}
	for (int i = 0; i < n; i++)
		r->x[hist + i] = in[i];
	int produced = 0;
	for (;;) {
		long i = r->t / r->L;
		if (i >= n)
			break;
		int p = (int)(r->t % r->L);
		const float *hp = r->h + p * r->tpp;
		const float *xp = r->x + hist + i;
		float acc = 0;
		for (int k = 0; k < r->tpp; k++)
			acc += hp[k] * xp[-k];
		out[produced++] = sat16f (acc);
		r->t += r->M;
	}
	r->t -= (long)n * r->L;
	memmove (r->x, r->x + n, sizeof (float) * hist);
	return produced;
}

/* ---- G.711 ---- */

uint8_t
alaw_encode (int16_t pcm)
{
	/* the ITU reference encoder, as PcmaCodec.encodeSample: below 256 the
	 * segment is 0 and the mantissa is bits 4..7 */
	int linear = pcm, mask;
	if (linear >= 0) {
		mask = 0xD5;
	} else {
		mask = 0x55;
		linear = -linear - 1;
	}
	int seg = 0;
	if (linear >= 256)
		for (int v = linear >> 8; v; v >>= 1)
			seg++;
	int mant = seg == 0 ? (linear >> 4) & 0x0F : (linear >> (seg + 3)) & 0x0F;
	return (uint8_t)(((seg << 4) | mant) ^ mask);
}

int16_t
alaw_decode (uint8_t a)
{
	int v = a ^ 0xD5;
	int sign = v & 0x80, exp = (v >> 4) & 7, mant = ((v & 0x0F) << 4) + 8;
	if (exp > 0)
		mant = (mant + 256) << (exp - 1);
	return (int16_t)(sign ? -mant : mant);
}

uint8_t
ulaw_encode (int16_t pcm)
{
	int s = pcm, sign = 0;
	if (s < 0) {
		s = -s;
		sign = 0x80;
	}
	if (s > 32635)
		s = 32635;
	s += 0x84;
	int exp = 7;
	for (int mask = 0x4000; !(s & mask) && exp > 0; mask >>= 1)
		exp--;
	int mant = (s >> (exp + 3)) & 0x0F;
	return (uint8_t)~(sign | (exp << 4) | mant);
}

int16_t
ulaw_decode (uint8_t u)
{
	u = (uint8_t)~u;
	int sign = u & 0x80, exp = (u >> 4) & 7, mant = u & 0x0F;
	int s = (((mant << 3) + 0x84) << exp) - 0x84;
	return (int16_t)(sign ? -s : s);
}

/* ---- RFC 4733 ---- */

gboolean
rfc4733_parse (const uint8_t *p, gsize len, TelephoneEvent *ev)
{
	if (len < 4)
		return FALSE;
	ev->event = p[0];
	ev->end = (p[1] & 0x80) != 0;
	ev->volume = p[1] & 0x3F;
	ev->duration = (p[2] << 8) | p[3];
	return TRUE;
}

char
rfc4733_to_char (int e)
{
	if (e >= 0 && e <= 9) return (char)('0' + e);
	if (e == 10) return '*';
	if (e == 11) return '#';
	if (e >= 12 && e <= 15) return (char)('A' + e - 12);
	return 0;
}

int
rfc4733_from_char (char c)
{
	if (c >= '0' && c <= '9') return c - '0';
	if (c == '*') return 10;
	if (c == '#') return 11;
	if (c >= 'A' && c <= 'D') return 12 + c - 'A';
	if (c >= 'a' && c <= 'd') return 12 + c - 'a';
	return -1;
}

void
dtmf_detector_init (DtmfDetector *d, DtmfBeginFn b, DtmfEndFn e, gpointer ud)
{
	memset (d, 0, sizeof *d);
	d->current_ts = -1;
	d->ended = TRUE;
	d->clock_rate = 8000;
	d->on_begin = b;
	d->on_end = e;
	d->ud = ud;
}

static void
finish_current (DtmfDetector *d)
{
	if (d->ended || !d->current)
		return;
	d->ended = TRUE;
	if (d->on_end)
		d->on_end (d->current, d->duration * 1000 / d->clock_rate, d->ud);
	if (d->nrecent == G_N_ELEMENTS (d->recent)) {
		memmove (d->recent, d->recent + 1, sizeof d->recent[0] * (d->nrecent - 1));
		d->nrecent--;
	}
	d->recent[d->nrecent++] = d->current_ts;
}

void
dtmf_detector_packet (DtmfDetector *d, guint32 ts, const uint8_t *payload, gsize len)
{
	TelephoneEvent ev;
	if (!rfc4733_parse (payload, len, &ev))
		return;
	char c = rfc4733_to_char (ev.event);
	if (!c)
		return;
	if ((gint64)ts != d->current_ts) {
		for (int i = 0; i < d->nrecent; i++)
			if (d->recent[i] == (gint64)ts)
				return;	/* a late copy of a key that has already ended */
		finish_current (d);
		d->current_ts = ts;
		d->current = c;
		d->duration = ev.duration;
		d->ended = FALSE;
		if (d->on_begin)
			d->on_begin (c, d->ud);
	} else if (d->ended) {
		return;		/* the repeated end packets */
	}
	d->duration = MAX (d->duration, ev.duration);
	if (ev.end)
		finish_current (d);
}

void
dtmf_detector_flush (DtmfDetector *d)
{
	finish_current (d);
}

static char
info_digit (const char *signal)
{
	if (!signal || !*signal)
		return 0;
	char *end = NULL;
	long v = strtol (signal, &end, 10);
	if (end && end != signal && !*end)
		return rfc4733_to_char ((int)v);	/* "10" for '*', as some senders do */
	char c = g_ascii_toupper (signal[0]);
	return rfc4733_from_char (c) >= 0 ? c : 0;
}

char
sip_info_dtmf_parse (const char *content_type, const char *body, int *duration_ms)
{
	*duration_ms = 160;
	if (!content_type || !body)
		return 0;
	g_autofree char *type = g_ascii_strdown (content_type, -1);
	char *semi = strchr (type, ';');
	if (semi)
		*semi = 0;
	g_strstrip (type);
	if (!strcmp (type, "application/dtmf")) {
		g_autofree char *b = g_strstrip (g_strdup (body));
		return info_digit (b);
	}
	if (strcmp (type, "application/dtmf-relay"))
		return 0;
	g_auto (GStrv) lines = g_strsplit (body, "\n", -1);
	g_autofree char *signal = NULL;
	for (int i = 0; lines[i]; i++) {
		char *eq = strchr (lines[i], '=');
		if (!eq)
			continue;
		*eq = 0;
		g_autofree char *key = g_ascii_strdown (g_strstrip (lines[i]), -1);
		char *val = g_strstrip (eq + 1);
		if (!strcmp (key, "signal")) {
			g_free (signal);
			signal = g_strdup (val);
		} else if (!strcmp (key, "duration")) {
			char *end = NULL;
			long v = strtol (val, &end, 10);
			if (end != val && !*end)
				*duration_ms = (int)v;
		}
	}
	return info_digit (signal);
}

/* ---- in-band tone schedule ---- */

void
dtmf_tone_init (DtmfTone *t)
{
	memset (t, 0, sizeof *t);
	t->last_stop = G_MININT64 / 2;
}

void
dtmf_tone_begin (DtmfTone *t, char digit, gint64 now)
{
	if (t->cur == digit && !t->end_requested && now < t->stop)
		return;		/* the same press, repeated */
	if (t->cur && t->start <= now)
		t->last_stop = MIN (t->stop, now);
	t->cur = digit;
	t->start = MAX (now, t->last_stop + DTMF_GAP_MS);
	t->stop = t->start + DTMF_MAX_TONE_MS;	/* watchdog: no end ever arrived */
	t->end_requested = FALSE;
}

void
dtmf_tone_end (DtmfTone *t, char digit, gint64 now)
{
	if (t->cur != digit || now >= t->stop)
		dtmf_tone_begin (t, digit, now);	/* the begin was lost: play it as a pulse */
	t->end_requested = TRUE;
	t->stop = MIN (t->stop, MAX (now, t->start + DTMF_MIN_TONE_MS));
}

void
dtmf_tone_pulse (DtmfTone *t, char digit, int duration_ms, gint64 now)
{
	dtmf_tone_begin (t, digit, now);
	t->end_requested = TRUE;
	t->stop = MAX (now, t->start + CLAMP (duration_ms, DTMF_MIN_TONE_MS, DTMF_MAX_TONE_MS));
}

void
dtmf_tone_cancel (DtmfTone *t, gint64 now)
{
	if (t->cur && t->start <= now)
		t->last_stop = MIN (t->stop, now);
	t->cur = 0;
	t->stop = t->start = 0;
	t->end_requested = FALSE;
}

char
dtmf_tone_at (const DtmfTone *t, gint64 now)
{
	return t->cur && now >= t->start && now < t->stop ? t->cur : 0;
}

gboolean
dtmf_freqs (char d, double *lo, double *hi)
{
	static const char *keys = "123A456B789C*0#D";
	static const double row[] = { 697, 770, 852, 941 }, col[] = { 1209, 1336, 1477, 1633 };
	const char *p = d ? strchr (keys, g_ascii_toupper (d)) : NULL;
	if (!p)
		return FALSE;
	int i = (int)(p - keys);
	*lo = row[i / 4];
	*hi = col[i % 4];
	return TRUE;
}

void
dtmf_tone_render (DtmfTone *t, char digit, int rate, int16_t *pcm, int n)
{
	double lo, hi;
	if (!digit || !dtmf_freqs (digit, &lo, &hi)) {
		t->playing = 0;
		return;
	}
	if (t->playing != digit) {
		t->playing = digit;
		t->ph1 = t->ph2 = 0;
	}
	/* about -11 dBFS low group, 2 dB more on the high group (twist) */
	double d1 = 2 * G_PI * lo / rate, d2 = 2 * G_PI * hi / rate;
	for (int i = 0; i < n; i++) {
		pcm[i] = (int16_t)lrint (7000 * sin (t->ph1) + 8800 * sin (t->ph2));
		t->ph1 += d1;
		t->ph2 += d2;
	}
	t->ph1 = fmod (t->ph1, 2 * G_PI);
	t->ph2 = fmod (t->ph2, 2 * G_PI);
}
