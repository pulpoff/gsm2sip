package com.callagent.gateway.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import com.callagent.gateway.service.CallLogStore
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
            SmsSender.ACTION_SENT ->
                onSent(context, id, part, resultCode, intent.getIntExtra("errorCode", -1))
            SmsSender.ACTION_DELIVERED -> onDelivered(context, id, part, intent)
            else -> return
        }
        GatewayService.reportSmsProgress(context, id)
    }

    private fun onSent(context: Context, id: String, part: Int, result: Int, cause: Int) {
        val ok = result == Activity.RESULT_OK
        // The network's cause is the half that says what to do about it:
        // "modem_err" alone is a shrug, "modem_err/facility_rejected" is the
        // carrier refusing the submission.
        val name = if (ok || cause < 0) {
            SmsSender.sentResultName(result)
        } else {
            "${SmsSender.sentResultName(result)}/${SmsSender.networkCauseName(cause)}"
        }
        Log.i(TAG, "Sent result for $id part $part: $name")
        SmsOutbox.update(context, id) {
            if (ok) it.copy(sentOk = it.sentOk + 1)
            else it.copy(sentFailed = it.sentFailed + 1, lastError = name)
        }
        CallLogStore.updateSms(context, id) {
            if (ok) it.copy(status = promote(it.status, "sent"))
            else it.copy(status = "failed", error = name)
        }
    }

    private fun onDelivered(context: Context, id: String, part: Int, intent: Intent) {
        // The SMSC's status report, as a raw PDU.  Status below 32 means the
        // network delivered it; 32-63 is still trying; 64 and up is a
        // permanent failure.  A carrier that returns nothing at all simply
        // never gets here, which is why "submitted" is reported separately.
        // The same PDU carries the service centre that handled the message.
        // It is the only place it is available on older platforms --
        // SmsManager.getSmscAddress() is API 30 and privileged -- so read it
        // here while the report is in hand.
        var smsc = ""
        val status = try {
            val pdu = intent.getByteArrayExtra("pdu")
            val format = intent.getStringExtra("format")
            val parsed = if (pdu != null) SmsMessage.createFromPdu(pdu, format) else null
            smsc = parsed?.serviceCenterAddress.orEmpty()
            parsed?.status
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
        SmsOutbox.update(context, id) { prev ->
            val rec = if (smsc.isNotEmpty()) prev.copy(smsc = smsc) else prev
            when {
                status == null -> rec.copy(deliveredOk = rec.deliveredOk + 1, status = "unknown")
                delivered -> rec.copy(deliveredOk = rec.deliveredOk + 1, status = status.toString())
                else -> rec.copy(
                    deliveredFailed = rec.deliveredFailed + 1,
                    status = status.toString(),
                    lastError = "status_$status"
                )
            }
        }
        CallLogStore.updateSms(context, id) {
            val withSmsc = if (smsc.isNotEmpty()) it.copy(smsc = smsc) else it
            when {
                delivered && status == null ->
                    withSmsc.copy(status = promote(withSmsc.status, "delivered (unconfirmed)"))
                delivered -> withSmsc.copy(status = promote(withSmsc.status, "delivered"))
                else -> withSmsc.copy(status = "failed", error = "status_$status")
            }
        }
    }

    /**
     * Keep the furthest-along outcome.
     *
     * A long message reports per part, and the parts do not arrive in order:
     * without this, a "sent" callback for part 2 landing after part 1 was
     * already delivered would walk the row backwards.  A failure is never
     * promoted over — it is written directly, because one failed part means
     * the message did not arrive whole.
     */
    private fun promote(current: String, next: String): String {
        if (current == "failed") return current
        val rank = { s: String ->
            when {
                s.startsWith("delivered") -> 3
                s == "sent" -> 2
                s == "pending" -> 1
                else -> 0
            }
        }
        return if (rank(next) >= rank(current)) next else current
    }

    companion object {
        private const val TAG = "SmsSendReceiver"
    }
}
