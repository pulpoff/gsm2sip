/* gsm2sipd: the gsm2sip gateway as a Linux daemon.  The Android app's
 * GatewayService: read the config, find the modem, register, bridge — and
 * tell the GNOME app about it over D-Bus (service.c). */
#define G_LOG_DOMAIN "gw"
#include "bridge.h"
#include "service.h"
#include "stun.h"
#include <errno.h>
#include <gio/gunixfdlist.h>
#include <glib-unix.h>
#include <locale.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef GSM2SIP_VERSION
#define GSM2SIP_VERSION "0"
#endif
#define DEFAULT_CONFIG "/etc/gsm2sip/gateway.conf"
#define DEFAULT_STATE_DIR "/var/lib/gsm2sip"

static struct {
	GMainLoop *loop;
	Bridge *bridge;
	SipClient *sip;
	Modem *modem;
	GwRecents *recents;
	GwService *service;
	gboolean use_stun, stunning, stopping, verbose;
	gint64 stop_deadline;
	/* a restart is a re-exec: the one way to take up a new config that
	 * leaves no half-rebuilt SIP client behind */
	gboolean restart, restart_pending;
	char **argv;	/* as started */
	int awake_fd;	/* logind's sleep inhibitor, -1 = none */
	gboolean awake_wanted, awake_asking, awake_said_sleeps;
	guint awake_timer;
} app = { .awake_fd = -1 };

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
	if (!domain)
		domain = "gsm2sipd";
	if (!msg)
		msg = "";
	g_autoptr (GDateTime) now = g_date_time_new_now_local ();
	if (g_getenv ("JOURNAL_STREAM")) {
		fprintf (stderr, "%s %s: %s\n", tag, domain, msg);
	} else {
		g_autofree char *ts = g_date_time_format (now, "%H:%M:%S.%f");
		ts[12] = 0;	/* milliseconds */
		fprintf (stderr, "%s %s %s: %s\n", ts, tag, domain, msg);
	}
	/* the app's Logs page: the Android log's "HH:mm:ss  message" */
	g_autofree char *hms = g_date_time_format (now, "%H:%M:%S");
	g_autofree char *line = g_strdup_printf ("%s  %s: %s%s", hms, domain, *tag == 'E' ? "ERROR: " : *tag == 'W' ? "WARNING: " : "",
						 msg);
	gw_log_line (line);
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

/* ---- what the app sees ---- */

static void
on_registered (gpointer ud)
{
	bridge_sip_callbacks ()->registered (ud);
	gw_service_set_state (app.service, "registered");
}

static void
on_registration_failed (gpointer ud)
{
	bridge_sip_callbacks ()->registration_failed (ud);
	gw_service_set_state (app.service, "failed");
}

static void restart (void);

