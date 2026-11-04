/* What the GNOME app talks to: com.callagent.Gsm2sipd on the system bus,
 * /com/callagent/Gsm2sipd, interface com.callagent.Gsm2sipd1.  It is the
 * Android app's view of GatewayService — status, the recents list, the log,
 * the settings — for an app that runs as the phone's user while the daemon
 * runs as root.  Who may call it is the bus policy's business
 * (data/com.callagent.Gsm2sipd.conf); the SIP password is write-only. */
#pragma once
#include "recents.h"
#include <gio/gio.h>

typedef struct GwService GwService;

typedef struct {
	/* the config file was rewritten: take it up (when no call is up) */
	void (*config_changed) (gpointer ud);
	/* the Enabled switch was written to it: take it up now */
	void (*enabled_changed) (gboolean enabled, gpointer ud);
	void (*reconnect) (gpointer ud);
	gboolean (*hangup) (gpointer ud);
	void (*set_agent_muted) (gboolean muted, gpointer ud);
} GwServiceOps;

GwService *gw_service_new (const char *config_path, GwRecents *recents, const GwServiceOps *ops, gpointer ud);
void gw_service_free (GwService *s);

/* "unconfigured" | "disabled" | "connecting" | "registered" | "failed" */
void gw_service_set_state (GwService *s, const char *state);
/* the config's [gateway] enabled: off, the modem is the phone's own */
void gw_service_set_enabled (GwService *s, gboolean enabled);
void gw_service_set_identity (GwService *s, const char *server, const char *user);
/* state: the bridge's, lower case ("idle", "bridged"...) */
void gw_service_set_call (GwService *s, const char *state, const char *info, const char *number, gboolean incoming,
			  gint64 bridged_at_ms, gboolean agent_muted);
void gw_service_set_restart_pending (GwService *s, gboolean pending);

/* One log line, from any thread.  Kept in a ring for GetLog whether or not
 * the service is up yet. */
void gw_log_line (const char *line);
