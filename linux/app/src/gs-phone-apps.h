/* The phone's own Phone and Messages apps — GNOME Calls and Chatty, as the
 * session's calls-daemon and sm.puri.Chatty-daemon user units.  While
 * gsm2sipd holds the modem they must not run: Calls would ring and answer
 * the same call, Chatty would take the SMS off the modem.  So the Enabled
 * switch holds them (stopped and masked, autostart hidden) and gives them
 * back (unmasked and started).  The user's own systemd, no root. */
#pragma once
#include <gio/gio.h>

G_BEGIN_DECLS

/* masked in ~/.config/systemd/user */
gboolean gs_phone_apps_held (void);
/* FALSE on a system that has neither (the dev box): nothing to do */
gboolean gs_phone_apps_installed (void);
void gs_phone_apps_hold_async (gboolean hold, GCancellable *cancel, GAsyncReadyCallback cb, gpointer user_data);
gboolean gs_phone_apps_hold_finish (GAsyncResult *res, GError **error);

G_END_DECLS
