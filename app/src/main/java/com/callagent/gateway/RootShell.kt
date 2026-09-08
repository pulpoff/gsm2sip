package com.callagent.gateway

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue

/**
 * Persistent root shell — opens `su` once and reuses it for all commands.
 * Eliminates repeated Magisk superuser popups.
 *
 * Usage:
 *   RootShell.exec("appops set --uid com.callagent.gateway RECORD_AUDIO allow")
 *   val output = RootShell.execForOutput("tinymix 2>&1 | grep -i Incall")
 */
object RootShell {
    private const val TAG = "RootShell"
    private const val MARKER = "___ROOT_SHELL_DONE___"

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null

    // Serialize all commands through one thread to avoid interleaving
    private data class Command(
        val cmd: String,
        val latch: CountDownLatch,
        var output: String = "",
        var exitCode: Int = -1
    )
    private val commandQueue = LinkedBlockingQueue<Command>()
    @Volatile private var workerThread: Thread? = null
    @Volatile private var alive = false

    /** Ensure the persistent shell is running. Safe to call multiple times. */
    @Synchronized
    fun init() {
        if (alive && process != null) return
        try {
            val proc = Runtime.getRuntime().exec("su")
            process = proc
            writer = OutputStreamWriter(proc.outputStream)
            reader = BufferedReader(InputStreamReader(proc.inputStream))
            alive = true

            // Drain stderr, or it will eventually block the shell.  su's
            // stderr is a pipe with a kernel buffer of a few dozen KB and
            // nothing was ever reading it: any command that does not redirect
            // — most of the capture diagnostics do not — fills it and then
            // blocks writing, and the command hangs until its timeout.
            // Kept separate from stdout rather than merged, so the marker
            // protocol and every caller that parses output stay unaffected.
            Thread({
                try {
                    BufferedReader(InputStreamReader(proc.errorStream)).forEachLine {
                        if (it.isNotBlank()) Log.d(TAG, "su stderr: $it")
                    }
                } catch (_: Exception) {
                    // Shell went away; nothing to report.
                }
            }, "RootShell-Err").apply { isDaemon = true; start() }

            workerThread = Thread({
                Log.i(TAG, "Root shell worker started")
                while (alive) {
                    try {
                        val cmd = commandQueue.poll(5, TimeUnit.SECONDS) ?: continue
                        executeInternal(cmd)
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Worker error: ${e.message}")
                    }
                }
                Log.i(TAG, "Root shell worker exited")
            }, "RootShell-Worker").apply { isDaemon = true; start() }

            Log.i(TAG, "Persistent root shell opened")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open root shell: ${e.message}")
            alive = false
        }
    }

    /** Run a command, wait up to [timeoutMs] for completion. Returns exit code. */
    fun exec(cmd: String, timeoutMs: Long = 5000): Int {
        if (!alive) init()
        if (!alive) {
            // Fallback: try one-shot su -c
            return execFallback(cmd)
        }
        val command = Command(cmd, CountDownLatch(1))
        commandQueue.put(command)
        if (!command.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            resetShell("exec timed out after ${timeoutMs}ms: ${cmd.take(80)}")
            return -1
        }
        return command.exitCode
    }

    /** Run a command and return its stdout. */
    fun execForOutput(cmd: String, timeoutMs: Long = 5000): String {
        if (!alive) init()
        if (!alive) return execFallbackOutput(cmd)
        val command = Command(cmd, CountDownLatch(1))
        commandQueue.put(command)
        if (!command.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            resetShell("execForOutput timed out after ${timeoutMs}ms: ${cmd.take(80)}")
            return ""
        }
        return command.output
    }

    /**
     * Tear the shell down and release everyone waiting on it.
     *
     * Giving up on a command is not enough on its own: the worker is still
     * blocked in readLine() waiting for that command's marker, so the next
     * command gets written into the same shell and the worker hands it the
     * *previous* command's trailing output.  From the first timeout onwards
     * every result is one command out of step, silently.  The only safe move
     * is to discard the shell; the next call opens a fresh one.
     */
    @Synchronized
    private fun resetShell(reason: String) {
        Log.w(TAG, "Resetting root shell — $reason")
        alive = false
        workerThread?.interrupt()
        workerThread = null
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        writer = null
        reader = null
        // Anything still queued was destined for the shell we just discarded.
        while (true) {
            val pending = commandQueue.poll() ?: break
            pending.exitCode = -1
            pending.latch.countDown()
        }
    }

    private fun executeInternal(command: Command) {
        try {
            val w = writer ?: return
            val r = reader ?: return

            // Write command, then echo a unique marker + exit code
            w.write("${command.cmd}\necho \"${MARKER}\$?\"\n")
            w.flush()

            val sb = StringBuilder()
            while (true) {
                val line = r.readLine() ?: break
                if (line.startsWith(MARKER)) {
                    command.exitCode = line.removePrefix(MARKER).trim().toIntOrNull() ?: 0
                    break
                }
                sb.appendLine(line)
            }
            command.output = sb.toString().trimEnd()
        } catch (e: Exception) {
            Log.e(TAG, "Execute error: ${e.message}")
            alive = false
        } finally {
            command.latch.countDown()
        }
        // readLine() returning null means the shell died mid-command; the
        // marker never arrived, so the exit code is meaningless and the
        // stream is no longer in a known state.
        if (command.exitCode == -1 && !alive) {
            Log.w(TAG, "Shell ended during: ${command.cmd.take(80)}")
        }
    }

    /** Fallback for when persistent shell fails — single su -c call */
    private fun execFallback(cmd: String): Int {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            if (proc.waitFor(5, TimeUnit.SECONDS)) proc.exitValue() else -1
        } catch (e: Exception) {
            Log.w(TAG, "Fallback exec failed: ${e.message}")
            -1
        }
    }

    private fun execFallbackOutput(cmd: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(5, TimeUnit.SECONDS)
            out
        } catch (e: Exception) {
            Log.w(TAG, "Fallback exec failed: ${e.message}")
            ""
        }
    }

    fun destroy() {
        alive = false
        workerThread?.interrupt()
        workerThread = null
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        writer = null
        reader = null
        Log.i(TAG, "Root shell destroyed")
    }
}
