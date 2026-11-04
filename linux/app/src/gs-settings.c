/* Settings: activity_main.xml's config tab, saved to gsm2sipd's
 * gateway.conf through its SetConfig.  On top, what the Android app does
 * not need: the Enabled switch that hands the modem to the gateway or back
 * to the phone. */
#include "gs-window.h"
#include "gs-config.h"
#include <stdlib.h>
#include <string.h>

struct GsSettings {
	GtkWidget *enabled, *enabled_help;
	GtkWidget *server, *port, *user, *password, *own, *simcap;
	GtkWidget *codec[3];
	GtkWidget *vol, *vol_label;
	GtkWidget *stun, *tls, *srtp, *srtp_help, *ascii;
	gboolean loading;	/* fields being filled: no toggle reactions */
	gboolean syncing;	/* the switch being set from the daemon */
	gboolean has_password;
	GCancellable *cancel;
};

static const char *const codecs[] = { "g722", "both", "g711" };

#define SRTP_HELP_ON "SDES-keyed SRTP (RFC 3711). Audio is encrypted when the server agrees; if it answers " \
	"without SRTP the call continues unencrypted and the log says so."
#define SRTP_HELP_OFF "Requires TLS. The keys travel inside the SIP signalling, so over plain UDP they would be " \
	"readable by anyone on the path."

static GtkWidget *
help (const char *text)
{
	GtkWidget *l = gs_label (text, "gs-help");
	gtk_label_set_wrap (GTK_LABEL (l), TRUE);
	gtk_widget_set_margin_top (l, 6);
	return l;
}

static GtkWidget *
label (const char *text)
{
	GtkWidget *l = gs_label (text, "gs-label");
	gtk_widget_set_margin_top (l, 14);
	return l;
}

static GtkWidget *
field (const char *placeholder, GtkInputPurpose purpose)
{
	GtkWidget *e = gtk_entry_new ();
	gtk_widget_add_css_class (e, "gs-field");
	gtk_entry_set_placeholder_text (GTK_ENTRY (e), placeholder);
	gtk_entry_set_input_purpose (GTK_ENTRY (e), purpose);
	gtk_entry_set_input_hints (GTK_ENTRY (e), GTK_INPUT_HINT_NO_SPELLCHECK | GTK_INPUT_HINT_NO_EMOJI);
	gtk_widget_set_margin_top (e, 4);
	return e;
}

static GtkWidget *
check (const char *text, int margin_top)
{
	/* a label that wraps: the longest codec choice set the window wider than a phone */
	GtkWidget *l = gtk_label_new (text);
	gtk_label_set_wrap (GTK_LABEL (l), TRUE);
	gtk_label_set_xalign (GTK_LABEL (l), 0);
	GtkWidget *c = gtk_check_button_new ();
	gtk_check_button_set_child (GTK_CHECK_BUTTON (c), l);
	gtk_accessible_update_property (GTK_ACCESSIBLE (c), GTK_ACCESSIBLE_PROPERTY_LABEL, text, -1);
	gtk_widget_add_css_class (c, "gs-check");
	gtk_widget_set_margin_top (c, margin_top);
	return c;
}

static void
set_text (GtkWidget *e, const char *text)
{
	gtk_editable_set_text (GTK_EDITABLE (e), text ? text : "");
}

static char *
get_text (GtkWidget *e)
{
	return g_strstrip (g_strdup (gtk_editable_get_text (GTK_EDITABLE (e))));
}

static void
vol_label_update (GsSettings *s)
{
	int step = (int)gtk_range_get_value (GTK_RANGE (s->vol)) - 3;
	g_autofree char *t = step > 0 ? g_strdup_printf ("+%d", step) : g_strdup_printf ("%d", step);
	gtk_label_set_text (GTK_LABEL (s->vol_label), t);
}

static void
on_vol (GtkRange *r, gpointer data)
{
	GsSettings *s = data;
	/* seven positions, no in-between */
	double v = gtk_range_get_value (r);
	if (v != (int)(v + 0.5)) {
		gtk_range_set_value (r, (int)(v + 0.5));
		return;
	}
	vol_label_update (s);
}

