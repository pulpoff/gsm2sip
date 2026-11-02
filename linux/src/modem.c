#define G_LOG_DOMAIN "modem"
#include "modem.h"
#include <string.h>

#define MM_BUS "org.freedesktop.ModemManager1"
#define MM_PATH "/org/freedesktop/ModemManager1"
#define IF_MODEM MM_BUS ".Modem"
#define IF_VOICE MM_BUS ".Modem.Voice"
#define IF_MESSAGING MM_BUS ".Modem.Messaging"
#define IF_CALL MM_BUS ".Call"
#define IF_SMS MM_BUS ".Sms"

#define MM_MODEM_STATE_DISABLED 3
#define MM_SMS_PDU_SUBMIT 2

struct Modem {
	ModemCallbacks cb;
	gpointer ud;
	GDBusConnection *bus;
	GDBusObjectManager *om;
	GCancellable *cancel;
	char *path;
	GDBusProxy *modem, *voice, *messaging;
	gulong voice_sig, msg_sig;
	GHashTable *calls;	/* path -> GDBusProxy */
	GHashTable *sms;	/* path -> GDBusProxy, received ones being watched or reported */
	GHashTable *reported;	/* path set */
	int pending;
	gboolean enable_asked;
};

static int
prop_int (GDBusProxy *p, const char *name, int def)
{
	g_autoptr (GVariant) v = p ? g_dbus_proxy_get_cached_property (p, name) : NULL;
	if (!v)
		return def;
	if (g_variant_is_of_type (v, G_VARIANT_TYPE_INT32))
		return g_variant_get_int32 (v);
	if (g_variant_is_of_type (v, G_VARIANT_TYPE_UINT32))
		return (int)g_variant_get_uint32 (v);
	return def;
}

static char *
prop_str (GDBusProxy *p, const char *name)
{
	g_autoptr (GVariant) v = p ? g_dbus_proxy_get_cached_property (p, name) : NULL;
	if (!v || !g_variant_is_of_type (v, G_VARIANT_TYPE_STRING))
		return NULL;
	return g_variant_dup_string (v, NULL);
}

gboolean
modem_present (Modem *m)
{
	return m->voice != NULL;
}

int
modem_pending (Modem *m)
{
	return m->pending;
}

/* ---- async method calls ---- */

typedef void (*ReplyFn) (Modem *m, GVariant *reply, const GError *err, gpointer ud);

typedef struct {
	Modem *m;
	char *what;
	ReplyFn fn;
	gpointer ud;
} CallCtx;

static void
on_reply (GObject *src, GAsyncResult *res, gpointer data)
{
	CallCtx *x = data;
	g_autoptr (GError) err = NULL;
	g_autoptr (GVariant) r = g_dbus_proxy_call_finish (G_DBUS_PROXY (src), res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED)) {
		g_free (x->what);
		g_free (x);
		return;
	}
	x->m->pending--;
	if (err)
		g_message ("%s: %s", x->what, err->message);
	if (x->fn)
		x->fn (x->m, r, err, x->ud);
	g_free (x->what);
	g_free (x);
}

static void
call_method (Modem *m, GDBusProxy *p, const char *method, GVariant *args, int timeout_ms, ReplyFn fn, gpointer ud)
{
	CallCtx *x = g_new0 (CallCtx, 1);
	x->m = m;
	x->what = g_strdup_printf ("%s %s", method, g_dbus_proxy_get_object_path (p));
	x->fn = fn;
	x->ud = ud;
	m->pending++;
	g_dbus_proxy_call (p, method, args, G_DBUS_CALL_FLAGS_NONE, timeout_ms, m->cancel, on_reply, x);
}

static GDBusProxy *
proxy_for (Modem *m, const char *path, const char *iface)
{
	g_autoptr (GError) err = NULL;
	GDBusProxy *p = g_dbus_proxy_new_sync (m->bus, G_DBUS_PROXY_FLAGS_NONE, NULL, MM_BUS, path, iface, m->cancel, &err);
	if (!p)
		g_warning ("%s %s: %s", iface, path, err->message);
	return p;
}

