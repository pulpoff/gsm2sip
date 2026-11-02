/* The audio arithmetic of the media path, kept free of ALSA, sockets and
 * threads so it can be tested on the build machine. */
#pragma once
#include <glib.h>
#include <stdint.h>

/* ---- rational polyphase resampler (Kaiser-windowed sinc) ----
 * L/M = out/in.  For M = 2 the prototype is a half-band filter (every other
 * tap is zero); for L = 3 it is the x3 interpolator 16 -> 48 kHz. */
typedef struct {
	int L, M, tpp;		/* taps per phase */
	float *h;		/* L * tpp, grouped by phase */
	float *x;		/* (tpp - 1) history + one block */
	int xcap;
	long t;			/* output position in 1/L input samples, relative to the block */
} Resampler;

Resampler *resampler_new (int in_rate, int out_rate);
void resampler_free (Resampler *r);
void resampler_reset (Resampler *r);
/* Returns the number of samples written; `out` must hold n * L / M + 2. */
int resampler_process (Resampler *r, const int16_t *in, int n, int16_t *out);

/* ---- G.711 ---- */
uint8_t alaw_encode (int16_t pcm);
int16_t alaw_decode (uint8_t a);
uint8_t ulaw_encode (int16_t pcm);
int16_t ulaw_decode (uint8_t u);

/* ---- RFC 4733 ---- */
typedef struct {
	int event;
	gboolean end;
	int volume;
	int duration;		/* timestamp units */
} TelephoneEvent;

gboolean rfc4733_parse (const uint8_t *p, gsize len, TelephoneEvent *ev);
char rfc4733_to_char (int event);	/* 0 if not a dial-pad key */
int rfc4733_from_char (char c);		/* -1 if not one */

/* One begin and one end per key press, whatever the network did to the
 * packets (RFC 4733 sends the start repeatedly and the end three times). */
typedef void (*DtmfBeginFn) (char digit, gpointer ud);
typedef void (*DtmfEndFn) (char digit, int duration_ms, gpointer ud);
typedef struct {
	gint64 current_ts;
	char current;
	int duration;
	gboolean ended;
	gint64 recent[8];
	int nrecent;
	int clock_rate;
	DtmfBeginFn on_begin;
	DtmfEndFn on_end;
	gpointer ud;
} DtmfDetector;

void dtmf_detector_init (DtmfDetector *d, DtmfBeginFn b, DtmfEndFn e, gpointer ud);
void dtmf_detector_packet (DtmfDetector *d, guint32 timestamp, const uint8_t *payload, gsize len);
void dtmf_detector_flush (DtmfDetector *d);

/* DTMF in a SIP INFO body; returns 0 when there is none. */
char sip_info_dtmf_parse (const char *content_type, const char *body, int *duration_ms);

/* ---- in-band tone schedule ----
 * The DtmfRelay of the Kotlin, without its thread: begin/end/pulse only move
 * times around, and the audio thread asks what is sounding at each frame.
 * The caller provides the locking. */
typedef struct {
	char cur;
	gint64 start, stop;	/* ms, monotonic; stop is the watchdog until an end arrives */
	gboolean end_requested;
	gint64 last_stop;
	double ph1, ph2;
	char playing;		/* the digit the generator last produced, for phase resets */
} DtmfTone;

#define DTMF_MIN_TONE_MS 120
#define DTMF_MAX_TONE_MS 2000
#define DTMF_GAP_MS 60

void dtmf_tone_init (DtmfTone *t);
void dtmf_tone_begin (DtmfTone *t, char digit, gint64 now);
void dtmf_tone_end (DtmfTone *t, char digit, gint64 now);
void dtmf_tone_pulse (DtmfTone *t, char digit, int duration_ms, gint64 now);
void dtmf_tone_cancel (DtmfTone *t, gint64 now);
char dtmf_tone_at (const DtmfTone *t, gint64 now);	/* 0 = silence */
/* Write n samples of the tone for `digit` at `rate` into pcm (replacing it). */
void dtmf_tone_render (DtmfTone *t, char digit, int rate, int16_t *pcm, int n);
gboolean dtmf_freqs (char digit, double *lo, double *hi);
