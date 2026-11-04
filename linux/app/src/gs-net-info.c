/* The link chips, the two link dialogs and the SIM captions, read from
 * ModemManager, NetworkManager and wpa_supplicant over plain GDBus (no
 * libmm-glib, no libnm).  The sources are object managers and proxies whose
 * properties GDBus keeps cached, so every synchronous call here is a few
 * hash lookups and never a round trip; the only calls that go out are
 * GetCellInfo / GetLocation, wpa_supplicant's SignalPoll, Signal.Setup (once,
 * only when nobody polls), and ping. */
#define G_LOG_DOMAIN "netinfo"
#include "gs-net-info.h"
#include <math.h>
#include <stdarg.h>
#include <string.h>

#define MM_BUS "org.freedesktop.ModemManager1"
#define MM_PATH "/org/freedesktop/ModemManager1"
#define IF_MODEM MM_BUS ".Modem"
#define IF_3GPP MM_BUS ".Modem.Modem3gpp"
#define IF_SIGNAL MM_BUS ".Modem.Signal"
#define IF_LOCATION MM_BUS ".Modem.Location"
#define IF_SIM MM_BUS ".Sim"

#define NM_BUS "org.freedesktop.NetworkManager"
#define NM_PATH "/org/freedesktop/NetworkManager"
#define IF_NM NM_BUS
#define IF_NM_DEVICE NM_BUS ".Device"
#define IF_NM_WIRELESS NM_BUS ".Device.Wireless"
#define IF_NM_AP NM_BUS ".AccessPoint"
#define IF_NM_IP4 NM_BUS ".IP4Config"
#define IF_NM_IP6 NM_BUS ".IP6Config"

#define WPA_BUS "fi.w1.wpa_supplicant1"
#define WPA_PATH "/fi/w1/wpa_supplicant1"
#define IF_WPA_IFACE WPA_BUS ".Interface"

#define DASH "—"

/* ModemManager-enums.h */
enum { MM_STATE_FAILED = -1, MM_STATE_UNKNOWN, MM_STATE_INITIALIZING, MM_STATE_LOCKED, MM_STATE_DISABLED,
       MM_STATE_DISABLING, MM_STATE_ENABLING, MM_STATE_ENABLED, MM_STATE_SEARCHING, MM_STATE_REGISTERED };
enum { MM_FAILED_SIM_MISSING = 2, MM_FAILED_SIM_ERROR = 3, MM_FAILED_ESIM_WITHOUT_PROFILES = 5 };
enum { MM_LOCK_UNKNOWN, MM_LOCK_NONE, MM_LOCK_SIM_PIN, MM_LOCK_SIM_PIN2, MM_LOCK_SIM_PUK, MM_LOCK_SIM_PUK2,
       MM_LOCK_PH_SIM_PIN = 10, MM_LOCK_PH_FSIM_PIN = 13, MM_LOCK_PH_FSIM_PUK = 14 };
enum { MM_REG_HOME = 1, MM_REG_ROAMING = 5, MM_REG_HOME_SMS_ONLY, MM_REG_ROAMING_SMS_ONLY,
       MM_REG_HOME_CSFB_NOT_PREFERRED = 9, MM_REG_ROAMING_CSFB_NOT_PREFERRED };
enum { MM_CELL_UNKNOWN, MM_CELL_CDMA, MM_CELL_GSM, MM_CELL_UMTS, MM_CELL_TDSCDMA, MM_CELL_LTE, MM_CELL_5GNR };
#define MM_LOCATION_3GPP_LAC_CI 1u

#define ACT_2G (1u << 1 | 1u << 2 | 1u << 3 | 1u << 4 | 1u << 10)	/* gsm, compact, gprs, edge, 1xrtt */
#define ACT_3G (0x1fu << 5 | 0x7u << 11)				/* umts … hspa+, evdo 0/a/b */
#define ACT_LTE (1u << 14 | 1u << 16 | 1u << 17)			/* lte, cat-m, nb-iot */
#define ACT_5G (1u << 15)

/* NetworkManager */
#define NM_DEVICE_ACTIVATED 100
#define NM_AP_PRIVACY 0x1u
#define NM_SEC_PSK 0x100u
#define NM_SEC_8021X 0x200u
#define NM_SEC_SAE 0x400u
#define NM_SEC_OWE 0x800u
#define NM_SEC_OWE_TM 0x1000u
#define NM_SEC_SUITE_B_192 0x2000u

/* a SignalPoll answer older than this no longer counts as the RSSI */
#define POLL_FRESH_US (15 * G_USEC_PER_SEC)
#define POLL_EVERY_US (3 * G_USEC_PER_SEC)

struct _GsNetInfo {
	GObject parent;
	GCancellable *cancel;
	GDBusConnection *bus;
	GDBusObjectManager *mm, *nm;
	gboolean bus_done, mm_done, nm_done;	/* answered, whether or not it worked */
	GHashTable *sims;			/* MM SIM path -> GDBusProxy, NULL while being made */
	GHashTable *signal_asked;		/* modem paths Signal.Setup was considered for */
	GHashTable *no_cell_info;		/* modem path -> why GetCellInfo will never answer */
	GDBusProxy *wpa;			/* wpa_supplicant's root object */
	GDBusProxy *wpa_iface;			/* its Interface for the Wi-Fi device */
	char *wpa_ifname;			/* the ifname wpa_iface is (being looked up) for */
	guint wpa_gen;				/* bumped on every reset: older answers are stale */
	gboolean wpa_busy, wpa_failed, polling;
	gint64 poll_at, poll_tried;		/* monotonic µs */
	int poll_rssi, poll_speed;
	guint state;				/* what "changed" was last emitted for */
};

G_DEFINE_FINAL_TYPE (GsNetInfo, gs_net_info, G_TYPE_OBJECT)

enum { SIG_CHANGED, N_SIGNALS };
static guint signals[N_SIGNALS];

static void wpa_sync (GsNetInfo *self, const char *ifname);

/* ---- small helpers ---- */

static GVariant *
prop (GDBusProxy *p, const char *name)
{
	return p ? g_dbus_proxy_get_cached_property (p, name) : NULL;
}

static gboolean
hex_of (const char *s, gint64 *out)
{
	char *end;
	if (!s || !*s)
		return FALSE;
	*out = (gint64)g_ascii_strtoull (s, &end, 16);
	return *end == '\0';
}

/* Any integer or double; a string is hex, which is how MM's cell info and
 * 3GPP location give their ids. */
static gboolean
num_of (GVariant *v, gint64 *out)
{
	if (!v)
		return FALSE;
	if (g_variant_is_of_type (v, G_VARIANT_TYPE_VARIANT)) {
		g_autoptr (GVariant) in = g_variant_get_variant (v);
		return num_of (in, out);
	}
	switch (g_variant_classify (v)) {
	case G_VARIANT_CLASS_BOOLEAN:
		*out = g_variant_get_boolean (v);
		return TRUE;
	case G_VARIANT_CLASS_BYTE:
		*out = g_variant_get_byte (v);
		return TRUE;
	case G_VARIANT_CLASS_INT16:
		*out = g_variant_get_int16 (v);
		return TRUE;
	case G_VARIANT_CLASS_UINT16:
		*out = g_variant_get_uint16 (v);
		return TRUE;
	case G_VARIANT_CLASS_INT32:
		*out = g_variant_get_int32 (v);
		return TRUE;
	case G_VARIANT_CLASS_UINT32:
		*out = g_variant_get_uint32 (v);
		return TRUE;
	case G_VARIANT_CLASS_INT64:
		*out = g_variant_get_int64 (v);
		return TRUE;
	case G_VARIANT_CLASS_UINT64:
		*out = (gint64)g_variant_get_uint64 (v);
		return TRUE;
	case G_VARIANT_CLASS_DOUBLE: {
		double d = g_variant_get_double (v);
		if (!isfinite (d))
			return FALSE;
		*out = (gint64)(d < 0 ? d - 0.5 : d + 0.5);
		return TRUE;
	}
	case G_VARIANT_CLASS_STRING:
		return hex_of (g_variant_get_string (v, NULL), out);
	default:
		return FALSE;
	}
}

static gint64
prop_num (GDBusProxy *p, const char *name, gint64 def)
{
	g_autoptr (GVariant) v = prop (p, name);
	gint64 n;
	return num_of (v, &n) ? n : def;
}

static char *
prop_str (GDBusProxy *p, const char *name)
{
	g_autoptr (GVariant) v = prop (p, name);
	if (v && (g_variant_is_of_type (v, G_VARIANT_TYPE_STRING) || g_variant_is_of_type (v, G_VARIANT_TYPE_OBJECT_PATH)))
		return g_variant_dup_string (v, NULL);
	return NULL;
}

static gboolean
is_path (const char *p)
{
	return p && *p && strcmp (p, "/");
}

static gboolean
dict_num (GVariant *dict, const char *key, gint64 *out)
{
	g_autoptr (GVariant) v = g_variant_lookup_value (dict, key, NULL);
	return num_of (v, out);
}

static void
row (GString *s, const char *label, const char *value)
{
	g_string_append_printf (s, "%-13s: %s\n", label, value && *value ? value : DASH);
}

G_GNUC_PRINTF (3, 4) static void
rowf (GString *s, const char *label, const char *fmt, ...)
{
	va_list ap;
	va_start (ap, fmt);
	g_autofree char *v = g_strdup_vprintf (fmt, ap);
	va_end (ap);
	row (s, label, v);
}

static void
row_num (GString *s, const char *label, gboolean ok, gint64 v)
{
	if (ok)
		rowf (s, label, "%" G_GINT64_FORMAT, v);
	else
		row (s, label, NULL);
}

/* The D-Bus error's own words, without the GDBus.Error:… prefix; the two
 * that would otherwise be a paragraph or a riddle get a short one. */
static char *
error_text (const GError *err)
{
	if (g_error_matches (err, G_DBUS_ERROR, G_DBUS_ERROR_ACCESS_DENIED))
		return g_strdup ("access denied by the D-Bus policy");
	if (g_error_matches (err, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_METHOD))
		return g_strdup ("not supported by this ModemManager");
	g_autoptr (GError) e = g_error_copy (err);
	g_dbus_error_strip_remote_error (e);
	return g_strdup (e->message);
}

static gboolean
om_up (GDBusObjectManager *om)
{
	if (!om)
		return FALSE;
	g_autofree char *owner = g_dbus_object_manager_client_get_name_owner (G_DBUS_OBJECT_MANAGER_CLIENT (om));
	return owner != NULL;
}

static GDBusProxy *
om_iface (GDBusObjectManager *om, const char *path, const char *iface)
{
	if (!om || !is_path (path) || !g_variant_is_object_path (path))
		return NULL;
	return G_DBUS_PROXY (g_dbus_object_manager_get_interface (om, path, iface));
}

static GDBusProxy *
obj_iface (GDBusObject *o, const char *iface)
{
	return o ? G_DBUS_PROXY (g_dbus_object_get_interface (o, iface)) : NULL;
}

static void
unref0 (gpointer p)
{
	if (p)
		g_object_unref (p);
}

/* ---- MCC -> ISO 3166 country (TelephonyManager.networkCountryIso) ---- */

