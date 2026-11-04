/* Logs: reached from the Settings header.  gsm2sipd's own ring of lines
 * when it opens, then every line as the daemon writes it. */
#include "gs-window.h"

/* what the view keeps while it stays open */
#define MAX_LINES 2000

struct GsLogs {
	GtkWidget *view;
	GtkTextBuffer *buf;
	GtkWidget *scroller;
	GCancellable *cancel;
};

static gboolean
scroll_end_idle (gpointer data)
{
	GsLogs *l = data;
	GtkTextIter end;
	gtk_text_buffer_get_end_iter (l->buf, &end);
	GtkTextMark *m = gtk_text_buffer_create_mark (l->buf, NULL, &end, FALSE);
	gtk_text_view_scroll_mark_onscreen (GTK_TEXT_VIEW (l->view), m);
	gtk_text_buffer_delete_mark (l->buf, m);
	return G_SOURCE_REMOVE;
}

static void
scroll_end (GsLogs *l)
{
	/* after the new text is laid out */
	g_idle_add_full (G_PRIORITY_LOW, scroll_end_idle, l, NULL);
}

void
gs_logs_append (GsCtx *ctx, const char *line)
{
	GsLogs *l = ctx->logs;
	GtkTextIter end;
	gtk_text_buffer_get_end_iter (l->buf, &end);
	if (gtk_text_buffer_get_char_count (l->buf) > 0)
		gtk_text_buffer_insert (l->buf, &end, "\n", 1);
	gtk_text_buffer_insert (l->buf, &end, line, -1);
	int over = gtk_text_buffer_get_line_count (l->buf) - MAX_LINES;
	if (over > 0) {
		GtkTextIter a, b;
		gtk_text_buffer_get_start_iter (l->buf, &a);
		gtk_text_buffer_get_iter_at_line (l->buf, &b, over);
		gtk_text_buffer_delete (l->buf, &a, &b);
	}
	scroll_end (l);
}

static void
on_log (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GError) err = NULL;
	g_autoptr (GVariant) v = gs_gateway_call_finish (GS_GATEWAY (src), res, &err);
	if (!v)
		return;
	GsCtx *ctx = data;
	g_autofree const char **lines = NULL;
	g_variant_get (v, "(^a&s)", &lines);
	/* an empty ring keeps what the view has */
	if (!lines || !*lines)
		return;
	g_autofree char *text = g_strjoinv ("\n", (char **)lines);
	gtk_text_buffer_set_text (ctx->logs->buf, text, -1);
	scroll_end (ctx->logs);
}

void
gs_logs_open (GsCtx *ctx)
{
	GsLogs *l = ctx->logs;
	g_cancellable_cancel (l->cancel);
	g_clear_object (&l->cancel);
	l->cancel = g_cancellable_new ();
	gs_gateway_call (ctx->gw, "GetLog", NULL, l->cancel, on_log, ctx);
	scroll_end (l);
}

static void
on_back (GsCtx *ctx)
{
	gs_ctx_show (ctx, "settings");
}

static void
on_copy (GsCtx *ctx)
{
	GtkTextIter a, b;
	gtk_text_buffer_get_bounds (ctx->logs->buf, &a, &b);
	g_autofree char *text = gtk_text_buffer_get_text (ctx->logs->buf, &a, &b, FALSE);
	if (!*text) {
		gs_ctx_toast (ctx, "Log is empty", FALSE);
		return;
	}
	gdk_clipboard_set_text (gtk_widget_get_clipboard (GTK_WIDGET (ctx->win)), text);
	gs_ctx_toast (ctx, "Log copied to clipboard", FALSE);
}

static void
on_clear (GsCtx *ctx)
{
	/* the daemon's ring too, as the Android app clears its service's */
	gs_gateway_call (ctx->gw, "ClearLog", NULL, NULL, NULL, NULL);
	gtk_text_buffer_set_text (ctx->logs->buf, "", 0);
	gs_ctx_toast (ctx, "Log cleared", FALSE);
}

static void
logs_free (GsLogs *l)
{
	g_cancellable_cancel (l->cancel);
	g_clear_object (&l->cancel);
	g_idle_remove_by_data (l);
	g_free (l);
}

GtkWidget *
gs_logs_new (GsCtx *ctx)
{
	GsLogs *l = ctx->logs = g_new0 (GsLogs, 1);
	GtkWidget *page = gtk_box_new (GTK_ORIENTATION_VERTICAL, 0);
	g_object_set_data_full (G_OBJECT (page), "gs-logs", l, (GDestroyNotify)logs_free);

	GtkWidget *header = gtk_box_new (GTK_ORIENTATION_HORIZONTAL, 0);
	gtk_widget_add_css_class (header, "gs-page");
	gtk_box_append (GTK_BOX (header), gs_icon_button ("gs-arrow-back-symbolic", "Back", G_CALLBACK (on_back), ctx));
	GtkWidget *title = gs_label ("Logs", "gs-title");
	gtk_widget_set_hexpand (title, TRUE);
	gtk_widget_set_margin_start (title, 12);
	gtk_box_append (GTK_BOX (header), title);
	GtkWidget *copy = gs_icon_button ("gs-copy-symbolic", "Copy log", G_CALLBACK (on_copy), ctx);
	gtk_widget_set_margin_end (copy, 8);
	gtk_box_append (GTK_BOX (header), copy);
	GtkWidget *clear = gs_icon_button ("gs-trash-symbolic", "Clear log", G_CALLBACK (on_clear), ctx);
	gtk_widget_add_css_class (clear, "gs-danger");
	gtk_box_append (GTK_BOX (header), clear);
	GtkWidget *handle = gtk_window_handle_new ();
	gtk_window_handle_set_child (GTK_WINDOW_HANDLE (handle), header);
	gtk_box_append (GTK_BOX (page), handle);

	l->view = gtk_text_view_new ();
	gtk_widget_add_css_class (l->view, "gs-log");
	gtk_text_view_set_editable (GTK_TEXT_VIEW (l->view), FALSE);
	gtk_text_view_set_cursor_visible (GTK_TEXT_VIEW (l->view), FALSE);
	gtk_text_view_set_wrap_mode (GTK_TEXT_VIEW (l->view), GTK_WRAP_WORD_CHAR);
	gtk_text_view_set_monospace (GTK_TEXT_VIEW (l->view), TRUE);
	gtk_text_view_set_left_margin (GTK_TEXT_VIEW (l->view), 8);
	gtk_text_view_set_right_margin (GTK_TEXT_VIEW (l->view), 8);
	gtk_text_view_set_top_margin (GTK_TEXT_VIEW (l->view), 8);
	gtk_text_view_set_bottom_margin (GTK_TEXT_VIEW (l->view), 8);
	l->buf = gtk_text_view_get_buffer (GTK_TEXT_VIEW (l->view));
	l->scroller = gtk_scrolled_window_new ();
	gtk_widget_add_css_class (l->scroller, "gs-logpane");
	gtk_scrolled_window_set_policy (GTK_SCROLLED_WINDOW (l->scroller), GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
	gtk_scrolled_window_set_child (GTK_SCROLLED_WINDOW (l->scroller), l->view);
	gtk_widget_set_vexpand (l->scroller, TRUE);
	gtk_widget_set_margin_start (l->scroller, 14);
	gtk_widget_set_margin_end (l->scroller, 14);
	gtk_widget_set_margin_bottom (l->scroller, 14);
	gtk_box_append (GTK_BOX (page), l->scroller);
	return page;
}
