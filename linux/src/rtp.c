#define G_LOG_DOMAIN "rtp"
#include "rtp.h"
#include "dsp.h"
#include "g722.h"
#include <alsa/asoundlib.h>
#include <gio/gio.h>
#include <arpa/inet.h>
#include <errno.h>
#include <math.h>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <poll.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <string.h>
#include <sys/random.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
#ifdef HAVE_SRTP
#include <srtp2/srtp.h>
#endif

#define RTP_PORT_MIN 30000
#define RTP_PORT_MAX 40000
#define RTP_TIMEOUT_US (30 * G_USEC_PER_SEC)
#define PT_PCMU 0
#define PT_PCMA 8
#define PT_G722 9
#define PT_TELEPHONE_EVENT 101
/* 240 ms of 20 ms frames; above 7 queued (140 ms) one frame per cycle is
 * shed, so the depth a burst leaves behind is not kept for the whole call */
#define JITTER_CAPACITY 12
#define JITTER_HIGH_WATER 7
#define JITTER_POLL_MS 30
#define MAX_PAYLOAD 1400
#define SRTP_TRAILER 16		/* >= SRTP_MAX_TRAILER_LEN for these suites (10-byte tag, no MKI) */

typedef struct {
	int pt;
	int len;
	guint8 data[MAX_PAYLOAD];
} Frame;

struct RtpSession {
	int fd, port;
	RtpAudioConfig ac;
	char *cap_dev, *pb_dev, *ctl_card, *uplink, *active;
	RtpCallbacks cb;
	gpointer ud;

	GMutex lock;		/* remote, latched, tone */
	struct sockaddr_in remote;
	gboolean latched;
	DtmfTone tone;

	int pt, te_pt;
	gboolean srtp;
#ifdef HAVE_SRTP
	srtp_t tx_srtp, rx_srtp;
#endif

	snd_pcm_t *cap, *pb;
	GThread *tx, *rx, *play;
	atomic_bool running;
	atomic_bool audio_up;	/* cap and pb are open (or never will be) */
	atomic_bool agent_muted;

	GMutex jlock;
	GCond jcond;
	Frame q[JITTER_CAPACITY];
	int qhead, qcount;

	atomic_int tx_packets, rx_packets, play_frames, rx_dropped, underruns, trimmed, dtmf_packets, srtp_fail;
	atomic_int cap_rms, play_rms;
	atomic_llong last_rx;

	DtmfDetector det;	/* receive thread only */
	guint stats_timer;
	int stats_tick;
	gboolean timeout_fired;
	gint64 started;
};

static gint64 mono_ms (void) { return g_get_monotonic_time () / 1000; }

static int
rms (const int16_t *p, int n)
{
	if (n <= 0)
		return 0;
	double s = 0;
	for (int i = 0; i < n; i++)
		s += (double)p[i] * p[i];
	return (int)sqrt (s / n);
}

/* ---- setup ---- */

double
rtp_agent_volume_factor (int step)
{
	static const double f[] = { 0.40, 0.55, 0.75, 1.0, 1.4, 2.0, 2.8 };
	return f[CLAMP (step, -3, 3) + 3];
}

RtpSession *
rtp_session_new (GError **err)
{
	int fd = socket (AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
	if (fd < 0) {
		g_set_error (err, G_IO_ERROR, g_io_error_from_errno (errno), "RTP socket: %s", g_strerror (errno));
		return NULL;
	}
	int tos = IPTOS_DSCP_EF;
	setsockopt (fd, IPPROTO_IP, IP_TOS, &tos, sizeof tos);
	/* a random starting point: consecutive calls racing for the same port
	 * was the one way the Kotlin's probe-then-bind gap reliably lost */
	int span = (RTP_PORT_MAX - RTP_PORT_MIN) / 2, start = g_random_int_range (0, span), port = 0;
	for (int i = 0; i < span; i++) {
		int p = RTP_PORT_MIN + ((start + i) % span) * 2;
		struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons (p), .sin_addr.s_addr = htonl (INADDR_ANY) };
		if (bind (fd, (struct sockaddr *)&a, sizeof a) == 0) {
			port = p;
			break;
		}
	}
	if (!port) {
		close (fd);
		g_set_error (err, G_IO_ERROR, G_IO_ERROR_FAILED, "No free RTP port in %d-%d", RTP_PORT_MIN, RTP_PORT_MAX);
		return NULL;
	}
	RtpSession *s = g_new0 (RtpSession, 1);
	s->fd = fd;
	s->port = port;
	g_mutex_init (&s->lock);
	g_mutex_init (&s->jlock);
	g_cond_init (&s->jcond);
	dtmf_tone_init (&s->tone);
	return s;
}

int
rtp_session_port (RtpSession *s)
{
	return s->port;
}

#ifdef HAVE_SRTP
static gpointer
srtp_init_once (gpointer data)
{
	(void)data;
	return GINT_TO_POINTER (srtp_init () == srtp_err_status_ok);
}

