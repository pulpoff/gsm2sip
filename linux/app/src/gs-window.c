#include "gs-window.h"
#include "gs-phone-apps.h"
#include <string.h>

void
gs_ctx_toast (GsCtx *ctx, const char *text, gboolean long_toast)
{
	AdwToast *t = adw_toast_new (text);
	/* Android's SHORT and LONG */
	adw_toast_set_timeout (t, long_toast ? 4 : 2);
	adw_toast_overlay_add_toast (ctx->toasts, t);
}

void
gs_ctx_show (GsCtx *ctx, const char *page)
{
	if (!strcmp (page, "settings"))
		gs_settings_open (ctx);
	else if (!strcmp (page, "logs"))
		gs_logs_open (ctx);
	gtk_stack_set_visible_child_name (ctx->stack, page);
	gs_home_set_visible (ctx, !strcmp (page, "home"));
	if (!strcmp (page, "home")) {
		gs_ctx_reload_recents (ctx);
		gs_home_update (ctx);
	}
}

GtkWidget *
gs_icon_button (const char *icon, const char *tooltip, GCallback cb, gpointer data)
{
	GtkWidget *b = gtk_button_new_from_icon_name (icon);
	gtk_widget_add_css_class (b, "gs-iconbtn");
	gtk_widget_set_tooltip_text (b, tooltip);
	gtk_widget_set_valign (b, GTK_ALIGN_CENTER);
	gtk_accessible_update_property (GTK_ACCESSIBLE (b), GTK_ACCESSIBLE_PROPERTY_LABEL, tooltip, -1);
	if (cb)
		g_signal_connect_swapped (b, "clicked", cb, data);
	return b;
}

GtkWidget *
gs_label (const char *text, const char *css_class)
{
	GtkWidget *l = gtk_label_new (text);
	gtk_label_set_xalign (GTK_LABEL (l), 0);
	if (css_class)
		gtk_widget_add_css_class (l, css_class);
	return l;
}

/* ---- what the daemon says ---- */

static void
on_recents (GObject *src, GAsyncResult *res, gpointer data)
{
	GsCtx *ctx = data;
	g_autoptr (GError) err = NULL;
	g_autoptr (GVariant) v = gs_gateway_call_finish (GS_GATEWAY (src), res, &err);
	if (!v)
		return;	/* not running: the list stays as it was */
	g_clear_pointer (&ctx->recents, g_variant_unref);
	ctx->recents = g_variant_get_child_value (v, 0);
	gs_home_update_recents (ctx);
}

void
gs_ctx_reload_recents (GsCtx *ctx)
{
	gs_gateway_call (ctx->gw, "GetRecents", NULL, NULL, on_recents, ctx);
}

static void
on_config (GObject *src, GAsyncResult *res, gpointer data)
{
	GsCtx *ctx = data;
	g_autoptr (GVariant) v = gs_gateway_call_finish (GS_GATEWAY (src), res, NULL);
	if (!v)
		return;
	g_autoptr (GVariant) cfg = g_variant_get_child_value (v, 0);
	const char *own = "";
	g_variant_lookup (cfg, "own_number", "&s", &own);
	g_free (ctx->own_number);
	ctx->own_number = g_strdup (own);
	ctx->tls = ctx->srtp = FALSE;
	g_variant_lookup (cfg, "tls", "b", &ctx->tls);
	g_variant_lookup (cfg, "srtp", "b", &ctx->srtp);
	gs_home_update (ctx);
}

void
gs_ctx_reload_config (GsCtx *ctx)
{
	gs_gateway_call (ctx->gw, "GetConfig", NULL, NULL, on_config, ctx);
}

/* ---- the Enabled switch ---- */

typedef struct {
	GsCtx *ctx;
	gboolean enabled;
	char *error;	/* SetEnabled's, while the phone apps are given back */
} Switch;

static void
switch_done (Switch *sw, const char *error)
{
	GsCtx *ctx = sw->ctx;
	ctx->switching = FALSE;
	if (error) {
		g_autofree char *t = g_strdup_printf ("Not switched %s: %s", sw->enabled ? "on" : "off", error);
		gs_ctx_toast (ctx, t, TRUE);
	} else {
		gs_ctx_toast (ctx, sw->enabled ? "gsm2sip on — it takes the calls and SMS"
					       : "gsm2sip off — calls and SMS are the phone's again", FALSE);
	}
	gs_settings_update (ctx);
	gs_home_update (ctx);
	g_free (sw->error);
	g_free (sw);
}