static const struct {
	guint16 mcc;
	char iso[3];
} mcc_iso[] = {
	{ 202, "GR" }, { 204, "NL" }, { 206, "BE" }, { 208, "FR" }, { 212, "MC" }, { 213, "AD" }, { 214, "ES" },
	{ 216, "HU" }, { 218, "BA" }, { 219, "HR" }, { 220, "RS" }, { 221, "XK" }, { 222, "IT" }, { 225, "VA" },
	{ 226, "RO" }, { 228, "CH" }, { 230, "CZ" }, { 231, "SK" }, { 232, "AT" }, { 234, "GB" }, { 235, "GB" },
	{ 238, "DK" }, { 240, "SE" }, { 242, "NO" }, { 244, "FI" }, { 246, "LT" }, { 247, "LV" }, { 248, "EE" },
	{ 250, "RU" }, { 255, "UA" }, { 257, "BY" }, { 259, "MD" }, { 260, "PL" }, { 262, "DE" }, { 266, "GI" },
	{ 268, "PT" }, { 270, "LU" }, { 272, "IE" }, { 274, "IS" }, { 276, "AL" }, { 278, "MT" }, { 280, "CY" },
	{ 282, "GE" }, { 283, "AM" }, { 284, "BG" }, { 286, "TR" }, { 288, "FO" }, { 290, "GL" }, { 292, "SM" },
	{ 293, "SI" }, { 294, "MK" }, { 295, "LI" }, { 297, "ME" }, { 302, "CA" }, { 308, "PM" }, { 310, "US" },
	{ 311, "US" }, { 312, "US" }, { 313, "US" }, { 314, "US" }, { 315, "US" }, { 316, "US" }, { 330, "PR" },
	{ 334, "MX" }, { 338, "JM" }, { 340, "GP" }, { 342, "BB" }, { 344, "AG" }, { 346, "KY" }, { 348, "VG" },
	{ 350, "BM" }, { 352, "GD" }, { 354, "MS" }, { 356, "KN" }, { 358, "LC" }, { 360, "VC" }, { 362, "CW" },
	{ 363, "AW" }, { 364, "BS" }, { 365, "AI" }, { 366, "DM" }, { 368, "CU" }, { 370, "DO" }, { 372, "HT" },
	{ 374, "TT" }, { 376, "TC" }, { 400, "AZ" }, { 401, "KZ" }, { 402, "BT" }, { 404, "IN" }, { 405, "IN" },
	{ 406, "IN" }, { 410, "PK" }, { 412, "AF" }, { 413, "LK" }, { 414, "MM" }, { 415, "LB" }, { 416, "JO" },
	{ 417, "SY" }, { 418, "IQ" }, { 419, "KW" }, { 420, "SA" }, { 421, "YE" }, { 422, "OM" }, { 424, "AE" },
	{ 425, "IL" }, { 426, "BH" }, { 427, "QA" }, { 428, "MN" }, { 429, "NP" }, { 430, "AE" }, { 431, "AE" },
	{ 432, "IR" }, { 434, "UZ" }, { 436, "TJ" }, { 437, "KG" }, { 438, "TM" }, { 440, "JP" }, { 441, "JP" },
	{ 450, "KR" }, { 452, "VN" }, { 454, "HK" }, { 455, "MO" }, { 456, "KH" }, { 457, "LA" }, { 460, "CN" },
	{ 461, "CN" }, { 466, "TW" }, { 467, "KP" }, { 470, "BD" }, { 472, "MV" }, { 502, "MY" }, { 505, "AU" },
	{ 510, "ID" }, { 514, "TL" }, { 515, "PH" }, { 520, "TH" }, { 525, "SG" }, { 528, "BN" }, { 530, "NZ" },
	{ 536, "NR" }, { 537, "PG" }, { 539, "TO" }, { 540, "SB" }, { 541, "VU" }, { 542, "FJ" }, { 544, "AS" },
	{ 545, "KI" }, { 546, "NC" }, { 547, "PF" }, { 548, "CK" }, { 549, "WS" }, { 550, "FM" }, { 551, "MH" },
	{ 552, "PW" }, { 602, "EG" }, { 603, "DZ" }, { 604, "MA" }, { 605, "TN" }, { 606, "LY" }, { 607, "GM" },
	{ 608, "SN" }, { 609, "MR" }, { 610, "ML" }, { 611, "GN" }, { 612, "CI" }, { 613, "BF" }, { 614, "NE" },
	{ 615, "TG" }, { 616, "BJ" }, { 617, "MU" }, { 618, "LR" }, { 619, "SL" }, { 620, "GH" }, { 621, "NG" },
	{ 622, "TD" }, { 623, "CF" }, { 624, "CM" }, { 625, "CV" }, { 626, "ST" }, { 627, "GQ" }, { 628, "GA" },
	{ 629, "CG" }, { 630, "CD" }, { 631, "AO" }, { 632, "GW" }, { 633, "SC" }, { 634, "SD" }, { 635, "RW" },
	{ 636, "ET" }, { 637, "SO" }, { 638, "DJ" }, { 639, "KE" }, { 640, "TZ" }, { 641, "UG" }, { 642, "BI" },
	{ 643, "MZ" }, { 645, "ZM" }, { 646, "MG" }, { 647, "RE" }, { 648, "ZW" }, { 649, "NA" }, { 650, "MW" },
	{ 651, "LS" }, { 652, "BW" }, { 653, "SZ" }, { 654, "KM" }, { 655, "ZA" }, { 657, "ER" }, { 659, "SS" },
	{ 702, "BZ" }, { 704, "GT" }, { 706, "SV" }, { 708, "HN" }, { 710, "NI" }, { 712, "CR" }, { 714, "PA" },
	{ 716, "PE" }, { 722, "AR" }, { 724, "BR" }, { 730, "CL" }, { 732, "CO" }, { 734, "VE" }, { 736, "BO" },
	{ 738, "GY" }, { 740, "EC" }, { 744, "PY" }, { 746, "SR" }, { 748, "UY" }, { 750, "FK" },
};

static const char *
country_of (const char *operator_code)
{
	if (!operator_code || strlen (operator_code) < 3)
		return NULL;
	char mcc_s[4] = { operator_code[0], operator_code[1], operator_code[2], 0 };
	guint mcc = (guint)g_ascii_strtoull (mcc_s, NULL, 10);
	for (guint i = 0; i < G_N_ELEMENTS (mcc_iso); i++)
		if (mcc_iso[i].mcc == mcc)
			return mcc_iso[i].iso;
	return NULL;
}

/* ---- ModemManager ---- */

typedef enum { GEN_UNKNOWN, GEN_OTHER, GEN_2G, GEN_3G, GEN_LTE, GEN_5G } Gen;

/* The newest technology in the AccessTechnologies mask. */
static Gen
gen_of (guint act)
{
	if (act & ACT_5G)
		return GEN_5G;
	if (act & ACT_LTE)
		return GEN_LTE;
	if (act & ACT_3G)
		return GEN_3G;
	if (act & ACT_2G)
		return GEN_2G;
	return act ? GEN_OTHER : GEN_UNKNOWN;
}

static const char *const gen_chip[] = { DASH, "?", "2G", "3G", "LTE", "5G" };
static const char *const gen_name[] = { "Unknown", "Unknown", "2G", "3G", "LTE", "5G NR" };

static int
path_cmp (gconstpointer a, gconstpointer b)
{
	const char *pa = g_dbus_object_get_object_path ((GDBusObject *)a);
	const char *pb = g_dbus_object_get_object_path ((GDBusObject *)b);
	size_t la = strlen (pa), lb = strlen (pb);
	/* Modem/2 before Modem/10 */
	if (la != lb)
		return la < lb ? -1 : 1;
	return strcmp (pa, pb);
}

/* Modem objects in path order, referenced. */
static GList *
mm_modems (GsNetInfo *self)
{
	GList *objs = self->mm ? g_dbus_object_manager_get_objects (self->mm) : NULL, *out = NULL;
	for (GList *l = objs; l; l = l->next) {
		g_autoptr (GDBusInterface) m = g_dbus_object_get_interface (l->data, IF_MODEM);
		if (m)
			out = g_list_prepend (out, g_object_ref (l->data));
	}
	g_list_free_full (objs, g_object_unref);
	return g_list_sort (out, path_cmp);
}

/* The modem the dialog describes: the first one holding a SIM. */
static GDBusObject *
mm_primary (GsNetInfo *self)
{
	GList *mods = mm_modems (self);
	GDBusObject *pick = NULL;
	for (GList *l = mods; l && !pick; l = l->next) {
		g_autoptr (GDBusProxy) m = obj_iface (l->data, IF_MODEM);
		g_autofree char *sim = prop_str (m, "Sim");
		if (is_path (sim))
			pick = l->data;
	}
	if (!pick && mods)
		pick = mods->data;
	if (pick)
		g_object_ref (pick);
	g_list_free_full (mods, g_object_unref);
	return pick;
}

static GDBusProxy *
sim_of (GsNetInfo *self, const char *path)
{
	return is_path (path) ? g_hash_table_lookup (self->sims, path) : NULL;
}

static gboolean
registered (guint reg)
{
	return reg == MM_REG_HOME || reg == MM_REG_ROAMING || reg == MM_REG_HOME_SMS_ONLY ||
	       reg == MM_REG_ROAMING_SMS_ONLY || reg == MM_REG_HOME_CSFB_NOT_PREFERRED ||
	       reg == MM_REG_ROAMING_CSFB_NOT_PREFERRED;
}

static gboolean
roaming (guint reg)
{
	return reg == MM_REG_ROAMING || reg == MM_REG_ROAMING_SMS_ONLY || reg == MM_REG_ROAMING_CSFB_NOT_PREFERRED;
}

/* TelephonyManager.simState's words */
static const char *
sim_state (GDBusProxy *m)
{
	g_autofree char *sim = prop_str (m, "Sim");
	int state = (int)prop_num (m, "State", MM_STATE_UNKNOWN);
	guint lock = (guint)prop_num (m, "UnlockRequired", MM_LOCK_UNKNOWN);
	if (state == MM_STATE_FAILED) {
		switch (prop_num (m, "StateFailedReason", 0)) {
		case MM_FAILED_SIM_MISSING:
		case MM_FAILED_ESIM_WITHOUT_PROFILES:
			return "absent";
		case MM_FAILED_SIM_ERROR:
			return "unknown(sim-error)";
		default:
			return "unknown(failed)";
		}
	}
	if (state <= MM_STATE_INITIALIZING)
		return "not ready";
	if (!is_path (sim))
		return "absent";
	switch (lock) {
	case MM_LOCK_UNKNOWN:
		return state == MM_STATE_LOCKED ? "not ready" : "ready";
	case MM_LOCK_NONE:
	case MM_LOCK_SIM_PIN2:	/* PIN2/PUK2 lock nothing a phone needs */
	case MM_LOCK_SIM_PUK2:
		return "ready";
	case MM_LOCK_SIM_PIN:
	case MM_LOCK_PH_SIM_PIN:
	case MM_LOCK_PH_FSIM_PIN:
		return "PIN required";
	case MM_LOCK_SIM_PUK:
	case MM_LOCK_PH_FSIM_PUK:
		return "PUK required";
	default:	/* the network/service-provider/corporate personalisations */
		return "network locked";
	}
}