static void
srtp_update (GsSettings *s)
{
	gboolean tls = gtk_check_button_get_active (GTK_CHECK_BUTTON (s->tls));
	gtk_widget_set_sensitive (s->srtp, tls);
	gtk_label_set_text (GTK_LABEL (s->srtp_help), tls ? SRTP_HELP_ON : SRTP_HELP_OFF);
}

static void
on_tls (GtkCheckButton *c, gpointer data)
{
	GsCtx *ctx = data;
	GsSettings *s = ctx->settings;
	srtp_update (s);
	if (s->loading)
		return;
	/* the field only; stored on Save */
	g_autofree char *port = get_text (s->port);
	gboolean tls = gtk_check_button_get_active (c);
	if (tls && !strcmp (port, "5060")) {
		set_text (s->port, "5061");
		gs_ctx_toast (ctx, "Port switched to 5061 for TLS", FALSE);
	} else if (!tls && !strcmp (port, "5061")) {
		set_text (s->port, "5060");
		gs_ctx_toast (ctx, "Port switched back to 5060", FALSE);
	}
}

/* ---- open: every field from the daemon's config ---- */

static void
on_config (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GError) err = NULL;
	g_autoptr (GVariant) v = gs_gateway_call_finish (GS_GATEWAY (src), res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED))
		return;
	GsCtx *ctx = data;
	GsSettings *s = ctx->settings;
	if (!v) {
		g_autofree char *why = gs_gateway_error_text (err);
		g_autofree char *t = g_strdup_printf ("Settings not read: %s", why);
		gs_ctx_toast (ctx, t, TRUE);
		return;
	}
	g_autoptr (GVariant) cfg = g_variant_get_child_value (v, 0);
	const char *server = "", *user = "", *own = "", *codec = "g722";
	gint32 port = 5060, vol = 0;
	gboolean stun = TRUE, tls = FALSE, srtp = FALSE, ascii = FALSE;
	s->has_password = FALSE;
	g_variant_lookup (cfg, "server", "&s", &server);
	g_variant_lookup (cfg, "port", "i", &port);
	g_variant_lookup (cfg, "user", "&s", &user);
	g_variant_lookup (cfg, "has_password", "b", &s->has_password);
	g_variant_lookup (cfg, "own_number", "&s", &own);
	g_variant_lookup (cfg, "codec", "&s", &codec);
	g_variant_lookup (cfg, "agent_volume", "i", &vol);
	g_variant_lookup (cfg, "stun", "b", &stun);
	g_variant_lookup (cfg, "tls", "b", &tls);
	g_variant_lookup (cfg, "srtp", "b", &srtp);
	g_variant_lookup (cfg, "sms_ascii", "b", &ascii);

	s->loading = TRUE;
	set_text (s->server, server);
	g_autofree char *p = g_strdup_printf ("%d", port);
	set_text (s->port, p);
	set_text (s->user, user);
	/* write-only: the daemon never hands it back, so empty means "keep" */
	set_text (s->password, "");
	gtk_entry_set_placeholder_text (GTK_ENTRY (s->password), s->has_password ? "saved — type to change" : "password");
	set_text (s->own, own);
	for (int i = 0; i < 3; i++)
		gtk_check_button_set_active (GTK_CHECK_BUTTON (s->codec[i]), !strcmp (codec, codecs[i]) || (i == 0 &&
					     strcmp (codec, "both") && strcmp (codec, "g711")));
	gtk_range_set_value (GTK_RANGE (s->vol), CLAMP (vol, -3, 3) + 3);
	vol_label_update (s);
	gtk_check_button_set_active (GTK_CHECK_BUTTON (s->stun), stun);
	gtk_check_button_set_active (GTK_CHECK_BUTTON (s->tls), tls);
	gtk_check_button_set_active (GTK_CHECK_BUTTON (s->srtp), srtp);
	gtk_check_button_set_active (GTK_CHECK_BUTTON (s->ascii), ascii);
	srtp_update (s);
	s->loading = FALSE;
}

