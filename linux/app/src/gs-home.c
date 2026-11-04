/* Home: activity_main.xml's home tab.  Header, the two link chips, the
 * filters, and a scroller with the active-call card over the recents. */
#include "gs-window.h"
#include <string.h>

#define MAX_ROWS 30

struct GsHome {
	GtkWidget *status, *status_icon, *tls, *srtp;
	GtkWidget *mobile, *wifi;
	GtkWidget *chips[4];
	const char *filter;	/* "all" "in" "out" "sms" */
	GtkWidget *card, *dir, *timer, *callnum, *connected, *mute, *mute_icon, *mute_label;
	GtkWidget *list, *empty;
	guint link_timer, call_timer;
	gboolean visible;
};

static const char *const filters[] = { "all", "in", "out", "sms" };
static const char *const filter_names[] = { "All", "Incoming", "Outgoing", "SMS" };

/* ---- formats ---- */

/* the live timer: MM:SS below an hour, H:MM:SS from one */
static char *
fmt_timer (gint64 secs)
{
	if (secs < 0)
		secs = 0;
	if (secs >= 3600)
		return g_strdup_printf ("%d:%02d:%02d", (int)(secs / 3600), (int)(secs / 60 % 60), (int)(secs % 60));
	return g_strdup_printf ("%02d:%02d", (int)(secs / 60), (int)(secs % 60));
}

/* a row's time: HH:mm today, "12 Oct 12:32:22" on any other day, in
 * English like the Android app whatever the locale */
