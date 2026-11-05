package com.callagent.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageTest {

    @Test
    fun migrationMovesOnlyWhenDeviceProtectedCopyIsMissing() {
        assertEquals(Storage.Action.MOVE, Storage.migrationAction(ceExists = true, deExists = false))
        // DE is authoritative once it exists: never overwrite it with CE.
        assertEquals(Storage.Action.DROP_CE, Storage.migrationAction(ceExists = true, deExists = true))
        assertEquals(Storage.Action.NONE, Storage.migrationAction(ceExists = false, deExists = true))
        assertEquals(Storage.Action.NONE, Storage.migrationAction(ceExists = false, deExists = false))
    }

    @Test
    fun migratesEveryPrefsFileTheAppUses() {
        // Must match the PREFS constants in SmsStore, SmsOutbox, CallLogStore.
        assertEquals(listOf("gateway", "sms_queue", "sms_outbox", "call_log"), Storage.PREF_FILES)
    }
}