/* ---- calls ---- */

static void
on_call_signal (GDBusProxy *p, const char *sender, const char *signal, GVariant *params, gpointer data)
{
	Modem *m = data;
	(void)sender;
	if (strcmp (signal, "StateChanged") || !g_variant_is_of_type (params, G_VARIANT_TYPE ("(iiu)")))
		return;
	gint32 old_state, new_state;
	guint32 reason;
	g_variant_get (params, "(iiu)", &old_state, &new_state, &reason);
	const char *path = g_dbus_proxy_get_object_path (p);
	g_message ("call %s: state %d -> %d (reason %u)", path, old_state, new_state, reason);
	if (m->cb.call_state)
		m->cb.call_state (m->ud, path, old_state, new_state, reason);
}

static GDBusProxy *
add_call (Modem *m, const char *path, gboolean announce)
{
	GDBusProxy *p = g_hash_table_lookup (m->calls, path);
	if (p)
		return p;
	p = proxy_for (m, path, IF_CALL);
	if (!p)
		return NULL;
	g_signal_connect (p, "g-signal", G_CALLBACK (on_call_signal), m);
	g_hash_table_insert (m->calls, g_strdup (path), p);
	if (announce) {
		g_autofree char *number = prop_str (p, "Number");
		int dir = prop_int (p, "Direction", 0), state = prop_int (p, "State", 0);
		g_message ("call %s added: %s %s, state %d", path, dir == 1 ? "incoming from" : "outgoing to",
			   number ? number : "?", state);
		if (m->cb.call_added)
			m->cb.call_added (m->ud, path, dir == 1, number, state);
	}
	return p;
}

int
modem_call_state (Modem *m, const char *path)
{
	GDBusProxy *p = path ? g_hash_table_lookup (m->calls, path) : NULL;
	return p ? prop_int (p, "State", -1) : -1;
}

void
modem_call_accept (Modem *m, const char *path)
{
	GDBusProxy *p = g_hash_table_lookup (m->calls, path);
	if (p)
		call_method (m, p, "Accept", NULL, 15000, NULL, NULL);
}

void
modem_call_hangup (Modem *m, const char *path)
{
	GDBusProxy *p = g_hash_table_lookup (m->calls, path);
	if (p && prop_int (p, "State", 0) != MM_CALL_TERMINATED)
		call_method (m, p, "Hangup", NULL, 15000, NULL, NULL);
}

void
modem_call_delete (Modem *m, const char *path)
{
	if (m->voice && path)
		call_method (m, m->voice, "DeleteCall", g_variant_new ("(o)", path), 15000, NULL, NULL);
}

typedef struct {
	ModemDialCallback cb;
	gpointer ud;
	char *path;
} DialCtx;

static void
on_started (Modem *m, GVariant *r, const GError *err, gpointer data)
{
	DialCtx *d = data;
	(void)m;
	(void)r;
	d->cb (d->path, err ? err->message : NULL, d->ud);
	g_free (d->path);
	g_free (d);
}

static void
on_created (Modem *m, GVariant *r, const GError *err, gpointer data)
{
	DialCtx *d = data;
	if (err || !r) {
		d->cb (NULL, err ? err->message : "no call object", d->ud);
		g_free (d);
		return;
	}
	g_variant_get (r, "(o)", &d->path);
	GDBusProxy *p = add_call (m, d->path, TRUE);
	if (!p) {
		d->cb (d->path, "call object unavailable", d->ud);
		g_free (d->path);
		g_free (d);
		return;
	}
	call_method (m, p, "Start", NULL, 60000, on_started, d);
}

