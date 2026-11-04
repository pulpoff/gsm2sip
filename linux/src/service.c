#define G_LOG_DOMAIN "dbus"
#include "service.h"
#include <errno.h>
#include <string.h>

#define BUS_NAME "com.callagent.Gsm2sipd"
#define OBJ_PATH "/com/callagent/Gsm2sipd"
#define IFACE "com.callagent.Gsm2sipd1"
#define LOG_LINES 500

static const char introspection[] =
	"<node>"
	" <interface name='" IFACE "'>"
	"  <method name='GetRecents'><arg direction='out' type='aa{sv}' name='entries'/></method>"
	"  <method name='ClearRecents'/>"
	"  <method name='GetLog'><arg direction='out' type='as' name='lines'/></method>"
	"  <method name='ClearLog'/>"
	"  <method name='GetConfig'><arg direction='out' type='a{sv}' name='config'/></method>"
	"  <method name='SetConfig'><arg direction='in' type='a{sv}' name='config'/></method>"
	"  <method name='SetEnabled'><arg direction='in' type='b' name='enabled'/></method>"
	"  <method name='Reconnect'/>"
	"  <method name='Hangup'/>"
	"  <method name='SetAgentMuted'><arg direction='in' type='b' name='muted'/></method>"
	"  <signal name='RecentsChanged'/>"
	"  <signal name='LogLines'><arg type='as' name='lines'/></signal>"
	"  <property name='Version' type='s' access='read'/>"
	"  <property name='Enabled' type='b' access='read'/>"
	"  <property name='State' type='s' access='read'/>"
	"  <property name='OnlineSince' type='x' access='read'/>"
	"  <property name='Server' type='s' access='read'/>"
	"  <property name='User' type='s' access='read'/>"
	"  <property name='CallState' type='s' access='read'/>"
	"  <property name='CallInfo' type='s' access='read'/>"
	"  <property name='CallNumber' type='s' access='read'/>"
	"  <property name='CallIncoming' type='b' access='read'/>"
	"  <property name='CallBridgedAt' type='x' access='read'/>"
	"  <property name='AgentMuted' type='b' access='read'/>"
	"  <property name='RestartPending' type='b' access='read'/>"
	" </interface>"
	"</node>";

struct GwService {
	char *config_path;
	GwRecents *recents;
	const GwServiceOps *ops;
	gpointer ud;
	guint owner_id, reg_id;
	GDBusConnection *conn;
	GDBusNodeInfo *info;

	char *state, *server, *user;
	gboolean enabled;
	gint64 online_since;
	char *call_state, *call_info, *call_number;
	gboolean call_incoming, agent_muted, restart_pending;
	gint64 call_bridged_at;
};

/* ---- the log ring: filled from any thread, flushed on the main loop ---- */

static struct {
	GMutex lock;
	char *lines[LOG_LINES];
	guint head, count;
	GPtrArray *pending;
	gboolean flush_queued;
	GwService *service;	/* main thread only */
} ring;

static gboolean
log_flush (gpointer data)
{
	(void)data;
	g_mutex_lock (&ring.lock);
	GPtrArray *batch = ring.pending;
	ring.pending = NULL;
	ring.flush_queued = FALSE;
	g_mutex_unlock (&ring.lock);
	if (!batch)
		return G_SOURCE_REMOVE;
	GwService *s = ring.service;
	if (s && s->conn) {
		g_ptr_array_add (batch, NULL);
		g_dbus_connection_emit_signal (s->conn, NULL, OBJ_PATH, IFACE, "LogLines",
					       g_variant_new ("(^as)", (char **)batch->pdata), NULL);
	}
	g_ptr_array_unref (batch);
	return G_SOURCE_REMOVE;
}