static gboolean
make_srtp (srtp_t *ctx, int bits, const guint8 *key, gboolean outbound)
{
	srtp_policy_t pol;
	memset (&pol, 0, sizeof pol);
	if (bits == 32)
		srtp_crypto_policy_set_aes_cm_128_hmac_sha1_32 (&pol.rtp);
	else
		srtp_crypto_policy_set_rtp_default (&pol.rtp);	/* AES_CM_128_HMAC_SHA1_80 */
	srtp_crypto_policy_set_rtcp_default (&pol.rtcp);	/* no RTCP is sent; libsrtp wants one anyway */
	pol.ssrc.type = outbound ? ssrc_any_outbound : ssrc_any_inbound;
	pol.key = (unsigned char *)key;
	pol.window_size = 128;
	pol.allow_repeat_tx = 0;
	pol.next = NULL;
	return srtp_create (ctx, &pol) == srtp_err_status_ok;
}
#endif

gboolean
rtp_session_set_srtp (RtpSession *s, int bits, const guint8 *send_key, const guint8 *recv_key, GError **err)
{
#ifdef HAVE_SRTP
	static GOnce once = G_ONCE_INIT;
	if (!GPOINTER_TO_INT (g_once (&once, srtp_init_once, NULL))) {
		g_set_error (err, G_IO_ERROR, G_IO_ERROR_FAILED, "srtp_init failed");
		return FALSE;
	}
	if (!make_srtp (&s->tx_srtp, bits, send_key, TRUE) || !make_srtp (&s->rx_srtp, bits, recv_key, FALSE)) {
		g_set_error (err, G_IO_ERROR, G_IO_ERROR_FAILED, "srtp_create failed");
		return FALSE;
	}
	s->srtp = TRUE;
	return TRUE;
#else
	(void)s;
	(void)bits;
	(void)send_key;
	(void)recv_key;
	g_set_error (err, G_IO_ERROR, G_IO_ERROR_NOT_SUPPORTED, "built without libsrtp2");
	return FALSE;
#endif
}

/* ---- ALSA ---- */

static void
uplink_switch (RtpSession *s, gboolean on)
{
	if (!s->uplink || !*s->uplink)
		return;
	snd_ctl_t *ctl = NULL;
	int r = snd_ctl_open (&ctl, s->ctl_card, 0);
	if (r < 0) {
		g_warning ("%s: %s", s->ctl_card, snd_strerror (r));
		return;
	}
	snd_ctl_elem_id_t *id = NULL;
	snd_ctl_elem_info_t *info = NULL;
	snd_ctl_elem_value_t *v = NULL;
	snd_ctl_elem_id_malloc (&id);
	snd_ctl_elem_info_malloc (&info);
	snd_ctl_elem_value_malloc (&v);
	snd_ctl_elem_id_set_interface (id, SND_CTL_ELEM_IFACE_MIXER);
	snd_ctl_elem_id_set_name (id, s->uplink);
	snd_ctl_elem_info_set_id (info, id);
	if ((r = snd_ctl_elem_info (ctl, info)) < 0) {
		g_warning ("ALSA control '%s' on %s: %s", s->uplink, s->ctl_card, snd_strerror (r));
	} else {
		snd_ctl_elem_value_set_id (v, id);
		for (unsigned i = 0; i < snd_ctl_elem_info_get_count (info); i++)
			snd_ctl_elem_value_set_boolean (v, i, on);
		if ((r = snd_ctl_elem_write (ctl, v)) < 0)
			g_warning ("Setting '%s' %s: %s", s->uplink, on ? "on" : "off", snd_strerror (r));
		else
			g_message ("'%s' %s", s->uplink, on ? "on" : "off");
	}
	snd_ctl_elem_value_free (v);
	snd_ctl_elem_info_free (info);
	snd_ctl_elem_id_free (id);
	snd_ctl_close (ctl);
}

typedef struct {
	char *name;
	long value;
} MutedControl;

/*
 * The S10e plays the call's downlink on its earpiece whatever the uplink
 * carries: the far end's voice comes out of the gateway, and through its mics
 * back to the far end. Hold the controls listed in "mute" at their minimum
 * for the call; on = FALSE puts back what they were.  The bridge does it for
 * the whole GSM call, not the session: the network's ringback and its
 * announcements come down before the call is up and the session opens.
 */
static GArray *amps_muted;	/* MutedControl: what to put back */