static void
on_released_after_failure (GObject *src, GAsyncResult *res, gpointer data)
{
	Switch *sw = data;
	g_autoptr (GError) err = NULL;
	gs_phone_apps_hold_finish (res, &err);
	if (err)
		g_warning ("Phone apps not given back: %s", err->message);
	switch_done (sw, sw->error);
}

static void
on_set_enabled (GObject *src, GAsyncResult *res, gpointer data);

static void
on_held (GObject *src, GAsyncResult *res, gpointer data)
{
	Switch *sw = data;
	g_autoptr (GError) err = NULL;
	if (!gs_phone_apps_hold_finish (res, &err)) {
		/* the gateway is not switched on over a Calls that still runs */
		switch_done (sw, err->message);
		return;
	}
	gs_gateway_call (sw->ctx->gw, "SetEnabled", g_variant_new ("(b)", TRUE), NULL, on_set_enabled, sw);
}

static void
on_released (GObject *src, GAsyncResult *res, gpointer data)
{
	Switch *sw = data;
	g_autoptr (GError) err = NULL;
	gs_phone_apps_hold_finish (res, &err);
	switch_done (sw, err ? err->message : NULL);
}

static void
on_set_enabled (GObject *src, GAsyncResult *res, gpointer data)
{
	Switch *sw = data;
	g_autoptr (GError) err = NULL;
	g_autoptr (GVariant) v = gs_gateway_call_finish (GS_GATEWAY (src), res, &err);
	gboolean apps = gs_phone_apps_installed ();
	if (!v) {
		g_autofree char *why = gs_gateway_error_text (err);
		if (sw->enabled && apps) {
			/* held for nothing: give the phone its apps back */
			sw->error = g_strdup (why);
			gs_phone_apps_hold_async (FALSE, NULL, on_released_after_failure, sw);
		} else {
			switch_done (sw, why);
		}
		return;
	}
	if (!sw->enabled && apps)
		/* only now: the daemon has let go of the modem */
		gs_phone_apps_hold_async (FALSE, NULL, on_released, sw);
	else
		switch_done (sw, NULL);
}

void
gs_ctx_set_enabled (GsCtx *ctx, gboolean enabled)
{
	if (ctx->switching)
		return;
	ctx->switching = TRUE;
	Switch *sw = g_new0 (Switch, 1);
	sw->ctx = ctx;
	sw->enabled = enabled;
	/* on: the phone's apps step aside first; off: the daemon goes first */
	if (enabled && gs_phone_apps_installed ())
		gs_phone_apps_hold_async (TRUE, NULL, on_held, sw);
	else
		gs_gateway_call (ctx->gw, "SetEnabled", g_variant_new ("(b)", enabled), NULL, on_set_enabled, sw);
}

/* The switch set some other way (the config edited by hand, a daemon that
 * came up unconfigured): the phone's apps follow what the daemon does. */
static void
on_reconciled (GObject *src, GAsyncResult *res, gpointer data)
{
	GsCtx *ctx = data;
	g_autoptr (GError) err = NULL;
	if (!gs_phone_apps_hold_finish (res, &err))
		g_warning ("Phone apps: %s", err->message);
	ctx->switching = FALSE;
}

static void
reconcile (GsCtx *ctx)
{
	if (ctx->switching || !gs_gateway_present (ctx->gw) || !gs_phone_apps_installed ())
		return;
	gboolean want = gs_gateway_owns_modem (ctx->gw);
	if (want == gs_phone_apps_held ())
		return;
	g_message ("Phone apps %s to match gsm2sipd", want ? "held" : "given back");
	ctx->switching = TRUE;
	gs_phone_apps_hold_async (want, NULL, on_reconciled, ctx);
}

static void
on_gw_changed (GsGateway *gw, gpointer data)
{
	GsCtx *ctx = data;
	gboolean present = gs_gateway_present (gw);
	gboolean was = GPOINTER_TO_INT (g_object_get_data (G_OBJECT (ctx->win), "gs-present"));
	g_object_set_data (G_OBJECT (ctx->win), "gs-present", GINT_TO_POINTER (present));
	if (present && !was) {
		/* started, or back after a restart */
		gs_ctx_reload_recents (ctx);
		gs_ctx_reload_config (ctx);
		gs_logs_open (ctx);
	}
	gs_home_update (ctx);
	gs_settings_update (ctx);
	reconcile (ctx);
}

