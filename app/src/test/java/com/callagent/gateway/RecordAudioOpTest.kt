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
    fun repeatedGrantsThatLeaveForegroundPinAndStopRetries() {
        repeat(RecordAudioOp.PIN_AFTER - 1) {
            assertFalse(RecordAudioOp.satisfied(pixel))  // grant
            assertFalse(RecordAudioOp.afterGrant(pixel)) // no effect
            assertFalse(RecordAudioOp.pinned)            // not yet
        }
        assertFalse(RecordAudioOp.afterGrant(pixel))
        assertTrue(RecordAudioOp.pinned)
        assertTrue(RecordAudioOp.satisfied(pixel))       // no more re-grants
        // A real revocation is still acted on.
        assertFalse(RecordAudioOp.satisfied("Uid mode: RECORD_AUDIO: ignore\nRECORD_AUDIO: ignore\n"))
    }

    @Test
    fun oneGrantLosingARaceDoesNotPin() {
        val allowed = "Uid mode: RECORD_AUDIO: allow\nRECORD_AUDIO: allow\n"
        // Boot: the first grant is re-revoked before the readback.
        assertFalse(RecordAudioOp.afterGrant(pixel))
        assertFalse(RecordAudioOp.pinned)
        // The next one sticks, which resets the count.
        assertTrue(RecordAudioOp.afterGrant(allowed))
        repeat(RecordAudioOp.PIN_AFTER - 1) { RecordAudioOp.afterGrant(pixel) }
        assertFalse(RecordAudioOp.pinned)
        assertTrue(RecordAudioOp.satisfied(allowed))
    }
}