void
rtp_mute_amps (const RtpAudioConfig *ac, gboolean on)
{
	if (on ? !ac->mute || !*ac->mute || amps_muted : !amps_muted)
		return;
	const char *card = ac->control_card ? ac->control_card : "hw:0";
	snd_ctl_t *ctl = NULL;
	int r = snd_ctl_open (&ctl, card, 0);
	if (r < 0) {
		g_warning ("%s: %s", card, snd_strerror (r));
		return;
	}
	snd_ctl_elem_id_t *id = NULL;
	snd_ctl_elem_info_t *info = NULL;
	snd_ctl_elem_value_t *v = NULL;
	snd_ctl_elem_id_malloc (&id);
	snd_ctl_elem_info_malloc (&info);
	snd_ctl_elem_value_malloc (&v);
	snd_ctl_elem_id_set_interface (id, SND_CTL_ELEM_IFACE_MIXER);
	if (on) {
		g_auto (GStrv) names = g_strsplit (ac->mute, ",", -1);
		amps_muted = g_array_new (FALSE, FALSE, sizeof (MutedControl));
		for (int i = 0; names[i]; i++) {
			const char *name = g_strstrip (names[i]);
			if (!*name)
				continue;
			snd_ctl_elem_id_set_name (id, name);
			snd_ctl_elem_info_set_id (info, id);
			if ((r = snd_ctl_elem_info (ctl, info)) < 0 ||
			    snd_ctl_elem_info_get_type (info) != SND_CTL_ELEM_TYPE_INTEGER) {
				g_warning ("ALSA control '%s' on %s: %s", name, card,
					   r < 0 ? snd_strerror (r) : "not an integer");
				continue;
			}
			snd_ctl_elem_value_set_id (v, id);
			if ((r = snd_ctl_elem_read (ctl, v)) < 0)
				continue;
			MutedControl m = { g_strdup (name), snd_ctl_elem_value_get_integer (v, 0) };
			long min = snd_ctl_elem_info_get_min (info);
			for (unsigned c = 0; c < snd_ctl_elem_info_get_count (info); c++)
				snd_ctl_elem_value_set_integer (v, c, min);
			if ((r = snd_ctl_elem_write (ctl, v)) < 0) {
				g_warning ("Muting '%s': %s", name, snd_strerror (r));
				g_free (m.name);
				continue;
			}
			g_array_append_val (amps_muted, m);
			g_message ("'%s' %ld -> %ld for the call", name, m.value, min);
		}
	} else {
		for (guint i = 0; i < amps_muted->len; i++) {
			MutedControl *m = &g_array_index (amps_muted, MutedControl, i);
			snd_ctl_elem_id_set_name (id, m->name);
			snd_ctl_elem_info_set_id (info, id);
			snd_ctl_elem_value_set_id (v, id);
			if (snd_ctl_elem_info (ctl, info) >= 0) {
				for (unsigned c = 0; c < snd_ctl_elem_info_get_count (info); c++)
					snd_ctl_elem_value_set_integer (v, c, m->value);
				if ((r = snd_ctl_elem_write (ctl, v)) < 0)
					g_warning ("Restoring '%s': %s", m->name, snd_strerror (r));
			}
			g_free (m->name);
		}
		g_array_free (amps_muted, TRUE);
		amps_muted = NULL;
	}
	snd_ctl_elem_value_free (v);
	snd_ctl_elem_info_free (info);
	snd_ctl_elem_id_free (id);
	snd_ctl_close (ctl);
}

/* a boolean mixer control: 1 or 0, -1 if the card has none by that name */
static int
ctl_get_bool (snd_ctl_t *ctl, const char *name)
{
	snd_ctl_elem_id_t *id = NULL;
	snd_ctl_elem_value_t *v = NULL;
	snd_ctl_elem_id_malloc (&id);
	snd_ctl_elem_value_malloc (&v);
	snd_ctl_elem_id_set_interface (id, SND_CTL_ELEM_IFACE_MIXER);
	snd_ctl_elem_id_set_name (id, name);
	snd_ctl_elem_value_set_id (v, id);
	int r = snd_ctl_elem_read (ctl, v) < 0 ? -1 : snd_ctl_elem_value_get_boolean (v, 0);
	snd_ctl_elem_value_free (v);
	snd_ctl_elem_id_free (id);
	return r;
}

static snd_pcm_t *
open_pcm (const char *dev, snd_pcm_stream_t dir, snd_pcm_format_t fmt, int ch, int rate)
{
	snd_pcm_t *p = NULL;
	/* non-blocking: the voice DMAs only run while the modem has a call up,
	 * so after a hangup a blocking read or write could wait for ever and
	 * take the teardown with it */
	int r = snd_pcm_open (&p, dev, dir, SND_PCM_NONBLOCK);
	if (r < 0) {
		g_warning ("Opening %s for %s: %s", dev, dir == SND_PCM_STREAM_CAPTURE ? "capture" : "playback", snd_strerror (r));
		return NULL;
	}
	/* 20 ms periods, 80 ms of buffer: enough to ride out a scheduling hiccup */
	if ((r = snd_pcm_set_params (p, fmt, SND_PCM_ACCESS_RW_INTERLEAVED, ch, rate, 0, 80000)) < 0) {
		g_warning ("%s: %d ch %d Hz %s: %s", dev, ch, rate, snd_pcm_format_name (fmt), snd_strerror (r));
		snd_pcm_close (p);
		return NULL;
	}
	return p;
}

/* ---- send ---- */

static void
put16 (guint8 *p, guint16 v) { p[0] = v >> 8; p[1] = v & 0xff; }
static void
put32 (guint8 *p, guint32 v) { p[0] = v >> 24; p[1] = (v >> 16) & 0xff; p[2] = (v >> 8) & 0xff; p[3] = v & 0xff; }

typedef struct {
	guint16 seq;
	guint32 ts, ssrc;
	gboolean first;
	G722State enc;
} TxState;

static void
send_frame (RtpSession *s, TxState *t, const guint8 *payload, int n)
{
	guint8 pkt[12 + MAX_PAYLOAD + SRTP_TRAILER];
	if (n > MAX_PAYLOAD)
		return;
	pkt[0] = 0x80;
	pkt[1] = (guint8)((t->first ? 0x80 : 0) | (s->pt & 0x7f));
	put16 (pkt + 2, t->seq++);
	put32 (pkt + 4, t->ts);
	put32 (pkt + 8, t->ssrc);
	memcpy (pkt + 12, payload, n);
	t->first = FALSE;
	/* 20 ms at the 8 kHz RTP clock, G.722 included (RFC 3551 §4.5.2) */
	t->ts += 160;
	int len = 12 + n;
#ifdef HAVE_SRTP
	if (s->srtp && srtp_protect (s->tx_srtp, pkt, &len) != srtp_err_status_ok)
		return;
#endif
	struct sockaddr_in dst;
	g_mutex_lock (&s->lock);
	dst = s->remote;
	g_mutex_unlock (&s->lock);
	if (sendto (s->fd, pkt, len, 0, (struct sockaddr *)&dst, sizeof dst) == len)
		atomic_fetch_add (&s->tx_packets, 1);
}

