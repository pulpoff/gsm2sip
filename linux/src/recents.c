#define G_LOG_DOMAIN "recents"
#include "recents.h"
#include <errno.h>
#include <string.h>

/* a recent-calls view: nothing reads past the top of it, and every change
 * rewrites the whole file */
#define MAX_ENTRIES 500

struct GwRecents {
	char *path;
	GPtrArray *entries;	/* GVariant a{sv}, oldest first */
	GwRecentsChanged changed;
	gpointer changed_data;
};

static void
save (GwRecents *r)
{
	if (r->path) {
		GVariantBuilder b;
		g_variant_builder_init (&b, G_VARIANT_TYPE ("aa{sv}"));
		for (guint i = 0; i < r->entries->len; i++)
			g_variant_builder_add_value (&b, r->entries->pdata[i]);
		g_autoptr (GVariant) v = g_variant_ref_sink (g_variant_builder_end (&b));
		g_autofree char *text = g_variant_print (v, FALSE);
		g_autoptr (GError) err = NULL;
		g_autofree char *dir = g_path_get_dirname (r->path);
		if (g_mkdir_with_parents (dir, 0700) < 0 ||
		    !g_file_set_contents_full (r->path, text, -1, G_FILE_SET_CONTENTS_CONSISTENT, 0600, &err))
			g_warning ("%s: %s", r->path, err ? err->message : g_strerror (errno));
	}
	if (r->changed)
		r->changed (r->changed_data);
}

static void
load (GwRecents *r)
{
	g_autofree char *text = NULL;
	g_autoptr (GError) err = NULL;
	if (!g_file_get_contents (r->path, &text, NULL, &err)) {
		if (!g_error_matches (err, G_FILE_ERROR, G_FILE_ERROR_NOENT))
			g_warning ("%s: %s", r->path, err->message);
		return;
	}
	g_autoptr (GVariant) v = g_variant_parse (G_VARIANT_TYPE ("aa{sv}"), text, NULL, NULL, &err);
	if (!v) {
		g_warning ("%s: %s — starting an empty list", r->path, err->message);
		return;
	}
	GVariantIter it;
	GVariant *e;
	g_variant_iter_init (&it, v);
	while ((e = g_variant_iter_next_value (&it)))
		g_ptr_array_add (r->entries, e);
}

GwRecents *
gw_recents_new (const char *path)
{
	GwRecents *r = g_new0 (GwRecents, 1);
	r->path = g_strdup (path);
	r->entries = g_ptr_array_new_with_free_func ((GDestroyNotify)g_variant_unref);
	if (path)
		load (r);
	return r;
}

void
gw_recents_free (GwRecents *r)
{
	if (!r)
		return;
	g_ptr_array_unref (r->entries);
	g_free (r->path);
	g_free (r);
}

void
gw_recents_set_changed (GwRecents *r, GwRecentsChanged fn, gpointer user_data)
{
	r->changed = fn;
	r->changed_data = user_data;
}

static void
append (GwRecents *r, GVariantDict *d)
{
	g_ptr_array_add (r->entries, g_variant_ref_sink (g_variant_dict_end (d)));
	if (r->entries->len > MAX_ENTRIES)
		g_ptr_array_remove_range (r->entries, 0, r->entries->len - MAX_ENTRIES);
	save (r);
}

void
gw_recents_add_call (GwRecents *r, gboolean incoming, const char *number, gint64 ts_ms, gint64 dur_s)
{
	g_auto (GVariantDict) d;
	g_variant_dict_init (&d, NULL);
	g_variant_dict_insert (&d, "dir", "s", incoming ? "IN" : "OUT");
	g_variant_dict_insert (&d, "num", "s", number ? number : "");
	g_variant_dict_insert (&d, "ts", "x", ts_ms);
	g_variant_dict_insert (&d, "dur", "x", dur_s);
	g_variant_dict_insert (&d, "type", "s", "CALL");
	append (r, &d);
}

static int
find_sms (GwRecents *r, const char *sms_id)
{
	/* newest last, and a repeated id is always the latest one */
	for (int i = (int)r->entries->len - 1; i >= 0; i--) {
		const char *id = NULL;
		if (g_variant_lookup (r->entries->pdata[i], "smsId", "&s", &id) && !g_strcmp0 (id, sms_id))
			return i;
	}
	return -1;
}

void
gw_recents_add_sms (GwRecents *r, gboolean incoming, const char *number, gint64 ts_ms, const char *text,
		    const char *sms_id, const char *encoding, int parts, const char *status)
{
	if (sms_id && *sms_id && find_sms (r, sms_id) >= 0)
		return;
	g_auto (GVariantDict) d;
	g_variant_dict_init (&d, NULL);
	g_variant_dict_insert (&d, "dir", "s", incoming ? "IN" : "OUT");
	g_variant_dict_insert (&d, "num", "s", number ? number : "");
	g_variant_dict_insert (&d, "ts", "x", ts_ms);
	g_variant_dict_insert (&d, "dur", "x", (gint64)0);
	g_variant_dict_insert (&d, "type", "s", "SMS");
	g_variant_dict_insert (&d, "text", "s", text ? text : "");
	if (sms_id && *sms_id)
		g_variant_dict_insert (&d, "smsId", "s", sms_id);
	if (encoding && *encoding)
		g_variant_dict_insert (&d, "enc", "s", encoding);
	if (parts > 0)
		g_variant_dict_insert (&d, "parts", "i", parts);
	if (status && *status)
		g_variant_dict_insert (&d, "status", "s", status);
	append (r, &d);
}

gboolean
gw_recents_update_sms (GwRecents *r, const char *sms_id, const char *status, const char *error)
{
	if (!sms_id || !*sms_id)
		return FALSE;
	int i = find_sms (r, sms_id);
	if (i < 0)
		return FALSE;	/* normal once the list has aged past it */
	g_auto (GVariantDict) d;
	g_variant_dict_init (&d, r->entries->pdata[i]);
	if (status)
		g_variant_dict_insert (&d, "status", "s", status);
	if (error && *error)
		g_variant_dict_insert (&d, "err", "s", error);
	else
		g_variant_dict_remove (&d, "err");
	g_variant_unref (r->entries->pdata[i]);
	r->entries->pdata[i] = g_variant_ref_sink (g_variant_dict_end (&d));
	save (r);
	return TRUE;
}

void
gw_recents_clear (GwRecents *r)
{
	g_ptr_array_set_size (r->entries, 0);
	save (r);
}

GVariant *
gw_recents_list (GwRecents *r)
{
	GVariantBuilder b;
	g_variant_builder_init (&b, G_VARIANT_TYPE ("aa{sv}"));
	for (int i = (int)r->entries->len - 1; i >= 0; i--)
		g_variant_builder_add_value (&b, r->entries->pdata[i]);
	return g_variant_builder_end (&b);
}

guint
gw_recents_count (GwRecents *r)
{
	return r->entries->len;
}
