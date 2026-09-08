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

            SmsOutbox.update(context, sms.id) { it.copy(parts = count, dispatched = true) }

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
                it.copy(dispatched = true, sentFailed = maxOf(it.parts, 1), lastError = describe(e))
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
     * indistinguishable; FLAG_IMMUTABLE because nothing outside should be able
     * to rewrite where a delivery report goes.
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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Android's send-failure codes, as something a dialplan can read. */
    fun sentResultName(resultCode: Int): String = when (resultCode) {
        android.app.Activity.RESULT_OK -> "ok"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic_failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no_service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null_pdu"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio_off"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "limit_exceeded"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "short_code_not_allowed"
        else -> "error_$resultCode"
    }

    private fun describe(e: Exception): String =
        "${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
}
