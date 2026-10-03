package com.callagent.gateway.rtp

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * DTMF from the SIP side into the GSM call.
 *
 * Asterisk (and every softphone behind it) sends key presses out of band,
 * either as RFC 4733 telephone-event RTP packets or, less often, as SIP INFO.
 * The gateway used to offer telephone-event in its SDP and then throw the
 * packets away, so IVR menus never saw a single digit.  The pieces here turn
 * those events into Call.playDtmfTone()/stopDtmfTone() on the GSM leg, which
 * the telephony stack then signals to the network properly — out of band on
 * VoLTE/VoWiFi (IMS), as START/STOP DTMF on circuit-switched calls.
 *
 * Everything in this file is plain Kotlin with no Android dependency so it can
 * be unit-tested on the JVM.
 */

/** One RFC 4733 telephone-event payload (4 bytes). */
data class TelephoneEvent(
    val event: Int,
    val end: Boolean,
    val volume: Int,
    /** Duration so far, in timestamp units (8 kHz for telephone-event/8000). */
    val duration: Int
)

object Rfc4733 {
    /** Parse the first 4 bytes of a telephone-event payload. */
    fun parse(payload: ByteArray): TelephoneEvent? {
        if (payload.size < 4) return null
        val event = payload[0].toInt() and 0xFF
        val b1 = payload[1].toInt() and 0xFF
        val end = (b1 and 0x80) != 0
        val volume = b1 and 0x3F
        val duration = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        return TelephoneEvent(event, end, volume, duration)
    }

    /** RFC 4733 §3.2 event codes 0-15 → dial-pad characters. */
    fun toChar(event: Int): Char? = when (event) {
        in 0..9 -> '0' + event
        10 -> '*'
        11 -> '#'
        in 12..15 -> 'A' + (event - 12)
        else -> null // 16 = flash and the tone events are not dial-pad keys
    }

    /** Inverse of [toChar], for building test packets. */
    fun fromChar(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        '*' -> 10
        '#' -> 11
        in 'A'..'D' -> 12 + (c - 'A')
        in 'a'..'d' -> 12 + (c - 'a')
        else -> null
    }

    fun build(event: Int, end: Boolean, volume: Int, duration: Int): ByteArray = byteArrayOf(
        event.toByte(),
        ((if (end) 0x80 else 0) or (volume and 0x3F)).toByte(),
        ((duration shr 8) and 0xFF).toByte(),
        (duration and 0xFF).toByte()
    )
}

/**
 * Turns a stream of telephone-event packets into begin/end callbacks.
 *
 * An event is identified by its RTP timestamp, which stays fixed for the whole
 * key press (RFC 4733 §2.5.1.2).  The sender repeats the start packet and
 * sends the end packet three times; duplicates and reordering are absorbed
 * here so each key press produces exactly one begin and one end.  If the
 * start packets are lost the first packet seen still begins the event; if the
 * end packets are lost the next event, or [flush], ends it.
 */
class DtmfDetector(
    private val onBegin: (Char) -> Unit,
    private val onEnd: (Char, Int) -> Unit,
    /** Sample rate of the telephone-event clock, normally 8000. */
    private val clockRate: Int = 8000
) {
    private var currentTs: Long = -1
    private var currentChar: Char? = null
    private var currentDuration = 0
    private var ended = true
    /** Timestamps of recently finished events, so late duplicates of an end
     *  packet arriving after the next key has started are not replayed. */
    private val recent = ArrayDeque<Long>()

    @Synchronized
    fun onPacket(timestamp: Long, payload: ByteArray) {
        val ev = Rfc4733.parse(payload) ?: return
        val c = Rfc4733.toChar(ev.event) ?: return

        if (timestamp != currentTs) {
            if (timestamp in recent) return // stale retransmission of an old key
            // A new key.  Close the previous one if its end never arrived.
            finishCurrent()
            currentTs = timestamp
            currentChar = c
            currentDuration = ev.duration
            ended = false
            onBegin(c)
        } else if (ended) {
            return // repeated end packets
        }

        currentDuration = maxOf(currentDuration, ev.duration)
        if (ev.end) finishCurrent()
    }

    /** End whatever is playing — call when the media stream stops. */
    @Synchronized
    fun flush() = finishCurrent()

    private fun finishCurrent() {
        val c = currentChar
        if (!ended && c != null) {
            ended = true
            onEnd(c, currentDuration * 1000 / clockRate)
            recent.addLast(currentTs)
            while (recent.size > 8) recent.removeFirst()
        }
    }
}

/** DTMF carried in a SIP INFO body (application/dtmf-relay or application/dtmf). */
object SipInfoDtmf {
    data class Digit(val char: Char, val durationMs: Int)

