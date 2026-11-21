package com.callagent.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageTest {

    @Test
    fun migrationMergesWhenBothCopiesExist() {
        assertEquals(Storage.Action.MOVE, Storage.migrationAction(ceExists = true, deExists = false))
        // Something wrote to DE before the first unlock: CE still has older data.
        assertEquals(Storage.Action.MERGE, Storage.migrationAction(ceExists = true, deExists = true))
        assertEquals(Storage.Action.NONE, Storage.migrationAction(ceExists = false, deExists = true))
        assertEquals(Storage.Action.NONE, Storage.migrationAction(ceExists = false, deExists = false))
    }

    @Test
    fun migratesEveryPrefsFileTheAppUses() {
        // Must match the PREFS constants in SmsStore, SmsOutbox, CallLogStore.
        assertEquals(listOf("gateway", "sms_queue", "sms_outbox", "call_log"), Storage.PREF_FILES)
        // Must match their KEY constants.
        assertEquals(mapOf("sms_queue" to "pending", "sms_outbox" to "messages", "call_log" to "entries"),
            Storage.LIST_KEYS)
    }

    @Test
    fun mergeCopiesCeOnlyKeysAndKeepsDeValues() {
        val ce = mapOf("server" to "old.example", "port" to 5060, "user" to "gw")
        val de = mapOf("server" to "new.example")
        val out = Storage.mergedValues(ce, de, listKey = null) { _, _ -> error("not a list file") }
        assertEquals(mapOf("port" to 5060, "user" to "gw"), out)
    }

    @Test
    fun mergeJoinsTheRecordList() {
        val ce = mapOf("entries" to "CE")
        val de = mapOf("entries" to "DE")
        val out = Storage.mergedValues(ce, de, listKey = "entries") { c, d -> "$c+$d" }
        assertEquals(mapOf("entries" to "CE+DE"), out)
    }

    @Test
    fun joinedListPutsOlderCeRecordsFirstWithoutDuplicates() {
        val ce = """[{"id":"a"},{"id":"b"}]"""
        val de = """[{"id":"b"},{"id":"c"}]"""
        assertEquals("""[{"id":"a"},{"id":"b"},{"id":"c"}]""", Storage.joinJsonLists(ce, de))
    }
}