void
gw_log_line (const char *line)
{
	g_mutex_lock (&ring.lock);
	guint slot = (ring.head + ring.count) % LOG_LINES;
	if (ring.count == LOG_LINES) {
		g_free (ring.lines[ring.head]);
		ring.head = (ring.head + 1) % LOG_LINES;
	} else {
		ring.count++;
	}
	ring.lines[slot] = g_strdup (line);
	if (!ring.pending)
		ring.pending = g_ptr_array_new_with_free_func (g_free);
	g_ptr_array_add (ring.pending, g_strdup (line));
	gboolean queue = !ring.flush_queued;
	ring.flush_queued = TRUE;
	g_mutex_unlock (&ring.lock);
	/* batched: a burst of lines is one signal */
	if (queue)
		g_timeout_add (100, log_flush, NULL);
}

static GVariant *
log_snapshot (void)
{
	GVariantBuilder b;
	g_variant_builder_init (&b, G_VARIANT_TYPE ("as"));
	g_mutex_lock (&ring.lock);
	for (guint i = 0; i < ring.count; i++)
		g_variant_builder_add (&b, "s", ring.lines[(ring.head + i) % LOG_LINES]);
	g_mutex_unlock (&ring.lock);
	return g_variant_builder_end (&b);
}

/* ---- the config file ---- */

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

/* The settings screen's fields, and where each lives in the file. */
static const struct {
	const char *name, *group, *key;
	char type;	/* 's' 'i' 'b' */
} fields[] = {
	{ "server", "sip", "server", 's' },
	{ "port", "sip", "port", 'i' },
	{ "user", "sip", "user", 's' },
	{ "codec", "sip", "codec", 's' },
	{ "stun", "sip", "stun", 'b' },
	{ "tls", "sip", "tls", 'b' },
	{ "srtp", "sip", "srtp", 'b' },
	{ "own_number", "gateway", "own_number", 's' },
	{ "sms_ascii", "gateway", "sms_ascii", 'b' },
	{ "agent_volume", "audio", "agent_volume", 'i' },
};

static GVariant *
config_read (const char *path)
{
	g_autoptr (GKeyFile) kf = g_key_file_new ();
	g_key_file_load_from_file (kf, path, G_KEY_FILE_NONE, NULL);	/* a missing file reads as defaults */
	g_auto (GVariantDict) d;
	g_variant_dict_init (&d, NULL);
	g_autofree char *server = kf_str (kf, "sip", "server", "");
	g_autofree char *user = kf_str (kf, "sip", "user", "");
	g_autofree char *codec = kf_str (kf, "sip", "codec", "g722");
	g_autofree char *own = kf_str (kf, "gateway", "own_number", "");
	g_autofree char *pw = g_key_file_get_string (kf, "sip", "password", NULL);
	gboolean tls = kf_bool (kf, "sip", "tls", FALSE);
	g_variant_dict_insert (&d, "server", "s", server);
	g_variant_dict_insert (&d, "port", "i", kf_int (kf, "sip", "port", tls ? 5061 : 5060));
	g_variant_dict_insert (&d, "user", "s", user);
	/* write-only: nothing on the bus ever carries it back out */
	g_variant_dict_insert (&d, "has_password", "b", pw && *pw);
	g_variant_dict_insert (&d, "codec", "s", codec);
	g_variant_dict_insert (&d, "stun", "b", kf_bool (kf, "sip", "stun", TRUE));
	g_variant_dict_insert (&d, "tls", "b", tls);
	/* kept as ticked with TLS off; acted on only with TLS */
	g_variant_dict_insert (&d, "srtp", "b", kf_bool (kf, "sip", "srtp", FALSE));
	g_variant_dict_insert (&d, "own_number", "s", own);
	g_variant_dict_insert (&d, "enabled", "b", kf_bool (kf, "gateway", "enabled", TRUE));
	g_variant_dict_insert (&d, "sms_ascii", "b", kf_bool (kf, "gateway", "sms_ascii", FALSE));
	g_variant_dict_insert (&d, "agent_volume", "i", CLAMP (kf_int (kf, "audio", "agent_volume", 0), -3, 3));
	if (pw)
		memset (pw, 0, strlen (pw));
	return g_variant_dict_end (&d);
}