void
modem_dial (Modem *m, const char *number, ModemDialCallback cb, gpointer ud)
{
	DialCtx *d = g_new0 (DialCtx, 1);
	d->cb = cb;
	d->ud = ud;
	if (!m->voice) {
		cb (NULL, "no modem", ud);
		g_free (d);
		return;
	}
	GVariantBuilder b;
	g_variant_builder_init (&b, G_VARIANT_TYPE ("a{sv}"));
	g_variant_builder_add (&b, "{sv}", "number", g_variant_new_string (number));
	call_method (m, m->voice, "CreateCall", g_variant_new ("(a{sv})", &b), 30000, on_created, d);
}

/* ---- SMS ---- */

static void
report_sms (Modem *m, GDBusProxy *p)
{
	const char *path = g_dbus_proxy_get_object_path (p);
	if (g_hash_table_contains (m->reported, path))
		return;
	g_hash_table_add (m->reported, g_strdup (path));
	g_autofree char *number = prop_str (p, "Number");
	g_autofree char *text = prop_str (p, "Text");
	g_autofree char *ts = prop_str (p, "Timestamp");
	if (!text || !*text) {
		/* a data SMS: Android never handed those to the gateway either */
		g_message ("SMS %s from %s has no text (binary) — not forwarded", path, number ? number : "?");
		return;
	}
	g_message ("SMS %s received from %s (%zu bytes)", path, number ? number : "?", strlen (text));
	if (m->cb.sms_received)
		m->cb.sms_received (m->ud, path, number ? number : "", text, ts ? ts : "");
}

static void
on_sms_props (GDBusProxy *p, GVariant *changed, GStrv invalidated, gpointer data)
{
	(void)changed;
	(void)invalidated;
	if (prop_int (p, "State", 0) == MM_SMS_RECEIVED)
		report_sms (data, p);
}

static void
watch_sms (Modem *m, const char *path)
{
	if (g_hash_table_contains (m->sms, path))
		return;
	GDBusProxy *p = proxy_for (m, path, IF_SMS);
	if (!p)
		return;
	if (prop_int (p, "PduType", 0) == MM_SMS_PDU_SUBMIT) {
		g_object_unref (p);	/* one of ours */
		return;
	}
	g_hash_table_insert (m->sms, g_strdup (path), p);
	/* a multipart message is RECEIVING until its last part is in */
	g_signal_connect (p, "g-properties-changed", G_CALLBACK (on_sms_props), m);
	if (prop_int (p, "State", 0) == MM_SMS_RECEIVED)
		report_sms (m, p);
}

void
modem_sms_rescan (Modem *m)
{
	if (!m->messaging)
		return;
	g_hash_table_remove_all (m->reported);
	g_autoptr (GVariant) v = g_dbus_proxy_get_cached_property (m->messaging, "Messages");
	if (!v || !g_variant_is_of_type (v, G_VARIANT_TYPE ("ao")))
		return;
	GVariantIter it;
	const char *path;
	g_variant_iter_init (&it, v);
	while (g_variant_iter_next (&it, "&o", &path)) {
		GDBusProxy *p = g_hash_table_lookup (m->sms, path);
		if (p) {
			if (prop_int (p, "State", 0) == MM_SMS_RECEIVED)
				report_sms (m, p);
		} else {
			watch_sms (m, path);
		}
	}
}

void
modem_sms_delete (Modem *m, const char *path)
{
	if (m->messaging && path)
		call_method (m, m->messaging, "Delete", g_variant_new ("(o)", path), 15000, NULL, NULL);
}

typedef struct {
	ModemSmsCallback cb;
	gpointer ud;
	char *path;
	GDBusProxy *sms;
} SendCtx;

static void
on_sent (Modem *m, GVariant *r, const GError *err, gpointer data)
{
	SendCtx *s = data;
	(void)r;
	if (s->cb)
		s->cb (err == NULL, err ? err->message : NULL, s->ud);
	/* sent or not, the object has done its job: MM would keep it for ever */
	modem_sms_delete (m, s->path);
	g_clear_object (&s->sms);
	g_free (s->path);
	g_free (s);
}

