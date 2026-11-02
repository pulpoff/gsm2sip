/* RFC 5389 binding request, to learn the public address SIP and SDP must
 * advertise from behind NAT.  A port of net/StunClient.kt: the same servers,
 * tried in the same order, the first answer wins. */
#pragma once
#include <gio/gio.h>

typedef void (*StunCallback) (const char *public_ip, gpointer user_data);	/* NULL: all failed */

/* Asynchronous, on the thread-default main context. */
void stun_discover (GCancellable *cancel, StunCallback cb, gpointer user_data);

/* Exposed for the tests.  Returns TRUE and fills ip (dotted quad) and port. */
gboolean stun_parse_response (const guint8 *buf, gsize len, const guint8 txid[12], char ip[16], int *port);
void stun_build_request (guint8 out[20], const guint8 txid[12]);