static GKeyFile *
config_load (const char *path, GError **err)
{
	g_autoptr (GKeyFile) kf = g_key_file_new ();
	g_autoptr (GError) lerr = NULL;
	if (!g_key_file_load_from_file (kf, path, G_KEY_FILE_KEEP_COMMENTS, &lerr) &&
	    !g_error_matches (lerr, G_FILE_ERROR, G_FILE_ERROR_NOENT)) {
		g_propagate_error (err, g_steal_pointer (&lerr));
		return NULL;
	}
	return g_steal_pointer (&kf);
}

/* what main needs to come up registered */
static gboolean
config_check (GKeyFile *kf, GError **err)
{
	g_autofree char *server = kf_str (kf, "sip", "server", NULL);
	g_autofree char *user = kf_str (kf, "sip", "user", NULL);
	g_autofree char *pass = g_key_file_get_string (kf, "sip", "password", NULL);
	g_autofree char *codec = kf_str (kf, "sip", "codec", "g722");
	int port = kf_int (kf, "sip", "port", 5060);
	int vol = kf_int (kf, "audio", "agent_volume", 0);
	const char *bad = !server ? "a SIP server" : !user ? "a SIP user" : !pass || !*pass ? "a SIP password"
			: port < 1 || port > 65535 ? "a port between 1 and 65535"
			: strcmp (codec, "g722") && strcmp (codec, "both") && strcmp (codec, "g711") ? "codec g722, both or g711"
			: vol < -3 || vol > 3 ? "an agent volume between -3 and +3" : NULL;
	if (pass)
		memset (pass, 0, strlen (pass));
	if (bad) {
		g_set_error (err, G_DBUS_ERROR, G_DBUS_ERROR_INVALID_ARGS, "The gateway needs %s", bad);
		return FALSE;
	}
	return TRUE;
}

static gboolean
config_save (const char *path, GKeyFile *kf, GError **err)
{
	gsize len = 0;
	g_autofree char *data = g_key_file_to_data (kf, &len, NULL);
	g_autofree char *dir = g_path_get_dirname (path);
	if (g_mkdir_with_parents (dir, 0700) < 0) {
		g_set_error (err, G_FILE_ERROR, g_file_error_from_errno (errno), "%s: %s", dir, g_strerror (errno));
		return FALSE;
	}
	/* 0600 from the first byte: it holds the password */
	gboolean ok = g_file_set_contents_full (path, data, (gssize)len, G_FILE_SET_CONTENTS_CONSISTENT, 0600, err);
	memset (data, 0, len);
	return ok;
}

static gboolean
config_write (const char *path, GVariant *cfg, GError **err)
{
	g_autoptr (GKeyFile) kf = config_load (path, err);
	if (!kf)
		return FALSE;
	for (gsize i = 0; i < G_N_ELEMENTS (fields); i++) {
		g_autoptr (GVariant) v = g_variant_lookup_value (cfg, fields[i].name, NULL);
		if (!v)
			continue;
		const char *want = fields[i].type == 's' ? "s" : fields[i].type == 'i' ? "i" : "b";
		if (!g_variant_is_of_type (v, G_VARIANT_TYPE (want))) {
			g_set_error (err, G_DBUS_ERROR, G_DBUS_ERROR_INVALID_ARGS, "%s must be of type %s", fields[i].name,
				     want);
			return FALSE;
		}
		if (fields[i].type == 's') {
			g_autofree char *str = g_strstrip (g_variant_dup_string (v, NULL));
			if (strpbrk (str, "\r\n")) {
				g_set_error (err, G_DBUS_ERROR, G_DBUS_ERROR_INVALID_ARGS, "%s has a line break",
					     fields[i].name);
				return FALSE;
			}
			g_key_file_set_string (kf, fields[i].group, fields[i].key, str);
		} else if (fields[i].type == 'i') {
			g_key_file_set_integer (kf, fields[i].group, fields[i].key, g_variant_get_int32 (v));
		} else {
			g_key_file_set_boolean (kf, fields[i].group, fields[i].key, g_variant_get_boolean (v));
		}
	}
	/* as typed: a password may begin or end with a space */
	const char *pw = NULL;
	if (g_variant_lookup (cfg, "password", "&s", &pw) && *pw) {
		if (strpbrk (pw, "\r\n")) {
			g_set_error (err, G_DBUS_ERROR, G_DBUS_ERROR_INVALID_ARGS, "password has a line break");
			return FALSE;
		}
		/* escaped as main's g_key_file_get_string unescapes it */
		g_key_file_set_string (kf, "sip", "password", pw);
	}

	return config_check (kf, err) && config_save (path, kf, err);
}

