package com.callagent.gateway

import android.util.Log

/**
 * Reads `appops get --uid <pkg> RECORD_AUDIO` correctly.
 *
 * The output has two lines, the uid mode and the package mode:
 *
 *     Uid mode: RECORD_AUDIO: foreground
 *     RECORD_AUDIO: allow
 *
 * The uid mode wins when present.  The old check was `contains("allow")`,
 * which matched the package line and reported "verified: allow" while the
 * uid mode was still foreground.
 *
 * On recent Android (seen on 17, Pixel 7 Pro) the uid mode of RECORD_AUDIO is
 * derived from the runtime permission, which has no background variant, so
 * it stays `foreground` whatever root sets — `appops set --uid ... allow` is
 * accepted and has no effect.  Re-running the grant (which kills the
 * permission controller each time) every few seconds is then pure cost, so
 * once [PIN_AFTER] grants in a row have left the uid mode at foreground the
 * mode is treated as [pinned] for the life of the process.  One failed grant
 * is not enough: where the grant does work, a single one can still lose a
 * race with PermissionController re-revoking at boot.  Call capture does not depend on it:
 * the telephony sources are checked against RECORD_INCOMING_PHONE_AUDIO,
 * which the privileged app holds as `allow`.
 */
object RecordAudioOp {
    private const val TAG = "RecordAudioOp"

    data class Modes(val uid: String?, val pkg: String?) {
        val effective: String? get() = uid ?: pkg
    }

    private val uidRe = Regex("""Uid mode:\s*RECORD_AUDIO:\s*(\w+)""", RegexOption.IGNORE_CASE)
    private val pkgRe = Regex("""^\s*RECORD_AUDIO:\s*(\w+)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    fun parse(output: String): Modes = Modes(
        uid = uidRe.find(output)?.groupValues?.get(1)?.lowercase(),
        pkg = pkgRe.find(output)?.groupValues?.get(1)?.lowercase(),
    )

    fun isAllowed(output: String): Boolean = parse(output).effective == "allow"

    /** Grants in a row that must leave the uid mode at foreground before it
     *  is taken as held by the system. */
    const val PIN_AFTER = 3

    /** The system holds the uid mode at foreground and grants did not move it. */
    @Volatile var pinned = false
        private set

    @Volatile private var heldInARow = 0

    /** Nothing to do: allowed, or pinned at foreground by the system. */
    fun satisfied(probe: String): Boolean {
        val m = parse(probe)
        return m.effective == "allow" || (pinned && m.uid == "foreground")
    }

    /** Evaluate the readback after a grant; marks [pinned] once [PIN_AFTER]
     *  grants in a row left the uid mode at foreground.  Returns whether it
     *  is allowed. */
    @Synchronized
    fun afterGrant(result: String): Boolean {
        val m = parse(result)
        if (m.effective == "allow") {
            heldInARow = 0
            return true
        }
        if (m.uid != "foreground") {
            heldInARow = 0
            return false
        }
        heldInARow++
        if (heldInARow >= PIN_AFTER && !pinned) {
            pinned = true
            log("RECORD_AUDIO uid mode is held at 'foreground' by the system " +
                "($heldInARow grants in a row, package mode ${m.pkg}); not re-granting " +
                "again.  Call capture uses RECORD_INCOMING_PHONE_AUDIO.")
        }
        return false
    }

    /** android.util.Log is a stub on the JVM test classpath. */
    private fun log(msg: String) {
        try { Log.i(TAG, msg) } catch (_: RuntimeException) {}
    }

    /** For tests. */
    internal fun resetForTest() { pinned = false; heldInARow = 0 }
}