void
gs_settings_open (GsCtx *ctx)
{
	GsSettings *s = ctx->settings;
	g_cancellable_cancel (s->cancel);
	g_clear_object (&s->cancel);
	s->cancel = g_cancellable_new ();
	gs_gateway_call (ctx->gw, "GetConfig", NULL, s->cancel, on_config, ctx);
	g_autoptr (GPtrArray) sims = gs_net_info_sim_captions (ctx->net);
	gtk_label_set_text (GTK_LABEL (s->simcap),
			    sims && sims->len ? g_ptr_array_index (sims, 0) : "No active SIM detected");
	gs_settings_update (ctx);
}

/* ---- the Enabled switch ---- */

void
gs_settings_update (GsCtx *ctx)
{
	GsSettings *s = ctx->settings;
	if (!s || ctx->switching)
		return;
	gboolean present = gs_gateway_present (ctx->gw);
	gboolean on = gs_gateway_enabled (ctx->gw);
	s->syncing = TRUE;
	gtk_switch_set_active (GTK_SWITCH (s->enabled), on);
	gtk_switch_set_state (GTK_SWITCH (s->enabled), on);
	s->syncing = FALSE;
	gtk_widget_set_sensitive (s->enabled, present);
	const char *st = gs_gateway_state (ctx->gw);
	gtk_label_set_text (GTK_LABEL (s->enabled_help),
		!present ? "gsm2sipd is not running, so nothing here can be changed."
		: !strcmp (st, "unconfigured") ? "Set the SIP server, username and password below and save: until then "
						 "the phone handles its calls and SMS itself."
		: on ? "On: gsm2sip answers the SIM's calls and SMS for the SIP server, and the phone's own Phone and "
		       "Messages apps are switched off."
		     : "Off: the phone works as a phone — its own Phone and Messages apps take the calls and SMS.");
}

static gboolean
on_enabled (GtkSwitch *sw, gboolean state, gpointer data)
{
	GsCtx *ctx = data;
	if (ctx->settings->syncing)
		return FALSE;
	/* the switch shows the new position at once and its state once the
	 * daemon and the phone's apps have followed (gs_settings_update) */
	gs_ctx_set_enabled (ctx, state);
	return TRUE;
}

/* ---- Save ---- */

static void
on_saved (GObject *src, GAsyncResult *res, gpointer data)
{
	GsCtx *ctx = data;
	g_autoptr (GError) err = NULL;
	g_autoptr (GVariant) v = gs_gateway_call_finish (GS_GATEWAY (src), res, &err);
	if (!v) {
		g_autofree char *why = gs_gateway_error_text (err);
		gs_ctx_toast (ctx, why, TRUE);
		return;
	}
	gs_ctx_toast (ctx, gs_gateway_enabled (ctx->gw) ? "Saved — reconnecting" : "Saved", FALSE);
	gs_ctx_reload_config (ctx);
	gs_ctx_show (ctx, "home");
}