static gboolean
config_write_enabled (const char *path, gboolean enabled, GError **err)
{
	g_autoptr (GKeyFile) kf = config_load (path, err);
	if (!kf)
		return FALSE;
	/* off needs nothing else; on has to be able to register */
	if (enabled && !config_check (kf, err))
		return FALSE;
	g_key_file_set_boolean (kf, "gateway", "enabled", enabled);
	return config_save (path, kf, err);
}

/* ---- D-Bus ---- */

static void
emit_changed (GwService *s, const char *name, GVariant *value)
{
	if (!s->conn) {
		g_variant_unref (g_variant_ref_sink (value));
		return;
	}
	GVariantBuilder b;
	g_variant_builder_init (&b, G_VARIANT_TYPE ("a{sv}"));
	g_variant_builder_add (&b, "{sv}", name, value);
	g_dbus_connection_emit_signal (s->conn, NULL, OBJ_PATH, "org.freedesktop.DBus.Properties", "PropertiesChanged",
				       g_variant_new ("(sa{sv}as)", IFACE, &b, NULL), NULL);
}

static GVariant *
get_property (GDBusConnection *c, const char *sender, const char *path, const char *iface, const char *name,
	      GError **err, gpointer data)
{
	GwService *s = data;
	if (!strcmp (name, "Version"))
		return g_variant_new_string (GSM2SIP_VERSION);
	if (!strcmp (name, "Enabled"))
		return g_variant_new_boolean (s->enabled);
	if (!strcmp (name, "State"))
		return g_variant_new_string (s->state);
	if (!strcmp (name, "OnlineSince"))
		return g_variant_new_int64 (s->online_since);
	if (!strcmp (name, "Server"))
		return g_variant_new_string (s->server ? s->server : "");
	if (!strcmp (name, "User"))
		return g_variant_new_string (s->user ? s->user : "");
	if (!strcmp (name, "CallState"))
		return g_variant_new_string (s->call_state);
	if (!strcmp (name, "CallInfo"))
		return g_variant_new_string (s->call_info ? s->call_info : "");
	if (!strcmp (name, "CallNumber"))
		return g_variant_new_string (s->call_number ? s->call_number : "");
	if (!strcmp (name, "CallIncoming"))
		return g_variant_new_boolean (s->call_incoming);
	if (!strcmp (name, "CallBridgedAt"))
		return g_variant_new_int64 (s->call_bridged_at);
	if (!strcmp (name, "AgentMuted"))
		return g_variant_new_boolean (s->agent_muted);
	if (!strcmp (name, "RestartPending"))
		return g_variant_new_boolean (s->restart_pending);
	g_set_error (err, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_PROPERTY, "No property %s", name);
	return NULL;
}

