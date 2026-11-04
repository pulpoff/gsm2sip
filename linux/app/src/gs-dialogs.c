/* The Android app's AlertDialogs: three pre-formatted detail texts (aligned
 * on a monospace column) and the Clear recents confirmation. */
#include "gs-window.h"
#include <string.h>

typedef struct {
	GsCtx *ctx;
	GtkWidget *body;
	GCancellable *cancel;
	char *text;	/* what COPY copies */
} Detail;

static void
detail_free (Detail *d)
{
	g_cancellable_cancel (d->cancel);
	g_clear_object (&d->cancel);
	g_free (d->text);
	g_free (d);
}

static void
detail_set (Detail *d, const char *text)
{
	g_free (d->text);
	d->text = g_strdup (text);
	gtk_label_set_text (GTK_LABEL (d->body), text);
}

static void
on_detail_response (AdwAlertDialog *dlg, const char *response, gpointer data)
{
	Detail *d = data;
	if (!strcmp (response, "copy") && d->text) {
		gdk_clipboard_set_text (gtk_widget_get_clipboard (GTK_WIDGET (d->ctx->win)), d->text);
		gs_ctx_toast (d->ctx, "Copied", FALSE);
	}
}

static void
on_detail_closed (AdwDialog *dlg, gpointer data)
{
	Detail *d = data;
	/* an answer that lands after the dialog is gone is dropped */
	g_cancellable_cancel (d->cancel);
}

static Detail *
detail_new (GsCtx *ctx, const char *title, const char *text, gboolean copy)
{
	Detail *d = g_new0 (Detail, 1);
	d->ctx = ctx;
	d->cancel = g_cancellable_new ();
	AdwDialog *dlg = adw_alert_dialog_new (title, NULL);
	gtk_widget_add_css_class (GTK_WIDGET (dlg), "gs-dialog");
	d->body = gtk_label_new (NULL);
	gtk_widget_add_css_class (d->body, "gs-detail");
	gtk_label_set_xalign (GTK_LABEL (d->body), 0);
	gtk_label_set_wrap (GTK_LABEL (d->body), TRUE);
	gtk_label_set_wrap_mode (GTK_LABEL (d->body), PANGO_WRAP_WORD_CHAR);
	gtk_label_set_selectable (GTK_LABEL (d->body), TRUE);
	GtkWidget *sw = gtk_scrolled_window_new ();
	gtk_scrolled_window_set_policy (GTK_SCROLLED_WINDOW (sw), GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
	gtk_scrolled_window_set_propagate_natural_height (GTK_SCROLLED_WINDOW (sw), TRUE);
	gtk_scrolled_window_set_max_content_height (GTK_SCROLLED_WINDOW (sw), 520);
	gtk_scrolled_window_set_child (GTK_SCROLLED_WINDOW (sw), d->body);
	adw_alert_dialog_set_extra_child (ADW_ALERT_DIALOG (dlg), sw);
	if (copy)
		adw_alert_dialog_add_response (ADW_ALERT_DIALOG (dlg), "copy", "COPY");
	adw_alert_dialog_add_response (ADW_ALERT_DIALOG (dlg), "close", "CLOSE");
	adw_alert_dialog_set_default_response (ADW_ALERT_DIALOG (dlg), "close");
	adw_alert_dialog_set_close_response (ADW_ALERT_DIALOG (dlg), "close");
	detail_set (d, text);
	g_signal_connect (dlg, "response", G_CALLBACK (on_detail_response), d);
	g_signal_connect (dlg, "closed", G_CALLBACK (on_detail_closed), d);
	g_object_set_data_full (G_OBJECT (dlg), "gs-detail", d, (GDestroyNotify)detail_free);
	adw_dialog_present (dlg, GTK_WIDGET (ctx->win));
	return d;
}

/* ---- Mobile network: the fast block, then the cells ---- */

static void
on_cells (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GError) err = NULL;
	g_autofree char *cells = gs_net_info_mobile_cells_finish (GS_NET_INFO (src), res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED))
		return;
	Detail *d = data;
	if (!cells || !*cells)
		return;
	g_autofree char *text = g_strdup_printf ("%s\n\n%s", d->text, cells);
	detail_set (d, text);
}

void
gs_dialog_mobile (GsCtx *ctx)
{
	g_autofree char *fast = gs_net_info_mobile_details (ctx->net);
	Detail *d = detail_new (ctx, "Mobile network", fast, FALSE);
	gs_net_info_mobile_cells_async (ctx->net, d->cancel, on_cells, d);
}

/* ---- WiFi: the fast block, then MAC/router lines and the pings ---- */

static void
on_wifi_extra (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GError) err = NULL;
	g_autofree char *extra = gs_net_info_wifi_extra_finish (GS_NET_INFO (src), res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED))
		return;
	Detail *d = data;
	if (!extra || !*extra)
		return;
	g_autofree char *text = g_strdup_printf ("%s\n%s", d->text, extra);
	detail_set (d, text);
}

