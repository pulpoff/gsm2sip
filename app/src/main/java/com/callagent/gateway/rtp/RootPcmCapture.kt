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
 * Runs `tinycap --` under su and reads its raw stdout (S16LE mono at
 * [pcmRate]), low-passes and decimates it to [outRate], and hands out frames
 * through [read] under the [CaptureSource] contract.
 */
class RootPcmCapture(
    private val card: Int,
    private val device: Int,
    private val pcmRate: Int,
    private val outRate: Int,
    private val setupCmd: String,
    private val restoreCmd: String,
) : CaptureSource {

    init {
        require(outRate > 0 && pcmRate % outRate == 0) {
            "PCM rate $pcmRate is not a multiple of the capture rate $outRate"
        }
    }

    private val factor = pcmRate / outRate
    private val taps = lowPassTaps(factor)

    // Input samples as floats, with the previous frame's last taps.size-1
    // samples kept in front so the filter can run straight across the frame
    // boundary.  Sized on the first read, when the frame size is known.
    private var window = FloatArray(0)
    private var raw = ByteArray(0)

    @Volatile private var proc: Process? = null
    private var input: DataInputStream? = null

    override val name = "PCM$card:$device@${pcmRate / 1000}k"

    /**
     * Launch tinycap and wait for its first bytes.
     *
     * Returning only once audio has arrived is what makes a missing or
     * non-executable tinycap, or a denied su, fail here rather than on the
     * first read: su launches fine either way, so the launch alone proves
     * nothing.  Idempotent once running.
     */
    override fun start(): Boolean {
        if (proc != null) return true
        // exec, so destroying su's child leaves no tinycap behind holding the
        // PCM.  Nothing but tinycap may write to stdout: tinymix echoes the
        // control it set ("Incall Capture Stream0: DL", 27 bytes), and an odd
        // byte count ahead of the audio splits every sample across two —
        // heard as loud, heavily distorted capture.
        val cmd = buildString {
            if (setupCmd.isNotEmpty()) append("{ ").append(setupCmd).append("; } >/dev/null 2>&1; ")
            append("exec $TINYCAP -- -D $card -d $device -c 1 -r $pcmRate -b 16 2>/dev/null")
        }
        try {
            val p = ProcessBuilder("su", "-c", cmd).start()
            p.outputStream.close()
            val inp = DataInputStream(p.inputStream.buffered(pcmRate / 50 * 2 * 4))
            // One sample, discarded.  Blocks until tinycap writes or exits.
            inp.readFully(ByteArray(2))
            proc = p
            input = inp
            Log.i(TAG, "Started $name -> ${outRate}Hz (decimate x$factor, ${taps.size} taps)")
            return true
        } catch (e: Exception) {
            val why = if (e is EOFException) "exited before producing audio (tinycap missing, or su denied?)" else e.message
            Log.w(TAG, "Could not start $name: $why")
            return false
        }
    }

    override fun read(buf: ByteArray): Int {
        val inp = input ?: return -1
        val outSamples = buf.size / 2
        val inSamples = outSamples * factor
        val keep = taps.size - 1
        if (raw.size != inSamples * 2) {
            raw = ByteArray(inSamples * 2)
            window = FloatArray(keep + inSamples)
        }
        try {
            inp.readFully(raw)
        } catch (_: EOFException) {
            return -1
        } catch (e: Exception) {
            if (proc != null) Log.w(TAG, "$name read failed: ${e.message}")
            return -1
        }

        // Carry the tail of the last frame, append this one as floats.
        System.arraycopy(window, inSamples, window, 0, keep)
        var w = keep
        var i = 0
        while (i < raw.size) {
            window[w++] = ((raw[i + 1].toInt() shl 8) or (raw[i].toInt() and 0xFF)).toFloat()
            i += 2
        }

        // One FIR evaluation per output sample, over a contiguous run of the
        // window: no ring buffer, no modulo, nothing data-dependent.
        var o = 0
        for (k in 0 until outSamples) {
            val base = k * factor
            var acc = 0f
            for (t in taps.indices) acc += taps[t] * window[base + t]
            val s = acc.toInt().coerceIn(-32768, 32767)
            buf[o++] = (s and 0xFF).toByte()
            buf[o++] = ((s shr 8) and 0xFF).toByte()
        }
        return o
    }

    /** Kill tinycap.  Also unblocks a [read] in progress. */
    override fun close() {
        val p = proc ?: return
        proc = null
        try { p.destroy() } catch (_: Exception) {}
        try { input?.close() } catch (_: Exception) {}
        input = null
    }

    /** Undo [setupCmd] and make sure no tinycap is left on the PCM. */
    override fun release() {
        close()
        // Anchored, so the pattern does not match the shell that is running
        // this very command (its own command line contains the pattern).
        val cmd = buildString {
            if (restoreCmd.isNotEmpty()) append(restoreCmd).append("; ")
            append("pkill -f '^$TINYCAP -- -D $card -d $device ' 2>/dev/null; true")
        }
        RootShell.exec(cmd)
    }

    companion object {
        private const val TAG = "RootPcmCapture"
        /** Where the Magisk module's service.sh installs it. */
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
