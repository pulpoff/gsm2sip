package com.callagent.gateway

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Log

/**
 * Where the gateway keeps its state: device-protected (DE) storage.
 *
 * Credential-encrypted storage — the default for getSharedPreferences() and
 * filesDir — stays locked after a reboot until someone enters the PIN, so a
 * gateway that kept its SIP settings there could not start until then.  A
 * phone sitting at home after a power cut would stay offline.  DE storage is
 * available from LOCKED_BOOT_COMPLETED on, which is what lets the app run
 * before the first unlock (android:directBootAware in the manifest).
 *
 * Everything here is migrated once from the old CE location; see [migrate].
 */
object Storage {
    private const val TAG = "Storage"

    /** Main settings: SIP server, credentials, own numbers, toggles. */
    const val GATEWAY = "gateway"

    /** Every SharedPreferences file the app owns, for [migrate]. */
    val PREF_FILES = listOf(GATEWAY, "sms_queue", "sms_outbox", "call_log")

    /** Flag/diagnostic files kept in filesDir, for [migrate]. */
    val FLAG_FILES = listOf("dump_audio")

    fun de(context: Context): Context =
        if (context.isDeviceProtectedStorage) context
        else context.createDeviceProtectedStorageContext()

    fun prefs(context: Context, name: String = GATEWAY): SharedPreferences =
        de(context).getSharedPreferences(name, Context.MODE_PRIVATE)

    fun filesDir(context: Context): java.io.File = de(context).filesDir

    enum class Action { MOVE, MERGE, NONE }

    /** The record-list key of each prefs file that holds a JSON array of
     *  records, oldest first.  These are merged entry by entry. */
    val LIST_KEYS = mapOf("sms_queue" to "pending", "sms_outbox" to "messages", "call_log" to "entries")

    /** What to do with one prefs file, given where it exists.  Both can
     *  exist when something wrote to DE before the first unlock after the
     *  update (an SMS arriving, a call log entry): the CE copy then still
     *  holds older data and is merged in, not dropped. */
    fun migrationAction(ceExists: Boolean, deExists: Boolean): Action = when {
        deExists && ceExists -> Action.MERGE
        ceExists -> Action.MOVE
        else -> Action.NONE
    }

    /**
     * The values to write into the DE copy so that it also holds the CE data.
     * Keys only in CE are copied; for keys in both, DE wins (it is newer),
     * except [listKey], whose two record lists are joined with [joinLists].
     */
    fun mergedValues(
        ce: Map<String, *>, de: Map<String, *>, listKey: String?,
        joinLists: (ce: String, de: String) -> String
    ): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in ce) {
            if (!de.containsKey(k)) {
                out[k] = v
            } else if (k == listKey) {
                val c = v as? String
                val d = de[k] as? String
                if (c != null && d != null) out[k] = joinLists(c, d)
            }
        }
        return out
    }

    /** CE records first (they are older), then DE records not already in CE. */
    fun joinJsonLists(ce: String, de: String): String {
        val c = org.json.JSONArray(ce)
        val seen = HashSet<String>()
        for (i in 0 until c.length()) seen.add(c.get(i).toString())
        val d = org.json.JSONArray(de)
        for (i in 0 until d.length()) {
            val item = d.get(i)
            if (seen.add(item.toString())) c.put(item)
        }
        return c.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun putAll(editor: SharedPreferences.Editor, values: Map<String, Any?>) {
        for ((k, v) in values) when (v) {
            is String -> editor.putString(k, v)
            is Int -> editor.putInt(k, v)
            is Long -> editor.putLong(k, v)
            is Float -> editor.putFloat(k, v)
            is Boolean -> editor.putBoolean(k, v)
            is Set<*> -> editor.putStringSet(k, v as Set<String>)
            else -> {}
        }
    }

    /**
     * One-time move from CE to DE.  Only possible while the user is unlocked
     * (CE is unreadable before), so it is called from Application.onCreate
     * and again on BOOT_COMPLETED; both are cheap no-ops once done.
     */
    fun migrate(context: Context) {
        val um = context.getSystemService(UserManager::class.java)
        if (um != null && !um.isUserUnlocked) return
        val ce = if (context.isDeviceProtectedStorage) return else context
        val de = de(context)
        val ceDir = java.io.File(ce.dataDir, "shared_prefs")
        val deDir = java.io.File(de.dataDir, "shared_prefs")
        for (name in PREF_FILES) {
            try {
                when (migrationAction(java.io.File(ceDir, "$name.xml").exists(),
                                      java.io.File(deDir, "$name.xml").exists())) {
                    Action.MOVE -> {
                        val ok = de.moveSharedPreferencesFrom(ce, name)
                        Log.i(TAG, "prefs '$name': moved to device-protected storage ok=$ok")
                    }
                    Action.MERGE -> {
                        val dePrefs = de.getSharedPreferences(name, Context.MODE_PRIVATE)
                        val ceValues = ce.getSharedPreferences(name, Context.MODE_PRIVATE).all
                        val merged = mergedValues(ceValues, dePrefs.all, LIST_KEYS[name], ::joinJsonLists)
                        val editor = dePrefs.edit()
                        putAll(editor, merged)
                        if (editor.commit()) {
                            ce.deleteSharedPreferences(name)
                            if (name == "call_log") com.callagent.gateway.service.CallLogStore.invalidateCache()
                            Log.i(TAG, "prefs '$name': merged ${merged.size} key(s) from CE into device-protected storage")
                        } else {
                            Log.w(TAG, "prefs '$name': merge write failed, CE copy kept")
                        }
                    }
                    Action.NONE -> {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "prefs '$name' migration failed: ${e.message}")
            }
        }
        for (name in FLAG_FILES) {
            try {
                val src = java.io.File(ce.filesDir, name)
                val dst = java.io.File(de.filesDir, name)
                when (migrationAction(src.exists(), dst.exists())) {
                    // A flag file carries no data: if DE has one, CE's is spare.
                    Action.MERGE -> src.delete()
                    Action.MOVE -> {
                        dst.parentFile?.mkdirs()
                        src.copyTo(dst, overwrite = false)
                        src.delete()
                        Log.i(TAG, "file '$name': moved to device-protected storage")
                    }
                    Action.NONE -> {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "file '$name' migration failed: ${e.message}")
            }
        }
    }
}