typedef struct {
	const char *kind;	/* Android's CellSignalStrength* suffix */
	int dbm;
} Reading;

/* Modem.Signal, in the order Android lists cellSignalStrengths; the dBm is
 * what Android's getDbm() gives for that kind (LTE/NR: RSRP, WCDMA: RSCP,
 * GSM/CDMA: RSSI). */
static guint
readings (GDBusProxy *sig, Reading out[6])
{
	static const struct {
		const char *prop, *key, *alt, *kind;
	} src[] = {
		{ "Cdma", "rssi", NULL, "Cdma" },
		{ "Evdo", "rssi", NULL, "Cdma" },
		{ "Gsm", "rssi", NULL, "Gsm" },
		{ "Umts", "rscp", "rssi", "Wcdma" },
		{ "Lte", "rsrp", NULL, "Lte" },
		{ "Nr5g", "rsrp", NULL, "Nr" },
	};
	guint n = 0;
	for (guint i = 0; i < G_N_ELEMENTS (src); i++) {
		g_autoptr (GVariant) d = prop (sig, src[i].prop);
		gint64 v;
		if (!d || !g_variant_is_of_type (d, G_VARIANT_TYPE_VARDICT))
			continue;
		if (!dict_num (d, src[i].key, &v) && !(src[i].alt && dict_num (d, src[i].alt, &v)))
			continue;
		if (v >= 0 || (n && !strcmp (out[n - 1].kind, src[i].kind)))
			continue;	/* not a reading; or Evdo after Cdma */
		out[n].kind = src[i].kind;
		out[n].dbm = (int)v;
		n++;
	}
	return n;
}

/* Android's level 0-4 for a dBm: the CarrierConfigManager default
 * thresholds (LTE RSRP -128/-118/-108/-98, NR SS-RSRP -110/-90/-80/-65,
 * WCDMA RSCP -115/-105/-95/-85, GSM RSSI -107/-103/-97/-89, CDMA RSSI
 * -100/-95/-85/-75). */
static int
level_of (const char *kind, int dbm)
{
	static const struct {
		const char *kind;
		int t[4];
	} th[] = {
		{ "Lte", { -128, -118, -108, -98 } },
		{ "Nr", { -110, -90, -80, -65 } },
		{ "Wcdma", { -115, -105, -95, -85 } },
		{ "Tdscdma", { -115, -105, -95, -85 } },
		{ "Gsm", { -107, -103, -97, -89 } },
		{ "Cdma", { -100, -95, -85, -75 } },
	};
	for (guint i = 0; i < G_N_ELEMENTS (th); i++) {
		if (strcmp (th[i].kind, kind))
			continue;
		int level = 0;
		for (int j = 0; j < 4; j++)
			if (dbm >= th[i].t[j])
				level = j + 1;
		return level;
	}
	return 0;
}

/* SignalQuality has no dBm behind it, so the level comes from the percentage
 * the way Android's legacy GSM levels came from ASU: MM's usual source is
 * AT+CSQ, quality = rssi * 100 / 31, so map back to ASU and bucket there
 * (<=2 none, 3-4 poor, 5-7 moderate, 8-11 good, >=12 great). */
static int
quality_level (guint q)
{
	guint asu = (q * 31 + 50) / 100;
	return asu <= 2 ? 0 : asu >= 12 ? 4 : asu >= 8 ? 3 : asu >= 5 ? 2 : 1;
}

static gboolean
quality_of (GDBusProxy *m, guint *q, gboolean *recent)
{
	g_autoptr (GVariant) v = prop (m, "SignalQuality");
	if (!v || !g_variant_is_of_type (v, G_VARIANT_TYPE ("(ub)")))
		return FALSE;
	g_variant_get (v, "(ub)", q, recent);
	return prop_num (m, "State", 0) >= MM_STATE_ENABLED && (*q > 0 || *recent);
}

/* The chip's one reading: the current technology's, else the first.  A
 * modem may leave another RAT's last value in Modem.Signal. */
static const Reading *
chip_reading (const Reading *r, guint n, Gen gen)
{
	const char *want = gen == GEN_5G ? "Nr" : gen == GEN_LTE ? "Lte" : gen == GEN_3G ? "Wcdma" : "Gsm";
	for (guint i = 0; i < n; i++)
		if (!strcmp (r[i].kind, want))
			return &r[i];
	for (guint i = 0; i < n; i++)	/* NSA: the LTE anchor */
		if (gen == GEN_5G && !strcmp (r[i].kind, "Lte"))
			return &r[i];
	return n ? &r[0] : NULL;
}

char *
gs_net_info_mobile_chip (GsNetInfo *self)
{
	g_return_val_if_fail (GS_IS_NET_INFO (self), NULL);
	if (!self->mm_done)
		return g_strdup (DASH);
	g_autoptr (GDBusObject) o = mm_primary (self);
	g_autoptr (GDBusProxy) m = obj_iface (o, IF_MODEM);
	if (!m)
		return g_strdup ("mobile: n/a");
	g_autoptr (GDBusProxy) g = obj_iface (o, IF_3GPP);
	g_autoptr (GDBusProxy) sig = obj_iface (o, IF_SIGNAL);
	g_autofree char *op = prop_str (g, "OperatorName");
	Gen gen = gen_of ((guint)prop_num (m, "AccessTechnologies", 0));
	GString *s = g_string_new (gen_chip[gen]);
	g_string_append_printf (s, " %s", op && *op ? op : "No service");
	Reading r[6];
	const Reading *best = chip_reading (r, readings (sig, r), gen);
	guint q;
	gboolean recent;
	if (best)
		g_string_append_printf (s, " %ddBm", best->dbm);
	else if (quality_of (m, &q, &recent))
		g_string_append_printf (s, " %u%%", q);	/* no dBm from this modem: say what there is */
	return g_string_free (s, FALSE);
}

char *
gs_net_info_mobile_details (GsNetInfo *self)
{
	g_return_val_if_fail (GS_IS_NET_INFO (self), NULL);
	if (!self->mm_done)
		return g_strdup ("telephony: still connecting to ModemManager\n");
	if (!om_up (self->mm))
		return g_strdup ("telephony: ModemManager is not running\n");
	g_autoptr (GDBusObject) o = mm_primary (self);
	g_autoptr (GDBusProxy) m = obj_iface (o, IF_MODEM);
	if (!m)
		return g_strdup ("telephony: no modem\n");
	g_autoptr (GDBusProxy) g = obj_iface (o, IF_3GPP);
	g_autoptr (GDBusProxy) sig = obj_iface (o, IF_SIGNAL);
	g_autofree char *op = prop_str (g, "OperatorName");
	g_autofree char *code = prop_str (g, "OperatorCode");
	g_autofree char *sim_path = prop_str (m, "Sim");
	GDBusProxy *sim = sim_of (self, sim_path);
	g_autofree char *spn = prop_str (sim, "OperatorName");
	if (!spn || !*spn) {
		g_free (spn);
		spn = prop_str (sim, "OperatorIdentifier");
	}
	guint reg = (guint)prop_num (g, "RegistrationState", 0);
	Gen gen = gen_of ((guint)prop_num (m, "AccessTechnologies", 0));

	GString *s = g_string_new (NULL);
	row (s, "Operator", op);
	row (s, "MCC/MNC", code);
	row (s, "Country", country_of (code));
	row (s, "SIM operator", spn);
	row (s, "SIM state", sim_state (m));
	row (s, "Roaming", roaming (reg) ? "yes" : "no");
	row (s, "Data network", gen_name[gen]);
	/* MM has no separate voice bearer: the same technology, when registered */
	row (s, "Voice network", registered (reg) ? gen_name[gen] : "Unknown");
	Reading r[6];
	guint n = readings (sig, r), q;
	gboolean recent;
	for (guint i = 0; i < n; i++) {
		g_autofree char *label = g_strdup_printf ("Signal[%u]", i);
		rowf (s, label, "%d dBm, level %d/4 (%s)", r[i].dbm, level_of (r[i].kind, r[i].dbm), r[i].kind);
	}
	if (!n && quality_of (m, &q, &recent))
		rowf (s, "Signal", "%u%%, level %d/4 (no dBm%s)", q, quality_level (q), recent ? "" : ", stale");
	return g_string_free (s, FALSE);
}

/* ---- the cell list ---- */

typedef struct {
	GDBusObject *obj;
	char *why;	/* what GetCellInfo said instead of cells */
} Cells;

static void
cells_free (gpointer p)
{
	Cells *c = p;
	g_clear_object (&c->obj);
	g_free (c->why);
	g_free (c);
}

static void
cell_signal (GString *s, const char *kind, gboolean ok, gint64 dbm)
{
	if (ok && dbm < 0)
		rowf (s, "Signal", "%d dBm (%d/4)", (int)dbm, level_of (kind, (int)dbm));
	else
		row (s, "Signal", NULL);
}

static char *
mcc_mnc (const char *opid)
{
	if (!opid || strlen (opid) < 5)
		return g_strdup (DASH "/" DASH);
	return g_strdup_printf ("%.3s/%s", opid, opid + 3);
}

/* One GetCellInfo dictionary as MainActivity.describeCell lays it out. */
static void
describe_cell (GString *s, GVariant *cell, GDBusObject *obj)
{
	gint64 type = MM_CELL_UNKNOWN, v = 0;
	gboolean serving = FALSE;
	g_autofree char *opid = NULL;
	g_variant_lookup (cell, "serving", "b", &serving);
	g_variant_lookup (cell, "operator-id", "s", &opid);
	dict_num (cell, "cell-type", &type);
	const char *role = serving ? "serving cell" : "neighbour";
	g_autofree char *mm = mcc_mnc (opid);
#define NUM(label, key) row_num (s, label, dict_num (cell, key, &v), v)
	switch (type) {
	case MM_CELL_LTE: {
		rowf (s, "Type", "LTE (%s)", role);
		NUM ("Cell ID", "ci");
		NUM ("PCI", "physical-ci");
		NUM ("TAC", "tac");
		NUM ("EARFCN", "earfcn");
		row (s, "MCC/MNC", mm);
		g_autoptr (GDBusProxy) g = serving ? obj_iface (obj, IF_3GPP) : NULL;
		g_autofree char *op = prop_str (g, "OperatorName");
		if (op && *op)
			row (s, "Carrier", op);
		gboolean ok = dict_num (cell, "rsrp", &v);
		cell_signal (s, "Lte", ok, v);
		break;
	}
	case MM_CELL_5GNR: {
		rowf (s, "Type", "5G NR (%s)", role);
		NUM ("NCI", "ci");
		NUM ("PCI", "physical-ci");
		NUM ("TAC", "tac");
		NUM ("NRARFCN", "nrarfcn");
		row (s, "MCC/MNC", mm);
		gboolean ok = dict_num (cell, "rsrp", &v);
		cell_signal (s, "Nr", ok, v);
		break;
	}
	case MM_CELL_UMTS: {
		rowf (s, "Type", "WCDMA (%s)", role);
		NUM ("Cell ID", "ci");
		NUM ("LAC", "lac");
		NUM ("PSC", "psc");
		NUM ("UARFCN", "uarfcn");
		row (s, "MCC/MNC", mm);
		gboolean ok = dict_num (cell, "rscp", &v);
		cell_signal (s, "Wcdma", ok, v);
		break;
	}
	case MM_CELL_GSM: {
		rowf (s, "Type", "GSM (%s)", role);
		NUM ("Cell ID", "ci");
		NUM ("LAC", "lac");
		NUM ("ARFCN", "arfcn");
		NUM ("BSIC", "base-station-id");
		row (s, "MCC/MNC", mm);
		/* RXLEV 0-63 is -111+n dBm (3GPP 45.008) */
		gboolean ok = dict_num (cell, "rx-level", &v) && v >= 0 && v <= 63;
		cell_signal (s, "Gsm", ok, v - 111);
		break;
	}
	case MM_CELL_TDSCDMA: {
		rowf (s, "Type", "Tdscdma (%s)", role);
		gboolean ok = dict_num (cell, "rscp", &v);
		cell_signal (s, "Tdscdma", ok, v);
		break;
	}
	case MM_CELL_CDMA:
		rowf (s, "Type", "Cdma (%s)", role);
		row (s, "Signal", NULL);
		break;
	default:
		rowf (s, "Type", "Unknown (%s)", role);
		row (s, "Signal", NULL);
		break;
	}
#undef NUM
}

