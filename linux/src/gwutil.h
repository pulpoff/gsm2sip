/* Small helpers shared by the bridge and the tests: number handling, SMS
 * measuring, the wire timestamp and id formats. */
#pragma once
#include <glib.h>

/* Strip spaces, '-' and '/'; "00..." becomes "+...".  No country guessing:
 * on Linux there is no SIM-country lookup to resolve a national number
 * against, so a national own_number goes out as written (see README). */
char *gw_to_international(const char *number);
char *gw_digits_of(const char *s);
/* Only digits and "+-.()", with at least `min_digits` digits. */
gboolean gw_looks_dialable(const char *user, int min_digits);

typedef struct {
	int parts;
	const char *encoding;	/* "GSM7" | "UCS2" */
} GwSmsMeasure;
GwSmsMeasure gw_sms_measure(const char *utf8);

/* A UUID-shaped id that is the same every time the same SMS is seen, so a
 * message forwarded again after a restart is recognisable as the same one. */
char *gw_sms_stable_id(const char *number, const char *timestamp, const char *text);
char *gw_uuid_random(void);

/* yyyy-MM-dd'T'HH:mm:ss'Z' in UTC; `when` NULL means now. */
char *gw_iso_utc(GDateTime *when);
/* ModemManager's SMS timestamp (ISO 8601 with offset) as gw_iso_utc, or now. */
char *gw_iso_utc_from_mm(const char *mm_timestamp);

void gw_json_append_string(GString *s, const char *v);
