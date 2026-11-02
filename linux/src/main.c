/* gsm2sipd: the gsm2sip gateway as a Linux daemon.  The Android app's
 * GatewayService, minus the UI: read the config, find the modem, register,
 * bridge. */
#define G_LOG_DOMAIN "gw"
#include "bridge.h"
#include "stun.h"
#include <glib-unix.h>
#include <locale.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>

#ifndef GSM2SIP_VERSION
#define GSM2SIP_VERSION "0"
#endif
#define DEFAULT_CONFIG "/etc/gsm2sip/gateway.conf"

static struct {
	GMainLoop *loop;
	Bridge *bridge;
	SipClient *sip;
	Modem *modem;
	gboolean use_stun, stunning, stopping, verbose;
	gint64 stop_deadline;
} app;

/* ---- logging: one line per message, no timestamps under journald ---- */

static GLogWriterOutput
log_writer (GLogLevelFlags level, const GLogField *fields, gsize n, gpointer data)
{
	(void)data;
	if ((level & G_LOG_LEVEL_DEBUG) && !app.verbose)
		return G_LOG_WRITER_HANDLED;
	const char *msg = NULL, *domain = NULL;
	for (gsize i = 0; i < n; i++) {
		if (!strcmp (fields[i].key, "MESSAGE"))
			msg = fields[i].value;
		else if (!strcmp (fields[i].key, "GLIB_DOMAIN"))
			domain = fields[i].value;
	}
	const char *tag = level & (G_LOG_LEVEL_ERROR | G_LOG_LEVEL_CRITICAL) ? "E"
			  : level & G_LOG_LEVEL_WARNING ? "W"
			  : level & G_LOG_LEVEL_DEBUG ? "D" : "I";
	if (g_getenv ("JOURNAL_STREAM")) {
		fprintf (stderr, "%s %s: %s\n", tag, domain ? domain : "gsm2sipd", msg ? msg : "");
	} else {
		g_autoptr (GDateTime) now = g_date_time_new_now_local ();
		g_autofree char *ts = g_date_time_format (now, "%H:%M:%S.%f");
		ts[12] = 0;	/* milliseconds */
		fprintf (stderr, "%s %s %s: %s\n", ts, tag, domain ? domain : "gsm2sipd", msg ? msg : "");
	}
	return G_LOG_WRITER_HANDLED;
}

/* ---- config ---- */

static char *
kf_str (GKeyFile *kf, const char *group, const char *key, const char *def)
{
	g_autofree char *v = g_key_file_get_string (kf, group, key, NULL);
	if (v)
		g_strstrip (v);
	return v && *v ? g_steal_pointer (&v) : g_strdup (def);
}

static int
kf_int (GKeyFile *kf, const char *group, const char *key, int def)
{
	g_autoptr (GError) err = NULL;
	int v = g_key_file_get_integer (kf, group, key, &err);
	return err ? def : v;
}

static gboolean
kf_bool (GKeyFile *kf, const char *group, const char *key, gboolean def)
{
	g_autoptr (GError) err = NULL;
	gboolean v = g_key_file_get_boolean (kf, group, key, &err);
	return err ? def : v;
}

/* The handset, not the server, in User-Agent: with several gateways on one
 * PBX it is what tells them apart in the CDRs. */
static char *
handset_name (void)
{
	const char *host = g_get_host_name ();
	g_autofree char *name = NULL;
	if (host && *host && strcmp (host, "localhost"))
		name = g_strdup (host);
	else if (!g_file_get_contents ("/proc/device-tree/model", &name, NULL, NULL))
		name = g_strdup ("unknown");
	g_strstrip (name);
	/* one header token */
	for (char *p = name; *p; p++)
		if (g_ascii_isspace (*p))
			*p = '-';
	return g_steal_pointer (&name);
}

/* ---- STUN ---- */

static void on_stun (const char *ip, gpointer data);

/* a failed STUN is usually a network that is not up yet (the unit starts
 * early in boot): keep asking, or every call advertises the local address */
#define STUN_RETRY_S 30

static gboolean
stun_retry (gpointer data)
{
	(void)data;
	if (app.use_stun && !app.stunning && !app.stopping) {
		app.stunning = TRUE;
		stun_discover (NULL, on_stun, GINT_TO_POINTER (FALSE));
	}
	return G_SOURCE_REMOVE;
}