static void
on_sms_created (Modem *m, GVariant *r, const GError *err, gpointer data)
{
	SendCtx *s = data;
	if (err || !r) {
		if (s->cb)
			s->cb (FALSE, err ? err->message : "no SMS object", s->ud);
		g_free (s);
		return;
	}
	g_variant_get (r, "(o)", &s->path);
	s->sms = proxy_for (m, s->path, IF_SMS);
	if (!s->sms) {
		if (s->cb)
			s->cb (FALSE, "SMS object unavailable", s->ud);
		modem_sms_delete (m, s->path);
		g_free (s->path);
		g_free (s);
		return;
	}
	call_method (m, s->sms, "Send", NULL, 120000, on_sent, s);
}

void
modem_sms_send (Modem *m, const char *number, const char *text, ModemSmsCallback cb, gpointer ud)
{
	if (!m->messaging) {
		if (cb)
			cb (FALSE, "no modem messaging interface", ud);
		return;
	}
	SendCtx *s = g_new0 (SendCtx, 1);
	s->cb = cb;
	s->ud = ud;
	GVariantBuilder b;
	g_variant_builder_init (&b, G_VARIANT_TYPE ("a{sv}"));
	g_variant_builder_add (&b, "{sv}", "number", g_variant_new_string (number));
	g_variant_builder_add (&b, "{sv}", "text", g_variant_new_string (text));
	call_method (m, m->messaging, "Create", g_variant_new ("(a{sv})", &b), 30000, on_sms_created, s);
}

/* ---- the modem ---- */

static void
on_voice_signal (GDBusProxy *p, const char *sender, const char *signal, GVariant *params, gpointer data)
{
	Modem *m = data;
	(void)p;
	(void)sender;
	if (!g_variant_is_of_type (params, G_VARIANT_TYPE ("(o)")))
		return;
	const char *path;
	g_variant_get (params, "(&o)", &path);
	if (!strcmp (signal, "CallAdded")) {
		add_call (m, path, TRUE);
	} else if (!strcmp (signal, "CallDeleted")) {
		g_message ("call %s deleted", path);
		g_autofree char *keep = g_strdup (path);
		g_hash_table_remove (m->calls, keep);
		if (m->cb.call_deleted)
			m->cb.call_deleted (m->ud, keep);
	}
}

static void
on_messaging_signal (GDBusProxy *p, const char *sender, const char *signal, GVariant *params, gpointer data)
{
	Modem *m = data;
	(void)p;
	(void)sender;
	if (!strcmp (signal, "Added") && g_variant_is_of_type (params, G_VARIANT_TYPE ("(ob)"))) {
		const char *path;
		gboolean received;
		g_variant_get (params, "(&ob)", &path, &received);
		if (received)
			watch_sms (m, path);
	} else if (!strcmp (signal, "Deleted") && g_variant_is_of_type (params, G_VARIANT_TYPE ("(o)"))) {
		const char *path;
		g_variant_get (params, "(&o)", &path);
		g_hash_table_remove (m->sms, path);
		g_hash_table_remove (m->reported, path);
	}
}

static void
attach_messaging (Modem *m, GDBusObject *obj)
{
	if (m->messaging)
		return;
	m->messaging = G_DBUS_PROXY (g_dbus_object_get_interface (obj, IF_MESSAGING));
	if (!m->messaging)
		return;
	m->msg_sig = g_signal_connect (m->messaging, "g-signal", G_CALLBACK (on_messaging_signal), m);
	g_message ("SMS available on %s", m->path);
	modem_sms_rescan (m);
}

static void
maybe_enable (Modem *m, GDBusObject *obj)
{
	g_autoptr (GDBusProxy) mi = G_DBUS_PROXY (g_dbus_object_get_interface (obj, IF_MODEM));
	if (!mi || m->enable_asked || prop_int (mi, "State", 0) != MM_MODEM_STATE_DISABLED)
		return;
	/* gnome-calls enables the modem on a phone; in gateway mode nothing
	 * else may be running to do it */
	m->enable_asked = TRUE;
	g_message ("Modem %s is disabled — enabling it", g_dbus_object_get_object_path (obj));
	call_method (m, mi, "Enable", g_variant_new ("(b)", TRUE), 60000, NULL, NULL);
}

