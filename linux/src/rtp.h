/* RTP media between the modem's call audio and the SIP peer.  The port of
 * rtp/RtpSession.kt without the Android audio stack: the S10e taps the voice
 * path directly with two ALSA PCMs.
 *
 *   capture  (GSM downlink, the caller)  -> mono -> 16/8 kHz -> G.722/G.711 -> [S]RTP
 *   [S]RTP -> jitter queue -> decode -> 48 kHz -> playback (GSM uplink, to the caller)
 *
 * Three threads (send, receive, playback); everything they report back is
 * read from the main loop. */
#pragma once
#include <glib.h>

typedef struct RtpSession RtpSession;

typedef struct {
	const char *capture_dev;	/* "hw:0,2" */
	int capture_rate;		/* 32000 */
	int capture_channels;		/* 2 */
	int capture_channel;		/* -1 average, 0 left, 1 right */
	int capture_gain_db;		/* 0; positive turns the caller up for the SIP side, clamped */
	const char *playback_dev;	/* "hw:0,3" */
	int playback_rate;		/* 48000 */
	int playback_channels;		/* 2, S32_LE */
	int playback_channel;		/* -1 every channel, else only this one (the rest silent) */
	int playback_gain_db;		/* 0; negative turns what goes into the uplink down */
	int playback_width;		/* 32: samples MSB-aligned in the S32 word; 24: in its low 3 bytes */
	int playback_stretch;		/* 1; 2: each PCM frame carries two samples at twice the rate */
	const char *playback_test;	/* NULL; raw S16_LE mono 48 kHz played in a loop instead of the far end */
	const char *control_card;	/* "hw:0" */
	const char *uplink_switch;	/* "Call Uplink Switch"; NULL or "" for none */
	const char *call_active;	/* "Call Active": the PCMs open once it is on; NULL or "" opens them at once */
	const char *mute;		/* controls held at their minimum while the call audio is open, comma-separated */
} RtpAudioConfig;

typedef struct {
	/* No RTP for 30 s.  Main loop. */
	void (*timeout) (gpointer ud);
} RtpCallbacks;

/* Binds an even UDP port in 30000-40000 and keeps it, so the port put in
 * the SDP cannot be taken by anyone else before the media starts. */
RtpSession *rtp_session_new (GError **err);
int rtp_session_port (RtpSession *s);

/* tag_bits 80 or 32; keys are 30 bytes, key||salt.  Before start. */
gboolean rtp_session_set_srtp (RtpSession *s, int tag_bits, const guint8 *send_key, const guint8 *recv_key,
			       GError **err);

/* remote_ip may be NULL or a name; fallback_ip (the server) is used then. */
gboolean rtp_session_start (RtpSession *s, const RtpAudioConfig *ac, const char *remote_ip, const char *fallback_ip,
			    int remote_port, int pt, int te_pt, const RtpCallbacks *cb, gpointer ud, GError **err);

/* A key press from SIP INFO, played into the GSM uplink. */
void rtp_session_dtmf_pulse (RtpSession *s, char digit, int duration_ms);

/* Stops the threads, releases the PCMs, switches the uplink tap off. */
void rtp_session_free (RtpSession *s);