static void
on_save (GsCtx *ctx)
{
	GsSettings *s = ctx->settings;
	g_autofree char *server = get_text (s->server);
	g_autofree char *user = get_text (s->user);
	g_autofree char *port_s = get_text (s->port);
	g_autofree char *own = get_text (s->own);
	if (!*server || !*user) {
		gs_ctx_toast (ctx, "Server and username are required", TRUE);
		return;
	}
	char *end = NULL;
	long port = strtol (port_s, &end, 10);
	if (!*port_s || *end || port <= 0)
		port = 5060;
	int codec = 0;
	for (int i = 0; i < 3; i++)
		if (gtk_check_button_get_active (GTK_CHECK_BUTTON (s->codec[i])))
			codec = i;
	GVariantDict d;
	g_variant_dict_init (&d, NULL);
	g_variant_dict_insert (&d, "server", "s", server);
	g_variant_dict_insert (&d, "port", "i", (gint32)port);
	g_variant_dict_insert (&d, "user", "s", user);
	/* trimmed like every other field, as the Android app does */
	g_autofree char *pw = get_text (s->password);
	if (*pw)
		g_variant_dict_insert (&d, "password", "s", pw);
	g_variant_dict_insert (&d, "own_number", "s", own);
	g_variant_dict_insert (&d, "codec", "s", codecs[codec]);
	g_variant_dict_insert (&d, "agent_volume", "i", (gint32)gtk_range_get_value (GTK_RANGE (s->vol)) - 3);
	g_variant_dict_insert (&d, "stun", "b", gtk_check_button_get_active (GTK_CHECK_BUTTON (s->stun)));
	g_variant_dict_insert (&d, "tls", "b", gtk_check_button_get_active (GTK_CHECK_BUTTON (s->tls)));
	g_variant_dict_insert (&d, "srtp", "b", gtk_check_button_get_active (GTK_CHECK_BUTTON (s->srtp)));
	g_variant_dict_insert (&d, "sms_ascii", "b", gtk_check_button_get_active (GTK_CHECK_BUTTON (s->ascii)));
	gs_gateway_call (ctx->gw, "SetConfig", g_variant_new ("(@a{sv})", g_variant_dict_end (&d)), NULL, on_saved,
			 ctx);
	if (*pw)
		memset (pw, 0, strlen (pw));
}

static void
on_back (GsCtx *ctx)
{
	gs_ctx_show (ctx, "home");
}

static void
on_logs (GsCtx *ctx)
{
	gs_ctx_show (ctx, "logs");
}

static void
settings_free (GsSettings *s)
{
	g_cancellable_cancel (s->cancel);
	g_clear_object (&s->cancel);
	g_free (s);
}

