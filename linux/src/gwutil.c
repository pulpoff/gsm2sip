#include "gwutil.h"
#include <string.h>

char *
gw_to_international (const char *number)
{
	GString *s = g_string_new (NULL);
	for (const char *p = number ? number : ""; *p; p++)
		if (*p != ' ' && *p != '-' && *p != '/' && *p != '\t')
			g_string_append_c (s, *p);
	if (g_str_has_prefix (s->str, "00")) {
		g_string_erase (s, 0, 2);
		g_string_prepend_c (s, '+');
	}
	return g_string_free (s, FALSE);
}

char *
gw_digits_of (const char *v)
{
	GString *s = g_string_new (NULL);
	for (const char *p = v ? v : ""; *p; p++)
		if (g_ascii_isdigit (*p))
			g_string_append_c (s, *p);
	return g_string_free (s, FALSE);
}

gboolean
gw_looks_dialable (const char *user, int min_digits)
{
	if (!user || !*user)
		return FALSE;
	for (const char *p = user; *p; p++)
		if (!g_ascii_isdigit (*p) && !strchr ("+-.()", *p))
			return FALSE;
	g_autofree char *d = gw_digits_of (user);
	return (int)strlen (d) >= min_digits;
}

/* GSM 03.38 default alphabet, as Unicode code points */
static const gunichar gsm7_basic[] = {
	'@', 0xA3, '$', 0xA5, 0xE8, 0xE9, 0xF9, 0xEC, 0xF2, 0xC7, '\n', 0xD8, 0xF8, '\r', 0xC5, 0xE5,
	0x394, '_', 0x3A6, 0x393, 0x39B, 0x3A9, 0x3A0, 0x3A8, 0x3A3, 0x398, 0x39E, 0xC6, 0xE6, 0xDF, 0xC9,
	' ', '!', '"', '#', 0xA4, '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
	':', ';', '<', '=', '>', '?', 0xA1, 0xC4, 0xD6, 0xD1, 0xDC, 0xA7, 0xBF, 0xE4, 0xF6, 0xF1, 0xFC, 0xE0,
};
static const gunichar gsm7_ext[] = { '^', '{', '}', '\\', '[', '~', ']', '|', 0x20AC, '\f' };

static int
gsm7_septets (gunichar c)
{
	if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))
		return 1;
	for (guint i = 0; i < G_N_ELEMENTS (gsm7_basic); i++)
		if (gsm7_basic[i] == c)
			return 1;
	for (guint i = 0; i < G_N_ELEMENTS (gsm7_ext); i++)
		if (gsm7_ext[i] == c)
			return 2;
	return 0;
}

GwSmsMeasure
gw_sms_measure (const char *utf8)
{
	GwSmsMeasure m = { 1, "GSM7" };
	int septets = 0, units = 0;
	gboolean gsm7 = TRUE;
	for (const char *p = utf8; p && *p; p = g_utf8_next_char (p)) {
		gunichar c = g_utf8_get_char (p);
		int n = gsm7_septets (c);
		if (!n)
			gsm7 = FALSE;
		septets += n;
		units += c > 0xFFFF ? 2 : 1;
	}
	if (gsm7) {
		m.parts = septets <= 160 ? 1 : (septets + 152) / 153;
	} else {
		m.encoding = "UCS2";
		m.parts = units <= 70 ? 1 : (units + 66) / 67;
	}
	return m;
}

static char *
uuid_from (guint8 b[16], int version)
{
	b[6] = (guint8)((b[6] & 0x0f) | (version << 4));
	b[8] = (guint8)((b[8] & 0x3f) | 0x80);
	return g_strdup_printf ("%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
				b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12],
				b[13], b[14], b[15]);
}

char *
gw_sms_stable_id (const char *number, const char *timestamp, const char *text)
{
	g_autoptr (GChecksum) h = g_checksum_new (G_CHECKSUM_SHA256);
	const char *parts[] = { number ? number : "", timestamp ? timestamp : "", text ? text : "" };
	for (guint i = 0; i < G_N_ELEMENTS (parts); i++) {
		g_checksum_update (h, (const guchar *)parts[i], strlen (parts[i]));
		g_checksum_update (h, (const guchar *)"\x1f", 1);
	}
	guint8 d[32];
	gsize len = sizeof d;
	g_checksum_get_digest (h, d, &len);
	return uuid_from (d, 5);
}

char *
gw_uuid_random (void)
{
	return g_uuid_string_random ();
}

char *
gw_iso_utc (GDateTime *when)
{
	g_autoptr (GDateTime) now = when ? NULL : g_date_time_new_now_utc ();
	g_autoptr (GDateTime) utc = g_date_time_to_utc (when ? when : now);
	return g_date_time_format (utc, "%Y-%m-%dT%H:%M:%SZ");
}