static char *
fmt_row_time (gint64 ms)
{
	static const char *const mon[] = { "Jan", "Feb", "Mar", "Apr", "May", "Jun",
					   "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
	g_autoptr (GDateTime) t = g_date_time_new_from_unix_local (ms / 1000);
	g_autoptr (GDateTime) now = g_date_time_new_now_local ();
	if (!t)
		return g_strdup ("");
	if (g_date_time_get_year (t) == g_date_time_get_year (now) &&
	    g_date_time_get_day_of_year (t) == g_date_time_get_day_of_year (now))
		return g_date_time_format (t, "%H:%M");
	g_autofree char *hms = g_date_time_format (t, "%H:%M:%S");
	return g_strdup_printf ("%d %s %s", g_date_time_get_day_of_month (t), mon[g_date_time_get_month (t) - 1], hms);
}

/* ---- the header's status badge ---- */

static const char *
status_class (GsCtx *ctx)
{
	const char *st = gs_gateway_state (ctx->gw);
	if (!strcmp (st, "registered") || strcmp (gs_gateway_call_state (ctx->gw), "idle"))
		return "gs-online";
	if (!strcmp (st, "connecting"))
		return "gs-connecting";
	return "gs-offline";	/* failed, disabled, unconfigured, no daemon */
}

static void
on_status (GsCtx *ctx)
{
	const char *st = gs_gateway_state (ctx->gw);
	if (!strcmp (status_class (ctx), "gs-online")) {
		gs_ctx_toast (ctx, "Registered", FALSE);
	} else if (!strcmp (st, "absent")) {
		gs_ctx_toast (ctx, "gsm2sipd is not running", FALSE);
	} else if (!strcmp (st, "disabled")) {
		gs_ctx_toast (ctx, "gsm2sip is off — switch it on in Settings", FALSE);
	} else if (!strcmp (st, "unconfigured")) {
		gs_ctx_toast (ctx, "Set the SIP server in Settings first", FALSE);
	} else {
		gs_ctx_toast (ctx, "Reconnecting…", FALSE);
		gs_gateway_call (ctx->gw, "Reconnect", NULL, NULL, NULL, NULL);
	}
}

static GtkWidget *
badge (const char *icon, const char *css, const char *a11y)
{
	GtkWidget *b = gtk_image_new_from_icon_name (icon);
	gtk_widget_add_css_class (b, "gs-badge");
	gtk_widget_add_css_class (b, css);
	gtk_widget_set_valign (b, GTK_ALIGN_CENTER);
	gtk_widget_set_margin_start (b, 6);
	gtk_widget_set_tooltip_text (b, a11y);
	gtk_accessible_update_property (GTK_ACCESSIBLE (b), GTK_ACCESSIBLE_PROPERTY_LABEL, a11y, -1);
	return b;
}

/* ---- link chips ---- */

static void
refresh_links (GsCtx *ctx)
{
	g_autofree char *m = gs_net_info_mobile_chip (ctx->net);
	g_autofree char *w = gs_net_info_wifi_chip (ctx->net);
	gtk_button_set_label (GTK_BUTTON (ctx->home->mobile), m && *m ? m : "—");
	gtk_button_set_label (GTK_BUTTON (ctx->home->wifi), w && *w ? w : "—");
}

static gboolean
link_tick (gpointer data)
{
	refresh_links (data);
	return G_SOURCE_CONTINUE;
}

/* a source that came or first answered: no waiting for the next tick */
static void
on_net_changed (GsNetInfo *net, gpointer data)
{
	GsCtx *ctx = data;
	if (ctx->home->visible)
		refresh_links (ctx);
}

static GtkWidget *
link_chip (GCallback cb, GsCtx *ctx)
{
	GtkWidget *b = gtk_button_new_with_label ("—");
	gtk_widget_add_css_class (b, "gs-linkchip");
	gtk_widget_set_hexpand (b, TRUE);
	GtkWidget *l = gtk_button_get_child (GTK_BUTTON (b));
	gtk_label_set_ellipsize (GTK_LABEL (l), PANGO_ELLIPSIZE_END);
	gtk_label_set_xalign (GTK_LABEL (l), 0);
	gtk_label_set_single_line_mode (GTK_LABEL (l), TRUE);
	g_signal_connect_swapped (b, "clicked", cb, ctx);
	return b;
}

/* ---- the active-call card ---- */

static void
tick_timer (GsCtx *ctx);

static gboolean
call_tick (gpointer data)
{
	GsCtx *ctx = data;
	ctx->home->call_timer = 0;
	tick_timer (ctx);
	return G_SOURCE_REMOVE;
}

static void
tick_timer (GsCtx *ctx)
{
	GsHome *h = ctx->home;
	gint64 at = gs_gateway_call_bridged_at (ctx->gw);
	gint64 elapsed = at ? g_get_real_time () / 1000 - at : 0;
	g_autofree char *t = fmt_timer (elapsed / 1000);
	gtk_label_set_text (GTK_LABEL (h->timer), t);
	g_clear_handle_id (&h->call_timer, g_source_remove);
	if (h->visible && gtk_widget_get_visible (h->card))
		/* on the gateway's own second boundaries, so reopening the app
		 * mid-call shows the true time */
		h->call_timer = g_timeout_add (1000 - (guint)((elapsed % 1000 + 1000) % 1000), call_tick, ctx);
}

static void
on_mute (GsCtx *ctx)
{
	gboolean muted = gs_gateway_agent_muted (ctx->gw);
	gs_gateway_call (ctx->gw, "SetAgentMuted", g_variant_new ("(b)", !muted), NULL, NULL, NULL);
}

static void
on_end (GsCtx *ctx)
{
	gs_gateway_call (ctx->gw, "Hangup", NULL, NULL, NULL, NULL);
}

static GtkWidget *
call_button (const char *icon, const char *label, GtkWidget **icon_out, GtkWidget **label_out)
{
	GtkWidget *b = gtk_button_new ();
	gtk_widget_add_css_class (b, "gs-callbtn");
	gtk_widget_add_css_class (b, "gs-material");
	gtk_widget_set_hexpand (b, TRUE);
	GtkWidget *box = gtk_box_new (GTK_ORIENTATION_VERTICAL, 2);
	GtkWidget *img = gtk_image_new_from_icon_name (icon);
	GtkWidget *l = gtk_label_new (label);
	gtk_box_append (GTK_BOX (box), img);
	gtk_box_append (GTK_BOX (box), l);
	gtk_button_set_child (GTK_BUTTON (b), box);
	if (icon_out)
		*icon_out = img;
	if (label_out)
		*label_out = l;
	return b;
}

static GtkWidget *
call_card (GsCtx *ctx)
{
	GsHome *h = ctx->home;
	GtkWidget *card = gtk_box_new (GTK_ORIENTATION_VERTICAL, 0);
	gtk_widget_add_css_class (card, "gs-card");
	gtk_widget_add_css_class (card, "gs-callcard");

	GtkWidget *top = gtk_box_new (GTK_ORIENTATION_HORIZONTAL, 0);
	GtkWidget *active = gs_label ("● ACTIVE CALL", "gs-call-active");
	gtk_widget_set_hexpand (active, TRUE);
	h->dir = gtk_label_new ("GSM → SIP");
	gtk_widget_add_css_class (h->dir, "gs-dirpill");
	gtk_box_append (GTK_BOX (top), active);
	gtk_box_append (GTK_BOX (top), h->dir);
	gtk_box_append (GTK_BOX (card), top);

	h->timer = gs_label ("00:00", "gs-timer");
	gtk_widget_set_margin_top (h->timer, 10);
	gtk_box_append (GTK_BOX (card), h->timer);
	h->callnum = gs_label ("—", "gs-callnum");
	gtk_widget_set_margin_top (h->callnum, 6);
	gtk_label_set_ellipsize (GTK_LABEL (h->callnum), PANGO_ELLIPSIZE_END);
	gtk_box_append (GTK_BOX (card), h->callnum);
	h->connected = gs_label ("Connected", "gs-connected");
	gtk_widget_set_margin_top (h->connected, 2);
	gtk_box_append (GTK_BOX (card), h->connected);

	/* MUTE and END; Android's SNOOP has nothing behind it on Linux yet */
	GtkWidget *row = gtk_box_new (GTK_ORIENTATION_HORIZONTAL, 8);
	gtk_box_set_homogeneous (GTK_BOX (row), TRUE);
	gtk_widget_set_margin_top (row, 16);
	h->mute = call_button ("gs-volume-xmark-symbolic", "MUTE", &h->mute_icon, &h->mute_label);
	g_signal_connect_swapped (h->mute, "clicked", G_CALLBACK (on_mute), ctx);
	GtkWidget *end = call_button ("gs-phone-slash-symbolic", "END", NULL, NULL);
	gtk_widget_add_css_class (end, "gs-end");
	g_signal_connect_swapped (end, "clicked", G_CALLBACK (on_end), ctx);
	gtk_box_append (GTK_BOX (row), h->mute);
	gtk_box_append (GTK_BOX (row), end);
	gtk_box_append (GTK_BOX (card), row);
	gtk_widget_set_visible (card, FALSE);
	return card;
}

/* ---- recents ---- */

static gboolean
entry_shown (const char *filter, GVariant *e)
{
	const char *dir = "", *type = "CALL";
	g_variant_lookup (e, "dir", "&s", &dir);
	g_variant_lookup (e, "type", "&s", &type);
	if (!strcmp (filter, "in"))
		return !strcmp (dir, "IN");
	if (!strcmp (filter, "out"))
		return strcmp (dir, "IN") != 0;
	if (!strcmp (filter, "sms"))
		return !strcmp (type, "SMS");
	return TRUE;
}

static void
on_row_pressed (GtkGestureClick *g, int n, double x, double y, gpointer data)
{
	GsCtx *ctx = data;
	GVariant *e = g_object_get_data (G_OBJECT (gtk_event_controller_get_widget (GTK_EVENT_CONTROLLER (g))), "gs-entry");
	if (e)
		gs_dialog_message (ctx, e);
}

static GtkWidget *
row_new (GsCtx *ctx, GVariant *e)
{
	const char *dir = "", *num = "", *type = "CALL", *text = "";
	gint64 ts = 0, dur = 0;
	g_variant_lookup (e, "dir", "&s", &dir);
	g_variant_lookup (e, "num", "&s", &num);
	g_variant_lookup (e, "type", "&s", &type);
	g_variant_lookup (e, "text", "&s", &text);
	g_variant_lookup (e, "ts", "x", &ts);
	g_variant_lookup (e, "dur", "x", &dur);
	gboolean in = !strcmp (dir, "IN"), sms = !strcmp (type, "SMS");

	GtkWidget *row = gtk_box_new (GTK_ORIENTATION_HORIZONTAL, 0);
	gtk_widget_add_css_class (row, "gs-card");
	gtk_widget_add_css_class (row, "gs-row");
	gtk_widget_set_margin_bottom (row, 8);

	GtkWidget *icon = gtk_image_new_from_icon_name (in ? "gs-call-incoming-symbolic" : "gs-call-outgoing-symbolic");
	GtkWidget *box = gtk_box_new (GTK_ORIENTATION_HORIZONTAL, 0);
	gtk_widget_add_css_class (box, "gs-iconbox");
	gtk_widget_add_css_class (box, in ? "gs-in" : "gs-out");
	gtk_widget_set_valign (box, GTK_ALIGN_CENTER);
	gtk_box_append (GTK_BOX (box), icon);
	gtk_box_append (GTK_BOX (row), box);

	GtkWidget *mid = gtk_box_new (GTK_ORIENTATION_VERTICAL, 0);
	gtk_widget_set_hexpand (mid, TRUE);
	gtk_widget_set_margin_start (mid, 12);
	gtk_widget_set_valign (mid, GTK_ALIGN_CENTER);
	GtkWidget *n = gs_label (*num ? num : "—", "gs-rownum");
	gtk_label_set_ellipsize (GTK_LABEL (n), PANGO_ELLIPSIZE_END);
	g_autofree char *sub = NULL;
	if (sms) {
		sub = g_strdup (text);
		g_strdelimit (sub, "\r\n", ' ');
		g_strstrip (sub);
		if (!*sub) {
			g_free (sub);
			sub = g_strdup ("(no text)");
		}
	} else {
		sub = g_strdup (dur > 0 ? (in ? "GSM → SIP" : "SIP → GSM") : "Not connected");
	}
	GtkWidget *s = gs_label (sub, "gs-rowsub");
	gtk_label_set_ellipsize (GTK_LABEL (s), PANGO_ELLIPSIZE_END);
	gtk_label_set_single_line_mode (GTK_LABEL (s), TRUE);
	gtk_box_append (GTK_BOX (mid), n);
	gtk_box_append (GTK_BOX (mid), s);
	gtk_box_append (GTK_BOX (row), mid);

	GtkWidget *right = gtk_box_new (GTK_ORIENTATION_VERTICAL, 0);
	gtk_widget_set_valign (right, GTK_ALIGN_CENTER);
	g_autofree char *d = sms ? g_strdup ("SMS")
			   : dur > 0 ? g_strdup_printf ("%02d:%02d", (int)(dur / 60), (int)(dur % 60)) : g_strdup ("—");
	GtkWidget *dl = gs_label (d, "gs-dur");
	gtk_widget_add_css_class (dl, sms ? "gs-dur-sms" : dur > 0 ? "gs-dur-ok" : "gs-dur-none");
	gtk_label_set_xalign (GTK_LABEL (dl), 1);
	g_autofree char *t = fmt_row_time (ts);
	GtkWidget *tl = gs_label (t, "gs-rowtime");
	gtk_label_set_xalign (GTK_LABEL (tl), 1);
	gtk_widget_set_margin_start (right, 8);
	gtk_box_append (GTK_BOX (right), dl);
	gtk_box_append (GTK_BOX (right), tl);
	gtk_box_append (GTK_BOX (row), right);

	if (sms) {
		/* only messages open a detail; a call row does nothing */
		g_object_set_data_full (G_OBJECT (row), "gs-entry", g_variant_ref (e), (GDestroyNotify)g_variant_unref);
		GtkGesture *g = gtk_gesture_click_new ();
		g_signal_connect (g, "released", G_CALLBACK (on_row_pressed), ctx);
		gtk_widget_add_controller (row, GTK_EVENT_CONTROLLER (g));
		gtk_widget_set_cursor_from_name (row, "pointer");
	}
	return row;
}

void
gs_home_update_recents (GsCtx *ctx)
{
	GsHome *h = ctx->home;
	GtkWidget *c;
	while ((c = gtk_widget_get_first_child (h->list)))
		gtk_box_remove (GTK_BOX (h->list), c);
	int shown = 0;
	if (ctx->recents) {
		gsize n = g_variant_n_children (ctx->recents);
		for (gsize i = 0; i < n && shown < MAX_ROWS; i++) {
			g_autoptr (GVariant) e = g_variant_get_child_value (ctx->recents, i);
			if (!entry_shown (h->filter, e))
				continue;
			gtk_box_append (GTK_BOX (h->list), row_new (ctx, e));
			shown++;
		}
	}
	gtk_widget_set_visible (h->empty, shown == 0);
}

static void
on_filter (GtkToggleButton *b, gpointer data)
{
	GsCtx *ctx = data;
	if (!gtk_toggle_button_get_active (b))
		return;
	ctx->home->filter = g_object_get_data (G_OBJECT (b), "gs-filter");
	gs_home_update_recents (ctx);
}

/* ---- state ---- */

void
gs_home_update (GsCtx *ctx)
{
	GsHome *h = ctx->home;
	if (!h)
		return;
	const char *cls = status_class (ctx);
	gtk_widget_remove_css_class (h->status_icon, "gs-online");
	gtk_widget_remove_css_class (h->status_icon, "gs-connecting");
	gtk_widget_remove_css_class (h->status_icon, "gs-offline");
	gtk_widget_add_css_class (h->status_icon, cls);
	const char *st = gs_gateway_state (ctx->gw);
	const char *name = !strcmp (cls, "gs-online") ? "Online" : !strcmp (cls, "gs-connecting") ? "Connecting"
			 : !strcmp (st, "disabled") ? "Off" : "Offline";
	gtk_widget_set_tooltip_text (h->status, name);
	gtk_accessible_update_property (GTK_ACCESSIBLE (h->status), GTK_ACCESSIBLE_PROPERTY_LABEL, name, -1);
	gtk_widget_set_visible (h->tls, ctx->tls);
	gtk_widget_set_visible (h->srtp, ctx->tls && ctx->srtp);

	gboolean live = !strcmp (gs_gateway_call_state (ctx->gw), "bridged");
	gboolean was = gtk_widget_get_visible (h->card);
	gtk_widget_set_visible (h->card, live);
	if (live) {
		const char *num = gs_gateway_call_number (ctx->gw);
		gtk_label_set_text (GTK_LABEL (h->callnum), *num ? num : gs_gateway_call_info (ctx->gw));
		gtk_label_set_text (GTK_LABEL (h->dir), gs_gateway_call_incoming (ctx->gw) ? "GSM → SIP" : "SIP → GSM");
		g_autofree char *to = ctx->own_number && *ctx->own_number
				      ? g_strdup_printf ("Connected to %s", ctx->own_number) : g_strdup ("Connected");
		gtk_label_set_text (GTK_LABEL (h->connected), to);
		gboolean muted = gs_gateway_agent_muted (ctx->gw);
		gtk_label_set_text (GTK_LABEL (h->mute_label), muted ? "UNMUTE" : "MUTE");
		gtk_image_set_from_icon_name (GTK_IMAGE (h->mute_icon),
					      muted ? "gs-volume-high-symbolic" : "gs-volume-xmark-symbolic");
		tick_timer (ctx);
	} else {
		g_clear_handle_id (&h->call_timer, g_source_remove);
		if (was)
			/* the call that just ended is a new row */
			gs_ctx_reload_recents (ctx);
	}
}

void
gs_home_set_visible (GsCtx *ctx, gboolean visible)
{
	GsHome *h = ctx->home;
	if (h->visible == visible)
		return;
	h->visible = visible;
	g_clear_handle_id (&h->link_timer, g_source_remove);
	if (visible) {
		refresh_links (ctx);
		h->link_timer = g_timeout_add_seconds (5, link_tick, ctx);
		if (gtk_widget_get_visible (h->card))
			tick_timer (ctx);
	} else {
		g_clear_handle_id (&h->call_timer, g_source_remove);
	}
}

static void
on_gear (GsCtx *ctx)
{
	gs_ctx_show (ctx, "settings");
}

static void
home_free (GsHome *h)
{
	g_clear_handle_id (&h->link_timer, g_source_remove);
	g_clear_handle_id (&h->call_timer, g_source_remove);
	g_free (h);
}

GtkWidget *
gs_home_new (GsCtx *ctx)
{
	GsHome *h = ctx->home = g_new0 (GsHome, 1);
	h->filter = "all";
	GtkWidget *page = gtk_box_new (GTK_ORIENTATION_VERTICAL, 0);
	gtk_widget_add_css_class (page, "gs-page");
	g_object_set_data_full (G_OBJECT (page), "gs-home", h, (GDestroyNotify)home_free);

	/* header */
	GtkWidget *header = gtk_box_new (GTK_ORIENTATION_HORIZONTAL, 0);
	gtk_widget_set_margin_bottom (header, 16);
	GtkWidget *title = gs_label ("GSM2SIP", "gs-title");
	gtk_widget_set_valign (title, GTK_ALIGN_CENTER);
	gtk_box_append (GTK_BOX (header), title);
	h->status = gtk_button_new ();
	h->status_icon = gtk_image_new_from_icon_name ("gs-globe-symbolic");
	gtk_button_set_child (GTK_BUTTON (h->status), h->status_icon);
	gtk_widget_add_css_class (h->status, "gs-badge");
	gtk_widget_add_css_class (h->status_icon, "gs-connecting");
	gtk_widget_set_valign (h->status, GTK_ALIGN_CENTER);
	gtk_widget_set_margin_start (h->status, 10);
	g_signal_connect_swapped (h->status, "clicked", G_CALLBACK (on_status), ctx);
	gtk_box_append (GTK_BOX (header), h->status);
	h->tls = badge ("gs-shield-lock-symbolic", "gs-tls", "SIP signalling encrypted with TLS");
	h->srtp = badge ("gs-key-symbolic", "gs-srtp", "Call audio encrypted with SRTP");
	gtk_box_append (GTK_BOX (header), h->tls);
	gtk_box_append (GTK_BOX (header), h->srtp);
	GtkWidget *spacer = gtk_box_new (GTK_ORIENTATION_HORIZONTAL, 0);
	gtk_widget_set_hexpand (spacer, TRUE);
	gtk_box_append (GTK_BOX (header), spacer);
	gtk_box_append (GTK_BOX (header), gs_icon_button ("gs-gear-symbolic", "Menu", G_CALLBACK (on_gear), ctx));
	GtkWidget *handle = gtk_window_handle_new ();
	gtk_window_handle_set_child (GTK_WINDOW_HANDLE (handle), header);
	gtk_box_append (GTK_BOX (page), handle);

	/* link chips */
	GtkWidget *links = gtk_box_new (GTK_ORIENTATION_HORIZONTAL, 8);
	gtk_box_set_homogeneous (GTK_BOX (links), TRUE);
	h->mobile = link_chip (G_CALLBACK (gs_dialog_mobile), ctx);
	h->wifi = link_chip (G_CALLBACK (gs_dialog_wifi), ctx);
	gtk_box_append (GTK_BOX (links), h->mobile);
	gtk_box_append (GTK_BOX (links), h->wifi);
	gtk_box_append (GTK_BOX (page), links);
	g_signal_connect (ctx->net, "changed", G_CALLBACK (on_net_changed), ctx);

	/* filters: left-aligned, not stretched */
	GtkWidget *fr = gtk_box_new (GTK_ORIENTATION_HORIZONTAL, 6);
	gtk_widget_set_halign (fr, GTK_ALIGN_START);
	gtk_widget_set_margin_top (fr, 16);
	for (int i = 0; i < 4; i++) {
		GtkWidget *b = h->chips[i] = gtk_toggle_button_new_with_label (filter_names[i]);
		gtk_widget_add_css_class (b, "gs-chip");
		g_object_set_data (G_OBJECT (b), "gs-filter", (gpointer)filters[i]);
		if (i)
			gtk_toggle_button_set_group (GTK_TOGGLE_BUTTON (b), GTK_TOGGLE_BUTTON (h->chips[0]));
		gtk_box_append (GTK_BOX (fr), b);
	}
	/* before the handlers: the list it would fill comes later.  The four
	 * take 285 px of a phone's 332 whole, so none ellipsizes (an ellipsized
	 * label given exactly its width still lost "Outgoing" to rounding) */
	gtk_toggle_button_set_active (GTK_TOGGLE_BUTTON (h->chips[0]), TRUE);
	for (int i = 0; i < 4; i++)
		g_signal_connect (h->chips[i], "toggled", G_CALLBACK (on_filter), ctx);
	gtk_box_append (GTK_BOX (page), fr);

	/* the scroller */
	/* the call card sits right under the chips */
	GtkWidget *content = gtk_box_new (GTK_ORIENTATION_VERTICAL, 0);
	h->card = call_card (ctx);
	gtk_box_append (GTK_BOX (content), h->card);
	GtkWidget *cap = gs_label ("RECENT CALLS", "gs-caption");
	gtk_widget_set_margin_top (cap, 18);
	gtk_widget_set_margin_bottom (cap, 8);
	gtk_box_append (GTK_BOX (content), cap);
	h->list = gtk_box_new (GTK_ORIENTATION_VERTICAL, 0);
	gtk_box_append (GTK_BOX (content), h->list);
	h->empty = gs_label ("No calls yet", "gs-empty");
	gtk_box_append (GTK_BOX (content), h->empty);
	GtkWidget *sw = gtk_scrolled_window_new ();
	gtk_scrolled_window_set_policy (GTK_SCROLLED_WINDOW (sw), GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
	gtk_scrolled_window_set_child (GTK_SCROLLED_WINDOW (sw), content);
	gtk_widget_set_vexpand (sw, TRUE);
	gtk_box_append (GTK_BOX (page), sw);
	return page;
}
