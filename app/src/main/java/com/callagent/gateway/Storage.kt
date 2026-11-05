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

    enum class Action { MOVE, DROP_CE, NONE }

    /** What to do with one file, given where it exists.  DE wins once it
     *  exists: every write since this build goes there, so a CE copy can
     *  only be older (or a root-seeded duplicate). */
    fun migrationAction(ceExists: Boolean, deExists: Boolean): Action = when {
        deExists && ceExists -> Action.DROP_CE
        ceExists -> Action.MOVE
        else -> Action.NONE
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
                    Action.DROP_CE -> {
                        ce.deleteSharedPreferences(name)
                        Log.i(TAG, "prefs '$name': already in device-protected storage, dropped CE copy")
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
                    Action.MOVE -> {
                        dst.parentFile?.mkdirs()
                        src.copyTo(dst, overwrite = false)
                        src.delete()
                        Log.i(TAG, "file '$name': moved to device-protected storage")
                    }
                    Action.DROP_CE -> src.delete()
                    Action.NONE -> {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "file '$name' migration failed: ${e.message}")
            }
        }
    }
}