static void
attach (Modem *m, GDBusObject *obj)
{
	g_autoptr (GDBusInterface) v = g_dbus_object_get_interface (obj, IF_VOICE);
	if (m->path || !v)
		return;
	m->path = g_strdup (g_dbus_object_get_object_path (obj));
	m->voice = G_DBUS_PROXY (g_object_ref (v));
	m->modem = G_DBUS_PROXY (g_dbus_object_get_interface (obj, IF_MODEM));
	m->voice_sig = g_signal_connect (m->voice, "g-signal", G_CALLBACK (on_voice_signal), m);
	g_autofree char *model = prop_str (m->modem, "Model");
	g_message ("Using modem %s (%s)", m->path, model ? model : "?");
	maybe_enable (m, obj);
	attach_messaging (m, obj);
	if (m->cb.present)
		m->cb.present (m->ud, TRUE);
	g_autoptr (GVariant) calls = g_dbus_proxy_get_cached_property (m->voice, "Calls");
	if (calls && g_variant_is_of_type (calls, G_VARIANT_TYPE ("ao"))) {
		GVariantIter it;
		const char *path;
		g_variant_iter_init (&it, calls);
		while (g_variant_iter_next (&it, "&o", &path))
			add_call (m, path, TRUE);
	}
}

static void
detach (Modem *m)
{
	if (!m->path)
		return;
	g_message ("Modem %s went away", m->path);
	GList *paths = g_hash_table_get_keys (m->calls);
	GPtrArray *gone = g_ptr_array_new_with_free_func (g_free);
	for (GList *l = paths; l; l = l->next)
		g_ptr_array_add (gone, g_strdup (l->data));
	g_list_free (paths);
	g_hash_table_remove_all (m->calls);
	g_hash_table_remove_all (m->sms);
	g_hash_table_remove_all (m->reported);
	if (m->voice_sig)
		g_signal_handler_disconnect (m->voice, m->voice_sig);
	if (m->msg_sig)
		g_signal_handler_disconnect (m->messaging, m->msg_sig);
	m->voice_sig = m->msg_sig = 0;
	g_clear_object (&m->voice);
	g_clear_object (&m->messaging);
	g_clear_object (&m->modem);
	g_clear_pointer (&m->path, g_free);
	m->enable_asked = FALSE;
	for (guint i = 0; i < gone->len; i++)
		if (m->cb.call_deleted)
			m->cb.call_deleted (m->ud, g_ptr_array_index (gone, i));
	g_ptr_array_unref (gone);
	if (m->cb.present)
		m->cb.present (m->ud, FALSE);
}

static void
pick (Modem *m)
{
	if (m->path || !m->om)
		return;
	GList *objs = g_dbus_object_manager_get_objects (m->om);
	for (GList *l = objs; l && !m->path; l = l->next)
		attach (m, l->data);
	/* no Voice anywhere yet: a disabled modem may only grow it once enabled */
	for (GList *l = objs; l && !m->path; l = l->next)
		maybe_enable (m, l->data);
	g_list_free_full (objs, g_object_unref);
	if (!m->path)
		g_message ("No modem with voice support yet — waiting for ModemManager");
}

static void
on_object_added (GDBusObjectManager *om, GDBusObject *obj, gpointer data)
{
	(void)om;
	Modem *m = data;
	if (!m->path)
		attach (m, obj);
	if (!m->path)
		maybe_enable (m, obj);
}

static void
on_object_removed (GDBusObjectManager *om, GDBusObject *obj, gpointer data)
{
	(void)om;
	Modem *m = data;
	if (m->path && !strcmp (m->path, g_dbus_object_get_object_path (obj))) {
		detach (m);
		pick (m);
	}
}