static void
on_gw_recents (GsGateway *gw, gpointer data)
{
	gs_ctx_reload_recents (data);
}

static void
on_gw_log (GsGateway *gw, const char *const *lines, gpointer data)
{
	for (; lines && *lines; lines++)
		gs_logs_append (data, *lines);
}

/* ---- the window ---- */

static gboolean
on_escape (GtkWidget *w, GVariant *args, gpointer data)
{
	GsCtx *ctx = data;
	const char *page = gtk_stack_get_visible_child_name (ctx->stack);
	if (!g_strcmp0 (page, "settings"))
		gs_ctx_show (ctx, "home");
	else if (!g_strcmp0 (page, "logs"))
		gs_ctx_show (ctx, "settings");
	return TRUE;
}

static void
on_active (GtkWindow *win, GParamSpec *pspec, gpointer data)
{
	GsCtx *ctx = data;
	/* Android stops its refresh timers in onPause */
	gboolean home = !g_strcmp0 (gtk_stack_get_visible_child_name (ctx->stack), "home");
	gs_home_set_visible (ctx, home && gtk_window_is_active (win));
}

static void
ctx_free (GsCtx *ctx)
{
	g_signal_handlers_disconnect_by_data (ctx->gw, ctx);
	g_signal_handlers_disconnect_by_data (ctx->net, ctx);
	g_clear_object (&ctx->gw);
	g_clear_object (&ctx->net);
	g_clear_pointer (&ctx->recents, g_variant_unref);
	g_free (ctx->own_number);
	g_free (ctx);
}

GtkWindow *
gs_window_new (AdwApplication *app)
{
	GsCtx *ctx = g_new0 (GsCtx, 1);
	ctx->win = ADW_APPLICATION_WINDOW (adw_application_window_new (GTK_APPLICATION (app)));
	GtkWindow *win = GTK_WINDOW (ctx->win);
	gtk_window_set_title (win, "gsm2sip");
	gtk_window_set_default_size (win, 420, 820);
	gtk_widget_add_css_class (GTK_WIDGET (win), "gs");
	g_object_set_data_full (G_OBJECT (win), "gs-ctx", ctx, (GDestroyNotify)ctx_free);

	ctx->gw = gs_gateway_new ();
	ctx->net = gs_net_info_new ();
	ctx->toasts = ADW_TOAST_OVERLAY (adw_toast_overlay_new ());
	ctx->stack = GTK_STACK (gtk_stack_new ());
	/* the Android app swaps pages without an animation */
	gtk_stack_set_transition_type (ctx->stack, GTK_STACK_TRANSITION_TYPE_NONE);
	/* each page as wide as it needs: one wide page must not push another
	 * past the screen's edge */
	gtk_stack_set_hhomogeneous (ctx->stack, FALSE);
	gtk_stack_add_named (ctx->stack, gs_home_new (ctx), "home");
	gtk_stack_add_named (ctx->stack, gs_settings_new (ctx), "settings");
	gtk_stack_add_named (ctx->stack, gs_logs_new (ctx), "logs");
	adw_toast_overlay_set_child (ctx->toasts, GTK_WIDGET (ctx->stack));
	adw_application_window_set_content (ctx->win, GTK_WIDGET (ctx->toasts));

	GtkEventController *sc = gtk_shortcut_controller_new ();
	gtk_shortcut_controller_add_shortcut (GTK_SHORTCUT_CONTROLLER (sc),
		gtk_shortcut_new (gtk_keyval_trigger_new (GDK_KEY_Escape, 0), gtk_callback_action_new (on_escape, ctx, NULL)));
	gtk_widget_add_controller (GTK_WIDGET (win), sc);

	g_signal_connect (ctx->gw, "changed", G_CALLBACK (on_gw_changed), ctx);
	g_signal_connect (ctx->gw, "recents-changed", G_CALLBACK (on_gw_recents), ctx);
	g_signal_connect (ctx->gw, "log-lines", G_CALLBACK (on_gw_log), ctx);
	g_signal_connect (win, "notify::is-active", G_CALLBACK (on_active), ctx);

	gs_ctx_show (ctx, "home");
	return win;
}