char *
gw_iso_utc_from_mm (const char *ts)
{
	if (ts && *ts) {
		g_autoptr (GDateTime) d = g_date_time_new_from_iso8601 (ts, NULL);
		if (d)
			return gw_iso_utc (d);
	}
	return gw_iso_utc (NULL);
}

void
gw_json_append_string (GString *s, const char *v)
{
	g_string_append_c (s, '"');
	for (const unsigned char *p = (const unsigned char *)(v ? v : ""); *p; p++) {
		switch (*p) {
		case '"': g_string_append (s, "\\\""); break;
		case '\\': g_string_append (s, "\\\\"); break;
		case '\n': g_string_append (s, "\\n"); break;
		case '\r': g_string_append (s, "\\r"); break;
		case '\t': g_string_append (s, "\\t"); break;
		default:
			if (*p < 0x20)
				g_string_append_printf (s, "\\u%04x", *p);
			else
				g_string_append_c (s, (char)*p);
		}
	}
	g_string_append_c (s, '"');
}

/* spellings that are conventions, not accents: decomposition turns ü into
 * u, not ue, and can do nothing with ß */
static const struct {
	gunichar c;
	const char *to;
} ascii_explicit[] = {
	{ 0x00e4, "ae" }, { 0x00f6, "oe" }, { 0x00fc, "ue" },
	{ 0x00c4, "Ae" }, { 0x00d6, "Oe" }, { 0x00dc, "Ue" },
	{ 0x00df, "ss" }, { 0x1e9e, "Ss" },
	{ 0x00e6, "ae" }, { 0x00c6, "Ae" },
	{ 0x0153, "oe" }, { 0x0152, "Oe" },
	{ 0x00f8, "oe" }, { 0x00d8, "Oe" },
	{ 0x00e5, "aa" }, { 0x00c5, "Aa" },
	{ 0x00fe, "th" }, { 0x00de, "Th" },
	{ 0x00f0, "d" }, { 0x00d0, "D" },
	{ 0x0142, "l" }, { 0x0141, "L" },
	/* punctuation word processors and language models emit freely */
	{ 0x2014, "-" }, { 0x2013, "-" }, { 0x2011, "-" },
	{ 0x2026, "..." },
	{ 0x2018, "'" }, { 0x2019, "'" }, { 0x201a, "'" },
	{ 0x201c, "\"" }, { 0x201d, "\"" }, { 0x201e, "\"" },
	{ 0x00ab, "\"" }, { 0x00bb, "\"" },
	{ 0x2192, "->" }, { 0x2190, "<-" }, { 0x21d2, "=>" },
	{ 0x2022, "*" }, { 0x25cf, "*" }, { 0x00b7, "-" },
	{ 0x20ac, "EUR" }, { 0x00a3, "GBP" },
	/* spaces that are not the ASCII space */
	{ 0x00a0, " " }, { 0x2009, " " }, { 0x202f, " " },
};

static gboolean
ascii_sendable (gunichar c)
{
	return (c >= 32 && c <= 126) || c == '\n' || c == '\r' || c == '\t';
}

char *
gw_sms_ascii_or_null (const char *utf8)
{
	if (!utf8 || !g_utf8_validate (utf8, -1, NULL))
		return NULL;
	gboolean plain = TRUE;
	for (const unsigned char *p = (const unsigned char *)utf8; *p && plain; p++)
		plain = (*p >= 32 && *p <= 126) || *p == '\n' || *p == '\r';
	if (plain)
		return g_strdup (utf8);
	g_autoptr (GString) s = g_string_new (NULL);
	for (const char *p = utf8; *p; p = g_utf8_next_char (p)) {
		gunichar c = g_utf8_get_char (p);
		const char *to = NULL;
		for (gsize i = 0; i < G_N_ELEMENTS (ascii_explicit) && !to; i++)
			if (ascii_explicit[i].c == c)
				to = ascii_explicit[i].to;
		if (to)
			g_string_append (s, to);
		else
			g_string_append_unichar (s, c);
	}
	/* then decomposition for anything merely accented: é becomes e */
	g_autofree char *nfd = g_utf8_normalize (s->str, -1, G_NORMALIZE_NFD);
	GString *out = g_string_new (NULL);
	for (const char *p = nfd; p && *p; p = g_utf8_next_char (p)) {
		gunichar c = g_utf8_get_char (p);
		if (g_unichar_type (c) == G_UNICODE_NON_SPACING_MARK)
			continue;
		if (!ascii_sendable (c)) {
			g_string_free (out, TRUE);
			return NULL;
		}
		g_string_append_c (out, (char)c);
	}
	return g_string_free (out, FALSE);
}