static void
on_stun (const char *ip, gpointer data)
{
	gboolean initial = GPOINTER_TO_INT (data);
	app.stunning = FALSE;
	if (!app.sip)
		return;
	if (ip) {
		sip_client_set_public_ip (app.sip, ip);
	} else {
		g_message ("STUN failed — advertising the local address, trying again in %d s", STUN_RETRY_S);
		g_timeout_add_seconds (STUN_RETRY_S, stun_retry, NULL);
	}
	if (initial)
		sip_client_start (app.sip);
}

static void
on_connection_lost (gpointer ud)
{
	(void)ud;
	/* the network may have changed: learn the public address again */
	if (app.use_stun && !app.stunning && !app.stopping) {
		app.stunning = TRUE;
		stun_discover (NULL, on_stun, GINT_TO_POINTER (FALSE));
	}
}

/* ---- shutdown ---- */

static gboolean
exit_poll (gpointer data)
{
	(void)data;
	/* the Hangup to ModemManager and the BYE/CANCEL have to actually leave */
	if ((app.modem && modem_pending (app.modem) > 0) && g_get_monotonic_time () < app.stop_deadline)
		return G_SOURCE_CONTINUE;
	g_main_loop_quit (app.loop);
	return G_SOURCE_REMOVE;
}

static gboolean
on_quit (gpointer data)
{
	if (app.stopping) {
		g_main_loop_quit (app.loop);
		return G_SOURCE_CONTINUE;
	}
	g_message ("%s — stopping", (const char *)data);
	app.stopping = TRUE;
	bridge_shutdown (app.bridge);
	if (app.sip)
		sip_client_stop (app.sip);
	app.stop_deadline = g_get_monotonic_time () + 2 * G_USEC_PER_SEC;
	g_timeout_add (300, exit_poll, NULL);
	return G_SOURCE_CONTINUE;
}

static gboolean
on_usr1 (gpointer data)
{
	(void)data;
	if (app.sip)
		sip_client_retry_now (app.sip);
	return G_SOURCE_CONTINUE;
}

