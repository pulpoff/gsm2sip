package com.callagent.gateway.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import com.callagent.gateway.service.GatewayService

/**
 * Sent and delivery callbacks for outbound SMS.
 *
 * Declared in the manifest rather than registered at runtime: a delivery
 * report can arrive long after the send, and the process may well have been
 * restarted in between.  A runtime receiver would simply not be there.
 */
class SmsSendReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(SmsSender.EXTRA_ID) ?: return
        val part = intent.getIntExtra(SmsSender.EXTRA_PART, 0)

        when (intent.action) {
            SmsSender.ACTION_SENT -> onSent(context, id, part, resultCode)
            SmsSender.ACTION_DELIVERED -> onDelivered(context, id, part, intent)
            else -> return
        }
        GatewayService.reportSmsProgress(context, id)
    }

    private fun onSent(context: Context, id: String, part: Int, result: Int) {
        val ok = result == Activity.RESULT_OK
        val name = SmsSender.sentResultName(result)
        Log.i(TAG, "Sent result for $id part $part: $name")
        SmsOutbox.update(context, id) {
            if (ok) it.copy(sentOk = it.sentOk + 1)
            else it.copy(sentFailed = it.sentFailed + 1, lastError = name)
        }
    }

    private fun onDelivered(context: Context, id: String, part: Int, intent: Intent) {
        // The SMSC's status report, as a raw PDU.  Status below 32 means the
        // network delivered it; 32-63 is still trying; 64 and up is a
        // permanent failure.  A carrier that returns nothing at all simply
        // never gets here, which is why "submitted" is reported separately.
        val status = try {
            val pdu = intent.getByteArrayExtra("pdu")
            val format = intent.getStringExtra("format")
            if (pdu != null) SmsMessage.createFromPdu(pdu, format)?.status else null
        } catch (e: Exception) {
            Log.w(TAG, "Cannot parse delivery report for $id: ${e.message}")
            null
        }
        // A report with no readable PDU still means the SMSC sent one, which
        // it only does for a message it has resolved — but it does not say
        // which way.  Counted as delivered, and recorded as unconfirmed so the
        // server can tell an inferred result from a stated one.
        val delivered = status == null || status < 32
        Log.i(TAG, "Delivery report for $id part $part: status=$status delivered=$delivered")
        SmsOutbox.update(context, id) {
            when {
                status == null -> it.copy(deliveredOk = it.deliveredOk + 1, status = "unknown")
                delivered -> it.copy(deliveredOk = it.deliveredOk + 1, status = status.toString())
                else -> it.copy(
                    deliveredFailed = it.deliveredFailed + 1,
                    status = status.toString(),
                    lastError = "status_$status"
                )
            }
        }
    }

    companion object {
        private const val TAG = "SmsSendReceiver"
    }
}