static void
method_call (GDBusConnection *c, const char *sender, const char *path, const char *iface, const char *method,
	     GVariant *params, GDBusMethodInvocation *inv, gpointer data)
{
	GwService *s = data;
	if (!strcmp (method, "GetRecents")) {
		g_dbus_method_invocation_return_value (inv, g_variant_new ("(@aa{sv})", gw_recents_list (s->recents)));
	} else if (!strcmp (method, "ClearRecents")) {
		g_message ("Recents cleared from the app");
		gw_recents_clear (s->recents);
		g_dbus_method_invocation_return_value (inv, NULL);
	} else if (!strcmp (method, "GetLog")) {
		g_dbus_method_invocation_return_value (inv, g_variant_new ("(@as)", log_snapshot ()));
	} else if (!strcmp (method, "ClearLog")) {
		g_mutex_lock (&ring.lock);
		for (guint i = 0; i < ring.count; i++)
			g_clear_pointer (&ring.lines[(ring.head + i) % LOG_LINES], g_free);
		ring.head = ring.count = 0;
		g_mutex_unlock (&ring.lock);
		g_dbus_method_invocation_return_value (inv, NULL);
	} else if (!strcmp (method, "GetConfig")) {
		g_dbus_method_invocation_return_value (inv, g_variant_new ("(@a{sv})", config_read (s->config_path)));
	} else if (!strcmp (method, "SetConfig")) {
		g_autoptr (GVariant) cfg = g_variant_get_child_value (params, 0);
		GError *err = NULL;
		if (!config_write (s->config_path, cfg, &err)) {
			g_message ("Settings not saved: %s", err->message);
			g_dbus_method_invocation_take_error (inv, err);
			return;
		}
		g_message ("Settings saved from the app");
		g_dbus_method_invocation_return_value (inv, NULL);
		s->ops->config_changed (s->ud);
	} else if (!strcmp (method, "SetEnabled")) {
		gboolean on = FALSE;
		g_variant_get (params, "(b)", &on);
		GError *err = NULL;
		if (!config_write_enabled (s->config_path, on, &err)) {
			g_message ("Not switched %s: %s", on ? "on" : "off", err->message);
			g_dbus_method_invocation_take_error (inv, err);
			return;
		}
		g_message ("Switched %s from the app", on ? "on — taking over calls and SMS" : "off — calls and SMS are the phone's");
		gw_service_set_enabled (s, on);
		g_dbus_method_invocation_return_value (inv, NULL);
		s->ops->enabled_changed (on, s->ud);
	} else if (!strcmp (method, "Reconnect")) {
		g_dbus_method_invocation_return_value (inv, NULL);
		s->ops->reconnect (s->ud);
	} else if (!strcmp (method, "Hangup")) {
		if (s->ops->hangup (s->ud))
			g_dbus_method_invocation_return_value (inv, NULL);
		else
			g_dbus_method_invocation_return_error (inv, G_DBUS_ERROR, G_DBUS_ERROR_FAILED, "No call in progress");
	} else if (!strcmp (method, "SetAgentMuted")) {
		gboolean on = FALSE;
		g_variant_get (params, "(b)", &on);
		s->ops->set_agent_muted (on, s->ud);
		g_dbus_method_invocation_return_value (inv, NULL);
	} else {
		g_dbus_method_invocation_return_error (inv, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_METHOD, "No method %s",
						       method);
	}
}

static const GDBusInterfaceVTable vtable = { method_call, get_property, NULL, { 0 } };

static void
on_recents_changed (gpointer data)
{
	GwService *s = data;
	if (s->conn)
		g_dbus_connection_emit_signal (s->conn, NULL, OBJ_PATH, IFACE, "RecentsChanged", NULL, NULL);
}

static void
on_bus (GDBusConnection *conn, const char *name, gpointer data)
{
	GwService *s = data;
	g_autoptr (GError) err = NULL;
	s->reg_id = g_dbus_connection_register_object (conn, OBJ_PATH, s->info->interfaces[0], &vtable, s, NULL, &err);
	if (!s->reg_id) {
		g_warning ("Cannot export %s: %s", OBJ_PATH, err->message);
		return;
	}
	s->conn = g_object_ref (conn);
}

static void
on_name_acquired (GDBusConnection *conn, const char *name, gpointer data)
{
	g_message ("On the system bus as %s", name);
}

static void
on_name_lost (GDBusConnection *conn, const char *name, gpointer data)
{
	/* no bus, or the policy file is not installed: the gateway still works,
	 * the app just cannot see it */
	g_warning ("%s not owned (%s) — the app cannot reach the gateway", name,
		   conn ? "another owner, or the bus policy is missing" : "no system bus");
}

