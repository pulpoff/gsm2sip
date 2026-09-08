package com.callagent.gateway.sms

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One received SMS, on its way to the SIP server.
 *
 * [id] is generated on arrival and travels with the message as X-SMS-Id.  It
 * is the server's idempotency key: a MESSAGE whose response is lost gets sent
 * again, and only the id tells the server the second copy is the same SMS.
 */
data class PendingSms(
    val id: String,
    val from: String,
    val to: String,
    val text: String,
    val receivedAt: Long,
    val parts: Int,
    val subId: Int,
    val slot: Int,
    val carrier: String,
    val attempts: Int = 0
)

/**
 * Durable queue of received SMS that have not been handed to the server yet.
 *
 * An SMS arrives whether or not SIP is registered — during a reconnect, in the
 * seconds after a reboot, while the modem has no data — and the message is the
 * only copy.  It is written to disk before any send is attempted, and removed
 * only once the server has answered 2xx.
 */
object SmsStore {
    private const val PREFS = "sms_queue"
    private const val KEY = "pending"

    /** Beyond this the queue is not a queue, it is a leak. Oldest go first. */
    private const val MAX_PENDING = 200

    fun newId(): String = UUID.randomUUID().toString()

    @Synchronized
    fun add(context: Context, sms: PendingSms) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        arr.put(toJson(sms))
        while (arr.length() > MAX_PENDING) arr.remove(0)
        prefs.edit().putString(KEY, arr.toString()).commit()
    }

    @Synchronized
    fun pending(context: Context): List<PendingSms> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
        }
    }

    /** Drop one message — called only after the server has accepted it. */
    @Synchronized
    fun remove(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) kept.put(o)
        }
        prefs.edit().putString(KEY, kept.toString()).commit()
    }

    /** Record a failed attempt so a message that never goes through is visible
     *  as such rather than just retried forever in silence. */
    @Synchronized
    fun markAttempt(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) o.put("attempts", o.optInt("attempts") + 1)
        }
        prefs.edit().putString(KEY, arr.toString()).commit()
    }

    private fun toJson(s: PendingSms) = JSONObject().apply {
        put("id", s.id)
        put("from", s.from)
        put("to", s.to)
        put("text", s.text)
        put("ts", s.receivedAt)
        put("parts", s.parts)
        put("sub", s.subId)
        put("slot", s.slot)
        put("carrier", s.carrier)
        put("attempts", s.attempts)
    }

    private fun fromJson(o: JSONObject) = PendingSms(
        id = o.getString("id"),
        from = o.optString("from"),
        to = o.optString("to"),
        text = o.optString("text"),
        receivedAt = o.optLong("ts"),
        parts = o.optInt("parts", 1),
        subId = o.optInt("sub", -1),
        slot = o.optInt("slot", -1),
        carrier = o.optString("carrier"),
        attempts = o.optInt("attempts")
    )
}
