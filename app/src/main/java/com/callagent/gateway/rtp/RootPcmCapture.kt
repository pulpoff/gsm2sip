package com.callagent.gateway.rtp

import android.util.Log
import com.callagent.gateway.RootShell
import java.io.DataInputStream
import java.io.EOFException

/**
 * Caller capture straight from an ALSA PCM over root, for HALs whose
 * AudioRecord route to the downlink is silent.
 *
 * On the Pixel 7 AudioPolicyManager always opens VOICE_DOWNLINK on the
 * "incall capture" mixPort, whose AoC stream reads exact zeros, while the
 * telephony-rx stream next to it carries the caller cleanly — but only the
 * framework's own call-audio patch can reach that one through the policy.
 * tinycap reaches it directly.
 *
 * Runs `tinycap --` under su and reads its raw stdout (S16_LE mono at
 * [pcmRate]), low-passes and decimates it to [outRate], and hands out frames
 * through a blocking [read] with the same contract as AudioRecord.read, so
 * the capture loop does not care which of the two it has.
 */
class RootPcmCapture(
    private val card: Int,
    private val device: Int,
    private val pcmRate: Int,
    private val outRate: Int,
    private val setupCmd: String,
    private val restoreCmd: String,
) {
    private val factor = pcmRate / outRate
    private val taps = lowPassTaps(factor)
    private val history = FloatArray(taps.size)
    private var histPos = 0
    private var raw = ByteArray(0)

    @Volatile private var proc: Process? = null
    private var input: DataInputStream? = null

    val name: String get() = "PCM$card:$device@${pcmRate / 1000}k"

    /** Start tinycap.  Returns false if the process could not be launched. */
    fun start(): Boolean {
        require(pcmRate % outRate == 0) { "pcmRate $pcmRate is not a multiple of $outRate" }
        // exec, so destroying su's child leaves no tinycap behind holding the
        // PCM; stderr is dropped because only stdout is audio.  The setup
        // command must not write to stdout either: tinymix echoes the control
        // it set ("Incall Capture Stream0: DL", 27 bytes), and an odd byte
        // count ahead of the audio splits every sample across two — heard as
        // loud, heavily distorted capture.
        val cmd = buildString {
            if (setupCmd.isNotEmpty()) append("{ ").append(setupCmd).append("; } >/dev/null 2>&1; ")
            append("exec $TINYCAP -- -D $card -d $device -c 1 -r $pcmRate -b 16 2>/dev/null")
        }
        return try {
            val p = ProcessBuilder("su", "-c", cmd).start()
            p.outputStream.close()
            proc = p
            input = DataInputStream(p.inputStream.buffered(pcmRate / 50 * 2 * 4))
            Log.i(TAG, "Started $name -> ${outRate}Hz (decimate x$factor, ${taps.size} taps)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start $name: ${e.message}")
            false
        }
    }

    /**
     * Fill [buf] with [buf].size / 2 samples at [outRate].
     * Returns the byte count, or -1 once tinycap has exited.
     */
    fun read(buf: ByteArray): Int {
        val inp = input ?: return -1
        val outSamples = buf.size / 2
        val need = outSamples * factor * 2
        if (raw.size != need) raw = ByteArray(need)
        try {
            inp.readFully(raw)
        } catch (_: EOFException) {
            return -1
        } catch (e: Exception) {
            if (proc != null) Log.w(TAG, "$name read failed: ${e.message}")
            return -1
        }

        var o = 0
        var i = 0
        while (i < need) {
            history[histPos] = ((raw[i + 1].toInt() shl 8) or (raw[i].toInt() and 0xFF)).toFloat()
            histPos = (histPos + 1) % history.size
            i += 2
            if ((i / 2) % factor == 0) {
                // FIR over the newest taps.size input samples
                var acc = 0f
                var h = histPos
                for (t in taps) {
                    h = if (h == 0) history.size - 1 else h - 1
                    acc += t * history[h]
                }
                val s = acc.toInt().coerceIn(-32768, 32767)
                buf[o++] = (s and 0xFF).toByte()
                buf[o++] = ((s shr 8) and 0xFF).toByte()
            }
        }
        return o
    }

    /** Kill tinycap.  Fast and non-blocking, so it is safe on the main
     *  thread; it also unblocks a [read] in progress. */
    fun close() {
        val p = proc ?: return
        proc = null
        try { p.destroy() } catch (_: Exception) {}
        try { input?.close() } catch (_: Exception) {}
        input = null
    }

    /** Undo [setupCmd] and make sure no tinycap is left on the PCM.
     *  Goes through the root shell, so call it off the main thread. */
    fun restore() {
        close()
        val cmd = buildString {
            if (restoreCmd.isNotEmpty()) append(restoreCmd).append("; ")
            append("pkill -f '$TINYCAP -- -D $card -d $device ' 2>/dev/null; true")
        }
        RootShell.exec(cmd)
    }

    companion object {
        private const val TAG = "RootPcmCapture"
        const val TINYCAP = "/data/local/tmp/tinycap"

        /** Hamming-windowed sinc low-pass with its cutoff just under the
         *  output Nyquist, normalised to unity gain at DC. */
        private fun lowPassTaps(factor: Int): FloatArray {
            val n = 12 * factor + 1
            val fc = 0.45 / factor                 // cycles per input sample
            val mid = (n - 1) / 2.0
            val taps = DoubleArray(n) { k ->
                val x = k - mid
                val sinc = if (x == 0.0) 2 * fc else Math.sin(2 * Math.PI * fc * x) / (Math.PI * x)
                sinc * (0.54 - 0.46 * Math.cos(2 * Math.PI * k / (n - 1)))
            }
            val sum = taps.sum()
            return FloatArray(n) { (taps[it] / sum).toFloat() }
        }
    }
}
