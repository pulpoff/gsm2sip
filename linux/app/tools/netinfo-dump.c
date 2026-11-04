/* Every text GsNetInfo makes, printed, for trying it without the UI:
 *   netinfo-dump [-w MS] [-n TICKS] [SIP_SERVER]
 * waits MS (default 1500) for the sources to load, prints the chips, both
 * fast blocks and the SIM captions, then the two async parts, then the
 * chips once more (by then SignalPoll has answered).  -n then keeps
 * printing chips and captions every second and on "changed", TICKS times:
 * for watching a ModemManager restart. */
#include "gs-net-info.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static GMainLoop *loop;
static int pending;
static gint64 t0;

static gboolean
on_wait (gpointer data)
{
	g_main_loop_quit (loop);
	return G_SOURCE_REMOVE;
}

static int ticks;

static void
print_watch (GsNetInfo *ni)
{
	g_autofree char *m = gs_net_info_mobile_chip (ni);
	g_autofree char *w = gs_net_info_wifi_chip (ni);
	g_autoptr (GPtrArray) caps = gs_net_info_sim_captions (ni);
	printf ("[%6.0f ms] %s | %s | %u SIM%s%s\n", (g_get_monotonic_time () - t0) / 1000.0, m, w, caps->len,
		caps->len ? ": " : "", caps->len ? (char *)g_ptr_array_index (caps, 0) : "");
	fflush (stdout);
}

static void
on_changed (GsNetInfo *ni, gpointer data)
{
	fprintf (stderr, "[%5.0f ms] changed\n", (g_get_monotonic_time () - t0) / 1000.0);
	if (data)
		print_watch (ni);
}

static gboolean
on_tick (gpointer data)
{
	print_watch (data);
	if (--ticks > 0)
		return G_SOURCE_CONTINUE;
	g_main_loop_quit (loop);
	return G_SOURCE_REMOVE;
}

static void
done (const char *title, char *text, GError *err)
{
	printf ("== %s (%.0f ms) ==\n%s\n", title, (g_get_monotonic_time () - t0) / 1000.0,
		text ? text : err->message);
	g_free (text);
	if (--pending == 0)
		g_main_loop_quit (loop);
}

static void
on_cells (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GError) err = NULL;
	char *s = gs_net_info_mobile_cells_finish (GS_NET_INFO (src), res, &err);
	done ("mobile cells", s, err);
}

static void
on_extra (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GError) err = NULL;
	char *s = gs_net_info_wifi_extra_finish (GS_NET_INFO (src), res, &err);
	done ("wifi extra", s, err);
}

static void
print_chips (GsNetInfo *ni)
{
	g_autofree char *m = gs_net_info_mobile_chip (ni);
	g_autofree char *w = gs_net_info_wifi_chip (ni);
	printf ("mobile chip: %s\nwifi chip  : %s\n\n", m, w);
}

int
main (int argc, char **argv)
{
	int wait_ms = 1500;
	const char *server = NULL;
	for (int i = 1; i < argc; i++) {
		if (!strcmp (argv[i], "-w") && i + 1 < argc)
			wait_ms = atoi (argv[++i]);
		else if (!strcmp (argv[i], "-n") && i + 1 < argc)
			ticks = atoi (argv[++i]);
		else if (argv[i][0] == '-') {
			fprintf (stderr, "usage: %s [-w MS] [-n TICKS] [SIP_SERVER]\n", argv[0]);
			return 2;
		} else
			server = argv[i];
	}
	t0 = g_get_monotonic_time ();
	loop = g_main_loop_new (NULL, FALSE);
	g_autoptr (GsNetInfo) ni = gs_net_info_new ();
	g_signal_connect (ni, "changed", G_CALLBACK (on_changed), NULL);
	print_chips (ni);	/* before anything loaded: "—" */
	g_timeout_add (wait_ms, on_wait, NULL);
	g_main_loop_run (loop);

	print_chips (ni);
	g_autofree char *md = gs_net_info_mobile_details (ni);
	g_autofree char *wd = gs_net_info_wifi_details (ni);
	printf ("== mobile details ==\n%s\n== wifi details ==\n%s\n== SIM captions ==\n", md, wd);
	g_autoptr (GPtrArray) caps = gs_net_info_sim_captions (ni);
	for (guint i = 0; i < caps->len; i++)
		printf ("%s\n", (char *)g_ptr_array_index (caps, i));
	if (!caps->len)
		printf ("(none: \"No active SIM detected\")\n");
	printf ("\n");

	pending = 2;
	gs_net_info_mobile_cells_async (ni, NULL, on_cells, NULL);
	gs_net_info_wifi_extra_async (ni, server, NULL, on_extra, NULL);
	g_main_loop_run (loop);
	print_chips (ni);
	if (ticks > 0) {
		g_signal_connect (ni, "changed", G_CALLBACK (on_changed), ni);
		g_timeout_add_seconds (1, on_tick, ni);
		g_main_loop_run (loop);
	}
	g_main_loop_unref (loop);
	return 0;
}