static int
encode (RtpSession *s, TxState *t, const int16_t *pcm, int n, guint8 *out)
{
	if (s->pt == PT_G722) {
		g722_encode (&t->enc, pcm, n, out);
		return n / 2;
	}
	for (int i = 0; i < n; i++)
		out[i] = s->pt == PT_PCMU ? ulaw_encode (pcm[i]) : alaw_encode (pcm[i]);
	return n;
}

static void
pace (struct timespec *next, int ms)
{
	next->tv_nsec += ms * 1000000L;
	if (next->tv_nsec >= 1000000000L) {
		next->tv_sec++;
		next->tv_nsec -= 1000000000L;
	}
	clock_nanosleep (CLOCK_MONOTONIC, TIMER_ABSTIME, next, NULL);
}

/* how long a call may take to bring its audio up before the PCMs open anyway */
#define CALL_AUDIO_WAIT_MS 10000

/*
 * The S10e's voice DMAs are the calliope firmware's, and exynos-callaudio
 * boots it only once the modem has the call connected, a second or so after
 * the answer. With our PCMs already open while it boots, it never starts
 * them: the call stays silent both ways. So wait for the call's audio
 * ("Call Active") first, sending paced silence meanwhile.
 */
static void
wait_call_audio (RtpSession *s, TxState *t, int16_t *frame, guint8 *payload, int per_frame)
{
	snd_ctl_t *ctl = NULL;
	if (!s->active || !*s->active || (!(s->cap_dev && *s->cap_dev) && !(s->pb_dev && *s->pb_dev)))
		return;
	int r = snd_ctl_open (&ctl, s->ctl_card, 0);
	if (r < 0) {
		g_warning ("%s: %s", s->ctl_card, snd_strerror (r));
		return;
	}
	struct timespec next;
	clock_gettime (CLOCK_MONOTONIC, &next);
	int waited = 0, on;
	while ((on = ctl_get_bool (ctl, s->active)) == 0 && waited < CALL_AUDIO_WAIT_MS && atomic_load (&s->running)) {
		pace (&next, 20);
		memset (frame, 0, sizeof (int16_t) * per_frame);
		send_frame (s, t, payload, encode (s, t, frame, per_frame, payload));
		waited += 20;
	}
	if (on < 0)
		g_warning ("ALSA control '%s' on %s not found: opening the call PCMs now", s->active, s->ctl_card);
	else if (!on && atomic_load (&s->running))
		g_warning ("No '%s' after %d s: opening the call PCMs anyway", s->active, waited / 1000);
	else if (on)
		g_message ("'%s' after %d ms", s->active, waited);
	snd_ctl_close (ctl);
}

static void
open_audio (RtpSession *s)
{
	const RtpAudioConfig *ac = &s->ac;
	if (atomic_load (&s->running)) {
		s->cap = s->cap_dev && *s->cap_dev ? open_pcm (s->cap_dev, SND_PCM_STREAM_CAPTURE, SND_PCM_FORMAT_S16_LE,
						 ac->capture_channels, ac->capture_rate) : NULL;
		s->pb = s->pb_dev && *s->pb_dev ? open_pcm (s->pb_dev, SND_PCM_STREAM_PLAYBACK, SND_PCM_FORMAT_S32_LE,
					       ac->playback_channels, ac->playback_rate) : NULL;
		if (s->pb)
			uplink_switch (s, TRUE);
		g_message ("Call audio: capture %s, playback %s (channel %d, %+d dB, agent volume %+d, %d-bit, stretch %d)",
			   s->cap ? s->cap_dev : "OFF (silence)", s->pb ? s->pb_dev : "OFF",
			   s->ac.playback_channel, s->ac.playback_gain_db, s->ac.agent_volume, s->ac.playback_width,
			   s->ac.playback_stretch);
	}
	atomic_store (&s->audio_up, true);
}

