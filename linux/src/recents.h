/* The recent calls and messages the app lists: CallLogStore.kt on Android,
 * here a file the daemon owns (/var/lib/gsm2sip/recents).  Each entry is an
 * a{sv} with Android's keys:
 *
 *   dir    s  "IN" | "OUT"
 *   num    s  the GSM party
 *   ts     x  ms since the epoch: when the call arrived or was dialled, when
 *             the message arrived or was accepted
 *   dur    x  seconds bridged; 0 is a call that never connected
 *   type   s  "CALL" | "SMS"
 *   text, smsId, enc, status, err  s     (messages only; status "pending",
 *   parts  i                              "sent", "failed")
 *
 * Kept as GVariant text, so `cat` reads it. */
#pragma once
#include <glib.h>

typedef struct GwRecents GwRecents;
typedef void (*GwRecentsChanged) (gpointer user_data);

/* path NULL: in memory only (the tests) */
GwRecents *gw_recents_new (const char *path);
void gw_recents_free (GwRecents *r);
void gw_recents_set_changed (GwRecents *r, GwRecentsChanged fn, gpointer user_data);

void gw_recents_add_call (GwRecents *r, gboolean incoming, const char *number, gint64 ts_ms, gint64 dur_s);
/* A message already listed under sms_id is not added again: an SMS the
 * server has not taken yet is read off the modem again after a restart. */
void gw_recents_add_sms (GwRecents *r, gboolean incoming, const char *number, gint64 ts_ms, const char *text,
			 const char *sms_id, const char *encoding, int parts, const char *status);
gboolean gw_recents_update_sms (GwRecents *r, const char *sms_id, const char *status, const char *error);
void gw_recents_clear (GwRecents *r);
/* aa{sv}, newest first, floating */
GVariant *gw_recents_list (GwRecents *r);
guint gw_recents_count (GwRecents *r);