static void
on_interface_added (GDBusObjectManager *om, GDBusObject *obj, GDBusInterface *iface, gpointer data)
{
	(void)om;
	Modem *m = data;
	GDBusInterfaceInfo *info = g_dbus_interface_get_info (iface);
	const char *name = info ? info->name : g_dbus_proxy_get_interface_name (G_DBUS_PROXY (iface));
	if (!m->path && !g_strcmp0 (name, IF_VOICE))
		attach (m, obj);
	else if (m->path && !strcmp (m->path, g_dbus_object_get_object_path (obj)) && !g_strcmp0 (name, IF_MESSAGING))
		attach_messaging (m, obj);
}

static void
on_interface_removed (GDBusObjectManager *om, GDBusObject *obj, GDBusInterface *iface, gpointer data)
{
	(void)om;
	Modem *m = data;
	const char *name = g_dbus_proxy_get_interface_name (G_DBUS_PROXY (iface));
	if (m->path && !strcmp (m->path, g_dbus_object_get_object_path (obj)) && !g_strcmp0 (name, IF_VOICE)) {
		detach (m);
		pick (m);
	}
}

Modem *
modem_new (const ModemCallbacks *cb, gpointer ud)
{
	g_autoptr (GError) err = NULL;
	Modem *m = g_new0 (Modem, 1);
	m->cb = *cb;
	m->ud = ud;
	m->cancel = g_cancellable_new ();
	m->calls = g_hash_table_new_full (g_str_hash, g_str_equal, g_free, g_object_unref);
	m->sms = g_hash_table_new_full (g_str_hash, g_str_equal, g_free, g_object_unref);
	m->reported = g_hash_table_new_full (g_str_hash, g_str_equal, g_free, NULL);
	m->bus = g_bus_get_sync (G_BUS_TYPE_SYSTEM, NULL, &err);
	if (!m->bus) {
		g_warning ("No system bus: %s — running without a modem", err->message);
		return m;
	}
	m->om = g_dbus_object_manager_client_new_sync (m->bus, G_DBUS_OBJECT_MANAGER_CLIENT_FLAGS_NONE, MM_BUS, MM_PATH,
						       NULL, NULL, NULL, m->cancel, &err);
	if (!m->om) {
		g_warning ("ModemManager object manager: %s — running without a modem", err->message);
		return m;
	}
	g_signal_connect (m->om, "object-added", G_CALLBACK (on_object_added), m);
	g_signal_connect (m->om, "object-removed", G_CALLBACK (on_object_removed), m);
	g_signal_connect (m->om, "interface-added", G_CALLBACK (on_interface_added), m);
	g_signal_connect (m->om, "interface-removed", G_CALLBACK (on_interface_removed), m);
	pick (m);
	return m;
}

void
modem_free (Modem *m)
{
	if (!m)
		return;
	g_cancellable_cancel (m->cancel);
	if (m->om)
		g_signal_handlers_disconnect_by_data (m->om, m);
	if (m->voice_sig)
		g_signal_handler_disconnect (m->voice, m->voice_sig);
	if (m->msg_sig)
		g_signal_handler_disconnect (m->messaging, m->msg_sig);
	GHashTableIter it;
	gpointer k, v;
	g_hash_table_iter_init (&it, m->calls);
	while (g_hash_table_iter_next (&it, &k, &v))
		g_signal_handlers_disconnect_by_data (v, m);
	g_hash_table_iter_init (&it, m->sms);
	while (g_hash_table_iter_next (&it, &k, &v))
		g_signal_handlers_disconnect_by_data (v, m);
	g_hash_table_unref (m->calls);
	g_hash_table_unref (m->sms);
	g_hash_table_unref (m->reported);
	g_clear_object (&m->voice);
	g_clear_object (&m->messaging);
	g_clear_object (&m->modem);
	g_clear_object (&m->om);
	g_clear_object (&m->bus);
	g_clear_object (&m->cancel);
	g_free (m->path);
	g_free (m);
}