static gpointer
tx_thread (gpointer data)
{
	RtpSession *s = data;
	TxState t = { 0 };
	g722_init (&t.enc);
	guint32 r[3];
	if (getrandom (r, sizeof r, 0) != sizeof r)
		for (int i = 0; i < 3; i++)
			r[i] = g_random_int ();
	t.seq = (guint16)r[0];
	t.ts = r[1];
	t.ssrc = r[2];
	t.first = TRUE;

	int enc_rate = s->pt == PT_G722 ? 16000 : 8000, per_frame = enc_rate / 50;
	int16_t frame[320];
	guint8 payload[320];

	/* the first packet out opens the NAT mapping the far end's media has
	 * to come back through; silence, so nobody hears it */
	memset (frame, 0, sizeof frame);
	send_frame (s, &t, payload, encode (s, &t, frame, per_frame, payload));

	wait_call_audio (s, &t, frame, payload, per_frame);
	open_audio (s);

	if (!s->cap) {
		/* no capture: keep a paced silent stream, which is what the far
		 * end's own media timeout and symmetric-RTP latching want */
		struct timespec next;
		clock_gettime (CLOCK_MONOTONIC, &next);
		while (atomic_load (&s->running)) {
			pace (&next, 20);
			memset (frame, 0, sizeof frame);
			send_frame (s, &t, payload, encode (s, &t, frame, per_frame, payload));
		}
		return NULL;
	}

	int in_frames = s->ac.capture_rate / 50, ch = MAX (1, s->ac.capture_channels);
	int16_t *raw = g_new (int16_t, in_frames * ch);
	int16_t *mono = g_new (int16_t, in_frames);
	int16_t *res = g_new (int16_t, in_frames * enc_rate / s->ac.capture_rate + 8);
	int16_t *fifo = g_new (int16_t, per_frame * 4);
	int fill = 0;
	Resampler *down = resampler_new (s->ac.capture_rate, enc_rate);
	/* Q16 */
	int64_t gain = (int64_t)lround (65536.0 * pow (10.0, s->ac.capture_gain_db / 20.0));
	gint64 last_send = g_get_monotonic_time ();
	snd_pcm_start (s->cap);
	while (atomic_load (&s->running)) {
		int w = snd_pcm_wait (s->cap, 100);
		if (w == 0) {
			/* the tap has gone quiet: keep the stream going in silence
			 * rather than let the far end's media timeout fire */
			gint64 now = g_get_monotonic_time ();
			memset (frame, 0, sizeof frame);
			for (; now - last_send >= 20000; last_send += 20000)
				send_frame (s, &t, payload, encode (s, &t, frame, per_frame, payload));
			continue;
		}
		snd_pcm_sframes_t got = w < 0 ? w : snd_pcm_readi (s->cap, raw, in_frames);
		if (got == -EAGAIN)
			continue;
		if (got < 0) {
			if (snd_pcm_recover (s->cap, (int)got, 1) < 0) {
				g_warning ("Capture: %s", snd_strerror ((int)got));
				g_usleep (20000);
			} else {
				snd_pcm_start (s->cap);
			}
			continue;
		}
		for (int i = 0; i < got; i++) {
			int64_t v;
			if (s->ac.capture_channel >= 0 && s->ac.capture_channel < ch) {
				v = raw[i * ch + s->ac.capture_channel];
			} else {
				int sum = 0;
				for (int c = 0; c < ch; c++)
					sum += raw[i * ch + c];
				v = sum / ch;
			}
			mono[i] = (int16_t)CLAMP (v * gain >> 16, INT16_MIN, INT16_MAX);
		}
		atomic_store (&s->cap_rms, rms (mono, (int)got));
		int n = resampler_process (down, mono, (int)got, res);
		for (int i = 0; i < n; i++) {
			fifo[fill++] = res[i];
			if (fill == per_frame) {
				send_frame (s, &t, payload, encode (s, &t, fifo, per_frame, payload));
				fill = 0;
				last_send = g_get_monotonic_time ();
			}
		}
	}
	resampler_free (down);
	g_free (raw);
	g_free (mono);
	g_free (res);
	g_free (fifo);
	return NULL;
}

/* ---- receive ---- */

static void
on_dtmf_begin (char d, gpointer ud)
{
	RtpSession *s = ud;
	g_message ("DTMF '%c' → GSM (RFC 4733)", d);
	g_mutex_lock (&s->lock);
	dtmf_tone_begin (&s->tone, d, mono_ms ());
	g_mutex_unlock (&s->lock);
}

static void
on_dtmf_end (char d, int ms, gpointer ud)
{
	RtpSession *s = ud;
	(void)ms;
	g_mutex_lock (&s->lock);
	dtmf_tone_end (&s->tone, d, mono_ms ());
	g_mutex_unlock (&s->lock);
}

static void
jitter_push (RtpSession *s, int pt, const guint8 *p, int n)
{
	g_mutex_lock (&s->jlock);
	if (s->qcount == JITTER_CAPACITY) {
		s->qhead = (s->qhead + 1) % JITTER_CAPACITY;	/* drop the oldest */
		s->qcount--;
		atomic_fetch_add (&s->rx_dropped, 1);
	}
	Frame *f = &s->q[(s->qhead + s->qcount) % JITTER_CAPACITY];
	f->pt = pt;
	f->len = n;
	memcpy (f->data, p, n);
	s->qcount++;
	g_cond_signal (&s->jcond);
	g_mutex_unlock (&s->jlock);
}