GtkWidget *
gs_settings_new (GsCtx *ctx)
{
	GsSettings *s = ctx->settings = g_new0 (GsSettings, 1);
	GtkWidget *page = gtk_box_new (GTK_ORIENTATION_VERTICAL, 0);
	g_object_set_data_full (G_OBJECT (page), "gs-settings", s, (GDestroyNotify)settings_free);

	GtkWidget *header = gtk_box_new (GTK_ORIENTATION_HORIZONTAL, 0);
	gtk_widget_add_css_class (header, "gs-page");
	gtk_box_append (GTK_BOX (header), gs_icon_button ("gs-arrow-back-symbolic", "Back", G_CALLBACK (on_back), ctx));
	GtkWidget *title = gs_label ("Settings", "gs-title");
	gtk_widget_set_hexpand (title, TRUE);
	gtk_widget_set_margin_start (title, 12);
	gtk_box_append (GTK_BOX (header), title);
	gtk_box_append (GTK_BOX (header), gs_icon_button ("gs-logs-symbolic", "Logs", G_CALLBACK (on_logs), ctx));
	GtkWidget *handle = gtk_window_handle_new ();
	gtk_window_handle_set_child (GTK_WINDOW_HANDLE (handle), header);
	gtk_box_append (GTK_BOX (page), handle);

	GtkWidget *b = gtk_box_new (GTK_ORIENTATION_VERTICAL, 0);
	gtk_widget_set_margin_start (b, 14);
	gtk_widget_set_margin_end (b, 14);
	gtk_widget_set_margin_bottom (b, 20);

	GtkWidget *icon = gtk_image_new_from_resource ("/com/callagent/Gsm2sip/com.callagent.Gsm2sip.png");
	gtk_image_set_pixel_size (GTK_IMAGE (icon), 72);
	gtk_widget_set_margin_top (icon, 14);
	gtk_box_append (GTK_BOX (b), icon);
	GtkWidget *name = gtk_label_new ("gsm2sip");
	gtk_widget_add_css_class (name, "gs-appname");
	gtk_widget_set_margin_top (name, 8);
	gtk_box_append (GTK_BOX (b), name);
	GtkWidget *ver = gtk_label_new ("v" GS_VERSION);
	gtk_widget_add_css_class (ver, "gs-version");
	gtk_widget_set_margin_top (ver, 2);
	gtk_widget_set_margin_bottom (ver, 14);
	gtk_box_append (GTK_BOX (b), ver);

	/* the Enabled switch */
	GtkWidget *card = gtk_box_new (GTK_ORIENTATION_VERTICAL, 0);
	gtk_widget_add_css_class (card, "gs-card");
	gtk_widget_add_css_class (card, "gs-enable-card");
	GtkWidget *row = gtk_box_new (GTK_ORIENTATION_HORIZONTAL, 12);
	GtkWidget *et = gs_label ("Enabled", "gs-enable-title");
	gtk_widget_set_hexpand (et, TRUE);
	gtk_widget_set_valign (et, GTK_ALIGN_CENTER);
	s->enabled = gtk_switch_new ();
	gtk_widget_set_valign (s->enabled, GTK_ALIGN_CENTER);
	gtk_accessible_update_property (GTK_ACCESSIBLE (s->enabled), GTK_ACCESSIBLE_PROPERTY_LABEL, "Enabled", -1);
	g_signal_connect (s->enabled, "state-set", G_CALLBACK (on_enabled), ctx);
	gtk_box_append (GTK_BOX (row), et);
	gtk_box_append (GTK_BOX (row), s->enabled);
	gtk_box_append (GTK_BOX (card), row);
	s->enabled_help = help ("");
	gtk_box_append (GTK_BOX (card), s->enabled_help);
	gtk_box_append (GTK_BOX (b), card);

	gtk_box_append (GTK_BOX (b), label ("SIP Server"));
	s->server = field ("callagent.pro", GTK_INPUT_PURPOSE_URL);
	gtk_box_append (GTK_BOX (b), s->server);
	gtk_box_append (GTK_BOX (b), label ("SIP Port"));
	s->port = field ("5060", GTK_INPUT_PURPOSE_DIGITS);
	gtk_box_append (GTK_BOX (b), s->port);
	gtk_box_append (GTK_BOX (b), label ("SIP Username"));
	s->user = field ("extension", GTK_INPUT_PURPOSE_FREE_FORM);
	gtk_box_append (GTK_BOX (b), s->user);
	gtk_box_append (GTK_BOX (b), label ("SIP Password"));
	s->password = field ("password", GTK_INPUT_PURPOSE_PASSWORD);
	gtk_entry_set_visibility (GTK_ENTRY (s->password), FALSE);
	gtk_box_append (GTK_BOX (b), s->password);

	gtk_box_append (GTK_BOX (b), label ("Own Number (SIM)"));
	s->own = field ("international, e.g. +4915112345678", GTK_INPUT_PURPOSE_PHONE);
	gtk_box_append (GTK_BOX (b), s->own);
	s->simcap = gs_label ("", "gs-simcap");
	gtk_label_set_wrap (GTK_LABEL (s->simcap), TRUE);
	gtk_widget_set_margin_top (s->simcap, 2);
	gtk_widget_set_margin_bottom (s->simcap, 4);
	gtk_box_append (GTK_BOX (b), s->simcap);
	gtk_box_append (GTK_BOX (b), help ("Sent as the SIP destination so the server can map the call to an assistant. "
					   "Without it the gateway addresses its own extension and the call loops back."));

	gtk_box_append (GTK_BOX (b), label ("Audio Codec"));
	static const char *const codec_names[] = { "G.722 only — wideband 16 kHz (recommended)",
						   "G.722 preferred, G.711 allowed", "G.711 only — narrowband 8 kHz" };
	for (int i = 0; i < 3; i++) {
		s->codec[i] = check (codec_names[i], i ? 0 : 4);
		if (i)
			gtk_check_button_set_group (GTK_CHECK_BUTTON (s->codec[i]), GTK_CHECK_BUTTON (s->codec[0]));
		gtk_box_append (GTK_BOX (b), s->codec[i]);
	}
	gtk_box_append (GTK_BOX (b), help ("Offering G.711 alongside G.722 usually means the server picks G.711 and every "
					   "call is narrowband, which is why G.722 alone is the default."));

	gtk_box_append (GTK_BOX (b), label ("Agent Volume"));
	GtkWidget *vr = gtk_box_new (GTK_ORIENTATION_HORIZONTAL, 0);
	gtk_widget_set_margin_top (vr, 4);
	s->vol = gtk_scale_new_with_range (GTK_ORIENTATION_HORIZONTAL, 0, 6, 1);
	gtk_widget_add_css_class (s->vol, "gs-scale");
	gtk_scale_set_draw_value (GTK_SCALE (s->vol), FALSE);
	gtk_range_set_round_digits (GTK_RANGE (s->vol), 0);
	gtk_widget_set_hexpand (s->vol, TRUE);
	gtk_range_set_value (GTK_RANGE (s->vol), 3);
	s->vol_label = gtk_label_new ("0");
	gtk_widget_add_css_class (s->vol_label, "gs-volval");
	gtk_widget_set_size_request (s->vol_label, 44, -1);
	gtk_label_set_xalign (GTK_LABEL (s->vol_label), 1);
	g_signal_connect (s->vol, "value-changed", G_CALLBACK (on_vol), s);
	gtk_box_append (GTK_BOX (vr), s->vol);
	gtk_box_append (GTK_BOX (vr), s->vol_label);
	gtk_box_append (GTK_BOX (b), vr);
	gtk_box_append (GTK_BOX (b), help ("How loud the agent is to the GSM caller. 0 is what the detected device asks "
					   "for; each step roughly a third louder or quieter. Raise it if callers say the "
					   "agent is faint, lower it if the line distorts."));

	s->stun = check ("Use STUN", 16);
	gtk_box_append (GTK_BOX (b), s->stun);
	gtk_box_append (GTK_BOX (b), help ("Finds the public IP so SIP and RTP survive NAT. Turn off for a SIP server on "
					   "the local network."));
	s->tls = check ("Enable TLS", 8);
	g_signal_connect (s->tls, "toggled", G_CALLBACK (on_tls), ctx);
	gtk_box_append (GTK_BOX (b), s->tls);
	gtk_box_append (GTK_BOX (b), help ("Encrypts SIP signalling — registration, calls and SMS text. Call audio (RTP) "
					   "is not encrypted either way. The server must offer TLS, usually on port 5061."));
	s->srtp = check ("Encrypt audio (SRTP)", 8);
	gtk_box_append (GTK_BOX (b), s->srtp);
	s->srtp_help = help (SRTP_HELP_OFF);
	gtk_box_append (GTK_BOX (b), s->srtp_help);
	s->ascii = check ("ASCII-only outgoing SMS", 8);
	gtk_box_append (GTK_BOX (b), s->ascii);
	gtk_box_append (GTK_BOX (b), help ("Rewrites accents before sending: für becomes fuer. Turn on if umlauts arrive "
					   "mangled. Cyrillic, Hebrew, Arabic and other non-Latin text is never rewritten "
					   "— it is sent as-is."));

	GtkWidget *clear = gtk_button_new_with_label ("CLEAR RECENTS");
	gtk_widget_add_css_class (clear, "gs-clear");
	gtk_widget_add_css_class (clear, "gs-material");
	gtk_widget_set_halign (clear, GTK_ALIGN_CENTER);
	gtk_widget_set_margin_top (clear, 24);
	gtk_widget_set_margin_bottom (clear, 20);
	g_signal_connect_swapped (clear, "clicked", G_CALLBACK (gs_dialog_clear_recents), ctx);
	gtk_box_append (GTK_BOX (b), clear);
	GtkWidget *save = gtk_button_new_with_label ("SAVE");
	gtk_widget_add_css_class (save, "gs-save");
	gtk_widget_add_css_class (save, "gs-material");
	g_signal_connect_swapped (save, "clicked", G_CALLBACK (on_save), ctx);
	gtk_box_append (GTK_BOX (b), save);

	GtkWidget *sw = gtk_scrolled_window_new ();
	gtk_scrolled_window_set_policy (GTK_SCROLLED_WINDOW (sw), GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
	gtk_scrolled_window_set_child (GTK_SCROLLED_WINDOW (sw), b);
	gtk_widget_set_vexpand (sw, TRUE);
	gtk_box_append (GTK_BOX (page), sw);
	return page;
}
