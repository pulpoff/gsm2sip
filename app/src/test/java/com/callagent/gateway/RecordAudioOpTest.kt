package com.callagent.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordAudioOpTest {
    // Verbatim from `appops get --uid com.callagent.gateway RECORD_AUDIO`,
    // Pixel 7 Pro, Android 17, 2026-11-04.
    private val pixel = "Uid mode: RECORD_AUDIO: foreground\nRECORD_AUDIO: allow\n"

    @Before fun reset() = RecordAudioOp.resetForTest()

    @Test
    fun uidModeWinsOverPackageMode() {
        val m = RecordAudioOp.parse(pixel)
        assertEquals("foreground", m.uid)
        assertEquals("allow", m.pkg)
        assertEquals("foreground", m.effective)
        // The old contains("allow") check said yes here.
        assertFalse(RecordAudioOp.isAllowed(pixel))
    }

    @Test
    fun packageModeAloneIsUsedWhenNoUidMode() {
        val m = RecordAudioOp.parse("RECORD_AUDIO: allow; time=+3m ago\n")
        assertNull(m.uid)
        assertTrue(RecordAudioOp.isAllowed("RECORD_AUDIO: allow; time=+3m ago\n"))
        assertTrue(RecordAudioOp.isAllowed("Uid mode: RECORD_AUDIO: allow\nRECORD_AUDIO: allow\n"))
    }

    @Test
    fun grantThatLeavesForegroundPinsAndStopsRetries() {
        assertFalse(RecordAudioOp.satisfied(pixel))      // first time: grant
        assertFalse(RecordAudioOp.afterGrant(pixel))     // grant had no effect
        assertTrue(RecordAudioOp.pinned)
        assertTrue(RecordAudioOp.satisfied(pixel))       // no more re-grants
        // A real revocation is still acted on.
        assertFalse(RecordAudioOp.satisfied("Uid mode: RECORD_AUDIO: ignore\nRECORD_AUDIO: ignore\n"))
    }
}