static gpointer
rx_thread (gpointer data)
{
	RtpSession *s = data;
	guint8 buf[2048];
	while (atomic_load (&s->running)) {
		struct pollfd pfd = { .fd = s->fd, .events = POLLIN };
		if (poll (&pfd, 1, 100) <= 0)
			continue;
		struct sockaddr_in from;
		socklen_t fl = sizeof from;
		ssize_t r = recvfrom (s->fd, buf, sizeof buf, 0, (struct sockaddr *)&from, &fl);
		if (r < 12)
			continue;
		int len = (int)r;
#ifdef HAVE_SRTP
		/* authenticate before parsing: a forgery, a replay or a key
		 * mismatch must never reach the jitter queue */
		if (s->srtp && srtp_unprotect (s->rx_srtp, buf, &len) != srtp_err_status_ok) {
			int n = atomic_fetch_add (&s->srtp_fail, 1) + 1;
			if (n == 1 || n % 100 == 0)
				g_warning ("SRTP auth failed on %d packet(s) — wrong key or forged", n);
			continue;
		}
#endif
		if ((buf[0] >> 6) != 2)
			continue;
		int cc = buf[0] & 0x0f, off = 12 + 4 * cc;
		if (buf[0] & 0x10) {	/* header extension */
			if (off + 4 > len)
				continue;
			off += 4 + 4 * ((buf[off + 2] << 8) | buf[off + 3]);
		}
		if (buf[0] & 0x20 && len > off)	/* padding */
			len -= buf[len - 1];
		if (off > len)
			continue;
		int pt = buf[1] & 0x7f;
		guint32 ts = ((guint32)buf[4] << 24) | ((guint32)buf[5] << 16) | ((guint32)buf[6] << 8) | buf[7];

		/* symmetric RTP: answer where the media actually comes from */
		g_mutex_lock (&s->lock);
		if (!s->latched) {
			s->latched = TRUE;
			s->remote = from;
			g_mutex_unlock (&s->lock);
			char ip[INET_ADDRSTRLEN];
			inet_ntop (AF_INET, &from.sin_addr, ip, sizeof ip);
			g_message ("Symmetric RTP: latched to %s:%d (first pt=%d len=%d)", ip, ntohs (from.sin_port), pt,
				   len - off);
		} else {
			g_mutex_unlock (&s->lock);
		}
		atomic_store (&s->last_rx, g_get_monotonic_time ());
		atomic_fetch_add (&s->rx_packets, 1);
		/* telephone-event first: never into the jitter queue, never dropped */
		if ((pt == s->te_pt || pt == PT_TELEPHONE_EVENT) && pt != s->pt) {
			atomic_fetch_add (&s->dtmf_packets, 1);
			dtmf_detector_packet (&s->det, ts, buf + off, len - off);
			continue;
		}
		if (pt == s->pt || pt == PT_PCMA || pt == PT_G722)
			jitter_push (s, pt, buf + off, len - off);
	}
	dtmf_detector_flush (&s->det);
	return NULL;
}

/* ---- playback ---- */

static void
write_all (RtpSession *s, const int32_t *wire, int n, int ch)
{
	int off = 0;
	while (off < n && atomic_load (&s->running)) {
		snd_pcm_sframes_t w = snd_pcm_writei (s->pb, wire + off * ch, n - off);
		if (w == -EAGAIN) {
			snd_pcm_wait (s->pb, 100);
			continue;
		}
		if (w < 0) {
			if (snd_pcm_recover (s->pb, (int)w, 1) < 0) {
				g_warning ("Playback: %s", snd_strerror ((int)w));
				g_usleep (20000);
				return;
			}
			continue;
		}
		off += (int)w;
	}
}

