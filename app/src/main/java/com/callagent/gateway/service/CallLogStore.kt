package com.callagent.gateway.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CallLogEntry(
    val direction: String,  // "IN" or "OUT"
    val number: String,
    val timestamp: Long,    // millis since epoch (call start)
    val durationSec: Long
)

object CallLogStore {
    private const val PREFS = "call_log"
    private const val KEY = "entries"

    // In-memory cache — avoids re-parsing JSON from SharedPreferences on every access
    @Volatile
    private var cachedEntries: List<CallLogEntry>? = null

    fun addEntry(context: Context, entry: CallLogEntry) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val obj = JSONObject().apply {
            put("dir", entry.direction)
            put("num", entry.number)
            put("ts", entry.timestamp)
            put("dur", entry.durationSec)
        }
        arr.put(obj)
        prefs.edit().putString(KEY, arr.toString()).apply()
        cachedEntries = null // invalidate cache
    }

    fun getEntries(context: Context): List<CallLogEntry> {
        cachedEntries?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val entries = (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            CallLogEntry(
                direction = obj.getString("dir"),
                number = obj.getString("num"),
                timestamp = obj.getLong("ts"),
                durationSec = obj.getLong("dur")
            )
        }.reversed() // newest first
        cachedEntries = entries
        return entries
    }

    data class Totals(
        val inCalls: Int, val inDurationSec: Long,
        val outCalls: Int, val outDurationSec: Long
    )

    fun getTotals(context: Context): Totals {
        val entries = getEntries(context)
        return Totals(
            inCalls = entries.count { it.direction == "IN" },
            inDurationSec = entries.filter { it.direction == "IN" }.sumOf { it.durationSec },
            outCalls = entries.count { it.direction == "OUT" },
            outDurationSec = entries.filter { it.direction == "OUT" }.sumOf { it.durationSec }
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, "[]").apply()
        cachedEntries = null // invalidate cache
    }
}
