#include "gs-gateway.h"
#include <string.h>

#define BUS_NAME "com.callagent.Gsm2sipd"
#define OBJ_PATH "/com/callagent/Gsm2sipd"
#define IFACE "com.callagent.Gsm2sipd1"

struct _GsGateway {
	GObject parent;
	GDBusProxy *proxy;
	GCancellable *cancel;
	GHashTable *strs;	/* property name -> its last string value */
};

G_DEFINE_FINAL_TYPE (GsGateway, gs_gateway, G_TYPE_OBJECT)

enum { SIG_CHANGED, SIG_RECENTS_CHANGED, SIG_LOG_LINES, N_SIGNALS };
static guint signals[N_SIGNALS];

static void
on_props (GDBusProxy *p, GVariant *changed, GStrv invalidated, gpointer data)
{
	g_signal_emit (data, signals[SIG_CHANGED], 0);
}

static void
on_owner (GObject *obj, GParamSpec *pspec, gpointer data)
{
	g_signal_emit (data, signals[SIG_CHANGED], 0);
}

static void
on_signal (GDBusProxy *p, const char *sender, const char *name, GVariant *params, gpointer data)
{
	if (!strcmp (name, "RecentsChanged")) {
		g_signal_emit (data, signals[SIG_RECENTS_CHANGED], 0);
	} else if (!strcmp (name, "LogLines") && g_variant_is_of_type (params, G_VARIANT_TYPE ("(as)"))) {
		g_autofree const char **lines = NULL;
		g_variant_get (params, "(^a&s)", &lines);
		g_signal_emit (data, signals[SIG_LOG_LINES], 0, lines);
	}
}

static void
on_proxy (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GError) err = NULL;
	GDBusProxy *proxy = g_dbus_proxy_new_for_bus_finish (res, &err);
	if (!proxy) {
		if (!g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED))
			g_warning ("gsm2sipd: %s", err->message);
		return;
	}
	GsGateway *self = data;
	self->proxy = proxy;
	g_signal_connect (proxy, "g-properties-changed", G_CALLBACK (on_props), self);
	g_signal_connect (proxy, "notify::g-name-owner", G_CALLBACK (on_owner), self);
	g_signal_connect (proxy, "g-signal", G_CALLBACK (on_signal), self);
	g_signal_emit (self, signals[SIG_CHANGED], 0);
}

static void
gs_gateway_dispose (GObject *obj)
{
	GsGateway *self = GS_GATEWAY (obj);
	g_cancellable_cancel (self->cancel);
	g_clear_object (&self->cancel);
	g_clear_object (&self->proxy);
	g_clear_pointer (&self->strs, g_hash_table_unref);
	G_OBJECT_CLASS (gs_gateway_parent_class)->dispose (obj);
}

static void
gs_gateway_class_init (GsGatewayClass *klass)
{
	G_OBJECT_CLASS (klass)->dispose = gs_gateway_dispose;
	signals[SIG_CHANGED] = g_signal_new ("changed", G_TYPE_FROM_CLASS (klass), G_SIGNAL_RUN_LAST, 0, NULL, NULL,
					     NULL, G_TYPE_NONE, 0);
	signals[SIG_RECENTS_CHANGED] = g_signal_new ("recents-changed", G_TYPE_FROM_CLASS (klass), G_SIGNAL_RUN_LAST,
						     0, NULL, NULL, NULL, G_TYPE_NONE, 0);
	signals[SIG_LOG_LINES] = g_signal_new ("log-lines", G_TYPE_FROM_CLASS (klass), G_SIGNAL_RUN_LAST, 0, NULL,
					       NULL, NULL, G_TYPE_NONE, 1, G_TYPE_STRV);
}

static void
gs_gateway_init (GsGateway *self)
{
	self->cancel = g_cancellable_new ();
	self->strs = g_hash_table_new_full (g_str_hash, g_str_equal, NULL, g_free);
	/* no auto-start: the daemon is a system service, not bus-activated */
	g_dbus_proxy_new_for_bus (G_BUS_TYPE_SYSTEM, G_DBUS_PROXY_FLAGS_DO_NOT_AUTO_START, NULL, BUS_NAME, OBJ_PATH,
				  IFACE, self->cancel, on_proxy, self);
}

GsGateway *
gs_gateway_new (void)
{
	return g_object_new (GS_TYPE_GATEWAY, NULL);
}

