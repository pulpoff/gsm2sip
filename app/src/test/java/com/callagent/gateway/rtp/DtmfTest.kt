package com.callagent.gateway.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtmfTest {

    // ── RFC 4733 payload ──

    @Test
    fun parsesTelephoneEvent() {
        val ev = Rfc4733.parse(Rfc4733.build(event = 11, end = true, volume = 10, duration = 1280))
        assertNotNull(ev)
        assertEquals(11, ev!!.event)
        assertTrue(ev.end)
        assertEquals(10, ev.volume)
        assertEquals(1280, ev.duration)
        assertEquals('#', Rfc4733.toChar(ev.event))
    }

    @Test
    fun mapsAllKeys() {
        val keys = "0123456789*#ABCD"
        for (c in keys) assertEquals(c, Rfc4733.toChar(Rfc4733.fromChar(c)!!))
        assertNull(Rfc4733.toChar(16)) // flash is not a key
        assertNull(Rfc4733.parse(byteArrayOf(1, 2, 3)))
    }

    // ── Detector: begin/end from a realistic packet stream ──

    private fun stream(c: Char, ts: Long, endCopies: Int = 3): List<Pair<Long, ByteArray>> {
        val ev = Rfc4733.fromChar(c)!!
        val pkts = mutableListOf<Pair<Long, ByteArray>>()
        for (d in listOf(160, 320, 480, 640)) pkts += ts to Rfc4733.build(ev, false, 10, d)
        repeat(endCopies) { pkts += ts to Rfc4733.build(ev, true, 10, 800) }
        return pkts
    }

    @Test
    fun oneBeginAndOneEndPerKeyPress() {
        val events = mutableListOf<String>()
        val det = DtmfDetector({ events += "B$it" }, { c, ms -> events += "E$c@$ms" })
        (stream('5', 1000) + stream('#', 5000)).forEach { (ts, p) -> det.onPacket(ts, p) }
        assertEquals(listOf("B5", "E5@100", "B#", "E#@100"), events)
    }

    @Test
    fun sameKeyTwiceIsTwoPresses() {
        val events = mutableListOf<String>()
        val det = DtmfDetector({ events += "B$it" }, { c, _ -> events += "E$c" })
        (stream('1', 1000) + stream('1', 3000)).forEach { (ts, p) -> det.onPacket(ts, p) }
        assertEquals(listOf("B1", "E1", "B1", "E1"), events)
    }

    @Test
    fun lostEndIsClosedByNextKeyAndLateDuplicatesIgnored() {
        val events = mutableListOf<String>()
        val det = DtmfDetector({ events += "B$it" }, { c, _ -> events += "E$c" })
        stream('7', 1000, endCopies = 0).forEach { (ts, p) -> det.onPacket(ts, p) }
        stream('8', 2000).forEach { (ts, p) -> det.onPacket(ts, p) }
        // A delayed end packet for '7' must not restart it.
        det.onPacket(1000, Rfc4733.build(7, true, 10, 800))
        assertEquals(listOf("B7", "E7", "B8", "E8"), events)
    }

    @Test
    fun flushEndsAHeldKey() {
        val events = mutableListOf<String>()
        val det = DtmfDetector({ events += "B$it" }, { c, _ -> events += "E$c" })
        det.onPacket(42, Rfc4733.build(3, false, 10, 160))
        det.flush()
        det.flush()
        assertEquals(listOf("B3", "E3"), events)
    }

    // ── SIP INFO ──

    @Test
    fun parsesSipInfoBodies() {
        val relay = SipInfoDtmf.parse("application/dtmf-relay", "Signal=5\r\nDuration=250\r\n")
        assertEquals(SipInfoDtmf.Digit('5', 250), relay)
        assertEquals('*', SipInfoDtmf.parse("application/dtmf-relay", "Signal=10\r\n")!!.char)
        assertEquals('#', SipInfoDtmf.parse("Application/DTMF-Relay; charset=utf-8", "Signal= #\nDuration= 160")!!.char)
        assertEquals('9', SipInfoDtmf.parse("application/dtmf", "9")!!.char)
        assertNull(SipInfoDtmf.parse("application/media_control+xml", "<x/>"))
        assertNull(SipInfoDtmf.parse(null, "Signal=1"))
    }

    // ── Relay timing onto the GSM call ──

    private class RecordingSink : DtmfSink {
        val log = mutableListOf<Pair<String, Long>>()
        private val t0 = System.nanoTime()
        private fun t() = (System.nanoTime() - t0) / 1_000_000
        override fun start(digit: Char) { synchronized(log) { log += "start:$digit" to t() } }
        override fun stop() { synchronized(log) { log += "stop" to t() } }
    }

    @Test
    fun shortPressIsHeldToMinimum() {
        val sink = RecordingSink()
        val relay = DtmfRelay(sink, minToneMs = 120, maxToneMs = 2000, interDigitGapMs = 50)
        relay.begin('1'); relay.end('1')
        Thread.sleep(300); relay.drain()
        assertEquals(listOf("start:1", "stop"), sink.log.map { it.first })
        val held = sink.log[1].second - sink.log[0].second
        assertTrue("held ${held}ms", held >= 110)
    }

    @Test
    fun repeatedDigitGetsAGap() {
        val sink = RecordingSink()
        val relay = DtmfRelay(sink, minToneMs = 80, maxToneMs = 2000, interDigitGapMs = 60)
        relay.begin('1'); relay.end('1'); relay.begin('1'); relay.end('1')
        Thread.sleep(500); relay.drain()
        assertEquals(listOf("start:1", "stop", "start:1", "stop"), sink.log.map { it.first })
        val gap = sink.log[2].second - sink.log[1].second
        assertTrue("gap ${gap}ms", gap >= 50)
    }

    @Test
    fun missingEndIsStoppedByWatchdog() {
        val sink = RecordingSink()
        val relay = DtmfRelay(sink, minToneMs = 50, maxToneMs = 200, interDigitGapMs = 10)
        relay.begin('9')
        Thread.sleep(400); relay.drain()
        assertEquals(listOf("start:9", "stop"), sink.log.map { it.first })
    }

    @Test
    fun pulseAndCancel() {
        val sink = RecordingSink()
        val relay = DtmfRelay(sink, minToneMs = 50, maxToneMs = 2000, interDigitGapMs = 10)
        relay.pulse('4', 1000)
        Thread.sleep(100)
        relay.cancel(); relay.drain()
        assertEquals(listOf("start:4", "stop"), sink.log.map { it.first })
        Thread.sleep(1100); relay.drain()
        assertEquals(2, sink.log.size) // the scheduled stop did not fire twice
    }

    @Test
    fun failingSinkDoesNotWedgeTheRelay() {
        var fail = true
        val log = mutableListOf<String>()
        val relay = DtmfRelay(object : DtmfSink {
            override fun start(digit: Char) { if (fail) throw IllegalStateException("no call"); log += "start:$digit" }
            override fun stop() { log += "stop" }
        }, minToneMs = 20, maxToneMs = 500, interDigitGapMs = 5)
        relay.begin('2'); relay.end('2'); relay.drain()
        fail = false
        relay.begin('3'); relay.end('3'); Thread.sleep(100); relay.drain()
        assertEquals(listOf("start:3", "stop"), log)
    }

    // ── RTP header parsing used by the receive path ──

    @Test
    fun rtpDecodeSkipsExtensionAndPadding() {
        val payload = Rfc4733.build(5, true, 10, 800)
        val hdr = byteArrayOf(
            (0x80 or 0x20 or 0x10).toByte(), (0x80 or 101).toByte(), 0, 7, 0, 0, 0x03, 0xE8.toByte(), 0, 0, 0, 1
        )
        val ext = byteArrayOf(0xBE.toByte(), 0xDE.toByte(), 0, 1, 1, 2, 3, 4)
        val pad = byteArrayOf(0, 0, 3)
        val pkt = hdr + ext + payload + pad
        val rtp = RtpPacket.decode(pkt, pkt.size)
        assertNotNull(rtp)
        assertEquals(101, rtp!!.payloadType)
        assertEquals(1000L, rtp.timestamp)
        assertEquals(payload.toList(), rtp.payload.toList())
    }
}