    fun parse(contentType: String?, body: String): Digit? {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when (type) {
            "application/dtmf-relay" -> {
                var signal: String? = null
                var duration = DEFAULT_DURATION_MS
                for (raw in body.lines()) {
                    val line = raw.trim()
                    val key = line.substringBefore('=').trim().lowercase()
                    val value = line.substringAfter('=', "").trim()
                    when (key) {
                        "signal" -> signal = value
                        "duration" -> duration = value.toIntOrNull() ?: duration
                    }
                }
                signal?.let { toDigit(it, duration) }
            }
            "application/dtmf" -> toDigit(body.trim(), DEFAULT_DURATION_MS)
            else -> null
        }
    }

    private fun toDigit(signal: String, durationMs: Int): Digit? {
        if (signal.isEmpty()) return null
        // Some senders use the RFC 4733 code ("10" for '*') instead of the key.
        val c = signal.toIntOrNull()?.let { Rfc4733.toChar(it) }
            ?: signal.first().uppercaseChar().takeIf { Rfc4733.fromChar(it) != null }
            ?: return null
        return Digit(c, durationMs)
    }

    const val DEFAULT_DURATION_MS = 160
}

/** Where tones are actually played — the GSM call in production. */
interface DtmfSink {
    fun start(digit: Char)
    fun stop()
}

/**
 * Serialises DTMF onto the GSM call with sane timing.
 *
 * Telecom's playDtmfTone()/stopDtmfTone() pair is stateful: only one tone at a
 * time, and a stop for a tone that was never started confuses some RILs.  The
 * network also needs each tone to last long enough to be detected, and a short
 * gap between consecutive tones — an IVR will merge "11" into "1" otherwise.
 * All operations run on one thread so begin/end pairs can never interleave.
 */
class DtmfRelay(
    private val sink: DtmfSink,
    private val minToneMs: Long = 120,
    private val maxToneMs: Long = 2_000,
    private val interDigitGapMs: Long = 60,
    private val log: (String) -> Unit = {}
) {
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "DtmfRelay").apply { isDaemon = true }
    }

    // State below is only touched on [exec].
    private var current: Char? = null
    /** True once the current tone has been asked to end, so a second press of
     *  the same key ("11") is recognised as a new tone, not a repeat. */
    private var endRequested = false
    private var startedAt = 0L
    private var lastStopAt = 0L
    private var generation = 0L
    private var pendingStop: ScheduledFuture<*>? = null
    private var watchdog: ScheduledFuture<*>? = null

    private fun now() = System.nanoTime() / 1_000_000

    /** A key went down (RFC 4733 begin). */
    fun begin(digit: Char) = exec.execute { beginNow(digit) }

    /** A key came up (RFC 4733 end).  Holds the tone to [minToneMs]. */
    fun end(digit: Char) = exec.execute {
        if (current != digit) {
            // The begin was lost or belonged to another key: play it as a pulse.
            beginNow(digit)
        }
        scheduleStop(minToneMs)
    }

    /** A complete key press of known length (SIP INFO). */
    fun pulse(digit: Char, durationMs: Int) = exec.execute {
        beginNow(digit)
        scheduleStop(durationMs.toLong().coerceIn(minToneMs, maxToneMs))
    }

    /** Stop immediately and forget everything — call when the bridge ends. */
    fun cancel() = exec.execute {
        pendingStop?.cancel(false)
        watchdog?.cancel(false)
        if (current != null) stopNow()
    }

    /** For tests: wait until every queued operation has run. */
    fun drain(timeoutMs: Long = 5_000) {
        exec.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun beginNow(digit: Char) {
        if (current == digit && !endRequested) return // repeated begin, same press
        if (current != null) stopNow()
        val wait = lastStopAt + interDigitGapMs - now()
        if (wait > 0) Thread.sleep(wait)
        try {
            sink.start(digit)
        } catch (e: Exception) {
            log("DTMF start '$digit' failed: ${e.message}")
            return
        }
        current = digit
        endRequested = false
        startedAt = now()
        val gen = ++generation
        log("DTMF '$digit' → GSM")
        watchdog = exec.schedule({
            if (generation == gen && current != null) {
                log("DTMF '$digit' had no end after ${maxToneMs}ms — stopping")
                stopNow()
            }
        }, maxToneMs, TimeUnit.MILLISECONDS)
    }

    private fun scheduleStop(minDurationMs: Long) {
        if (current == null) return
        endRequested = true
        val gen = generation
        pendingStop?.cancel(false)
        val remaining = startedAt + minDurationMs - now()
        if (remaining <= 0) {
            stopNow()
        } else {
            pendingStop = exec.schedule({
                if (generation == gen && current != null) stopNow()
            }, remaining, TimeUnit.MILLISECONDS)
        }
    }

    private fun stopNow() {
        pendingStop?.cancel(false)
        watchdog?.cancel(false)
        pendingStop = null
        watchdog = null
        try {
            sink.stop()
        } catch (e: Exception) {
            log("DTMF stop failed: ${e.message}")
        }
        current = null
        endRequested = false
        lastStopAt = now()
        generation++
    }
}
