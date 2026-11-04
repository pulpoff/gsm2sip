/* The two link chips and their detail dialogs (UI spec §3.4, §4.1, §4.2) and
 * the Settings SIM captions (§5.3): ModemManager, NetworkManager and
 * wpa_supplicant over plain GDBus, the Linux stand-ins for TelephonyManager
 * and WifiManager.  Everything synchronous reads cached properties only;
 * "changed" fires when a source comes, goes or first answers, so the UI need
 * not wait for its 5 s tick to replace the initial "—". */
#pragma once
#include <gio/gio.h>

G_BEGIN_DECLS

#define GS_TYPE_NET_INFO (gs_net_info_get_type ())
G_DECLARE_FINAL_TYPE (GsNetInfo, gs_net_info, GS, NET_INFO, GObject)

/* Connects to the system bus (ModemManager, NetworkManager) on its own; never
 * fails: without them the texts say n/a. */
GsNetInfo *gs_net_info_new (void);

/* §3.4 chips: cheap (cached D-Bus properties), called every 5 s from the UI.
 * "—" while the sources are still being looked up. */
char *gs_net_info_mobile_chip (GsNetInfo *self);	/* "LTE LycaMobile -93dBm", "mobile: n/a" ... */
char *gs_net_info_wifi_chip (GsNetInfo *self);		/* "HomeNet 5G 866Mbps -52dBm", "WiFi off", "WiFi not connected" ... */

/* §4.1: the fast block, shown at once */
char *gs_net_info_mobile_details (GsNetInfo *self);
/* §4.1: the cell list (blank-line separated blocks, first 6 cells), appended
 * after a blank line when it arrives.  The text does not start with that
 * blank line.  Fails only with G_IO_ERROR_CANCELLED. */
void gs_net_info_mobile_cells_async (GsNetInfo *self, GCancellable *cancel, GAsyncReadyCallback cb,
				     gpointer user_data);
char *gs_net_info_mobile_cells_finish (GsNetInfo *self, GAsyncResult *res, GError **error);

/* §4.2: the fast block */
char *gs_net_info_wifi_details (GsNetInfo *self);
/* §4.2: the async part — Phone MAC / Router IP / Router MAC lines, then
 * "\n— reachability —\n" block with pings (router, and sip_server when
 * non-NULL/non-empty).  Takes up to ~5 s (ping -c 3 -W 2, both at once).
 * Fails only with G_IO_ERROR_CANCELLED. */
void gs_net_info_wifi_extra_async (GsNetInfo *self, const char *sip_server, GCancellable *cancel,
				   GAsyncReadyCallback cb, gpointer user_data);
char *gs_net_info_wifi_extra_finish (GsNetInfo *self, GAsyncResult *res, GError **error);

/* §5.3: one caption per active SIM in slot order, e.g.
 * "SIM 1  ·  LycaMobile  ·  IMEI 35…"; empty array if none.
 * Element type char*, the array frees them. */
GPtrArray *gs_net_info_sim_captions (GsNetInfo *self);

G_END_DECLS