static gpointer
play_thread (gpointer data)
{
	RtpSession *s = data;
	int rate = s->ac.playback_rate, ch = MAX (1, s->ac.playback_channels);
	int frame = rate / 50, only = s->ac.playback_channel;
	/* a 24-bit sink reads the low 3 bytes of each word: the top byte is
	 * the sign only */
	int shift = s->ac.playback_width - 16;
	int64_t peak = ((int64_t)1 << (s->ac.playback_width - 1)) - 1;
	/* Q16; the uplink is a mic input with its own gain, and a line-level
	 * voice there clips */
	int64_t gain = (int64_t)lround (65536.0 * pow (10.0, s->ac.playback_gain_db / 20.0) *
				       rtp_agent_volume_factor (s->ac.agent_volume));
	/* A sink that reads a 4 ch frame as two 2 ch ones at twice the rate:
	 * stretch 2 resamples to rate x 2 and puts consecutive samples in
	 * the frame's channel groups ([a0 a0 a1 a1]), which a real 4 ch
	 * reader sees as the voice at the PCM's rate. */
	int stretch = ch % MAX (1, s->ac.playback_stretch) ? 1 : MAX (1, s->ac.playback_stretch);
	int group = ch / stretch, srate = rate * stretch;
	G722State dec;
	g722_init (&dec);
	Resampler *up16 = resampler_new (16000, srate), *up8 = resampler_new (8000, srate);
	int16_t pcm[MAX_PAYLOAD * 2];
	int16_t *out = g_new (int16_t, (MAX_PAYLOAD * 2 * 6 + 16) * stretch);
	/* playback_test: a known signal in place of the far end, to tell the
	 * uplink's damage from the SIP side's */
	g_autofree char *test = NULL;
	gsize test_len = 0, test_pos = 0;
	Resampler *uptest = NULL;
	if (s->ac.playback_test) {
		g_autoptr (GError) err = NULL;
		if (g_file_get_contents (s->ac.playback_test, &test, &test_len, &err) && test_len >= 1920) {
			test_len /= 2;
			uptest = resampler_new (48000, srate);
			g_message ("Playing %s (%.1f s) in place of the far end", s->ac.playback_test,
				   test_len / 48000.0);
		} else {
			g_warning ("playback_test: %s", err ? err->message : "too short");
			g_clear_pointer (&test, g_free);
		}
	}
	int32_t *wire = g_new (int32_t, (MAX_PAYLOAD * 2 * 6 + 16) * ch);
	Frame f;

	/* the tx thread opens the PCMs once the call's audio is up; what
	 * arrives before that is stale by then */
	while (atomic_load (&s->running) && !atomic_load (&s->audio_up))
		g_usleep (10000);
	g_mutex_lock (&s->jlock);
	s->qcount = 0;
	g_mutex_unlock (&s->jlock);

	if (s->pb) {
		/* 40 ms of silence first, so the first late packet is not an underrun */
		memset (wire, 0, sizeof (int32_t) * frame * 2 * ch);
		write_all (s, wire, frame * 2, ch);
	}
	while (atomic_load (&s->running)) {
		/* how much the device still has queued: below 40 ms a silent frame
		 * goes in rather than waiting for a packet that may not come */
		int queued_ms = 60;
		snd_pcm_sframes_t delay;
		if (s->pb && snd_pcm_delay (s->pb, &delay) == 0)
			queued_ms = (int)(delay * 1000 / rate);
		gboolean have = FALSE;
		int n = 0;
		if (uptest) {
			g_mutex_lock (&s->jlock);
			s->qcount = 0;		/* the far end is not played */
			g_mutex_unlock (&s->jlock);
			/* no gate on queued_ms: the stream starts only once its
			 * buffer is full, and write_all waits for room */
			int16_t chunk[960];
			for (int i = 0; i < 960; i++, test_pos = (test_pos + 1) % test_len)
				chunk[i] = ((const int16_t *)test)[test_pos];
			n = resampler_process (uptest, chunk, 960, out);
			atomic_store (&s->play_rms, rms (out, n));
			atomic_fetch_add (&s->play_frames, 1);
			goto render;
		}
		g_mutex_lock (&s->jlock);
		if (!s->qcount && queued_ms >= 40) {
			gint64 until = g_get_monotonic_time () + JITTER_POLL_MS * 1000;
			while (!s->qcount && atomic_load (&s->running))
				if (!g_cond_wait_until (&s->jcond, &s->jlock, until))
					break;
		}
		if (s->qcount) {
			f = s->q[s->qhead];
			s->qhead = (s->qhead + 1) % JITTER_CAPACITY;
			s->qcount--;
			have = TRUE;
			if (s->qcount > JITTER_HIGH_WATER) {
				s->qhead = (s->qhead + 1) % JITTER_CAPACITY;
				s->qcount--;
				atomic_fetch_add (&s->trimmed, 1);
			}
		}
		g_mutex_unlock (&s->jlock);

		if (have) {
			if (f.pt == PT_G722) {
				g722_decode (&dec, f.data, f.len, pcm);
				n = resampler_process (up16, pcm, f.len * 2, out);
			} else {
				for (int i = 0; i < f.len; i++)
					pcm[i] = f.pt == PT_PCMU ? ulaw_decode (f.data[i]) : alaw_decode (f.data[i]);
				n = resampler_process (up8, pcm, f.len, out);
			}
			atomic_store (&s->play_rms, rms (out, n));
			atomic_fetch_add (&s->play_frames, 1);
		} else {
			if (queued_ms >= 60 && s->pb)
				continue;	/* enough buffered; do not grow the latency */
			atomic_fetch_add (&s->underruns, 1);
			atomic_store (&s->play_rms, 0);
			n = frame * stretch;
			memset (out, 0, sizeof (int16_t) * n);
		}

render:
		if (atomic_load (&s->agent_muted))
			memset (out, 0, sizeof (int16_t) * n);
		/* DTMF from the SIP side, in band: cpd has no +VTS, so the tone
		 * replaces the far end's audio for as long as it sounds */
		g_mutex_lock (&s->lock);
		char digit = dtmf_tone_at (&s->tone, mono_ms ());
		dtmf_tone_render (&s->tone, digit, srate, out, n);
		g_mutex_unlock (&s->lock);

		if (!s->pb)
			continue;
		int frames = n / stretch;	/* the ratios are whole: nothing left over */
		for (int i = 0; i < frames; i++)
			for (int c = 0; c < ch; c++) {
				int32_t v = (int32_t)CLAMP (((int64_t)out[i * stretch + c / group] << shift) * gain >> 16,
							    -peak - 1, peak);
				wire[i * ch + c] = only < 0 || c % group == only ? v : 0;
			}
		write_all (s, wire, frames, ch);
	}
	resampler_free (up16);
	resampler_free (up8);
	if (uptest)
		resampler_free (uptest);
	g_free (out);
	g_free (wire);
	return NULL;
}

/* ---- stats and the media timeout, on the main loop ---- */

static gboolean
stats_tick (gpointer data)
{
	RtpSession *s = data;
	s->stats_tick++;
	/* close together at first: a 16 s call must still leave a line */
	if (s->stats_tick <= 3 || s->stats_tick % 3 == 0) {
		int jb;
		g_mutex_lock (&s->jlock);
		jb = s->qcount;
		g_mutex_unlock (&s->jlock);
		g_message ("RTP: tx=%d rx=%d play=%d capRMS=%d playRMS=%d jbuf=%d drop=%d under=%d trim=%d dtmf=%d%s",
			   atomic_load (&s->tx_packets), atomic_load (&s->rx_packets), atomic_load (&s->play_frames),
			   atomic_load (&s->cap_rms), atomic_load (&s->play_rms), jb, atomic_load (&s->rx_dropped),
			   atomic_load (&s->underruns), atomic_load (&s->trimmed), atomic_load (&s->dtmf_packets),
			   s->srtp ? " srtp" : "");
	}
	gint64 last = atomic_load (&s->last_rx);
	gint64 since = g_get_monotonic_time () - (last ? last : s->started);
	if (!s->timeout_fired && since > RTP_TIMEOUT_US) {
		s->timeout_fired = TRUE;
		g_warning ("RTP timeout: nothing received for %" G_GINT64_FORMAT "s", since / G_USEC_PER_SEC);
		if (s->cb.timeout)
			s->cb.timeout (s->ud);
	}
	return G_SOURCE_CONTINUE;
}

