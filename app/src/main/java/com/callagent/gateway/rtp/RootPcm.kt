package com.callagent.gateway.rtp

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One tinypcm process, run as root, streaming raw 16-bit mono PCM over its
 * stdio: capture arrives on [input], playback goes to [output].
 *
 * For devices whose call audio cannot be reached through the audio HAL at all
 * (see [com.callagent.gateway.DeviceProfile.rootPcm]).  The process is not
 * the su client the app spawns but a child of the su daemon, holding the very
 * pipes the app holds the other ends of, so closing those is what ends it: a
 * player sees end of file on stdin and a capturer a broken pipe on stdout.
 * That also holds if the app dies, which is why [stop] needs no root at all.
 */
class RootPcm private constructor(
    private val process: Process,
    val label: String,
) {
    val input: InputStream get() = process.inputStream
    val output: OutputStream get() = process.outputStream

    /** Whatever tinypcm has said on stderr since it started, newest last. */
    private val messages = ArrayDeque<String>()

    private fun note(line: String) = synchronized(messages) {
        messages.addLast(line)
        if (messages.size > 20) messages.removeFirst()
    }

    fun recentMessages(): String = synchronized(messages) { messages.joinToString(" | ") }

    fun stop() {
        try { process.outputStream.close() } catch (_: Exception) {}
        try { process.inputStream.close() } catch (_: Exception) {}
        process.destroy()
    }

    companion object {
        private const val TAG = "RootPcm"

        /**
         * Start `tinypcm <args>` and wait for it to report its PCM open.
         *
         * tinypcm prints one "tinypcm play|cap C<card>D<dev>: ..." line per PCM
         * once it is configured and prepared, so seeing [banners] of them means
         * the stream exists; anything else before the deadline (an "Error:"
         * line, or the process exiting) means it never will.  Returns null in
         * that case, with the reason logged.
         */
        fun start(tinypcm: String, args: String, label: String, banners: Int = 1,
                  timeoutMs: Long = 3000): RootPcm? {
            val process = try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "exec $tinypcm $args"))
            } catch (e: Exception) {
                Log.e(TAG, "$label: could not run su: ${e.message}")
                return null
            }
            val pcm = RootPcm(process, label)
            val ready = CountDownLatch(banners)
            val failed = CountDownLatch(1)
            Thread({
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).useLines { lines ->
                        for (line in lines) {
                            pcm.note(line)
                            Log.i(TAG, "$label: $line")
                            when {
                                line.startsWith("tinypcm play") || line.startsWith("tinypcm cap") ->
                                    ready.countDown()
                                line.startsWith("Error:") || line.startsWith("Usage:") ->
                                    failed.countDown()
                            }
                        }
                    }
                } catch (_: Exception) {}
                failed.countDown()  // stderr closed: the process is gone
            }, "RootPcm-$label").start()

            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                if (ready.await(20, TimeUnit.MILLISECONDS)) return pcm
                if (failed.count == 0L) break
            }
            Log.e(TAG, "$label did not start: ${pcm.recentMessages().ifEmpty { "no output" }}")
            pcm.stop()
            return null
        }
    }
}