/* The serving cell's signal for a Location-only block: Modem.Signal for the
 * same kind, else the percentage. */
static void
serving_signal (GString *s, GDBusObject *obj, const char *kind)
{
	g_autoptr (GDBusProxy) m = obj_iface (obj, IF_MODEM);
	g_autoptr (GDBusProxy) sig = obj_iface (obj, IF_SIGNAL);
	Reading r[6];
	guint n = readings (sig, r), q;
	gboolean recent;
	for (guint i = 0; i < n; i++)
		if (!strcmp (r[i].kind, kind)) {
			cell_signal (s, kind, TRUE, r[i].dbm);
			return;
		}
	if (quality_of (m, &q, &recent))
		rowf (s, "Signal", "%u%% (%d/4)", q, quality_level (q));
	else
		row (s, "Signal", NULL);
}

/* Location's 3GPP source, "MCC,MNC,LAC,CI,TAC" with hex ids: the serving
 * cell only, and only its ids.  FALSE when it names no cell. */
static gboolean
describe_location (GString *s, const char *loc, GDBusObject *obj)
{
	g_auto (GStrv) f = g_strsplit (loc, ",", -1);
	if (g_strv_length (f) < 4)
		return FALSE;
	gint64 lac = 0, ci = 0, tac = 0;
	hex_of (f[2], &lac);
	hex_of (f[3], &ci);
	hex_of (f[4], &tac);
	if (!ci)
		return FALSE;
	g_autoptr (GDBusProxy) m = obj_iface (obj, IF_MODEM);
	g_autoptr (GDBusProxy) g = obj_iface (obj, IF_3GPP);
	g_autofree char *op = prop_str (g, "OperatorName");
	g_autofree char *mm = g_strdup_printf ("%s/%s", f[0], f[1]);
	/* MM uses 0 for an id it does not have */
	switch (gen_of ((guint)prop_num (m, "AccessTechnologies", 0))) {
	case GEN_5G:
		row (s, "Type", "5G NR (serving cell)");
		row_num (s, "NCI", TRUE, ci);
		row (s, "PCI", NULL);
		row_num (s, "TAC", tac != 0, tac);
		row (s, "NRARFCN", NULL);
		row (s, "MCC/MNC", mm);
		serving_signal (s, obj, "Nr");
		break;
	case GEN_LTE:
		row (s, "Type", "LTE (serving cell)");
		row_num (s, "Cell ID", TRUE, ci);
		row (s, "PCI", NULL);
		row_num (s, "TAC", tac != 0, tac);
		if (lac)	/* what the modem reported through +CREG */
			row_num (s, "LAC", TRUE, lac);
		row (s, "EARFCN", NULL);
		row (s, "MCC/MNC", mm);
		if (op && *op)
			row (s, "Carrier", op);
		serving_signal (s, obj, "Lte");
		break;
	case GEN_3G:
		row (s, "Type", "WCDMA (serving cell)");
		row_num (s, "Cell ID", TRUE, ci);
		row_num (s, "LAC", lac != 0, lac);
		row (s, "PSC", NULL);
		row (s, "UARFCN", NULL);
		row (s, "MCC/MNC", mm);
		serving_signal (s, obj, "Wcdma");
		break;
	case GEN_2G:
		row (s, "Type", "GSM (serving cell)");
		row_num (s, "Cell ID", TRUE, ci);
		row_num (s, "LAC", lac != 0, lac);
		row (s, "ARFCN", NULL);
		row (s, "BSIC", NULL);
		row (s, "MCC/MNC", mm);
		serving_signal (s, obj, "Gsm");
		break;
	default:
		row (s, "Type", "Unknown (serving cell)");
		row_num (s, "Cell ID", TRUE, ci);
		row_num (s, "LAC", lac != 0, lac);
		row_num (s, "TAC", tac != 0, tac);
		row (s, "MCC/MNC", mm);
		serving_signal (s, obj, "Gsm");
		break;
	}
	return TRUE;
}

/* a{uv} from Location / GetLocation -> the 3GPP string, or NULL */
static char *
lac_ci_of (GVariant *locs)
{
	if (!locs || !g_variant_is_of_type (locs, G_VARIANT_TYPE ("a{uv}")))
		return NULL;
	GVariantIter it;
	guint32 src;
	GVariant *v;
	g_variant_iter_init (&it, locs);
	while (g_variant_iter_next (&it, "{uv}", &src, &v)) {
		g_autoptr (GVariant) val = v;
		if (src == MM_LOCATION_3GPP_LAC_CI && g_variant_is_of_type (val, G_VARIANT_TYPE_STRING))
			return g_variant_dup_string (val, NULL);
	}
	return NULL;
}

static void
cells_return (GTask *t, GString *s)
{
	g_task_return_pointer (t, g_string_free (s, FALSE), g_free);
}

/* GetCellInfo gave nothing: say why, and what Location knows. */
static void
cells_fallback_done (GTask *t, const char *lac_ci, const char *loc_why)
{
	Cells *c = g_task_get_task_data (t);
	GString *s = g_string_new (NULL);
	if (lac_ci && describe_location (s, lac_ci, c->obj)) {
		cells_return (t, s);
		return;
	}
	if (lac_ci)
		loc_why = "no serving cell";
	g_string_append_printf (s, "%s\n", c->why);
	if (loc_why)
		g_string_append_printf (s, "location: %s\n", loc_why);
	cells_return (t, s);
}

static void
on_location (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GTask) t = data;
	g_autoptr (GError) err = NULL;
	g_autoptr (GVariant) r = g_dbus_proxy_call_finish (G_DBUS_PROXY (src), res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED)) {
		g_task_return_error (t, g_steal_pointer (&err));
		return;
	}
	g_autoptr (GVariant) locs = r && g_variant_is_of_type (r, G_VARIANT_TYPE ("(a{uv})")) ?
					    g_variant_get_child_value (r, 0) : NULL;
	g_autofree char *lac_ci = lac_ci_of (locs);
	g_autofree char *why = err ? error_text (err) : g_strdup ("no 3GPP location reported");
	cells_fallback_done (t, lac_ci, why);
}

/* The older way to the serving cell (§11.1).  Never Location.Setup: if the
 * 3GPP source is not on, it stays off. */
static void
cells_from_location (GTask *t)
{
	Cells *c = g_task_get_task_data (t);
	g_autoptr (GDBusProxy) loc = obj_iface (c->obj, IF_LOCATION);
	g_autoptr (GVariant) cached = prop (loc, "Location");
	g_autofree char *lac_ci = lac_ci_of (cached);
	if (lac_ci || !loc) {
		cells_fallback_done (t, lac_ci, loc ? NULL : "no Location interface");
		return;
	}
	if (!((guint)prop_num (loc, "Enabled", 0) & MM_LOCATION_3GPP_LAC_CI)) {
		cells_fallback_done (t, NULL, "3GPP source not enabled");
		return;
	}
	g_dbus_proxy_call (loc, "GetLocation", NULL, G_DBUS_CALL_FLAGS_NONE, 10000, g_task_get_cancellable (t),
			   on_location, g_object_ref (t));
}

/* Refused by the bus policy (root-only on Mobian, and every refusal is a
 * line in the journal), or not in this MM or modem: no point asking again. */
static gboolean
cell_info_never (const GError *err)
{
	g_autofree char *name = g_dbus_error_get_remote_error (err);
	return g_error_matches (err, G_DBUS_ERROR, G_DBUS_ERROR_ACCESS_DENIED) ||
	       g_error_matches (err, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_METHOD) ||
	       !g_strcmp0 (name, MM_BUS ".Error.Core.Unsupported");
}

static void
on_cell_info (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GTask) t = data;
	Cells *c = g_task_get_task_data (t);
	g_autoptr (GError) err = NULL;
	g_autoptr (GVariant) r = g_dbus_proxy_call_finish (G_DBUS_PROXY (src), res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED)) {
		g_task_return_error (t, g_steal_pointer (&err));
		return;
	}
	if (r && g_variant_is_of_type (r, G_VARIANT_TYPE ("(aa{sv})"))) {
		g_autoptr (GVariant) cells = g_variant_get_child_value (r, 0);
		gsize n = g_variant_n_children (cells);
		if (n) {
			GString *s = g_string_new (NULL);
			for (gsize i = 0; i < MIN (n, 6); i++) {
				g_autoptr (GVariant) cell = g_variant_get_child_value (cells, i);
				if (i)
					g_string_append_c (s, '\n');
				describe_cell (s, cell, c->obj);
			}
			cells_return (t, s);
			return;
		}
		c->why = g_strdup ("none reported");
	} else {
		g_autofree char *e = err ? error_text (err) : g_strdup ("unexpected reply");
		c->why = g_strdup_printf ("unavailable: %s", e);
		if (err && cell_info_never (err)) {
			GsNetInfo *self = g_task_get_source_object (t);
			g_hash_table_insert (self->no_cell_info, g_strdup (g_dbus_object_get_object_path (c->obj)),
					     g_steal_pointer (&e));
		}
	}
	cells_from_location (t);
}

void
gs_net_info_mobile_cells_async (GsNetInfo *self, GCancellable *cancel, GAsyncReadyCallback cb, gpointer user_data)
{
	g_return_if_fail (GS_IS_NET_INFO (self));
	GTask *t = g_task_new (self, cancel, cb, user_data);
	g_task_set_source_tag (t, gs_net_info_mobile_cells_async);
	Cells *c = g_new0 (Cells, 1);
	g_task_set_task_data (t, c, cells_free);
	c->obj = mm_primary (self);
	g_autoptr (GDBusProxy) m = obj_iface (c->obj, IF_MODEM);
	if (!m) {
		g_task_return_pointer (t,
				       g_strdup (om_up (self->mm) ? "unavailable: no modem\n" :
								    "unavailable: ModemManager is not running\n"),
				       g_free);
		g_object_unref (t);
		return;
	}
	const char *never = g_hash_table_lookup (self->no_cell_info, g_dbus_object_get_object_path (c->obj));
	if (never) {
		c->why = g_strdup_printf ("unavailable: %s", never);
		cells_from_location (t);
		g_object_unref (t);
		return;
	}
	/* MM >= 1.20; the radio is asked, so it can take a moment */
	g_dbus_proxy_call (m, "GetCellInfo", NULL, G_DBUS_CALL_FLAGS_NONE, 15000, cancel, on_cell_info, t);
}