/* ---- start / stop ---- */

gboolean
rtp_session_start (RtpSession *s, const RtpAudioConfig *ac, const char *remote_ip, const char *fallback_ip,
		   int remote_port, int pt, int te_pt, const RtpCallbacks *cb, gpointer ud, GError **err)
{
	struct in_addr a;
	if (!remote_ip || inet_pton (AF_INET, remote_ip, &a) != 1) {
		if (!fallback_ip || inet_pton (AF_INET, fallback_ip, &a) != 1) {
			g_set_error (err, G_IO_ERROR, G_IO_ERROR_INVALID_ARGUMENT, "No usable RTP address (%s)",
				     remote_ip ? remote_ip : "none");
			return FALSE;
		}
		g_message ("SDP address %s is not an IPv4 literal — sending media to the server, %s",
			   remote_ip ? remote_ip : "(none)", fallback_ip);
	}
	s->remote.sin_family = AF_INET;
	s->remote.sin_addr = a;
	s->remote.sin_port = htons (remote_port);
	s->ac = *ac;
	s->cap_dev = g_strdup (ac->capture_dev);
	s->pb_dev = g_strdup (ac->playback_dev);
	s->ctl_card = g_strdup (ac->control_card ? ac->control_card : "hw:0");
	s->uplink = g_strdup (ac->uplink_switch);
	s->active = g_strdup (ac->call_active);
	s->ac.capture_dev = s->cap_dev;
	s->ac.playback_dev = s->pb_dev;
	s->ac.control_card = s->ctl_card;
	s->ac.uplink_switch = s->uplink;
	s->ac.call_active = s->active;
	s->ac.mute = NULL;	/* the bridge's: rtp_mute_amps */
	s->pt = pt == PT_PCMU || pt == PT_PCMA ? pt : PT_G722;
	s->te_pt = te_pt > 0 ? te_pt : PT_TELEPHONE_EVENT;
	if (cb)
		s->cb = *cb;
	s->ud = ud;
	dtmf_detector_init (&s->det, on_dtmf_begin, on_dtmf_end, s);

	char ip[INET_ADDRSTRLEN];
	inet_ntop (AF_INET, &a, ip, sizeof ip);
	g_message ("RTP %d -> %s:%d pt=%d te=%d%s", s->port, ip, remote_port, s->pt, s->te_pt, s->srtp ? " SRTP" : "");

	s->started = g_get_monotonic_time ();
	atomic_store (&s->running, true);
	s->rx = g_thread_new ("rtp-rx", rx_thread, s);
	s->play = g_thread_new ("rtp-play", play_thread, s);
	s->tx = g_thread_new ("rtp-tx", tx_thread, s);
	s->stats_timer = g_timeout_add_seconds (5, stats_tick, s);
	return TRUE;
}

void
rtp_session_set_agent_muted (RtpSession *s, gboolean muted)
{
	atomic_store (&s->agent_muted, muted);
}

void
rtp_session_dtmf_pulse (RtpSession *s, char digit, int duration_ms)
{
	g_mutex_lock (&s->lock);
	dtmf_tone_pulse (&s->tone, digit, duration_ms, mono_ms ());
	g_mutex_unlock (&s->lock);
	g_message ("DTMF '%c' → GSM (INFO, %d ms)", digit, duration_ms);
}

void
rtp_session_free (RtpSession *s)
{
	if (!s)
		return;
	if (s->stats_timer)
		g_source_remove (s->stats_timer);
	atomic_store (&s->running, false);
	g_mutex_lock (&s->jlock);
	g_cond_broadcast (&s->jcond);
	g_mutex_unlock (&s->jlock);
	if (s->tx)
		g_thread_join (s->tx);
	if (s->rx)
		g_thread_join (s->rx);
	if (s->play)
		g_thread_join (s->play);
	if (s->tx || s->rx)
		g_message ("RTP stopped: tx=%d rx=%d play=%d drop=%d under=%d trim=%d dtmf=%d",
			   atomic_load (&s->tx_packets), atomic_load (&s->rx_packets), atomic_load (&s->play_frames),
			   atomic_load (&s->rx_dropped), atomic_load (&s->underruns), atomic_load (&s->trimmed),
			   atomic_load (&s->dtmf_packets));
	if (s->pb) {
		snd_pcm_drop (s->pb);
		snd_pcm_close (s->pb);
		uplink_switch (s, FALSE);
	}
	if (s->cap)
		snd_pcm_close (s->cap);
#ifdef HAVE_SRTP
	if (s->tx_srtp)
		srtp_dealloc (s->tx_srtp);
	if (s->rx_srtp)
		srtp_dealloc (s->rx_srtp);
#endif
	close (s->fd);
	g_mutex_clear (&s->lock);
	g_mutex_clear (&s->jlock);
	g_cond_clear (&s->jcond);
	g_free (s->cap_dev);
	g_free (s->pb_dev);
	g_free (s->ctl_card);
	g_free (s->uplink);
	g_free (s->active);
	g_free (s);
}
