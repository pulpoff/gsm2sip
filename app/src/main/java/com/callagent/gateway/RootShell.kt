package com.callagent.gateway

import android.os.SystemClock
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

    /** Incremented every time the shell is torn down.  A worker runs only
     *  while it is the current generation: tearing the shell down clears
     *  `alive`, but [init] sets it again for the replacement shell, and
     *  without this a worker that had not yet noticed the teardown would see
     *  `alive` back to true and keep consuming the queue.  Two workers
     *  interleaving commands on one shell is the corruption the teardown
     *  exists to prevent. */
    @Volatile private var generation = 0

    /** Set once the superuser manager has refused us, cleared when a command
     *  next succeeds.  Root can be revoked and re-granted while the app runs,
     *  so this is a current state, not a verdict. */
    @Volatile private var denied = false
    @Volatile private var lastInitAttemptMs = 0L

    /** Don't respawn `su` more than this often once it is being refused.
     *  Every command was opening a fresh `su` and having it die immediately,
     *  which cost a process per command and buried the reason in D-level logs. */
    private const val INIT_BACKOFF_MS = 10_000L

    /** Routes root availability changes to the app log viewer, like
     *  [com.callagent.gateway.gsm.GsmCallManager.logCallback].  Set by
     *  GatewayService.  A denial is the difference between a working gateway
     *  and one that answers calls it cannot bridge, so it belongs somewhere
     *  the operator will actually see it. */
    @Volatile var statusCallback: ((String) -> Unit)? = null

    /** Current root state, for diagnostics: "ok", "denied" or "unavailable". */
    fun rootState(): String = when {
        denied -> "denied"
        alive -> "ok"
        else -> "unavailable"
    }

    private fun reportDenied(detail: String) {
        if (denied) return
        denied = true
        val msg = "ROOT DENIED by the superuser manager ($detail) — mixer routing " +
            "and appops grants are unavailable, so calls will answer with no audio. " +
            "Grant root to this app in Magisk; it is picked up on the next call, " +
            "with no restart needed."
        Log.e(TAG, msg)
        statusCallback?.invoke(msg)
    }

    private fun reportRecovered() {
        denied = false
        val msg = "root access restored"
        Log.i(TAG, msg)
        statusCallback?.invoke(msg)
    }

    /** Ensure the persistent shell is running. Safe to call multiple times. */
    @Synchronized
    fun init() {
        if (alive && process != null) return
        // While root is being refused, opening `su` again only produces
        // another process that exits immediately.  Retry on a timer rather
        // than per command, so a denial costs one process every
        // INIT_BACKOFF_MS instead of one per mixer write — but keep retrying,
        // because root may be granted again without the app restarting.
        val now = SystemClock.elapsedRealtime()
        if (denied && now - lastInitAttemptMs < INIT_BACKOFF_MS) return
        lastInitAttemptMs = now
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
                        if (it.isNotBlank()) {
                            Log.d(TAG, "su stderr: $it")
                            // "Permission denied" here is su itself being
                            // refused, not a command failing — the shell is
                            // about to exit and every command will EPIPE.
                            val l = it.lowercase()
                            if (l.contains("permission denied") ||
                                l.contains("access denied") ||
                                l.contains("not allowed")
                            ) reportDenied(it.trim())
                        }
                    }
                } catch (_: Exception) {
                    // Shell went away; nothing to report.
                }
            }, "RootShell-Err").apply { isDaemon = true; start() }

            val myGeneration = generation
            workerThread = Thread({
                Log.i(TAG, "Root shell worker started (gen $myGeneration)")
                while (alive && generation == myGeneration) {
                    try {
                        val cmd = commandQueue.poll(5, TimeUnit.SECONDS) ?: continue
                        executeInternal(cmd)
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Worker error: ${e.message}")
                    }
                }
                Log.i(TAG, "Root shell worker exited (gen $myGeneration)")
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
        generation++
        // Interrupting ourselves would only set a flag on a thread that is
        // about to fall out of its loop anyway; the generation bump is what
        // actually stops it.
        workerThread?.takeIf { it != Thread.currentThread() }?.interrupt()
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
        var failure: String? = null
        try {
            val w = writer
            val r = reader
            if (w == null || r == null) {
                failure = "shell gone before: ${command.cmd.take(80)}"
                return
            }

            // Write command, then echo a unique marker + exit code
            w.write("${command.cmd}\necho \"${MARKER}\$?\"\n")
            w.flush()

            val sb = StringBuilder()
            var sawMarker = false
            while (true) {
                val line = r.readLine() ?: break
                if (line.startsWith(MARKER)) {
                    command.exitCode = line.removePrefix(MARKER).trim().toIntOrNull() ?: 0
                    sawMarker = true
                    break
                }
                sb.appendLine(line)
            }
            command.output = sb.toString().trimEnd()
            // readLine() returning null means the shell died mid-command: the
            // marker never arrived, so the exit code is meaningless and the
            // stream is no longer in a known state.
            if (!sawMarker) failure = "shell ended during: ${command.cmd.take(80)}"
        } catch (e: Exception) {
            failure = "${e.message} during: ${command.cmd.take(80)}"
        } finally {
            command.latch.countDown()
        }

        if (failure != null) {
            // Discard the shell instead of leaving it half-dead.  Clearing
            // `alive` on its own left the worker, the process and both streams
            // in place, so the next command was written into a shell nobody
            // was reading: from the first failure onwards every result came
            // back one command out of step, silently.  When su had been denied
            // outright it was worse — the app respawned `su` for every command
            // and never said why, and the only visible symptom was that calls
            // connected with no audio.
            Log.w(TAG, "Root shell failed — $failure")
            resetShell(failure)
        } else if (denied) {
            // A command got all the way through, so whatever was refusing us
            // has stopped.  Root can be re-granted while the app runs.
            reportRecovered()
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
        generation++
        workerThread?.takeIf { it != Thread.currentThread() }?.interrupt()
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