char *
gs_net_info_mobile_cells_finish (GsNetInfo *self, GAsyncResult *res, GError **error)
{
	g_return_val_if_fail (g_task_is_valid (res, self), NULL);
	return g_task_propagate_pointer (G_TASK (res), error);
}

/* ---- SIM captions ---- */

GPtrArray *
gs_net_info_sim_captions (GsNetInfo *self)
{
	g_return_val_if_fail (GS_IS_NET_INFO (self), NULL);
	GPtrArray *out = g_ptr_array_new_with_free_func (g_free);
	GList *mods = mm_modems (self);
	guint base = 0;	/* slots of the modems before this one */
	for (GList *l = mods; l; l = l->next) {
		g_autoptr (GDBusProxy) m = obj_iface (l->data, IF_MODEM);
		g_autoptr (GDBusProxy) g = obj_iface (l->data, IF_3GPP);
		g_autofree char *active = prop_str (m, "Sim");
		g_autoptr (GVariant) slots = prop (m, "SimSlots");
		gsize n_slots = slots && g_variant_is_of_type (slots, G_VARIANT_TYPE ("ao")) ? g_variant_n_children (slots) : 0;
		/* only the SIM a modem is using is live; a second card in a
		 * dual-slot modem waits for a slot switch */
		guint slot = 0;
		for (gsize i = 0; i < n_slots; i++) {
			const char *p;
			g_variant_get_child (slots, i, "&o", &p);
			if (is_path (active) && !g_strcmp0 (p, active))
				slot = (guint)i;
		}
		if (is_path (active)) {
			GDBusProxy *sim = sim_of (self, active);
			g_autofree char *carrier = prop_str (sim, "OperatorName");
			g_autofree char *imei = prop_str (g, "Imei");
			g_autofree char *iccid = prop_str (sim, "SimIdentifier");
			if (!carrier || !*carrier) {
				/* SubscriptionInfo.carrierName falls back to the network's name too */
				g_free (carrier);
				carrier = prop_str (g, "OperatorName");
			}
			if (!imei || !*imei) {
				g_free (imei);
				imei = prop_str (m, "EquipmentIdentifier");
			}
			GString *s = g_string_new (NULL);
			g_string_printf (s, "SIM %u", base + slot + 1);
			if (carrier && *(g_strstrip (carrier)))
				g_string_append_printf (s, "  ·  %s", carrier);
			if (imei && *imei)
				g_string_append_printf (s, "  ·  IMEI %s", imei);
			else if (iccid && *iccid)
				g_string_append_printf (s, "  ·  ICCID …%s", iccid + MAX (0, (int)strlen (iccid) - 6));
			g_ptr_array_add (out, g_string_free (s, FALSE));
		}
		base += MAX (n_slots, 1);
	}
	g_list_free_full (mods, g_object_unref);
	return out;
}

/* ---- NetworkManager ---- */

typedef struct {
	GDBusProxy *dev, *wifi, *ap, *ip4, *ip6;
} Wifi;

static void
wifi_clear (Wifi *w)
{
	g_clear_object (&w->dev);
	g_clear_object (&w->wifi);
	g_clear_object (&w->ap);
	g_clear_object (&w->ip4);
	g_clear_object (&w->ip6);
}
G_DEFINE_AUTO_CLEANUP_CLEAR_FUNC (Wifi, wifi_clear)

static gboolean
wireless_on (GDBusProxy *nm)
{
	return prop_num (nm, "WirelessEnabled", TRUE) && prop_num (nm, "WirelessHardwareEnabled", TRUE);
}

/* The Wi-Fi device NM lists first, an activated one before any other. */
static gboolean
wifi_lookup (GsNetInfo *self, Wifi *w)
{
	g_autoptr (GDBusProxy) nm = om_iface (self->nm, NM_PATH, IF_NM);
	g_autoptr (GVariant) devs = prop (nm, "Devices");
	if (!devs || !g_variant_is_of_type (devs, G_VARIANT_TYPE ("ao")))
		return FALSE;
	GVariantIter it;
	const char *path;
	g_variant_iter_init (&it, devs);
	while (g_variant_iter_next (&it, "&o", &path)) {
		g_autoptr (GDBusProxy) wifi = om_iface (self->nm, path, IF_NM_WIRELESS);
		g_autoptr (GDBusProxy) dev = wifi ? om_iface (self->nm, path, IF_NM_DEVICE) : NULL;
		if (!dev)
			continue;
		gboolean up = prop_num (dev, "State", 0) == NM_DEVICE_ACTIVATED;
		if (w->dev && !up)
			continue;
		wifi_clear (w);
		w->dev = g_steal_pointer (&dev);
		w->wifi = g_steal_pointer (&wifi);
		if (up)
			break;
	}
	if (!w->dev)
		return FALSE;
	g_autofree char *ap = prop_str (w->wifi, "ActiveAccessPoint");
	g_autofree char *ip4 = prop_str (w->dev, "Ip4Config");
	g_autofree char *ip6 = prop_str (w->dev, "Ip6Config");
	w->ap = om_iface (self->nm, ap, IF_NM_AP);
	w->ip4 = om_iface (self->nm, ip4, IF_NM_IP4);
	w->ip6 = om_iface (self->nm, ip6, IF_NM_IP6);
	return TRUE;
}

static gboolean
wifi_up (Wifi *w)
{
	return prop_num (w->dev, "State", 0) == NM_DEVICE_ACTIVATED;
}

static char *
ap_ssid (GDBusProxy *ap)
{
	g_autoptr (GVariant) v = prop (ap, "Ssid");
	if (!v || !g_variant_is_of_type (v, G_VARIANT_TYPE_BYTESTRING))
		return g_strdup ("");
	gsize n;
	const char *b = g_variant_get_fixed_array (v, &n, 1);
	return g_utf8_make_valid (b, (gssize)n);
}

static char *
lower_mac (char *mac)
{
	if (!mac || !*mac) {
		g_free (mac);
		return NULL;
	}
	for (char *c = mac; *c; c++)
		*c = g_ascii_tolower (*c);
	return mac;
}

static char *
dev_mac (Wifi *w)
{
	char *mac = prop_str (w->dev, "HwAddress");	/* NM >= 1.24 */
	if (!mac || !*mac) {
		g_free (mac);
		mac = prop_str (w->wifi, "HwAddress");
	}
	return lower_mac (mac);
}

static gboolean
rssi_now (GsNetInfo *self, int *rssi)
{
	if (!self->poll_at || g_get_monotonic_time () - self->poll_at > POLL_FRESH_US || self->poll_rssi >= 0)
		return FALSE;
	*rssi = self->poll_rssi;
	return TRUE;
}

/* Mbit/s: the SignalPoll that gave the RSSI if fresh, else NM's Bitrate. */
static int
link_speed (GsNetInfo *self, Wifi *w)
{
	int rssi;
	if (rssi_now (self, &rssi) && self->poll_speed > 0)
		return self->poll_speed;
	gint64 kbit = prop_num (w->wifi, "Bitrate", 0);
	return kbit > 0 ? (int)(kbit / 1000) : -1;
}

/* WifiInfo.currentSecurityType's words: what wpa_supplicant negotiated,
 * else what the access point offers. */
static const char *
security_name (GsNetInfo *self, GDBusProxy *ap)
{
	g_autofree char *mode = prop_str (self->wpa_iface, "CurrentAuthMode");
	guint flags = (guint)prop_num (ap, "Flags", 0);
	if (ap && mode && *mode && strcmp (mode, "INACTIVE") && strcmp (mode, "UNKNOWN")) {
		if (strstr (mode, "SUITE-B-192"))
			return "WPA3-Enterprise 192-bit";
		if (strstr (mode, "EAP") || g_str_has_prefix (mode, "FILS"))
			return "WPA-EAP";
		if (strstr (mode, "SAE"))
			return "WPA3-SAE";
		if (strstr (mode, "OWE"))
			return "OWE (enhanced open)";
		if (strstr (mode, "PSK"))
			return "WPA/WPA2-PSK";
		if (!strcmp (mode, "NONE"))
			return flags & NM_AP_PRIVACY ? "WEP" : "open (none)";
		return "unknown";
	}
	if (!ap)
		return NULL;
	guint k = (guint)(prop_num (ap, "WpaFlags", 0) | prop_num (ap, "RsnFlags", 0));
	if (k & NM_SEC_SUITE_B_192)
		return "WPA3-Enterprise 192-bit";
	if (k & NM_SEC_8021X)
		return "WPA-EAP";
	if (k & NM_SEC_PSK)	/* a PSK+SAE transition AP: PSK is the safe guess */
		return "WPA/WPA2-PSK";
	if (k & NM_SEC_SAE)
		return "WPA3-SAE";
	if (k & (NM_SEC_OWE | NM_SEC_OWE_TM))
		return "OWE (enhanced open)";
	return flags & NM_AP_PRIVACY ? "WEP" : "open (none)";
}

static void
list_add (GString *s, const char *item)
{
	if (s->len)
		g_string_append (s, ", ");
	g_string_append (s, item);
}

static void
add_dns (GString *s, GDBusProxy *ip4, GDBusProxy *ip6)
{
	g_autoptr (GVariant) data = prop (ip4, "NameserverData");	/* NM >= 1.14 */
	if (data && g_variant_is_of_type (data, G_VARIANT_TYPE ("aa{sv}"))) {
		GVariantIter it;
		GVariant *d;
		g_variant_iter_init (&it, data);
		while ((d = g_variant_iter_next_value (&it))) {
			const char *a;
			if (g_variant_lookup (d, "address", "&s", &a))
				list_add (s, a);
			g_variant_unref (d);
		}
	} else {
		g_autoptr (GVariant) old = prop (ip4, "Nameservers");	/* au, network order */
		if (old && g_variant_is_of_type (old, G_VARIANT_TYPE ("au"))) {
			gsize n;
			const guint32 *a = g_variant_get_fixed_array (old, &n, sizeof (guint32));
			for (gsize i = 0; i < n; i++) {
				g_autoptr (GInetAddress) ia = g_inet_address_new_from_bytes ((const guint8 *)&a[i],
											     G_SOCKET_FAMILY_IPV4);
				g_autofree char *t = g_inet_address_to_string (ia);
				list_add (s, t);
			}
		}
	}
	/* LinkProperties.dnsServers holds both families */
	g_autoptr (GVariant) v6 = prop (ip6, "Nameservers");
	if (v6 && g_variant_is_of_type (v6, G_VARIANT_TYPE ("aay"))) {
		GVariantIter it;
		GVariant *b;
		g_variant_iter_init (&it, v6);
		while ((b = g_variant_iter_next_value (&it))) {
			gsize n;
			const guint8 *a = g_variant_get_fixed_array (b, &n, 1);
			if (n == 16) {
				g_autoptr (GInetAddress) ia = g_inet_address_new_from_bytes (a, G_SOCKET_FAMILY_IPV6);
				g_autofree char *t = g_inet_address_to_string (ia);
				list_add (s, t);
			}
			g_variant_unref (b);
		}
	}
}

