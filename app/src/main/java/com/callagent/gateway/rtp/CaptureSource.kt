package com.callagent.gateway.rtp

import android.media.AudioRecord
import android.util.Log

/**
 * Where the capture loop reads the caller from.
 *
 * Two implementations: an [AudioRecord] opened through the framework
 * ([AudioRecordSource]) and an ALSA PCM read over root ([RootPcmCapture]).
 * The loop holds one of these and does not care which.
 */
interface CaptureSource {
    /** For the logs: the source and rate, e.g. `VOICE_DOWNLINK@16k`. */
    val name: String

    /** Begin delivering audio.  False if the source cannot start at all. */
    fun start(): Boolean

    /**
     * Fill [buf] with PCM S16LE at the capture rate.  Returns the byte count,
     * 0 if nothing was available this time, or -1 once the source is gone for
     * good and the loop should move to another one.
     */
    fun read(buf: ByteArray): Int

    /** Fast and non-blocking: make a [read] in progress return.  Safe on
     *  the main thread. */
    fun close()

    /** Give the device back.  May be slow (root shell, native release), so
     *  call it off the main thread, after the reading thread has stopped. */
    fun release()
}

/** An [AudioRecord] behind the [CaptureSource] contract. */
class AudioRecordSource(
    val record: AudioRecord,
    override val name: String,
) : CaptureSource {

    override fun start(): Boolean {
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.startRecording()
        }
        Log.i(TAG, "Capture routedFrom=${record.routedDevice?.type}")
        return record.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }

    override fun read(buf: ByteArray): Int {
        val n = record.read(buf, 0, buf.size)
        // Negative is an error code.  A dead IAudioRecord never recovers, so
        // report the source gone; the other codes are transient and read as
        // "nothing this time", as they always have here.
        return if (n == AudioRecord.ERROR_DEAD_OBJECT) -1 else n.coerceAtLeast(0)
    }

    override fun close() {
        try { record.stop() } catch (_: Exception) {}
    }

    override fun release() {
        close()
        try { record.release() } catch (_: Exception) {}
    }

    private companion object {
        const val TAG = "RtpSession"
    }
}
