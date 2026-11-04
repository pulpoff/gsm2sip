/* gsm2sipd as the app sees it: com.callagent.Gsm2sipd on the system bus
 * (linux/src/service.c).  Properties are cached; "changed" fires on any of
 * them and when the daemon comes or goes. */
#pragma once
#include <gio/gio.h>

G_BEGIN_DECLS

#define GS_TYPE_GATEWAY (gs_gateway_get_type ())
G_DECLARE_FINAL_TYPE (GsGateway, gs_gateway, GS, GATEWAY, GObject)

GsGateway *gs_gateway_new (void);

/* the daemon owns its name on the bus */
gboolean gs_gateway_present (GsGateway *self);
/* "unconfigured" | "disabled" | "connecting" | "registered" | "failed";
 * "absent" without the daemon */
const char *gs_gateway_state (GsGateway *self);
gboolean gs_gateway_enabled (GsGateway *self);
/* the daemon holds the modem, or is about to: switched on and configured */
gboolean gs_gateway_owns_modem (GsGateway *self);
const char *gs_gateway_server (GsGateway *self);
/* the bridge's state, lower case: "idle", "gsm_ringing" ... "bridged" */
const char *gs_gateway_call_state (GsGateway *self);
const char *gs_gateway_call_info (GsGateway *self);
const char *gs_gateway_call_number (GsGateway *self);
gboolean gs_gateway_call_incoming (GsGateway *self);
gint64 gs_gateway_call_bridged_at (GsGateway *self);
gboolean gs_gateway_agent_muted (GsGateway *self);
gboolean gs_gateway_restart_pending (GsGateway *self);

/* A method of com.callagent.Gsm2sipd1; params floating or NULL. */
void gs_gateway_call (GsGateway *self, const char *method, GVariant *params, GCancellable *cancel,
		      GAsyncReadyCallback cb, gpointer user_data);
GVariant *gs_gateway_call_finish (GsGateway *self, GAsyncResult *res, GError **error);

/* The D-Bus error's own message, without the GDBus.Error:… prefix. */
char *gs_gateway_error_text (const GError *error);

G_END_DECLS
