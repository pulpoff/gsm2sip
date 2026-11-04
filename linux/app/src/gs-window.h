/* The one window: Home, Settings and Logs as pages of a stack, each drawing
 * its own header (the Android app has no toolbar), toasts over all three. */
#pragma once
#include "gs-gateway.h"
#include "gs-net-info.h"
#include <adwaita.h>

typedef struct GsHome GsHome;
typedef struct GsSettings GsSettings;
typedef struct GsLogs GsLogs;

typedef struct {
	AdwApplicationWindow *win;
	AdwToastOverlay *toasts;
	GtkStack *stack;
	GsGateway *gw;
	GsNetInfo *net;
	GsHome *home;
	GsSettings *settings;
	GsLogs *logs;
	/* the recents as last read, newest first (aa{sv}); NULL until then */
	GVariant *recents;
	/* the gateway's own number, for "Connected to" and Message detail */
	char *own_number;
	gboolean tls, srtp;
	/* the Enabled switch is being applied */
	gboolean switching;
} GsCtx;

GtkWindow *gs_window_new (AdwApplication *app);

void gs_ctx_toast (GsCtx *ctx, const char *text, gboolean long_toast);
/* "home" | "settings" | "logs" */
void gs_ctx_show (GsCtx *ctx, const char *page);
/* re-read the recents (the list, and the count Clear asks about) */
void gs_ctx_reload_recents (GsCtx *ctx);
/* re-read the settings the home page shows (own number, TLS/SRTP badges) */
void gs_ctx_reload_config (GsCtx *ctx);
/* The Enabled switch: the daemon and the phone's own apps, in the order
 * that never leaves the modem with two owners or none. */
void gs_ctx_set_enabled (GsCtx *ctx, gboolean enabled);

/* a widget helper the pages share: an icon button of the header */
GtkWidget *gs_icon_button (const char *icon, const char *tooltip, GCallback cb, gpointer data);
GtkWidget *gs_label (const char *text, const char *css_class);

/* pages */
GtkWidget *gs_home_new (GsCtx *ctx);
void gs_home_update (GsCtx *ctx);		/* gateway state */
void gs_home_update_recents (GsCtx *ctx);	/* the list */
void gs_home_set_visible (GsCtx *ctx, gboolean visible);	/* timers run only while shown */

GtkWidget *gs_settings_new (GsCtx *ctx);
void gs_settings_open (GsCtx *ctx);	/* reload every field */
void gs_settings_update (GsCtx *ctx);	/* gateway state: the Enabled switch */

GtkWidget *gs_logs_new (GsCtx *ctx);
void gs_logs_open (GsCtx *ctx);
void gs_logs_append (GsCtx *ctx, const char *line);

/* dialogs */
void gs_dialog_mobile (GsCtx *ctx);
void gs_dialog_wifi (GsCtx *ctx);
void gs_dialog_message (GsCtx *ctx, GVariant *entry);
void gs_dialog_clear_recents (GsCtx *ctx);