void
gs_dialog_wifi (GsCtx *ctx)
{
	g_autofree char *fast = gs_net_info_wifi_details (ctx->net);
	Detail *d = detail_new (ctx, "WiFi", fast, FALSE);
	gs_net_info_wifi_extra_async (ctx->net, gs_gateway_server (ctx->gw), d->cancel, on_wifi_extra, d);
}

/* ---- Message detail ---- */

static void
row (GString *s, const char *label, const char *value)
{
	g_string_append_printf (s, "%-9s: %s\n", label, value && *value ? value : "—");
}

void
gs_dialog_message (GsCtx *ctx, GVariant *e)
{
	const char *dir = "", *num = "", *text = "", *enc = "", *status = "", *err = "", *smsc = "";
	gint64 ts = 0;
	gint32 parts = 0;
	g_variant_lookup (e, "dir", "&s", &dir);
	g_variant_lookup (e, "num", "&s", &num);
	g_variant_lookup (e, "text", "&s", &text);
	g_variant_lookup (e, "enc", "&s", &enc);
	g_variant_lookup (e, "status", "&s", &status);
	g_variant_lookup (e, "err", "&s", &err);
	g_variant_lookup (e, "smsc", "&s", &smsc);
	g_variant_lookup (e, "ts", "x", &ts);
	g_variant_lookup (e, "parts", "i", &parts);
	gboolean in = !strcmp (dir, "IN");
	const char *own = ctx->own_number;

	g_autoptr (GString) s = g_string_new (NULL);
	row (s, "Direction", in ? "Incoming" : "Outgoing");
	row (s, "From", in ? num : own);
	row (s, "To", in ? own : num);
	row (s, "SMSC", smsc);
	g_autoptr (GDateTime) t = g_date_time_new_from_unix_local (ts / 1000);
	g_autofree char *date = t ? g_date_time_format (t, "%d/%m/%Y %H:%M:%S") : NULL;
	row (s, "Date", date);
	row (s, "Format", in ? NULL : enc);
	g_autofree char *len = parts > 0 ? g_strdup_printf ("%ld chars, %d %s", g_utf8_strlen (text, -1), parts,
							    parts == 1 ? "part" : "parts")
				       : g_strdup_printf ("%ld chars", g_utf8_strlen (text, -1));
	row (s, "Length", len);
	if (!in) {
		row (s, "Status", status);
		if (*err)
			row (s, "Error", err);
	}
	g_string_append_printf (s, "\n%s", *text ? text : "(no text)");
	detail_new (ctx, "Message detail", s->str, TRUE);
}

/* ---- Clear recents? ---- */

static void
on_cleared (GObject *src, GAsyncResult *res, gpointer data)
{
	GsCtx *ctx = data;
	g_autoptr (GError) err = NULL;
	g_autoptr (GVariant) v = gs_gateway_call_finish (GS_GATEWAY (src), res, &err);
	if (!v) {
		g_autofree char *why = gs_gateway_error_text (err);
		gs_ctx_toast (ctx, why, TRUE);
		return;
	}
	gs_ctx_toast (ctx, "Recents cleared", FALSE);
	gs_ctx_reload_recents (ctx);
}

static void
on_clear_response (AdwAlertDialog *dlg, const char *response, gpointer data)
{
	GsCtx *ctx = data;
	if (!strcmp (response, "clear"))
		gs_gateway_call (ctx->gw, "ClearRecents", NULL, NULL, on_cleared, ctx);
}

void
gs_dialog_clear_recents (GsCtx *ctx)
{
	gsize n = ctx->recents ? g_variant_n_children (ctx->recents) : 0;
	if (!n) {
		gs_ctx_toast (ctx, "Nothing to clear", FALSE);
		return;
	}
	g_autofree char *body = g_strdup_printf ("Removes all %" G_GSIZE_FORMAT " %s from the list. This cannot be undone.",
						 n, n == 1 ? "call or message" : "calls and messages");
	AdwDialog *dlg = adw_alert_dialog_new ("Clear recents?", body);
	gtk_widget_add_css_class (GTK_WIDGET (dlg), "gs-dialog");
	adw_alert_dialog_add_response (ADW_ALERT_DIALOG (dlg), "cancel", "CANCEL");
	adw_alert_dialog_add_response (ADW_ALERT_DIALOG (dlg), "clear", "CLEAR");
	adw_alert_dialog_set_close_response (ADW_ALERT_DIALOG (dlg), "cancel");
	g_signal_connect (dlg, "response", G_CALLBACK (on_clear_response), ctx);
	adw_dialog_present (dlg, GTK_WIDGET (ctx->win));
}