GwService *
gw_service_new (const char *config_path, GwRecents *recents, const GwServiceOps *ops, gpointer ud)
{
	GwService *s = g_new0 (GwService, 1);
	s->config_path = g_strdup (config_path);
	s->recents = recents;
	s->ops = ops;
	s->ud = ud;
	s->state = g_strdup ("unconfigured");
	s->call_state = g_strdup ("idle");
	s->info = g_dbus_node_info_new_for_xml (introspection, NULL);
	g_assert (s->info);
	ring.service = s;
	gw_recents_set_changed (recents, on_recents_changed, s);
	s->owner_id = g_bus_own_name (G_BUS_TYPE_SYSTEM, BUS_NAME, G_BUS_NAME_OWNER_FLAGS_NONE, on_bus,
				      on_name_acquired, on_name_lost, s, NULL);
	return s;
}

void
gw_service_free (GwService *s)
{
	if (!s)
		return;
	log_flush (NULL);
	ring.service = NULL;
	gw_recents_set_changed (s->recents, NULL, NULL);
	if (s->conn && s->reg_id)
		g_dbus_connection_unregister_object (s->conn, s->reg_id);
	g_bus_unown_name (s->owner_id);
	if (s->conn)
		g_dbus_connection_flush_sync (s->conn, NULL, NULL);
	g_clear_object (&s->conn);
	g_dbus_node_info_unref (s->info);
	g_free (s->config_path);
	g_free (s->state);
	g_free (s->server);
	g_free (s->user);
	g_free (s->call_state);
	g_free (s->call_info);
	g_free (s->call_number);
	g_free (s);
}

static gboolean
set_str (char **field, const char *v)
{
	if (!g_strcmp0 (*field, v))
		return FALSE;
	g_free (*field);
	*field = g_strdup (v);
	return TRUE;
}

void
gw_service_set_state (GwService *s, const char *state)
{
	if (!set_str (&s->state, state))
		return;
	gint64 since = !strcmp (state, "registered") ? g_get_real_time () / 1000 : 0;
	emit_changed (s, "State", g_variant_new_string (state));
	if (since != s->online_since) {
		s->online_since = since;
		emit_changed (s, "OnlineSince", g_variant_new_int64 (since));
	}
}

void
gw_service_set_enabled (GwService *s, gboolean enabled)
{
	if (s->enabled == enabled)
		return;
	s->enabled = enabled;
	emit_changed (s, "Enabled", g_variant_new_boolean (enabled));
}

void
gw_service_set_identity (GwService *s, const char *server, const char *user)
{
	if (set_str (&s->server, server))
		emit_changed (s, "Server", g_variant_new_string (server ? server : ""));
	if (set_str (&s->user, user))
		emit_changed (s, "User", g_variant_new_string (user ? user : ""));
}

void
gw_service_set_call (GwService *s, const char *state, const char *info, const char *number, gboolean incoming,
		     gint64 bridged_at_ms, gboolean agent_muted)
{
	/* the number first: an app that sees CallState change reads it then */
	if (set_str (&s->call_number, number))
		emit_changed (s, "CallNumber", g_variant_new_string (number ? number : ""));
	if (s->call_incoming != incoming) {
		s->call_incoming = incoming;
		emit_changed (s, "CallIncoming", g_variant_new_boolean (incoming));
	}
	if (s->call_bridged_at != bridged_at_ms) {
		s->call_bridged_at = bridged_at_ms;
		emit_changed (s, "CallBridgedAt", g_variant_new_int64 (bridged_at_ms));
	}
	if (s->agent_muted != agent_muted) {
		s->agent_muted = agent_muted;
		emit_changed (s, "AgentMuted", g_variant_new_boolean (agent_muted));
	}
	if (info && set_str (&s->call_info, info))
		emit_changed (s, "CallInfo", g_variant_new_string (info));
	if (set_str (&s->call_state, state))
		emit_changed (s, "CallState", g_variant_new_string (state));
}

void
gw_service_set_restart_pending (GwService *s, gboolean pending)
{
	if (s->restart_pending == pending)
		return;
	s->restart_pending = pending;
	emit_changed (s, "RestartPending", g_variant_new_boolean (pending));
}
