#define G_LOG_DOMAIN "stun"
#include "stun.h"
#include <string.h>
#include <sys/random.h>

#define MAGIC 0x2112A442u

static const struct { const char *host; guint16 port; } servers[] = {
	{ "stun.sipgate.net", 3478 },
	{ "stun.1und1.de", 3478 },
	{ "stun.schlund.de", 3478 },
	{ "stun.gmx.net", 3478 },
	{ "stun.cloudflare.com", 3478 },
	{ "stun.nextcloud.com", 3478 },
	{ "stun.l.google.com", 19302 },
};

void
stun_build_request (guint8 out[20], const guint8 txid[12])
{
	out[0] = 0x00; out[1] = 0x01;	/* Binding Request */
	out[2] = 0x00; out[3] = 0x00;	/* no attributes */
	out[4] = MAGIC >> 24; out[5] = (MAGIC >> 16) & 0xff; out[6] = (MAGIC >> 8) & 0xff; out[7] = MAGIC & 0xff;
	memcpy (out + 8, txid, 12);
}

static guint16 rd16 (const guint8 *p) { return (guint16)((p[0] << 8) | p[1]); }
static guint32 rd32 (const guint8 *p) { return ((guint32)p[0] << 24) | ((guint32)p[1] << 16) | ((guint32)p[2] << 8) | p[3]; }

gboolean
stun_parse_response (const guint8 *b, gsize len, const guint8 txid[12], char ip[16], int *port)
{
	if (len < 20 || rd16 (b) != 0x0101 || rd32 (b + 4) != MAGIC || memcmp (b + 8, txid, 12))
		return FALSE;
	gsize end = MIN (len, 20 + (gsize)rd16 (b + 2));
	for (gsize off = 20; off + 4 <= end;) {
		guint16 type = rd16 (b + off), alen = rd16 (b + off + 2);
		const guint8 *v = b + off + 4;
		if (off + 4 + alen > end)
			break;
		/* IPv4 only, as the Kotlin: the gateway advertises a v4 address */
		if ((type == 0x0020 || type == 0x0001) && alen >= 8 && v[1] == 0x01) {
			guint32 a = rd32 (v + 4);
			int p = rd16 (v + 2);
			if (type == 0x0020) {
				a ^= MAGIC;
				p ^= MAGIC >> 16;
			}
			g_snprintf (ip, 16, "%u.%u.%u.%u", a >> 24, (a >> 16) & 0xff, (a >> 8) & 0xff, a & 0xff);
			*port = p;
			return TRUE;
		}
		off += 4 + ((alen + 3u) & ~3u);
	}
	return FALSE;
}

typedef struct {
	guint idx;
	GSocket *sock;
	GSource *src;
	guint timer;
	guint8 txid[12];
	StunCallback cb;
	gpointer ud;
	GCancellable *cancel;
} Query;

static void next_server (Query *q);

static void
drop_wait (Query *q)
{
	if (q->src) {
		g_source_destroy (q->src);
		g_source_unref (q->src);
		q->src = NULL;
	}
	if (q->timer) {
		g_source_remove (q->timer);
		q->timer = 0;
	}
}

static void
finish (Query *q, const char *ip, gboolean notify)
{
	drop_wait (q);
	g_clear_object (&q->sock);
	if (notify)
		q->cb (ip, q->ud);
	g_clear_object (&q->cancel);
	g_free (q);
}

static gboolean
on_timeout (gpointer data)
{
	Query *q = data;
	q->timer = 0;
	g_debug ("STUN %s: no answer", servers[q->idx - 1].host);
	drop_wait (q);
	next_server (q);
	return G_SOURCE_REMOVE;
}

static gboolean
on_readable (GSocket *s, GIOCondition cond, gpointer data)
{
	Query *q = data;
	(void)cond;
	guint8 buf[512];
	gssize n;
	while ((n = g_socket_receive (s, (gchar *)buf, sizeof buf, NULL, NULL)) > 0) {
		char ip[16];
		int port;
		if (stun_parse_response (buf, n, q->txid, ip, &port)) {
			g_message ("STUN result from %s: %s:%d", servers[q->idx - 1].host, ip, port);
			finish (q, ip, TRUE);
			return G_SOURCE_REMOVE;
		}
	}
	return G_SOURCE_CONTINUE;
}

static void
on_resolved (GObject *src, GAsyncResult *res, gpointer data)
{
	Query *q = data;
	g_autoptr (GError) err = NULL;
	GList *addrs = g_resolver_lookup_by_name_finish (G_RESOLVER (src), res, &err);
	if (!addrs) {
		if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED)) {
			finish (q, NULL, FALSE);
			return;
		}
		g_debug ("STUN %s: %s", servers[q->idx - 1].host, err->message);
		next_server (q);
		return;
	}
	GInetAddress *v4 = NULL;
	for (GList *l = addrs; l; l = l->next)
		if (g_inet_address_get_family (l->data) == G_SOCKET_FAMILY_IPV4) { v4 = l->data; break; }
	if (!v4) {
		g_resolver_free_addresses (addrs);
		next_server (q);
		return;
	}
	if (!q->sock) {
		q->sock = g_socket_new (G_SOCKET_FAMILY_IPV4, G_SOCKET_TYPE_DATAGRAM, G_SOCKET_PROTOCOL_UDP, &err);
		if (!q->sock) {
			g_warning ("STUN socket: %s", err->message);
			g_resolver_free_addresses (addrs);
			finish (q, NULL, TRUE);
			return;
		}
		g_socket_set_blocking (q->sock, FALSE);
	}
	if (getrandom (q->txid, sizeof q->txid, 0) != sizeof q->txid)
		for (guint i = 0; i < sizeof q->txid; i++)
			q->txid[i] = (guint8)g_random_int ();
	guint8 req[20];
	stun_build_request (req, q->txid);
	g_autoptr (GSocketAddress) sa = g_inet_socket_address_new (v4, servers[q->idx - 1].port);
	g_resolver_free_addresses (addrs);
	if (g_socket_send_to (q->sock, sa, (const gchar *)req, sizeof req, NULL, &err) < 0) {
		g_debug ("STUN %s: send: %s", servers[q->idx - 1].host, err->message);
		next_server (q);
		return;
	}
	q->src = g_socket_create_source (q->sock, G_IO_IN, NULL);
	g_source_set_callback (q->src, G_SOURCE_FUNC (on_readable), q, NULL);
	g_source_attach (q->src, NULL);
	/* a live server answers in well under 100 ms */
	q->timer = g_timeout_add (1500, on_timeout, q);
}

static void
next_server (Query *q)
{
	if (q->idx >= G_N_ELEMENTS (servers)) {
		g_warning ("All STUN servers failed");
		finish (q, NULL, TRUE);
		return;
	}
	const char *host = servers[q->idx++].host;
	g_autoptr (GResolver) r = g_resolver_get_default ();
	g_resolver_lookup_by_name_async (r, host, q->cancel, on_resolved, q);
}

void
stun_discover (GCancellable *cancel, StunCallback cb, gpointer ud)
{
	Query *q = g_new0 (Query, 1);
	q->cb = cb;
	q->ud = ud;
	q->cancel = cancel ? g_object_ref (cancel) : NULL;
	next_server (q);
}
