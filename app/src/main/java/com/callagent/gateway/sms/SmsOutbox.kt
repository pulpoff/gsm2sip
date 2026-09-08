package com.callagent.gateway.sms

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * An SMS the server asked us to send, and what has happened to it so far.
 *
 * [id] comes from the server's X-SMS-Id where it sends one, so both ends refer
 * to the message by the same name and a repeated request is recognised as a
 * repeat rather than sent twice.
 */
data class OutboundSms(
    val id: String,
    val to: String,
    val text: String,
    val subId: Int,
    val parts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val dispatched: Boolean = false,
    val sentOk: Int = 0,
    val sentFailed: Int = 0,
    val lastError: String = "",
    val deliveredOk: Int = 0,
    val deliveredFailed: Int = 0,
    /** The SMSC's status value, or "unknown" when the report carried no
     *  readable PDU — an inferred result should not look like a stated one. */
    val status: String = "",
    /** Service centre that handled the message, read back from the delivery
     *  report PDU.  Empty until one arrives. */
    val smsc: String = "",
    /** "GSM7", "UCS2", "8BIT" — how the text went out on the air. */
    val encoding: String = "",
    val submitReported: Boolean = false,
    val finalReported: Boolean = false
)

/**
 * Durable record of outbound messages.
 *
 * Once the server has been told 202 the message is ours, so it has to survive
 * the process: the delivery report can arrive minutes later, and a gateway
 * that forgot what it sent could neither report nor refuse to send it twice.
 */
object SmsOutbox {
    private const val PREFS = "sms_outbox"
    private const val KEY = "messages"

    private const val MAX_ENTRIES = 200

    /** Entries older than this are given up on — a delivery report that has
     *  not arrived within a day is not going to. */
    private const val EXPIRY_MS = 24L * 60 * 60 * 1000

    @Synchronized
    fun add(context: Context, sms: OutboundSms) {
        val all = read(context).toMutableList()
        if (all.any { it.id == sms.id }) return
        all.add(sms)
        write(context, all.takeLast(MAX_ENTRIES))
    }

    @Synchronized
    fun get(context: Context, id: String): OutboundSms? = read(context).firstOrNull { it.id == id }

    @Synchronized
    fun all(context: Context): List<OutboundSms> = read(context)

    @Synchronized
    fun update(context: Context, id: String, transform: (OutboundSms) -> OutboundSms): OutboundSms? {
        val all = read(context).toMutableList()
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) return null
        val updated = transform(all[i])
        all[i] = updated
        write(context, all)
        return updated
    }

    /**
     * Take ownership of a message for dispatch, once.
     *
     * Returns false if someone already has it.  Read-then-write was not
     * enough: every accepted request kicks a dispatch pass, and two passes
     * scanning the same queue both saw dispatched=false and both handed the
     * message to the modem — the recipient got it twice.
     */
    @Synchronized
    fun claimForDispatch(context: Context, id: String): Boolean {
        val all = read(context).toMutableList()
        val i = all.indexOfFirst { it.id == id }
        if (i < 0 || all[i].dispatched) return false
        all[i] = all[i].copy(dispatched = true)
        write(context, all)
        return true
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        write(context, read(context).filterNot { it.id == id })
    }

    /** Drop everything finished or too old to still be waiting on a report. */
    @Synchronized
    fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - EXPIRY_MS
        write(context, read(context).filterNot { it.finalReported || it.createdAt < cutoff })
    }

    private fun read(context: Context): List<OutboundSms> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
        }
    }

    private fun write(context: Context, list: List<OutboundSms>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).commit()
    }

    private fun toJson(s: OutboundSms) = JSONObject().apply {
        put("id", s.id); put("to", s.to); put("text", s.text); put("sub", s.subId)
        put("parts", s.parts); put("created", s.createdAt); put("dispatched", s.dispatched)
        put("sentOk", s.sentOk); put("sentFailed", s.sentFailed); put("err", s.lastError)
        put("delOk", s.deliveredOk); put("delFailed", s.deliveredFailed)
        put("status", s.status); put("smsc", s.smsc); put("encoding", s.encoding)
        put("submitReported", s.submitReported); put("finalReported", s.finalReported)
    }

    private fun fromJson(o: JSONObject) = OutboundSms(
        id = o.getString("id"),
        to = o.optString("to"),
        text = o.optString("text"),
        subId = o.optInt("sub", -1),
        parts = o.optInt("parts"),
        createdAt = o.optLong("created"),
        dispatched = o.optBoolean("dispatched"),
        sentOk = o.optInt("sentOk"),
        sentFailed = o.optInt("sentFailed"),
        lastError = o.optString("err"),
        deliveredOk = o.optInt("delOk"),
        deliveredFailed = o.optInt("delFailed"),
        status = o.optString("status"),
        smsc = o.optString("smsc"),
        encoding = o.optString("encoding"),
        submitReported = o.optBoolean("submitReported"),
        finalReported = o.optBoolean("finalReported")
    )
}