int
main (int argc, char **argv)
{
	g_autofree char *config = NULL;
	gboolean version = FALSE;
	GOptionEntry entries[] = {
		{ "config", 'c', 0, G_OPTION_ARG_FILENAME, &config, "Configuration file (" DEFAULT_CONFIG ")", "PATH" },
		{ "verbose", 'v', 0, G_OPTION_ARG_NONE, &app.verbose, "Log every SIP message's first line", NULL },
		{ "version", 0, 0, G_OPTION_ARG_NONE, &version, "Print the version", NULL },
		{ NULL, 0, 0, 0, NULL, NULL, NULL },
	};
	setlocale (LC_ALL, "");
	g_autoptr (GOptionContext) oc = g_option_context_new ("- GSM to SIP gateway");
	g_option_context_add_main_entries (oc, entries, NULL);
	g_autoptr (GError) err = NULL;
	if (!g_option_context_parse (oc, &argc, &argv, &err)) {
		fprintf (stderr, "%s\n", err->message);
		return 2;
	}
	if (version) {
		printf ("gsm2sipd %s\n", GSM2SIP_VERSION);
		return 0;
	}
	g_log_set_writer_func (log_writer, NULL, NULL);
	signal (SIGPIPE, SIG_IGN);

	const char *path = config ? config : DEFAULT_CONFIG;
	g_autoptr (GKeyFile) kf = g_key_file_new ();
	if (!g_key_file_load_from_file (kf, path, G_KEY_FILE_NONE, &err)) {
		g_critical ("%s: %s", path, err->message);
		return 1;
	}
	struct stat st;
	if (stat (path, &st) == 0 && (st.st_mode & 077))
		g_warning ("%s is readable by others and holds the SIP password — chmod 600 it", path);

	g_autofree char *server = kf_str (kf, "sip", "server", NULL);
	g_autofree char *user = kf_str (kf, "sip", "user", NULL);
	/* as written: a password may begin or end with a space */
	g_autofree char *password = g_key_file_get_string (kf, "sip", "password", NULL);
	if (!server || !user || !password || !*password) {
		g_critical ("%s: [sip] server, user and password are required", path);
		return 1;
	}
	gboolean tls = kf_bool (kf, "sip", "tls", FALSE);
	g_autofree char *codec = kf_str (kf, "sip", "codec", "g722");
	if (strcmp (codec, "g722") && strcmp (codec, "g711") && strcmp (codec, "both")) {
		g_warning ("codec=%s is not g722, g711 or both — using g722", codec);
		g_free (codec);
		codec = g_strdup ("g722");
	}
	g_autofree char *public_ip = kf_str (kf, "sip", "public_ip", NULL);
	g_autofree char *own = kf_str (kf, "gateway", "own_number", "");
	g_autofree char *cap = kf_str (kf, "audio", "capture", "hw:0,2");
	g_autofree char *pb = kf_str (kf, "audio", "playback", "hw:0,3");
	g_autofree char *card = kf_str (kf, "audio", "control_card", "hw:0");
	g_autofree char *uplink = kf_str (kf, "audio", "uplink_switch", "Call Uplink Switch");
	g_autofree char *active = kf_str (kf, "audio", "call_active", "Call Active");
	g_autofree char *mute = kf_str (kf, "audio", "mute", "RCV Digital PCM Volume,SPK Digital PCM Volume");
	g_autofree char *chan = kf_str (kf, "audio", "capture_channel", "mix");
	g_autofree char *pbchan = kf_str (kf, "audio", "playback_channel", "all");
	g_autofree char *pbtest = kf_str (kf, "audio", "playback_test", NULL);
	app.use_stun = kf_bool (kf, "sip", "stun", TRUE) && !public_ip;

	g_autofree char *handset = handset_name ();
	g_autofree char *ua = g_strdup_printf ("gsm2sip v%s %s", GSM2SIP_VERSION, handset);

	BridgeConfig bc = {
		.own_number = own,
		.audio = {
			.capture_dev = cap,
			.capture_rate = kf_int (kf, "audio", "capture_rate", 32000),
			.capture_channels = kf_int (kf, "audio", "capture_channels", 2),
			.capture_channel = !strcmp (chan, "left") ? 0 : !strcmp (chan, "right") ? 1 : -1,
			.capture_gain_db = kf_int (kf, "audio", "capture_gain_db", 0),
			.playback_dev = pb,
			.playback_rate = kf_int (kf, "audio", "playback_rate", 48000),
			.playback_channels = kf_int (kf, "audio", "playback_channels", 2),
			.playback_channel = !strcmp (pbchan, "all") ? -1 : (int)g_ascii_strtoll (pbchan, NULL, 10),
			.playback_gain_db = kf_int (kf, "audio", "playback_gain_db", 0),
			.playback_width = kf_int (kf, "audio", "playback_width", 32) == 24 ? 24 : 32,
			.playback_stretch = kf_int (kf, "audio", "playback_stretch", 1),
			.playback_test = pbtest && *pbtest ? pbtest : NULL,
			.control_card = card,
			.uplink_switch = !strcmp (uplink, "none") ? "" : uplink,
			.call_active = !strcmp (active, "none") ? "" : active,
			.mute = !strcmp (mute, "none") ? "" : mute,
		},
	};
	SipConfig sc = {
		.server = server,
		.port = kf_int (kf, "sip", "port", tls ? 5061 : 5060),
		.user = user,
		.password = password,
		.tls = tls,
		.srtp = kf_bool (kf, "sip", "srtp", FALSE),
		.codec = codec,
		.local_port = kf_int (kf, "sip", "local_port", 5062),
		.public_ip = public_ip,
		.user_agent = ua,
	};
	app.loop = g_main_loop_new (NULL, FALSE);
	app.bridge = bridge_new (&bc);
	SipClientCallbacks scb = *bridge_sip_callbacks ();
	scb.connection_lost = on_connection_lost;
	app.sip = sip_client_new (&sc, &scb, app.bridge);
	g_message ("gsm2sipd %s: %s:%d over %s, codec %s, SRTP %s, STUN %s, UA \"%s\"", GSM2SIP_VERSION, server, sc.port,
		   tls ? "TLS" : "UDP", codec, sip_client_srtp_enabled (app.sip) ? "on" : "off", app.use_stun ? "on" : "off",
		   ua);
	/* from here the password lives in the SIP client only, and in no log */
	memset (password, 0, strlen (password));
	g_key_file_remove_key (kf, "sip", "password", NULL);
	app.modem = modem_new (bridge_modem_callbacks (), app.bridge);
	bridge_attach (app.bridge, app.sip, app.modem);

	if (app.use_stun) {
		app.stunning = TRUE;
		stun_discover (NULL, on_stun, GINT_TO_POINTER (TRUE));
	} else {
		sip_client_start (app.sip);
	}

	g_unix_signal_add (SIGTERM, on_quit, "SIGTERM");
	g_unix_signal_add (SIGINT, on_quit, "SIGINT");
	g_unix_signal_add (SIGUSR1, on_usr1, NULL);
	g_main_loop_run (app.loop);

	bridge_free (app.bridge);
	app.bridge = NULL;
	sip_client_free (app.sip);
	app.sip = NULL;
	modem_free (app.modem);
	app.modem = NULL;
	g_main_loop_unref (app.loop);
	g_message ("Stopped");
	return 0;
}