static void
on_bridge_state (BridgeState state, const char *info, gpointer ud)
{
	g_autofree char *name = g_ascii_strdown (bridge_state_name (state), -1);
	gw_service_set_call (app.service, name, info, bridge_call_number (app.bridge), bridge_call_incoming (app.bridge),
			     bridge_call_bridged_at (app.bridge), bridge_agent_muted (app.bridge));
	if (state == BRIDGE_IDLE && app.restart_pending)
		restart ();
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

static void
stop (const char *why)
{
	if (app.stopping) {
		g_main_loop_quit (app.loop);
		return;
	}
	g_message ("%s — stopping", why);
	app.stopping = TRUE;
	if (app.bridge)
		bridge_shutdown (app.bridge);
	if (app.sip)
		sip_client_stop (app.sip);
	app.stop_deadline = g_get_monotonic_time () + 2 * G_USEC_PER_SEC;
	g_timeout_add (300, exit_poll, NULL);
}

static gboolean
on_quit (gpointer data)
{
	app.restart = FALSE;
	stop (data);
	return G_SOURCE_CONTINUE;
}

/* Take up the config file as it now is.  A restart is a re-exec: the one
 * way to apply it that leaves no half-rebuilt SIP client behind. */
static void
restart (void)
{
	/* a call in progress is not dropped for a settings change: it waits */
	if (app.bridge && bridge_state (app.bridge) != BRIDGE_IDLE) {
		if (!app.restart_pending)
			g_message ("Restart waits for the call to end");
		app.restart_pending = TRUE;
		gw_service_set_restart_pending (app.service, TRUE);
		return;
	}
	app.restart_pending = FALSE;
	app.restart = TRUE;
	stop ("Restarting");
}

static gboolean
on_hup (gpointer data)
{
	restart ();
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

/* ---- the app's controls (service.c) ---- */

static void
op_config_changed (gpointer ud)
{
	restart ();
}

static void
op_enabled_changed (gboolean enabled, gpointer ud)
{
	/* switched off in so many words: the call in progress goes with it,
	 * and the phone has its modem back */
	if (!enabled && app.bridge)
		bridge_hangup (app.bridge);
	restart ();
}

static void
op_reconnect (gpointer ud)
{
	if (app.sip) {
		gw_service_set_state (app.service, "connecting");
		sip_client_retry_now (app.sip);
	}
}

static gboolean
op_hangup (gpointer ud)
{
	return app.bridge && bridge_hangup (app.bridge);
}

static void
op_set_agent_muted (gboolean muted, gpointer ud)
{
	if (app.bridge)
		bridge_set_agent_muted (app.bridge, muted);
}

static const GwServiceOps service_ops = {
	.config_changed = op_config_changed,
	.enabled_changed = op_enabled_changed,
	.reconnect = op_reconnect,
	.hangup = op_hangup,
	.set_agent_muted = op_set_agent_muted,
};

static void
reexec (void)
{
	fflush (stderr);
	/* the bus connection, sockets and PCMs of this run go with it */
	close_range (3, ~0U, 0);
	execv ("/proc/self/exe", app.argv);
	fprintf (stderr, "E gw: re-exec failed: %s\n", g_strerror (errno));
	_exit (1);
}

/* ---- keeping the phone awake ---- */

/* While the gateway is on, its calls and SMS arrive over SIP.  A phone whose
 * Wi-Fi wakes it may sleep: the dongle stays associated through a suspend and
 * a unicast frame (an INVITE, the server's OPTIONS) wakes the phone as a GSM
 * call does.  Such a device says so in /sys/class/net/<if>/device/power/wakeup.
 * Otherwise nothing wakes a sleeping phone for an INVITE and it stays up: a
 * logind block inhibitor, which a phone's own sleep policy (the S10e's
 * s10e-sleepd) honours as logind does.  It goes with the process: on exit,
 * and on a re-exec, whose new run takes it again if it is still needed.
 * Looked at again every 30 s: at boot the Wi-Fi driver may come up after us,
 * and a dropped link takes the wake with it. */
#define AWAKE_CHECK_S 30

static gboolean
network_wakes (void)
{
	g_autoptr (GDir) d = g_dir_open ("/sys/class/net", 0, NULL);
	const char *n;
	while (d && (n = g_dir_read_name (d))) {
		g_autofree char *wl = g_build_filename ("/sys/class/net", n, "wireless", NULL);
		g_autofree char *op = g_build_filename ("/sys/class/net", n, "operstate", NULL);
		g_autofree char *wk = g_build_filename ("/sys/class/net", n, "device", "power", "wakeup", NULL);
		g_autofree char *state = NULL, *wake = NULL;
		if (g_file_test (wl, G_FILE_TEST_IS_DIR) && g_file_get_contents (op, &state, NULL, NULL) &&
		    g_str_has_prefix (state, "up") && g_file_get_contents (wk, &wake, NULL, NULL) &&
		    g_str_has_prefix (wake, "enabled"))
			return TRUE;
	}
	return FALSE;
}

static void awake_update (void);

static void
on_inhibit (GObject *src, GAsyncResult *res, gpointer data)
{
	(void)data;
	g_autoptr (GError) err = NULL;
	g_autoptr (GUnixFDList) fds = NULL;
	g_autoptr (GVariant) v = g_dbus_connection_call_with_unix_fd_list_finish (G_DBUS_CONNECTION (src), &fds, res, &err);
	app.awake_asking = FALSE;
	if (!v) {
		g_warning ("Could not keep the phone awake: %s", err->message);
		return;
	}
	gint32 h = -1;
	g_variant_get (v, "(h)", &h);
	int fd = fds ? g_unix_fd_list_get (fds, h, &err) : -1;
	if (fd < 0) {
		g_warning ("Could not keep the phone awake: %s", err ? err->message : "no inhibitor fd");
		return;
	}
	app.awake_fd = fd;
	app.awake_said_sleeps = FALSE;
	g_message ("The phone stays awake while the gateway is on: its Wi-Fi cannot wake it");
	awake_update ();	/* the answer may come after the Wi-Fi did */
}

static void
stay_awake (void)
{
	g_autoptr (GError) err = NULL;
	g_autoptr (GDBusConnection) bus = g_bus_get_sync (G_BUS_TYPE_SYSTEM, NULL, &err);
	if (!bus) {
		g_warning ("Could not keep the phone awake: %s", err->message);
		return;
	}
	app.awake_asking = TRUE;
	g_dbus_connection_call_with_unix_fd_list (bus, "org.freedesktop.login1", "/org/freedesktop/login1",
						  "org.freedesktop.login1.Manager", "Inhibit",
						  g_variant_new ("(ssss)", "sleep", "gsm2sipd",
								 "Gateway mode: calls and SMS go over SIP", "block"),
						  G_VARIANT_TYPE ("(h)"), G_DBUS_CALL_FLAGS_NONE, -1, NULL, NULL, on_inhibit,
						  NULL);
}

static void
awake_update (void)
{
	if (!app.awake_wanted || network_wakes ()) {
		if (app.awake_fd >= 0) {
			close (app.awake_fd);
			app.awake_fd = -1;
		}
		if (app.awake_wanted && !app.awake_said_sleeps) {
			g_message ("Wi-Fi wakes the phone: it may sleep while the gateway waits");
			app.awake_said_sleeps = TRUE;
		}
	} else if (app.awake_fd < 0 && !app.awake_asking) {
		stay_awake ();
	}
}

static gboolean
awake_tick (gpointer data)
{
	(void)data;
	awake_update ();
	return G_SOURCE_CONTINUE;
}

/* ---- bring-up ---- */

/* The gateway from the config file, or nothing: no settings yet, or
 * switched off — then the modem is left alone and the phone's own apps
 * take its calls and messages. */
static void
gateway_up (const char *path)
{
	g_autoptr (GError) err = NULL;
	g_autoptr (GKeyFile) kf = g_key_file_new ();
	if (!g_key_file_load_from_file (kf, path, G_KEY_FILE_NONE, &err)) {
		g_message ("%s: %s — waiting for settings from the app", path, err->message);
		gw_service_set_state (app.service, "unconfigured");
		return;
	}
	struct stat st;
	if (stat (path, &st) == 0 && (st.st_mode & 077))
		g_warning ("%s is readable by others and holds the SIP password — chmod 600 it", path);

	g_autofree char *server = kf_str (kf, "sip", "server", NULL);
	g_autofree char *user = kf_str (kf, "sip", "user", NULL);
	/* as written: a password may begin or end with a space */
	g_autofree char *password = g_key_file_get_string (kf, "sip", "password", NULL);
	gboolean enabled = kf_bool (kf, "gateway", "enabled", TRUE);
	gw_service_set_enabled (app.service, enabled);
	if (!server || !user || !password || !*password) {
		g_message ("%s: [sip] server, user and password are required — waiting for settings from the app", path);
		gw_service_set_state (app.service, "unconfigured");
		return;
	}
	gw_service_set_identity (app.service, server, user);
	if (!enabled) {
		g_message ("gsm2sipd %s: switched off (enabled=false) — calls and SMS are the phone's, and it may sleep",
			   GSM2SIP_VERSION);
		gw_service_set_state (app.service, "disabled");
		memset (password, 0, strlen (password));
		return;
	}
	app.awake_wanted = TRUE;
	awake_update ();
	app.awake_timer = g_timeout_add_seconds (AWAKE_CHECK_S, awake_tick, NULL);
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
		.sms_ascii = kf_bool (kf, "gateway", "sms_ascii", FALSE),
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
			.agent_volume = CLAMP (kf_int (kf, "audio", "agent_volume", 0), -3, 3),
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
	app.bridge = bridge_new (&bc);
	bridge_set_recents (app.bridge, app.recents);
	bridge_set_state_notify (app.bridge, on_bridge_state, NULL);
	SipClientCallbacks scb = *bridge_sip_callbacks ();
	scb.connection_lost = on_connection_lost;
	scb.registered = on_registered;
	scb.registration_failed = on_registration_failed;
	app.sip = sip_client_new (&sc, &scb, app.bridge);
	g_message ("gsm2sipd %s: %s:%d over %s, codec %s, SRTP %s, STUN %s, UA \"%s\"", GSM2SIP_VERSION, server, sc.port,
		   tls ? "TLS" : "UDP", codec, sip_client_srtp_enabled (app.sip) ? "on" : "off", app.use_stun ? "on" : "off",
		   ua);
	/* from here the password lives in the SIP client only, and in no log */
	memset (password, 0, strlen (password));
	g_key_file_remove_key (kf, "sip", "password", NULL);
	app.modem = modem_new (bridge_modem_callbacks (), app.bridge);
	bridge_attach (app.bridge, app.sip, app.modem);
	gw_service_set_state (app.service, "connecting");

	if (app.use_stun) {
		app.stunning = TRUE;
		stun_discover (NULL, on_stun, GINT_TO_POINTER (TRUE));
	} else {
		sip_client_start (app.sip);
	}
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
	app.argv = g_strdupv (argv);	/* for the re-exec: parsing eats it */
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
	app.loop = g_main_loop_new (NULL, FALSE);
	/* systemd's StateDirectory= says where; by hand it is the default */
	const char *sd = g_getenv ("STATE_DIRECTORY");
	g_autofree char *state_dir = g_strdup (sd && *sd && !strchr (sd, ':') ? sd : DEFAULT_STATE_DIR);
	g_autofree char *recents_path = g_build_filename (state_dir, "recents", NULL);
	app.recents = gw_recents_new (recents_path);
	app.service = gw_service_new (path, app.recents, &service_ops, NULL);
	g_unix_signal_add (SIGTERM, on_quit, "SIGTERM");
	g_unix_signal_add (SIGINT, on_quit, "SIGINT");
	g_unix_signal_add (SIGUSR1, on_usr1, NULL);
	g_unix_signal_add (SIGHUP, on_hup, NULL);
	gateway_up (path);
	g_main_loop_run (app.loop);

	bridge_free (app.bridge);
	app.bridge = NULL;
	sip_client_free (app.sip);
	app.sip = NULL;
	modem_free (app.modem);
	app.modem = NULL;
	if (!app.restart)
		g_message ("Stopped");
	gw_service_free (app.service);
	app.service = NULL;
	gw_recents_free (app.recents);
	app.recents = NULL;
	g_main_loop_unref (app.loop);
	if (app.restart)
		reexec ();
	g_strfreev (app.argv);
	return 0;
}
