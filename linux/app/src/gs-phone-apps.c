#include "gs-phone-apps.h"
#include <glib/gstdio.h>
#include <string.h>

static const struct {
	const char *unit, *autostart;
} apps[] = {
	{ "calls-daemon.service", "org.gnome.Calls-daemon.desktop" },
	{ "sm.puri.Chatty-daemon.service", "sm.puri.Chatty-daemon.desktop" },
};

/* what tells our overrides from a file the user wrote */
#define MARKER "gateway mode (gsm2sipd owns calls and SMS)"

static gboolean
unit_installed (const char *unit)
{
	static const char *const dirs[] = { "/usr/lib/systemd/user", "/lib/systemd/user", "/etc/systemd/user" };
	for (gsize i = 0; i < G_N_ELEMENTS (dirs); i++) {
		g_autofree char *p = g_build_filename (dirs[i], unit, NULL);
		if (g_file_test (p, G_FILE_TEST_EXISTS))
			return TRUE;
	}
	return FALSE;
}

gboolean
gs_phone_apps_installed (void)
{
	for (gsize i = 0; i < G_N_ELEMENTS (apps); i++)
		if (unit_installed (apps[i].unit))
			return TRUE;
	return FALSE;
}

gboolean
gs_phone_apps_held (void)
{
	for (gsize i = 0; i < G_N_ELEMENTS (apps); i++) {
		if (!unit_installed (apps[i].unit))
			continue;
		g_autofree char *p = g_build_filename (g_get_user_config_dir (), "systemd", "user", apps[i].unit, NULL);
		g_autofree char *target = g_file_read_link (p, NULL);
		if (!target || strcmp (target, "/dev/null"))
			return FALSE;
	}
	return gs_phone_apps_installed ();
}

/* Hidden autostart entries too, for a session that starts them without
 * systemd (X-GNOME-HiddenUnderSystemd covers the other case). */
static void
autostart_set (gboolean hold)
{
	g_autofree char *dir = g_build_filename (g_get_user_config_dir (), "autostart", NULL);
	for (gsize i = 0; i < G_N_ELEMENTS (apps); i++) {
		if (!unit_installed (apps[i].unit))
			continue;
		g_autofree char *p = g_build_filename (dir, apps[i].autostart, NULL);
		g_autofree char *have = NULL;
		gboolean ours = g_file_get_contents (p, &have, NULL, NULL) && strstr (have, MARKER);
		if (hold && !have) {
			g_autofree char *name = g_strndup (apps[i].autostart, strlen (apps[i].autostart) - strlen (".desktop"));
			g_autofree char *text = g_strdup_printf ("[Desktop Entry]\nType=Application\nName=%s\n"
								 "# " MARKER "; the gsm2sip app's Enabled switch removes it\n"
								 "Hidden=true\n", name);
			g_mkdir_with_parents (dir, 0755);
			g_file_set_contents (p, text, -1, NULL);
		} else if (!hold && ours) {
			g_unlink (p);
		}
	}
}

/* ---- the systemd calls, one after the other ---- */

typedef struct {
	const char *method;
	GVariant *params;
	gboolean must;	/* its failure fails the whole */
} Step;

typedef struct {
	GDBusConnection *bus;
	GPtrArray *steps;
	guint next;
	gboolean hold;
} Job;

static void
step_free (Step *s)
{
	if (s->params)
		g_variant_unref (s->params);
	g_free (s);
}

static void
job_free (Job *j)
{
	g_clear_object (&j->bus);
	g_ptr_array_unref (j->steps);
	g_free (j);
}

static void
add (Job *j, const char *method, GVariant *params, gboolean must)
{
	Step *s = g_new0 (Step, 1);
	s->method = method;
	s->params = params ? g_variant_ref_sink (params) : NULL;
	s->must = must;
	g_ptr_array_add (j->steps, s);
}

static void run_next (GTask *task);

static void
on_step (GObject *src, GAsyncResult *res, gpointer data)
{
	GTask *task = data;
	Job *j = g_task_get_task_data (task);
	Step *s = j->steps->pdata[j->next - 1];
	g_autoptr (GError) err = NULL;
	g_autoptr (GVariant) v = g_dbus_connection_call_finish (G_DBUS_CONNECTION (src), res, &err);
	if (!v) {
		if (s->must) {
			g_task_return_error (task, g_steal_pointer (&err));
			g_object_unref (task);
			return;
		}
		g_message ("%s: %s", s->method, err->message);
	}
	run_next (task);
}

static void
run_next (GTask *task)
{
	Job *j = g_task_get_task_data (task);
	if (j->next >= j->steps->len) {
		autostart_set (j->hold);
		g_task_return_boolean (task, TRUE);
		g_object_unref (task);
		return;
	}
	Step *s = j->steps->pdata[j->next++];
	g_dbus_connection_call (j->bus, "org.freedesktop.systemd1", "/org/freedesktop/systemd1",
				"org.freedesktop.systemd1.Manager", s->method, s->params, NULL, G_DBUS_CALL_FLAGS_NONE,
				30000, g_task_get_cancellable (task), on_step, task);
}

void
gs_phone_apps_hold_async (gboolean hold, GCancellable *cancel, GAsyncReadyCallback cb, gpointer user_data)
{
	GTask *task = g_task_new (NULL, cancel, cb, user_data);
	g_autoptr (GError) err = NULL;
	GDBusConnection *bus = g_bus_get_sync (G_BUS_TYPE_SESSION, cancel, &err);
	if (!bus) {
		g_task_return_error (task, g_steal_pointer (&err));
		g_object_unref (task);
		return;
	}
	Job *j = g_new0 (Job, 1);
	j->bus = bus;
	j->hold = hold;
	j->steps = g_ptr_array_new_with_free_func ((GDestroyNotify)step_free);
	GVariantBuilder units;
	g_variant_builder_init (&units, G_VARIANT_TYPE ("as"));
	for (gsize i = 0; i < G_N_ELEMENTS (apps); i++)
		if (unit_installed (apps[i].unit))
			g_variant_builder_add (&units, "s", apps[i].unit);
	GVariant *list = g_variant_builder_end (&units);
	g_variant_ref_sink (list);
	if (hold) {
		/* stopped first, so nothing still holds the modem's calls or
		 * messages when gsm2sipd takes it */
		for (gsize i = 0; i < g_variant_n_children (list); i++) {
			const char *u = NULL;
			g_variant_get_child (list, i, "&s", &u);
			add (j, "StopUnit", g_variant_new ("(ss)", u, "replace"), FALSE);
		}
		add (j, "MaskUnitFiles", g_variant_new ("(@asbb)", list, FALSE, TRUE), TRUE);
		add (j, "Reload", NULL, FALSE);
	} else {
		add (j, "UnmaskUnitFiles", g_variant_new ("(@asb)", list, FALSE), TRUE);
		add (j, "Reload", NULL, TRUE);
		for (gsize i = 0; i < g_variant_n_children (list); i++) {
			const char *u = NULL;
			g_variant_get_child (list, i, "&s", &u);
			add (j, "StartUnit", g_variant_new ("(ss)", u, "replace"), FALSE);
		}
	}
	g_variant_unref (list);
	g_task_set_task_data (task, j, (GDestroyNotify)job_free);
	run_next (task);
}

gboolean
gs_phone_apps_hold_finish (GAsyncResult *res, GError **error)
{
	return g_task_propagate_boolean (G_TASK (res), error);
}
