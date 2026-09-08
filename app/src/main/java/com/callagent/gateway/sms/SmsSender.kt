package com.callagent.gateway.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log

/**
 * Hands an outbound SMS to the modem and keeps the paperwork straight.
 *
 * Android reports a long message per *part*: one sent result and one delivery
 * report for each of them.  A caller wants one answer per message, so the
 * parts are counted back in and only a complete set produces a report.
 */
object SmsSender {
    private const val TAG = "SmsSender"

    const val ACTION_SENT = "com.callagent.gateway.SMS_SENT"
    const val ACTION_DELIVERED = "com.callagent.gateway.SMS_DELIVERED"
    const val EXTRA_ID = "sms_id"
    const val EXTRA_PART = "sms_part"

    /**
     * Send one queued message.  Returns false if the modem refused it outright,
     * in which case nothing will ever call back and the caller must report the
     * failure itself.
     */
    fun dispatch(context: Context, sms: OutboundSms): Boolean {
        return try {
            val manager = smsManagerFor(context, sms.subId)
            val parts = manager.divideMessage(sms.text)
            val count = parts.size

            SmsOutbox.update(context, sms.id) { it.copy(parts = count) }

            val sentIntents = ArrayList<PendingIntent>(count)
            val deliveryIntents = ArrayList<PendingIntent>(count)
            for (i in 0 until count) {
                sentIntents.add(pendingIntent(context, ACTION_SENT, sms.id, i))
                deliveryIntents.add(pendingIntent(context, ACTION_DELIVERED, sms.id, i))
            }

            manager.sendMultipartTextMessage(sms.to, null, parts, sentIntents, deliveryIntents)
            Log.i(TAG, "Dispatched ${sms.id} to ${sms.to} ($count part(s), sub=${sms.subId})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Dispatch of ${sms.id} failed: ${e.message}", e)
            SmsOutbox.update(context, sms.id) {
                it.copy(sentFailed = maxOf(it.parts, 1), lastError = describe(e))
            }
            false
        }
    }

    /**
     * The SmsManager bound to the SIM the server asked for.
     *
     * A dual-SIM device has no meaningful "default" for a gateway: a reply has
     * to leave by the SIM the conversation is on, so an explicit subscription
     * is used whenever the request names one.
     */
    private fun smsManagerFor(context: Context, subId: Int): SmsManager {
        val base = context.getSystemService(SmsManager::class.java)
            ?: @Suppress("DEPRECATION") SmsManager.getDefault()
        return if (subId >= 0 && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            runCatching { base.createForSubscriptionId(subId) }.getOrDefault(base)
        } else {
            base
        }
    }

    /**
     * Distinct PendingIntents per part.  Without a unique request code the
     * platform hands every part the same one and the parts become
     * indistinguishable.
     *
     * FLAG_MUTABLE is not a lapse here, it is the requirement: telephony
     * reports its results by *filling in* extras — the network's cause code on
     * a failure, and the status report's PDU on delivery — and an immutable
     * PendingIntent drops both without a word.  That is why failures read as a
     * bare "modem_err" with no reason, and why every delivery report parsed as
     * status=unknown.  The intent is explicit — our own package, our own
     * receiver class — so nothing else can be targeted through it.
     */
    private fun pendingIntent(context: Context, action: String, id: String, part: Int): PendingIntent {
        val intent = Intent(action).apply {
            setPackage(context.packageName)
            setClass(context, SmsSendReceiver::class.java)
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_PART, part)
        }
        val requestCode = (id.hashCode() * 31 + part) * 2 + if (action == ACTION_SENT) 0 else 1
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    /**
     * Android's send-failure codes, as something a dialplan can read.
     *
     * The RIL range (100+) is where the interesting failures live — a bare
     * "error_111" says nothing, while "modem_err" plus the network's own
     * cause value says whether to retry, fix the number, or call the carrier.
     */
    fun sentResultName(resultCode: Int): String = when (resultCode) {
        android.app.Activity.RESULT_OK -> "ok"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic_failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no_service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null_pdu"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio_off"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "limit_exceeded"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "short_code_not_allowed"
        100 -> "radio_not_available"
        101 -> "send_fail_retry"
        102 -> "network_reject"
        103 -> "invalid_state"
        104 -> "invalid_arguments"
        105 -> "no_memory"
        106 -> "request_rate_limited"
        107 -> "invalid_sms_format"
        108 -> "system_err"
        109 -> "encoding_err"
        110 -> "invalid_smsc_address"
        111 -> "modem_err"
        112 -> "network_err"
        113 -> "internal_err"
        114 -> "request_not_supported"
        115 -> "invalid_modem_state"
        116 -> "network_not_ready"
        117 -> "operation_not_allowed"
        118 -> "no_resources"
        119 -> "cancelled"
        120 -> "sim_absent"
        else -> "error_$resultCode"
    }

    /**
     * The network's own reason, from GSM 04.11 — carried in the sent
     * broadcast's "errorCode" extra when the failure came from the network
     * rather than the framework.
     */
    fun networkCauseName(cause: Int): String = when (cause) {
        1 -> "unassigned_number"
        8 -> "operator_determined_barring"
        10 -> "call_barred"
        21 -> "sms_transfer_rejected"
        27 -> "destination_out_of_order"
        28 -> "unidentified_subscriber"
        29 -> "facility_rejected"
        30 -> "unknown_subscriber"
        38 -> "network_out_of_order"
        41 -> "temporary_failure"
        42 -> "congestion"
        47 -> "resources_unavailable"
        69 -> "facility_not_implemented"
        95 -> "semantically_incorrect"
        96 -> "invalid_mandatory_information"
        111 -> "protocol_error"
        127 -> "interworking"
        else -> "cause_$cause"
    }

    private fun describe(e: Exception): String =
        "${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
}