gboolean
gs_gateway_present (GsGateway *self)
{
	if (!self->proxy)
		return FALSE;
	g_autofree char *owner = g_dbus_proxy_get_name_owner (self->proxy);
	return owner != NULL;
}

/* a cached property's string: valid until the same property is read again */
static const char *
prop_str (GsGateway *self, const char *name, const char *def)
{
	if (!gs_gateway_present (self))
		return def;
	g_autoptr (GVariant) v = g_dbus_proxy_get_cached_property (self->proxy, name);
	if (!v || !g_variant_is_of_type (v, G_VARIANT_TYPE_STRING))
		return def;
	char *copy = g_variant_dup_string (v, NULL);
	g_hash_table_replace (self->strs, (gpointer)name, copy);
	return copy;
}

static gboolean
prop_bool (GsGateway *self, const char *name)
{
	if (!gs_gateway_present (self))
		return FALSE;
	g_autoptr (GVariant) v = g_dbus_proxy_get_cached_property (self->proxy, name);
	return v && g_variant_is_of_type (v, G_VARIANT_TYPE_BOOLEAN) && g_variant_get_boolean (v);
}

static gint64
prop_int64 (GsGateway *self, const char *name)
{
	if (!gs_gateway_present (self))
		return 0;
	g_autoptr (GVariant) v = g_dbus_proxy_get_cached_property (self->proxy, name);
	return v && g_variant_is_of_type (v, G_VARIANT_TYPE_INT64) ? g_variant_get_int64 (v) : 0;
}

const char *gs_gateway_state (GsGateway *self) { return prop_str (self, "State", "absent"); }
gboolean gs_gateway_enabled (GsGateway *self) { return prop_bool (self, "Enabled"); }
const char *gs_gateway_server (GsGateway *self) { return prop_str (self, "Server", ""); }
const char *gs_gateway_call_state (GsGateway *self) { return prop_str (self, "CallState", "idle"); }
const char *gs_gateway_call_info (GsGateway *self) { return prop_str (self, "CallInfo", ""); }
const char *gs_gateway_call_number (GsGateway *self) { return prop_str (self, "CallNumber", ""); }
gboolean gs_gateway_call_incoming (GsGateway *self) { return prop_bool (self, "CallIncoming"); }
gint64 gs_gateway_call_bridged_at (GsGateway *self) { return prop_int64 (self, "CallBridgedAt"); }
gboolean gs_gateway_agent_muted (GsGateway *self) { return prop_bool (self, "AgentMuted"); }
gboolean gs_gateway_restart_pending (GsGateway *self) { return prop_bool (self, "RestartPending"); }

gboolean
gs_gateway_owns_modem (GsGateway *self)
{
	/* Enabled, not State: SetEnabled sets it before it returns, while the
	 * State it leads to comes only with the restart after */
	return gs_gateway_present (self) && gs_gateway_enabled (self) && strcmp (gs_gateway_state (self), "unconfigured");
}

static void
call_done (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GTask) task = data;
	GError *err = NULL;
	GVariant *v = g_dbus_proxy_call_finish (G_DBUS_PROXY (src), res, &err);
	if (v)
		g_task_return_pointer (task, v, (GDestroyNotify)g_variant_unref);
	else
		g_task_return_error (task, err);
}

void
gs_gateway_call (GsGateway *self, const char *method, GVariant *params, GCancellable *cancel, GAsyncReadyCallback cb,
		 gpointer user_data)
{
	GTask *task = g_task_new (self, cancel, cb, user_data);
	if (!gs_gateway_present (self)) {
		if (params)
			g_variant_unref (g_variant_ref_sink (params));
		g_task_return_new_error (task, G_IO_ERROR, G_IO_ERROR_NOT_CONNECTED, "gsm2sipd is not running");
		g_object_unref (task);
		return;
	}
	g_dbus_proxy_call (self->proxy, method, params, G_DBUS_CALL_FLAGS_NONE, 10000, cancel, call_done, task);
}

GVariant *
gs_gateway_call_finish (GsGateway *self, GAsyncResult *res, GError **error)
{
	return g_task_propagate_pointer (G_TASK (res), error);
}

char *
gs_gateway_error_text (const GError *error)
{
	if (!error)
		return g_strdup ("");
	g_autoptr (GError) copy = g_error_copy (error);
	if (g_dbus_error_is_remote_error (copy))
		g_dbus_error_strip_remote_error (copy);
	return g_strdup (copy->message);
}
