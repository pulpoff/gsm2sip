/* ModemManager over plain GDBus (no libmm-glib): the one modem with a Voice
 * interface, its calls, and its SMS.  Stands in for the Android Telecom
 * (GsmCallManager) and SmsManager halves of the Kotlin gateway. */
#pragma once
#include <gio/gio.h>

typedef struct Modem Modem;

/* MMCallState */
enum { MM_CALL_UNKNOWN, MM_CALL_DIALING, MM_CALL_RINGING_OUT, MM_CALL_RINGING_IN, MM_CALL_ACTIVE,
       MM_CALL_HELD, MM_CALL_WAITING, MM_CALL_TERMINATED };
/* MMCallStateReason */
enum { MM_REASON_UNKNOWN, MM_REASON_OUTGOING_STARTED, MM_REASON_INCOMING_NEW, MM_REASON_ACCEPTED,
       MM_REASON_TERMINATED, MM_REASON_REFUSED_OR_BUSY, MM_REASON_ERROR, MM_REASON_AUDIO_SETUP_FAILED,
       MM_REASON_TRANSFERRED, MM_REASON_DEFLECTED };
/* MMSmsState */
enum { MM_SMS_UNKNOWN, MM_SMS_STORED, MM_SMS_RECEIVING, MM_SMS_RECEIVED, MM_SMS_SENDING, MM_SMS_SENT };

typedef struct {
	void (*present) (gpointer ud, gboolean present);
	/* A call object appeared; `incoming` from its Direction. */
	void (*call_added) (gpointer ud, const char *path, gboolean incoming, const char *number, int state);
	void (*call_state) (gpointer ud, const char *path, int old_state, int new_state, guint reason);
	void (*call_deleted) (gpointer ud, const char *path);
	/* A received SMS, complete (state RECEIVED).  Delete it with
	 * modem_sms_delete once it has been handed on. */
	void (*sms_received) (gpointer ud, const char *path, const char *number, const char *text, const char *timestamp);
} ModemCallbacks;

typedef void (*ModemDialCallback) (const char *call_path, const char *error, gpointer ud);
typedef void (*ModemSmsCallback) (gboolean ok, const char *error, gpointer ud);

Modem *modem_new (const ModemCallbacks *cb, gpointer ud);
void modem_free (Modem *m);
gboolean modem_present (Modem *m);

void modem_call_accept (Modem *m, const char *path);
void modem_call_hangup (Modem *m, const char *path);
/* Delete a terminated call object, so MM's call list does not grow. */
void modem_call_delete (Modem *m, const char *path);
void modem_dial (Modem *m, const char *number, ModemDialCallback cb, gpointer ud);
int modem_call_state (Modem *m, const char *path);	/* -1 if unknown */

void modem_sms_send (Modem *m, const char *number, const char *text, ModemSmsCallback cb, gpointer ud);
void modem_sms_delete (Modem *m, const char *path);
/* Report every stored, received SMS through sms_received again. */
void modem_sms_rescan (Modem *m);
/* D-Bus calls still in flight: the exit path waits for these to drain, so
 * a Hangup sent during shutdown actually reaches ModemManager. */
int modem_pending (Modem *m);