static char *
first_ipv4 (GDBusProxy *ip4)
{
	g_autoptr (GVariant) data = prop (ip4, "AddressData");
	char *a = NULL;
	if (data && g_variant_is_of_type (data, G_VARIANT_TYPE ("aa{sv}")) && g_variant_n_children (data)) {
		g_autoptr (GVariant) d = g_variant_get_child_value (data, 0);
		g_variant_lookup (d, "address", "s", &a);
	}
	return a;
}

/* ---- wpa_supplicant: the real RSSI ---- */

typedef struct {
	GsNetInfo *self;	/* not a ref: self->cancel guards it */
	guint gen;
} WpaCtx;

static WpaCtx *
wpa_ctx (GsNetInfo *self)
{
	WpaCtx *c = g_new (WpaCtx, 1);
	c->self = self;
	c->gen = self->wpa_gen;
	return c;
}

static void
wpa_reset (GsNetInfo *self)
{
	self->wpa_gen++;
	g_clear_object (&self->wpa_iface);
	g_clear_pointer (&self->wpa_ifname, g_free);
	self->wpa_busy = self->wpa_failed = self->polling = FALSE;
	self->poll_at = self->poll_tried = 0;
}

static guint state_bits (GsNetInfo *self);

static void
check_changed (GsNetInfo *self)
{
	guint s = state_bits (self);
	if (s == self->state)
		return;
	self->state = s;
	g_signal_emit (self, signals[SIG_CHANGED], 0);
}

static void
on_poll (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autofree WpaCtx *c = data;
	g_autoptr (GError) err = NULL;
	g_autoptr (GVariant) r = g_dbus_proxy_call_finish (G_DBUS_PROXY (src), res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED) || c->gen != c->self->wpa_gen)
		return;
	GsNetInfo *self = c->self;
	self->polling = FALSE;
	if (!r) {
		g_debug ("SignalPoll: %s", err->message);	/* not associated, mostly */
		self->poll_at = 0;
		check_changed (self);
		return;
	}
	/* "v" wrapping a{sv} in 2.10; take a bare a{sv} too */
	g_autoptr (GVariant) d = g_variant_get_child_value (r, 0);
	if (g_variant_is_of_type (d, G_VARIANT_TYPE_VARIANT)) {
		GVariant *in = g_variant_get_variant (d);
		g_variant_unref (d);
		d = in;
	}
	gint64 rssi = 0, speed = -1;
	if (g_variant_is_of_type (d, G_VARIANT_TYPE_VARDICT)) {
		dict_num (d, "rssi", &rssi);
		dict_num (d, "linkspeed", &speed);
	}
	self->poll_rssi = rssi < 0 && rssi > -200 ? (int)rssi : 0;
	self->poll_speed = (int)speed;
	self->poll_at = g_get_monotonic_time ();
	g_debug ("SignalPoll: rssi %d, linkspeed %d", self->poll_rssi, self->poll_speed);
	check_changed (self);
}

/* At most one SignalPoll in flight and one per POLL_EVERY_US: the chips ask
 * every 5 s, so the RSSI they show is at most one tick old. */
static void
poll_kick (GsNetInfo *self)
{
	gint64 now = g_get_monotonic_time ();
	if (!self->wpa_iface || self->polling || (self->poll_tried && now - self->poll_tried < POLL_EVERY_US))
		return;
	self->poll_tried = now;
	self->polling = TRUE;
	g_dbus_proxy_call (self->wpa_iface, "SignalPoll", NULL, G_DBUS_CALL_FLAGS_NONE, 3000, self->cancel, on_poll,
			   wpa_ctx (self));
}

static void
on_wpa_iface (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autofree WpaCtx *c = data;
	g_autoptr (GError) err = NULL;
	GDBusProxy *p = g_dbus_proxy_new_finish (res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED))
		return;
	if (c->gen != c->self->wpa_gen) {
		g_clear_object (&p);
		return;
	}
	GsNetInfo *self = c->self;
	self->wpa_busy = FALSE;
	if (!p) {
		g_debug ("wpa_supplicant interface: %s", err->message);
		self->wpa_failed = TRUE;
		return;
	}
	self->wpa_iface = p;
	g_debug ("wpa_supplicant: %s is %s", self->wpa_ifname, g_dbus_proxy_get_object_path (p));
	poll_kick (self);
}

static void
on_wpa_get_interface (GObject *src, GAsyncResult *res, gpointer data)
{
	WpaCtx *c = data;
	g_autoptr (GError) err = NULL;
	g_autoptr (GVariant) r = g_dbus_proxy_call_finish (G_DBUS_PROXY (src), res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED) || c->gen != c->self->wpa_gen) {
		g_free (c);
		return;
	}
	GsNetInfo *self = c->self;
	if (!r) {
		/* not in group netdev, or wpa_supplicant does not run this
		 * device: no dBm then, and no asking again until it changes */
		g_message ("wpa_supplicant GetInterface(%s): %s — Wi-Fi RSSI unavailable", self->wpa_ifname,
			   err->message);
		self->wpa_busy = FALSE;
		self->wpa_failed = TRUE;
		g_free (c);
		return;
	}
	const char *path;
	g_variant_get (r, "(&o)", &path);
	g_dbus_proxy_new (self->bus, G_DBUS_PROXY_FLAGS_DO_NOT_AUTO_START | G_DBUS_PROXY_FLAGS_DO_NOT_CONNECT_SIGNALS,
			  NULL, WPA_BUS, path, IF_WPA_IFACE, self->cancel, on_wpa_iface, c);
}

/* Find wpa_supplicant's Interface for ifname once, again after a reset. */
static void
wpa_sync (GsNetInfo *self, const char *ifname)
{
	if (!self->wpa || !ifname || !*ifname)
		return;
	if (!g_strcmp0 (self->wpa_ifname, ifname) && (self->wpa_iface || self->wpa_busy || self->wpa_failed))
		return;
	g_autofree char *owner = g_dbus_proxy_get_name_owner (self->wpa);
	if (!owner)
		return;
	wpa_reset (self);
	self->wpa_ifname = g_strdup (ifname);
	self->wpa_busy = TRUE;
	g_debug ("wpa_supplicant: looking up %s", ifname);
	g_dbus_proxy_call (self->wpa, "GetInterface", g_variant_new ("(s)", ifname), G_DBUS_CALL_FLAGS_NONE, 5000,
			   self->cancel, on_wpa_get_interface, wpa_ctx (self));
}

static void
wpa_sync_current (GsNetInfo *self)
{
	g_auto (Wifi) w = { 0 };
	if (!wifi_lookup (self, &w))
		return;
	g_autofree char *ifname = prop_str (w.dev, "Interface");
	wpa_sync (self, ifname);
}

/* ---- Wi-Fi texts ---- */

char *
gs_net_info_wifi_chip (GsNetInfo *self)
{
	g_return_val_if_fail (GS_IS_NET_INFO (self), NULL);
	if (!self->nm_done)
		return g_strdup (DASH);
	g_autoptr (GDBusProxy) nm = om_iface (self->nm, NM_PATH, IF_NM);
	if (!nm)
		return g_strdup ("wifi: n/a");
	if (!wireless_on (nm))
		return g_strdup ("WiFi off");
	g_auto (Wifi) w = { 0 };
	if (!wifi_lookup (self, &w))
		return g_strdup ("wifi: n/a");
	g_autofree char *ifname = prop_str (w.dev, "Interface");
	wpa_sync (self, ifname);
	/* the Wi-Fi link itself, not whether it carries the default route: a
	 * VPN on top would otherwise read "not connected" */
	if (!wifi_up (&w))
		return g_strdup ("WiFi not connected");
	poll_kick (self);
	g_autofree char *ssid = ap_ssid (w.ap);
	gint64 freq = prop_num (w.ap, "Frequency", 0);
	int speed = link_speed (self, &w), rssi;
	GString *s = g_string_new (*ssid ? ssid : "WiFi");
	if (freq > 0)
		g_string_append (s, freq > 4000 ? " 5G" : " 2.4G");
	if (speed > 0)
		g_string_append_printf (s, " %dMbps", speed);
	if (rssi_now (self, &rssi))
		g_string_append_printf (s, " %ddBm", rssi);
	return g_string_free (s, FALSE);
}

char *
gs_net_info_wifi_details (GsNetInfo *self)
{
	g_return_val_if_fail (GS_IS_NET_INFO (self), NULL);
	if (!self->nm_done)
		return g_strdup ("wifi: still connecting to NetworkManager\n");
	g_autoptr (GDBusProxy) nm = om_iface (self->nm, NM_PATH, IF_NM);
	if (!nm)
		return g_strdup ("wifi: NetworkManager is not running\n");
	GString *s = g_string_new (NULL);
	row (s, "Enabled", wireless_on (nm) ? "yes" : "no");
	g_auto (Wifi) w = { 0 };
	if (!wifi_lookup (self, &w)) {
		g_string_append (s, "wifi: no Wi-Fi device\n");
		return g_string_free (s, FALSE);
	}
	g_autofree char *ifname = prop_str (w.dev, "Interface");
	wpa_sync (self, ifname);
	gboolean up = wifi_up (&w);
	if (up)
		poll_kick (self);
	GDBusProxy *ap = up ? w.ap : NULL;
	g_autofree char *ssid = ap_ssid (ap);
	g_autofree char *bssid = lower_mac (prop_str (ap, "HwAddress"));
	gint64 freq = prop_num (ap, "Frequency", 0);
	int rssi;
	row (s, "SSID", ssid);
	row (s, "BSSID (AP)", bssid);
	row (s, "Security", up ? security_name (self, ap) : NULL);
	rowf (s, "Link speed", "%d Mbps", up ? link_speed (self, &w) : -1);
	if (freq > 0)
		rowf (s, "Frequency", "%" G_GINT64_FORMAT " MHz (%s)", freq, freq > 4000 ? "5 GHz" : "2.4 GHz");
	else
		row (s, "Frequency", NULL);
	if (up && rssi_now (self, &rssi))
		rowf (s, "RSSI", "%d dBm", rssi);
	else if (ap)	/* NM has only a percentage; no dBm is better than a made-up one */
		rowf (s, "RSSI", DASH " (strength %d%%)", (int)prop_num (ap, "Strength", 0));
	else
		row (s, "RSSI", NULL);
	g_autofree char *ip = up ? first_ipv4 (w.ip4) : NULL;
	row (s, "IP address", ip);
	g_autoptr (GString) dns = g_string_new (NULL);
	if (up)
		add_dns (dns, w.ip4, w.ip6);
	row (s, "DNS", dns->str);
	return g_string_free (s, FALSE);
}

/* ---- the Wi-Fi dialog's slow part: router MAC and pings ---- */

typedef struct {
	char *mac, *gw, *server;
	char *gw_mac, *ping_gw, *ping_srv;
	GSubprocess *proc[2];	/* router, server */
	int pending;
	gboolean timed_out;
	guint timeout;
	GCancellable *cancel;
	gulong cancel_id;
} Extra;

