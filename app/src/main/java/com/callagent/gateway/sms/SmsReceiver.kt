package com.callagent.gateway.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import com.callagent.gateway.service.GatewayService

/**
 * Incoming SMS on any SIM, on its way to the SIP server.
 *
 * Listens for SMS_RECEIVED rather than SMS_DELIVER: the latter goes only to
 * the default SMS app, while the former reaches any app holding RECEIVE_SMS.
 * The gateway does not need to own the SMS role to forward messages, and not
 * taking it leaves the device's normal messaging untouched.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val parts = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot parse SMS PDUs: ${e.message}")
            null
        } ?: return
        if (parts.isEmpty()) return

        // A long SMS arrives as several PDUs of one message, in order.
        val sender = parts[0].displayOriginatingAddress ?: parts[0].originatingAddress ?: ""
        val text = parts.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
        val receivedAt = parts[0].timestampMillis

        // Which SIM took it.  Two extra names are in circulation and OEMs
        // disagree about which they set, so try both before giving up.
        val subId = intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            intent.getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        )
        val (slot, carrier, ownNumber) = describeSim(context, subId)

        val sms = PendingSms(
            id = SmsStore.newId(),
            from = sender,
            to = ownNumber,
            text = text,
            receivedAt = if (receivedAt > 0) receivedAt else System.currentTimeMillis(),
            parts = parts.size,
            subId = subId,
            slot = slot,
            carrier = carrier
        )

        // Written to disk before anything is attempted: this broadcast is the
        // only copy of the message we will ever get.
        SmsStore.add(context, sms)
        Log.i(TAG, "SMS from $sender (${parts.size} part(s), sub=$subId slot=$slot) queued as ${sms.id}")

        GatewayService.deliverQueuedSms(context)
    }

    /** Slot, carrier and own number for the subscription that received it. */
    private fun describeSim(context: Context, subId: Int): Triple<Int, String, String> {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return Triple(-1, "", "")
        }
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
                ?: return Triple(-1, "", "")
            @Suppress("MissingPermission")
            val info = sm.getActiveSubscriptionInfo(subId) ?: return Triple(-1, "", "")
            val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { info.number }.getOrDefault("")
            } else {
                @Suppress("DEPRECATION")
                runCatching { info.number }.getOrDefault("")
            }
            Triple(
                info.simSlotIndex,
                info.carrierName?.toString().orEmpty(),
                number.orEmpty()
            )
        } catch (e: Exception) {
            // Reading subscription details needs READ_PHONE_STATE, and the
            // MSISDN is often simply not on the SIM.  Neither is a reason to
            // drop the message — the server can route on the sender alone.
            Log.w(TAG, "SIM details unavailable for sub=$subId: ${e.message}")
            Triple(-1, "", "")
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
