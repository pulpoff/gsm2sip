/* gsm2sip: the gateway's app for GNOME, the Android app's screens over
 * gsm2sipd's D-Bus interface. */
#include "gs-config.h"
#include "gs-window.h"

/* the dark colours over style.css while the system is dark */
static void
on_dark (AdwStyleManager *sm, GParamSpec *ps, GtkCssProvider *dark)
{
	GdkDisplay *d = gdk_display_get_default ();
	if (adw_style_manager_get_dark (sm))
		gtk_style_context_add_provider_for_display (d, GTK_STYLE_PROVIDER (dark),
							    GTK_STYLE_PROVIDER_PRIORITY_APPLICATION + 1);
	else
		gtk_style_context_remove_provider_for_display (d, GTK_STYLE_PROVIDER (dark));
}

static void
on_startup (GApplication *app)
{
	/* for the app's lifetime */
	GtkCssProvider *dark = gtk_css_provider_new ();
	gtk_css_provider_load_from_resource (dark, "/com/callagent/Gsm2sip/dark.css");
	AdwStyleManager *sm = adw_style_manager_get_default ();
	g_signal_connect (sm, "notify::dark", G_CALLBACK (on_dark), dark);
	on_dark (sm, NULL, dark);
}

static void
on_activate (GApplication *app)
{
	GtkWindow *win = gtk_application_get_active_window (GTK_APPLICATION (app));
	if (!win)
		win = gs_window_new (ADW_APPLICATION (app));
	gtk_window_present (win);
}

int
main (int argc, char **argv)
{
	g_autoptr (AdwApplication) app = adw_application_new (GS_APP_ID, G_APPLICATION_DEFAULT_FLAGS);
	g_set_application_name ("gsm2sip");
	gtk_window_set_default_icon_name (GS_APP_ID);
	g_signal_connect_after (app, "startup", G_CALLBACK (on_startup), NULL);
	g_signal_connect (app, "activate", G_CALLBACK (on_activate), NULL);
	return g_application_run (G_APPLICATION (app), argc, argv);
}