static void
extra_free (gpointer p)
{
	Extra *x = p;
	g_clear_handle_id (&x->timeout, g_source_remove);
	if (x->cancel_id)
		g_cancellable_disconnect (x->cancel, x->cancel_id);
	g_clear_object (&x->cancel);
	for (int i = 0; i < 2; i++)
		if (x->proc[i]) {
			g_subprocess_force_exit (x->proc[i]);
			g_object_unref (x->proc[i]);
		}
	g_free (x->mac);
	g_free (x->gw);
	g_free (x->server);
	g_free (x->gw_mac);
	g_free (x->ping_gw);
	g_free (x->ping_srv);
	g_free (x);
}

/* MainActivity.pingAvg on `ping -c 3 -W 2 host | tail -2`; the patterns fit
 * both iputils and GNU inetutils (Mobian's ping). */
static char *
ping_text (const char *out, gboolean timed_out)
{
	g_autoptr (GRegex) avg_re = g_regex_new ("= [0-9.]+/([0-9.]+)/", 0, 0, NULL);
	g_autoptr (GRegex) loss_re = g_regex_new ("([0-9.]+)% packet loss", 0, 0, NULL);
	g_autoptr (GMatchInfo) mi = NULL, li = NULL;
	g_autofree char *avg = NULL, *loss = NULL;
	if (g_regex_match (avg_re, out, 0, &mi))
		avg = g_match_info_fetch (mi, 1);
	if (g_regex_match (loss_re, out, 0, &li))
		loss = g_match_info_fetch (li, 1);
	if (avg)
		return loss ? g_strdup_printf ("%s ms avg, %s%% loss", avg, loss) : g_strdup_printf ("%s ms avg", avg);
	if (!g_strcmp0 (loss, "100"))
		return g_strdup ("no reply (100% loss)");
	if (timed_out)
		return g_strdup ("unreachable (no answer in 15 s)");
	g_auto (GStrv) lines = g_strsplit (out, "\n", -1);
	guint n = g_strv_length (lines);
	while (n && !*g_strstrip (lines[n - 1]))
		n--;
	for (guint i = n > 2 ? n - 2 : 0; i < n; i++)
		if (*g_strstrip (lines[i]))
			return g_strdup (lines[i]);
	return g_strdup ("unreachable");
}

static void
extra_done (GTask *t)
{
	Extra *x = g_task_get_task_data (t);
	if (--x->pending == 0) {
		g_clear_handle_id (&x->timeout, g_source_remove);
		GString *s = g_string_new (NULL);
		row (s, "Phone MAC", x->mac);
		row (s, "Router IP", x->gw);
		if (x->gw)
			row (s, "Router MAC", x->gw_mac);
		g_string_append (s, "\n— reachability —\n");
		if (x->gw)
			g_string_append_printf (s, "Router  : %s\n", x->ping_gw ? x->ping_gw : "unreachable");
		if (x->server)
			g_string_append_printf (s, "SIP srv : %s  (%s)\n", x->ping_srv ? x->ping_srv : "unreachable",
						x->server);
		g_task_return_pointer (t, g_string_free (s, FALSE), g_free);
	}
	g_object_unref (t);
}

/* /proc/net/arp: "IP  HW-type  Flags  HW-address  Mask  Device" */
static void
on_arp (GObject *src, GAsyncResult *res, gpointer data)
{
	GTask *t = data;
	Extra *x = g_task_get_task_data (t);
	g_autofree char *text = NULL;
	if (g_file_load_contents_finish (G_FILE (src), res, &text, NULL, NULL, NULL)) {
		g_auto (GStrv) lines = g_strsplit (text, "\n", -1);
		for (guint i = 1; lines[i] && !x->gw_mac; i++) {
			g_auto (GStrv) f = g_strsplit_set (lines[i], " \t", -1);
			const char *col[4] = { 0 };
			guint n = 0;
			for (guint j = 0; f[j] && n < 4; j++)
				if (*f[j])
					col[n++] = f[j];
			/* flags 0x0 is an incomplete entry, its MAC all zeros */
			if (n == 4 && !strcmp (col[0], x->gw) && strcmp (col[2], "0x0") &&
			    strcmp (col[3], "00:00:00:00:00:00"))
				x->gw_mac = g_ascii_strdown (col[3], -1);
		}
	}
	extra_done (t);
}

static void
read_arp (GTask *t)
{
	Extra *x = g_task_get_task_data (t);
	g_autoptr (GFile) arp = g_file_new_for_path ("/proc/net/arp");
	x->pending++;
	g_file_load_contents_async (arp, g_task_get_cancellable (t), on_arp, g_object_ref (t));
}

static void
on_ping (GObject *src, GAsyncResult *res, gpointer data)
{
	GTask *t = data;
	Extra *x = g_task_get_task_data (t);
	GSubprocess *p = G_SUBPROCESS (src);
	int which = p == x->proc[1];
	g_autofree char *out = NULL;
	g_autoptr (GError) err = NULL;
	if (!g_subprocess_communicate_utf8_finish (p, res, &out, NULL, &err))
		g_subprocess_force_exit (p);	/* cancelled: leave no ping behind */
	char *text = err ? g_strdup (err->message) : ping_text (out ? out : "", x->timed_out);
	if (which)
		x->ping_srv = text;
	else
		x->ping_gw = text;
	g_clear_object (&x->proc[which]);
	if (!which && !g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED))
		read_arp (t);	/* after the ping, so the router has just answered ARP */
	extra_done (t);
}

static void
start_ping (GTask *t, int which, const char *host)
{
	Extra *x = g_task_get_task_data (t);
	char **slot = which ? &x->ping_srv : &x->ping_gw;
	if (host[0] == '-') {	/* would be read as an option */
		*slot = g_strdup ("unreachable");
		if (!which)
			read_arp (t);
		return;
	}
	g_autoptr (GSubprocessLauncher) l =
		g_subprocess_launcher_new (G_SUBPROCESS_FLAGS_STDOUT_PIPE | G_SUBPROCESS_FLAGS_STDERR_MERGE);
	g_subprocess_launcher_setenv (l, "LC_ALL", "C", TRUE);	/* the parse needs English */
	g_autoptr (GError) err = NULL;
	x->proc[which] = g_subprocess_launcher_spawn (l, &err, "ping", "-c", "3", "-W", "2", host, NULL);
	if (!x->proc[which]) {
		*slot = g_strdup (err->message);
		if (!which)
			read_arp (t);
		return;
	}
	x->pending++;
	g_subprocess_communicate_utf8_async (x->proc[which], NULL, g_task_get_cancellable (t), on_ping, g_object_ref (t));
}

static void
extra_kill (Extra *x)
{
	for (int i = 0; i < 2; i++)
		if (x->proc[i])
			g_subprocess_force_exit (x->proc[i]);
}

/* -c 3 -W 2 ends by itself in ~4 s; this is for a resolver that hangs */
static gboolean
extra_timeout (gpointer data)
{
	Extra *x = data;
	x->timeout = 0;
	x->timed_out = TRUE;
	extra_kill (x);
	return G_SOURCE_REMOVE;
}

/* at once, not when the main loop gets round to the cancelled reads: the
 * caller may be closing the dialog, or exiting */
static void
extra_cancelled (GCancellable *cancel, gpointer data)
{
	extra_kill (data);
}

void
gs_net_info_wifi_extra_async (GsNetInfo *self, const char *sip_server, GCancellable *cancel, GAsyncReadyCallback cb,
			      gpointer user_data)
{
	g_return_if_fail (GS_IS_NET_INFO (self));
	GTask *t = g_task_new (self, cancel, cb, user_data);
	g_task_set_source_tag (t, gs_net_info_wifi_extra_async);
	Extra *x = g_new0 (Extra, 1);
	g_task_set_task_data (t, x, extra_free);
	g_auto (Wifi) w = { 0 };
	if (wifi_lookup (self, &w)) {
		x->mac = dev_mac (&w);
		if (wifi_up (&w)) {
			x->gw = prop_str (w.ip4, "Gateway");
			if (x->gw && !*x->gw)
				g_clear_pointer (&x->gw, g_free);
		}
	}
	if (sip_server && *sip_server) {
		x->server = g_strstrip (g_strdup (sip_server));
		if (!*x->server)
			g_clear_pointer (&x->server, g_free);
	}
	x->pending = 1;	/* this function's own, dropped below */
	if (x->gw)
		start_ping (t, 0, x->gw);
	if (x->server)
		start_ping (t, 1, x->server);
	if (x->pending > 1) {
		x->timeout = g_timeout_add_seconds (15, extra_timeout, x);
		if (cancel) {
			x->cancel = g_object_ref (cancel);
			x->cancel_id = g_cancellable_connect (cancel, G_CALLBACK (extra_cancelled), x, NULL);
		}
	}
	extra_done (t);
}

char *
gs_net_info_wifi_extra_finish (GsNetInfo *self, GAsyncResult *res, GError **error)
{
	g_return_val_if_fail (g_task_is_valid (res, self), NULL);
	return g_task_propagate_pointer (G_TASK (res), error);
}

/* ---- keeping up with the sources ---- */

static guint
state_bits (GsNetInfo *self)
{
	guint b = self->mm_done | self->nm_done << 1;
	g_autoptr (GDBusObject) o = mm_primary (self);
	g_autoptr (GDBusProxy) m = obj_iface (o, IF_MODEM);
	if (m) {
		g_autofree char *sim = prop_str (m, "Sim");
		b |= 1u << 2;
		b |= (prop_num (m, "State", 0) >= MM_STATE_REGISTERED) << 3;
		g_autoptr (GDBusProxy) sig = obj_iface (o, IF_SIGNAL);
		b |= (sim_of (self, sim) != NULL) << 4;
		b |= (sig != NULL) << 5;
	}
	g_autoptr (GDBusProxy) nm = om_iface (self->nm, NM_PATH, IF_NM);
	if (nm) {
		g_auto (Wifi) w = { 0 };
		b |= 1u << 6;
		b |= wireless_on (nm) << 7;
		if (wifi_lookup (self, &w))
			b |= 1u << 8 | wifi_up (&w) << 9;
	}
	int rssi;
	b |= rssi_now (self, &rssi) << 10;
	return b;
}

static void
on_setup (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GError) err = NULL;
	g_autoptr (GVariant) r = g_dbus_proxy_call_finish (G_DBUS_PROXY (src), res, &err);
	if (!r && !g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED))
		g_message ("Signal.Setup(5) on %s: %s", g_dbus_proxy_get_object_path (G_DBUS_PROXY (src)), err->message);
}

/* Modem.Signal reports nothing until someone sets a rate.  Only when none is
 * set: another client's rate is theirs. */
static void
maybe_setup_signal (GsNetInfo *self, GDBusObject *o)
{
	const char *path = g_dbus_object_get_object_path (o);
	g_autoptr (GDBusProxy) sig = obj_iface (o, IF_SIGNAL);
	if (!sig || g_hash_table_contains (self->signal_asked, path))
		return;
	g_hash_table_add (self->signal_asked, g_strdup (path));
	if (prop_num (sig, "Rate", -1) != 0)
		return;
	g_dbus_proxy_call (sig, "Setup", g_variant_new ("(u)", 5u), G_DBUS_CALL_FLAGS_NONE, 10000, self->cancel, on_setup,
			   NULL);
}

