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
            Log.w(TAG, "Command timed out after ${timeoutMs}ms: ${cmd.take(80)}")
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
            Log.w(TAG, "Command timed out after ${timeoutMs}ms: ${cmd.take(80)}")
            return ""
        }
        return command.output
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
        try { writer?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        writer = null
        reader = null
        Log.i(TAG, "Root shell destroyed")
    }
}