static void
on_sim (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GError) err = NULL;
	GDBusProxy *p = g_dbus_proxy_new_finish (res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED))
		return;
	GsNetInfo *self = data;
	if (!p) {
		g_message ("SIM: %s", err->message);
		return;
	}
	gpointer old;
	const char *path = g_dbus_proxy_get_object_path (p);
	/* only into its own empty slot: the SIM may be gone again, or made twice */
	if (g_hash_table_lookup_extended (self->sims, path, NULL, &old) && !old)
		g_hash_table_insert (self->sims, g_strdup (path), p);
	else
		g_object_unref (p);
	check_changed (self);
}

/* SIM proxies for every SIM a modem points at, none for any other. */
static void
sync_sims (GsNetInfo *self)
{
	g_autoptr (GHashTable) want = g_hash_table_new_full (g_str_hash, g_str_equal, g_free, NULL);
	GList *mods = mm_modems (self);
	for (GList *l = mods; l; l = l->next) {
		g_autoptr (GDBusProxy) m = obj_iface (l->data, IF_MODEM);
		g_autofree char *sim = prop_str (m, "Sim");
		g_autoptr (GVariant) slots = prop (m, "SimSlots");
		if (is_path (sim))
			g_hash_table_add (want, g_steal_pointer (&sim));
		if (slots && g_variant_is_of_type (slots, G_VARIANT_TYPE ("ao"))) {
			GVariantIter it;
			const char *p;
			g_variant_iter_init (&it, slots);
			while (g_variant_iter_next (&it, "&o", &p))
				if (is_path (p))
					g_hash_table_add (want, g_strdup (p));
		}
		maybe_setup_signal (self, l->data);
	}
	g_list_free_full (mods, g_object_unref);

	GHashTableIter it;
	gpointer key;
	g_hash_table_iter_init (&it, self->sims);
	while (g_hash_table_iter_next (&it, &key, NULL))
		if (!g_hash_table_contains (want, key))
			g_hash_table_iter_remove (&it);
	g_hash_table_iter_init (&it, want);
	while (g_hash_table_iter_next (&it, &key, NULL)) {
		if (g_hash_table_contains (self->sims, key))
			continue;
		g_hash_table_insert (self->sims, g_strdup (key), NULL);
		g_dbus_proxy_new (self->bus, G_DBUS_PROXY_FLAGS_DO_NOT_AUTO_START | G_DBUS_PROXY_FLAGS_DO_NOT_CONNECT_SIGNALS,
				  NULL, MM_BUS, key, IF_SIM, self->cancel, on_sim, self);
	}
}

static void
mm_changed (GsNetInfo *self)
{
	sync_sims (self);
	check_changed (self);
}

static void
on_mm_object (GDBusObjectManager *om, GDBusObject *o, gpointer data)
{
	mm_changed (data);
}

static void
on_mm_object_removed (GDBusObjectManager *om, GDBusObject *o, gpointer data)
{
	GsNetInfo *self = data;
	/* a ModemManager restart brings it back under a new path anyway */
	g_hash_table_remove (self->signal_asked, g_dbus_object_get_object_path (o));
	g_hash_table_remove (self->no_cell_info, g_dbus_object_get_object_path (o));
	mm_changed (self);
}

static void
on_mm_iface (GDBusObjectManager *om, GDBusObject *o, GDBusInterface *iface, gpointer data)
{
	mm_changed (data);
}

static void
on_mm_props (GDBusObjectManagerClient *om, GDBusObjectProxy *o, GDBusProxy *p, GVariant *changed, GStrv invalidated,
	     gpointer data)
{
	if (!g_strcmp0 (g_dbus_proxy_get_interface_name (p), IF_MODEM))
		mm_changed (data);
}

static void
on_owner (GObject *obj, GParamSpec *pspec, gpointer data)
{
	check_changed (data);
}

static void
on_mm (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GError) err = NULL;
	GDBusObjectManager *om = g_dbus_object_manager_client_new_finish (res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED))
		return;
	GsNetInfo *self = data;
	self->mm_done = TRUE;
	self->mm = om;
	if (!om) {
		g_message ("ModemManager: %s", err->message);
	} else {
		g_signal_connect (om, "object-added", G_CALLBACK (on_mm_object), self);
		g_signal_connect (om, "object-removed", G_CALLBACK (on_mm_object_removed), self);
		g_signal_connect (om, "interface-added", G_CALLBACK (on_mm_iface), self);
		g_signal_connect (om, "interface-removed", G_CALLBACK (on_mm_iface), self);
		g_signal_connect (om, "interface-proxy-properties-changed", G_CALLBACK (on_mm_props), self);
		g_signal_connect (om, "notify::name-owner", G_CALLBACK (on_owner), self);
		sync_sims (self);
	}
	check_changed (self);
}

static void
nm_changed (GsNetInfo *self)
{
	wpa_sync_current (self);
	check_changed (self);
}

static void
on_nm_object (GDBusObjectManager *om, GDBusObject *o, gpointer data)
{
	nm_changed (data);
}

static void
on_nm_props (GDBusObjectManagerClient *om, GDBusObjectProxy *o, GDBusProxy *p, GVariant *changed, GStrv invalidated,
	     gpointer data)
{
	/* not the access points: their Strength changes all the time */
	const char *name = g_dbus_proxy_get_interface_name (p);
	if (!g_strcmp0 (name, IF_NM) || !g_strcmp0 (name, IF_NM_DEVICE) || !g_strcmp0 (name, IF_NM_WIRELESS))
		nm_changed (data);
}

static void
on_nm (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GError) err = NULL;
	GDBusObjectManager *om = g_dbus_object_manager_client_new_finish (res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED))
		return;
	GsNetInfo *self = data;
	self->nm_done = TRUE;
	self->nm = om;
	if (!om) {
		g_message ("NetworkManager: %s", err->message);
	} else {
		g_signal_connect (om, "object-added", G_CALLBACK (on_nm_object), self);
		g_signal_connect (om, "object-removed", G_CALLBACK (on_nm_object), self);
		g_signal_connect (om, "interface-proxy-properties-changed", G_CALLBACK (on_nm_props), self);
		g_signal_connect (om, "notify::name-owner", G_CALLBACK (on_owner), self);
		wpa_sync_current (self);
	}
	check_changed (self);
}

/* wpa_supplicant restarted, or gained or lost an interface: look again */
static void
on_wpa_restart (GsNetInfo *self)
{
	wpa_reset (self);
	wpa_sync_current (self);
	check_changed (self);
}

static void
on_wpa_owner (GObject *obj, GParamSpec *pspec, gpointer data)
{
	on_wpa_restart (data);
}

static void
on_wpa_signal (GDBusProxy *p, const char *sender, const char *name, GVariant *params, gpointer data)
{
	if (!strcmp (name, "InterfaceAdded") || !strcmp (name, "InterfaceRemoved"))
		on_wpa_restart (data);
}

static void
on_wpa (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GError) err = NULL;
	GDBusProxy *p = g_dbus_proxy_new_finish (res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED))
		return;
	GsNetInfo *self = data;
	if (!p) {
		g_message ("wpa_supplicant: %s", err->message);
		return;
	}
	self->wpa = p;
	g_signal_connect (p, "notify::g-name-owner", G_CALLBACK (on_wpa_owner), self);
	g_signal_connect (p, "g-signal", G_CALLBACK (on_wpa_signal), self);
	wpa_sync_current (self);
}

static void
on_bus (GObject *src, GAsyncResult *res, gpointer data)
{
	g_autoptr (GError) err = NULL;
	GDBusConnection *bus = g_bus_get_finish (res, &err);
	if (g_error_matches (err, G_IO_ERROR, G_IO_ERROR_CANCELLED))
		return;
	GsNetInfo *self = data;
	self->bus_done = TRUE;
	self->bus = bus;
	if (!bus) {
		g_message ("No system bus: %s", err->message);
		self->mm_done = self->nm_done = TRUE;
		check_changed (self);
		return;
	}
	/* never start a service just to describe it */
	g_dbus_object_manager_client_new (bus, G_DBUS_OBJECT_MANAGER_CLIENT_FLAGS_DO_NOT_AUTO_START, MM_BUS, MM_PATH,
					  NULL, NULL, NULL, self->cancel, on_mm, self);
	g_dbus_object_manager_client_new (bus, G_DBUS_OBJECT_MANAGER_CLIENT_FLAGS_DO_NOT_AUTO_START, NM_BUS,
					  "/org/freedesktop", NULL, NULL, NULL, self->cancel, on_nm, self);
	g_dbus_proxy_new (bus, G_DBUS_PROXY_FLAGS_DO_NOT_AUTO_START | G_DBUS_PROXY_FLAGS_DO_NOT_LOAD_PROPERTIES, NULL,
			  WPA_BUS, WPA_PATH, WPA_BUS, self->cancel, on_wpa, self);
}

/* ---- GObject ---- */

static void
gs_net_info_dispose (GObject *obj)
{
	GsNetInfo *self = GS_NET_INFO (obj);
	g_cancellable_cancel (self->cancel);
	if (self->mm)
		g_signal_handlers_disconnect_by_data (self->mm, self);
	if (self->nm)
		g_signal_handlers_disconnect_by_data (self->nm, self);
	if (self->wpa)
		g_signal_handlers_disconnect_by_data (self->wpa, self);
	g_clear_object (&self->mm);
	g_clear_object (&self->nm);
	g_clear_object (&self->wpa_iface);
	g_clear_object (&self->wpa);
	g_clear_object (&self->bus);
	if (self->sims)
		g_hash_table_remove_all (self->sims);
	G_OBJECT_CLASS (gs_net_info_parent_class)->dispose (obj);
}

static void
gs_net_info_finalize (GObject *obj)
{
	GsNetInfo *self = GS_NET_INFO (obj);
	g_hash_table_unref (self->sims);
	g_hash_table_unref (self->signal_asked);
	g_hash_table_unref (self->no_cell_info);
	g_free (self->wpa_ifname);
	g_clear_object (&self->cancel);
	G_OBJECT_CLASS (gs_net_info_parent_class)->finalize (obj);
}

static void
gs_net_info_class_init (GsNetInfoClass *klass)
{
	GObjectClass *oc = G_OBJECT_CLASS (klass);
	oc->dispose = gs_net_info_dispose;
	oc->finalize = gs_net_info_finalize;
	/* a source came, went or first answered: worth refreshing the chips */
	signals[SIG_CHANGED] =
		g_signal_new ("changed", G_TYPE_FROM_CLASS (klass), G_SIGNAL_RUN_LAST, 0, NULL, NULL, NULL, G_TYPE_NONE, 0);
}

static void
gs_net_info_init (GsNetInfo *self)
{
	self->cancel = g_cancellable_new ();
	self->sims = g_hash_table_new_full (g_str_hash, g_str_equal, g_free, unref0);
	self->signal_asked = g_hash_table_new_full (g_str_hash, g_str_equal, g_free, NULL);
	self->no_cell_info = g_hash_table_new_full (g_str_hash, g_str_equal, g_free, g_free);
	self->state = G_MAXUINT;
}

GsNetInfo *
gs_net_info_new (void)
{
	GsNetInfo *self = g_object_new (GS_TYPE_NET_INFO, NULL);
	g_bus_get (G_BUS_TYPE_SYSTEM, self->cancel, on_bus, self);
	return self;
}
