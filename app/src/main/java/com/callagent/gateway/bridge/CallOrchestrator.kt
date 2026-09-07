package com.callagent.gateway.bridge

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.util.Log
import com.callagent.gateway.RootShell
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.rtp.RtpPacket
import com.callagent.gateway.rtp.RtpSession
import com.callagent.gateway.sip.SipCall
import com.callagent.gateway.sip.SipClient
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Orchestrates the bidirectional GSM ↔ SIP bridge.
 *
 * Two call flows:
 *
 * INBOUND (someone calls the Israeli SIM):
 *   1. GSM rings → keep ringing, place SIP call to Asterisk
 *   2. Asterisk/agent answers SIP → answer GSM call
 *   3. GSM goes active → RTP starts immediately
 *   4. Audio flows: GSM speaker/mic ↔ RTP/SIP (shared hardware)
 *   5. Either side hangs up → terminate both
 *
 *   Caller hears normal ringing until the agent is ready, then
 *   picks up and hears the agent immediately — no dead air.
 *
 * OUTBOUND (Asterisk wants to call an Israeli number):
 *   1. SIP INVITE arrives with X-GSM-Forward header
 *   2. Dial GSM call to the destination
 *   3. GSM answers → SIP 200 OK
 *   4. Audio flows: SIP RTP ↔ GSM speaker/mic (shared hardware)
 *   5. Either side hangs up → terminate both
 */
class CallOrchestrator(
    private val context: Context,
    private val sipClient: SipClient
) : SipClient.Listener, GsmCallManager.Listener, SipCall.Listener {

    private var activeRtpSession: RtpSession? = null
    private var activeSipCall: SipCall? = null
    private var activeGsmCall: Call? = null
    @Volatile private var diallerInitiated = false
    @Volatile private var lastStateChangeTime = 0L

    // Pending RTP info: saved when SIP answers before GSM is picked up.
    // onGsmCallActive reads these to start RTP immediately after GSM pickup.
    private var pendingRtpAddr: String? = null
    private var pendingRtpPort: Int = 0
    private var pendingPayloadType: Int = 0
    private var pendingLocalRtpPort: Int = 0

    // SIP call retry: if SIP fails while GSM is ringing, retry before giving up.
    // Transient network issues or socket races can kill the first attempt.
    private var sipCallRetries = 0
    private val MAX_SIP_RETRIES = 2

    /** Current bridge state */
    @Volatile var bridgeState: BridgeState = BridgeState.IDLE
        private set

    @Volatile var listener: OrchestratorListener? = null

    interface OrchestratorListener {
        fun onStateChanged(state: BridgeState, info: String)
        fun onError(error: String)
        fun onRtpStats(stats: String) {}
    }

    enum class BridgeState {
        IDLE,
        GSM_RINGING,        // Incoming GSM, waiting to answer
        GSM_ANSWERED,        // GSM answered, placing SIP call
        SIP_CALLING,         // SIP INVITE sent, waiting for answer
        SIP_RINGING,         // SIP ringing at Asterisk
        BRIDGED,             // Both sides active, audio flowing
        GSM_DIALING,         // Outbound: dialing GSM number
        TEARING_DOWN         // Hanging up
    }

    fun start() {
        sipClient.listener = this
        GsmCallManager.listener = this
        Log.i(TAG, "CallOrchestrator started")
    }

    fun stop() {
        tearDown("Orchestrator stopped")
        sipClient.listener = null
        GsmCallManager.listener = null
    }

    /** Initiate an outgoing GSM call from the dialler, then bridge to SIP */
    fun initiateDiallerCall(number: String) {
        if (bridgeState != BridgeState.IDLE) {
            // Check for stale state: if bridge has been non-IDLE for too long
            // without reaching BRIDGED, force a reset.  This happens on cold boot
            // when InCallService isn't bound yet and call events never arrive.
            val staleMs = System.currentTimeMillis() - lastStateChangeTime
            if (staleMs > STALE_STATE_TIMEOUT_MS) {
                Log.w(TAG, "Bridge stuck in $bridgeState for ${staleMs/1000}s — force resetting")
                forceReset("Stale state: $bridgeState for ${staleMs/1000}s")
            } else {
                Log.w(TAG, "Busy ($bridgeState) — cannot dial from dialler")
                listener?.onError("Busy — cannot dial")
                return
            }
        }
        Log.i(TAG, "Dialler-initiated call to $number")
        diallerInitiated = true
        lastStateChangeTime = System.currentTimeMillis()
        bridgeState = BridgeState.GSM_DIALING
        listener?.onStateChanged(bridgeState, "Dialing $number")
        GsmCallManager.makeCall(context, number)

        // Timeout: if GSM doesn't go active within 45s, tear down.
        // On cold boot, InCallService may not be bound, so call events
        // never arrive and the bridge gets stuck in GSM_DIALING.
        Thread({
            Thread.sleep(GSM_DIAL_TIMEOUT_MS)
            if (bridgeState == BridgeState.GSM_DIALING) {
                Log.w(TAG, "GSM dial timeout — no call events in ${GSM_DIAL_TIMEOUT_MS / 1000}s")
                tearDown("GSM dial timeout")
            }
        }, "GSM-Dial-Timeout").start()
    }

    // ── SipClient.Listener ──────────────────────────────

    override fun onRegistered() {
        Log.i(TAG, "SIP registered — ready for calls")
        listener?.onStateChanged(BridgeState.IDLE, "SIP registered")
    }

    override fun onRegistrationFailed() {
        Log.e(TAG, "SIP registration failed")
        listener?.onError("SIP registration failed")
    }

    /** Incoming SIP INVITE from Asterisk */
    override fun onIncomingCall(call: SipCall) {
        Log.i(TAG, "Incoming SIP call: ${call.callId}, gsm_forward=${call.gsmForwardNumber}")

        if (bridgeState != BridgeState.IDLE) {
            Log.w(TAG, "Busy — rejecting SIP call")
            call.hangup()
            return
        }

        val gsmDest = call.gsmForwardNumber
        if (gsmDest != null) {
            // OUTBOUND flow: Asterisk wants us to dial a GSM number
            handleOutboundFlow(call, gsmDest)
        } else {
            // Unexpected SIP call without forward header — answer anyway
            Log.w(TAG, "SIP INVITE without X-GSM-Forward header, answering directly")
            val rtpPort = allocateRtpPort()
            call.listener = this
            call.accept(rtpPort)
            activeSipCall = call
        }
    }

    /** Handles termination from both SipClient.Listener and SipCall.Listener */
    override fun onCallTerminated(call: SipCall) {
        Log.i(TAG, "SIP call terminated: ${call.callId} (bridge=$bridgeState, retries=$sipCallRetries)")
        if (call != activeSipCall) return

        // If GSM is still ringing and we haven't exhausted retries, try again.
        // Transient network issues or socket races can kill the first SIP attempt.
        if ((bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING)
            && sipCallRetries < MAX_SIP_RETRIES && activeGsmCall != null) {
            sipCallRetries++
            Log.w(TAG, "SIP call failed while GSM ringing — retrying ($sipCallRetries/$MAX_SIP_RETRIES)")
            listener?.onStateChanged(bridgeState, "SIP retry $sipCallRetries/$MAX_SIP_RETRIES")
            activeSipCall = null
            sipClient.removeCall(call.callId)
            // Retry after a short delay to let any transient issue settle
            Thread({
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
                if (bridgeState != BridgeState.SIP_CALLING && bridgeState != BridgeState.SIP_RINGING) return@Thread
                activeGsmCall?.let { handleInboundFlow(it) }
                    ?: Log.e(TAG, "SIP retry: GSM call gone, aborting")
            }, "SIP-Retry-$sipCallRetries").start()
            return
        }

        tearDown("SIP call ended")
    }

    // ── GsmCallManager.Listener ─────────────────────────

    /** Incoming GSM call — this is the INBOUND flow trigger */
    override fun onIncomingGsmCall(call: Call, number: String) {
        Log.i(TAG, "Incoming GSM call from $number")

        if (bridgeState != BridgeState.IDLE) {
            Log.w(TAG, "Busy — rejecting GSM call")
            GsmCallManager.rejectCall(call)
            return
        }

        sipCallRetries = 0
        bridgeState = BridgeState.GSM_RINGING
        activeGsmCall = call
        listener?.onStateChanged(bridgeState, "GSM call from $number")

        // Don't answer GSM yet — place SIP call to Asterisk first.
        // When the agent answers on SIP, we'll answer GSM so the caller
        // hears the agent immediately with no dead air.
        // The caller hears normal ringing in the meantime.
        Log.i(TAG, "GSM ringing from $number — placing SIP call first")
        Thread({ handleInboundFlow(call) }, "SIP-OutCall").start()
    }

    /** GSM call is now active (answered) */
    override fun onGsmCallActive(call: Call) {
        Log.i(TAG, "GSM call active")
        activeGsmCall = call

        when (bridgeState) {
            BridgeState.SIP_CALLING, BridgeState.SIP_RINGING -> {
                // INBOUND flow: GSM answered (triggered from onRtpReady).
                // SIP agent is ready — start RTP immediately so caller
                // hears the agent from the first moment.
                val addr = pendingRtpAddr
                val port = pendingRtpPort
                val pt = pendingPayloadType
                val localPort = pendingLocalRtpPort
                pendingRtpAddr = null

                if (addr != null && port > 0) {
                    Thread({
                        startRtp(localPort, addr, port, pt)
                        // Guard: tearDown may have run while startRtp was blocking
                        // (AudioRecord retries take 30+ seconds on cold boot).
                        // Don't overwrite IDLE — that causes "Busy" on next call.
                        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
                            Log.w(TAG, "Bridge torn down during RTP setup — not transitioning to BRIDGED")
                            return@Thread
                        }
                        bridgeState = BridgeState.BRIDGED
                        listener?.onStateChanged(bridgeState, "Bridged (inbound)")
                        Log.i(TAG, "Inbound bridge established — zero dead air")
                    }, "RTP-Start").start()
                } else {
                    // Edge case: GSM answered but SIP RTP info not ready yet.
                    // This shouldn't happen in normal flow since we answer GSM
                    // from onRtpReady, but handle gracefully.
                    Log.w(TAG, "GSM active but no pending RTP info — waiting for SIP")
                    bridgeState = BridgeState.GSM_ANSWERED
                }
            }
            BridgeState.GSM_DIALING -> {
                if (diallerInitiated) {
                    // DIALLER flow: GSM active → place SIP call to Asterisk (like inbound)
                    diallerInitiated = false
                    bridgeState = BridgeState.GSM_ANSWERED
                    listener?.onStateChanged(bridgeState, "GSM answered, calling Asterisk")
                    Thread({ handleInboundFlow(call) }, "SIP-OutCall").start()
                } else {
                    // SIP-initiated OUTBOUND flow: GSM destination answered → start audio bridge
                    bridgeState = BridgeState.BRIDGED
                    listener?.onStateChanged(bridgeState, "Bridged (outbound)")

                    // Answer the SIP call off the main thread
                    Thread({
                        activeSipCall?.let { sipCall ->
                            val rtpPort = allocateRtpPort()
                            sipCall.listener = this
                            sipCall.accept(rtpPort)

                            val addr = sipCall.remoteRtpAddress ?: sipClient.serverDomain
                            val port = sipCall.remoteRtpPort
                            val pt = sipCall.negotiatedPayloadType
                            if (port > 0) {
                                startRtp(rtpPort, addr, port, pt)
                            }
                        }
                        Log.i(TAG, "Outbound bridge established")
                    }, "SIP-Bridge").start()
                }
            }
            else -> {}
        }
    }

    override fun onGsmCallStateChanged(call: Call, state: Int) {
        val stateStr = when (state) {
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            else -> "OTHER($state)"
        }
        Log.d(TAG, "GSM state: $stateStr")

        // Track the GSM call object as soon as we see it, so teardown works
        // even if the call never reaches ACTIVE (e.g. wrong number, rejected)
        if (activeGsmCall == null && bridgeState != BridgeState.IDLE) {
            activeGsmCall = call
        }

        if (state == Call.STATE_DISCONNECTED && bridgeState != BridgeState.IDLE) {
            tearDown("GSM call disconnected")
        }
    }

    override fun onGsmCallEnded(call: Call) {
        Log.i(TAG, "GSM call ended")
        // Tear down if this is our tracked call, OR if we're in a call state
        // but activeGsmCall was never set (call failed before going ACTIVE)
        if (call == activeGsmCall ||
            (activeGsmCall == null && bridgeState != BridgeState.IDLE)) {
            tearDown("GSM call ended")
        }
    }

    // ── SipCall.Listener ────────────────────────────────

    override fun onCallAnswered(call: SipCall) {
        Log.i(TAG, "SIP call answered: ${call.callId}")
    }

    // onCallTerminated is already implemented above (shared by SipClient.Listener and SipCall.Listener)

    override fun onRtpReady(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) {
        val codecName = when (payloadType) {
            RtpPacket.PT_G722 -> "G.722"
            RtpPacket.PT_PCMA -> "PCMA"
            RtpPacket.PT_PCMU -> "PCMU"
            else -> "PT$payloadType"
        }
        Log.i(TAG, "RTP ready: $remoteRtpAddr:$remoteRtpPort codec=$codecName bridgeState=$bridgeState")

        if (bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING) {
            // Check if GSM is already active (dialler-initiated calls).
            // For inbound calls GSM is still ringing — answer it and wait for
            // onGsmCallActive to start RTP.  For dialler calls GSM is already
            // active so onGsmCallActive won't fire again — start RTP now.
            val gsmAlreadyActive = GsmCallManager.isCallActive

            if (gsmAlreadyActive) {
                Log.i(TAG, "SIP answered (codec=$codecName) — GSM already active, starting RTP now")
                val localRtpPort = call.localRtpPort
                Thread({
                    startRtp(localRtpPort, remoteRtpAddr, remoteRtpPort, payloadType)
                    if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
                        Log.w(TAG, "Bridge torn down during RTP setup — not transitioning to BRIDGED")
                        return@Thread
                    }
                    bridgeState = BridgeState.BRIDGED
                    listener?.onStateChanged(bridgeState, "Bridged (dialler)")
                    Log.i(TAG, "Dialler bridge established (codec=$codecName)")
                }, "RTP-Start").start()
            } else {
                // INBOUND flow: SIP/agent answered — save RTP info and answer GSM.
                // When GSM goes active (onGsmCallActive), RTP starts immediately
                // so the caller hears the agent from the first moment.
                pendingRtpAddr = remoteRtpAddr
                pendingRtpPort = remoteRtpPort
                pendingPayloadType = payloadType
                pendingLocalRtpPort = call.localRtpPort

                Log.i(TAG, "SIP answered (codec=$codecName) — answering GSM call now")
                activeGsmCall?.let { GsmCallManager.answerCall(it) }
                    ?: Log.e(TAG, "SIP answered but no active GSM call to answer!")
            }
        } else if (bridgeState == BridgeState.GSM_ANSWERED) {
            // Edge case: GSM was already answered (e.g. user picked up manually)
            // before SIP was ready.  Start RTP now.
            val localRtpPort = call.localRtpPort
            startRtp(localRtpPort, remoteRtpAddr, remoteRtpPort, payloadType)
            bridgeState = BridgeState.BRIDGED
            listener?.onStateChanged(bridgeState, "Bridged (inbound)")
            Log.i(TAG, "Bridge established (codec=$codecName)")
        } else {
            Log.w(TAG, "onRtpReady ignored — bridgeState=$bridgeState (expected SIP_CALLING or SIP_RINGING)")
            listener?.onError("RTP ready but bridge state wrong: $bridgeState")
            Log.i(TAG, "Inbound bridge established — GSM was already active (codec=$codecName)")
        }
    }

    // ── Inbound flow (GSM → SIP) ───────────────────────

    private fun handleInboundFlow(gsmCall: Call) {
        val callerNumber = gsmCall.details?.handle?.schemeSpecificPart ?: "unknown"
        Log.i(TAG, "Inbound flow: placing SIP call for GSM caller $callerNumber")

        bridgeState = BridgeState.SIP_CALLING
        listener?.onStateChanged(bridgeState, "Calling Asterisk for $callerNumber")

        val rtpPort = allocateRtpPort()
        val sipCall = sipClient.makeCall(
            targetExtension = sipClient.username, // call our own extension — Asterisk routes to agent
            localRtpPort = rtpPort,
            callerIdNumber = callerNumber,
            callerIdName = callerNumber
        )
        sipCall.listener = this
        activeSipCall = sipCall

        Log.i(TAG, "SIP INVITE sent to Asterisk (caller=$callerNumber, rtp=$rtpPort)")

        // Timeout: if Asterisk doesn't answer within 30s, tear down
        Thread({
            Thread.sleep(SIP_CALL_TIMEOUT_MS)
            if (bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING) {
                Log.w(TAG, "SIP call timeout — Asterisk didn't answer in ${SIP_CALL_TIMEOUT_MS / 1000}s")
                tearDown("Asterisk not answering")
            }
        }, "SIP-Timeout").start()
    }

    // ── Outbound flow (SIP → GSM) ──────────────────────

    private fun handleOutboundFlow(sipCall: SipCall, gsmDestination: String) {
        Log.i(TAG, "Outbound flow: dialing GSM $gsmDestination")

        bridgeState = BridgeState.GSM_DIALING
        activeSipCall = sipCall
        listener?.onStateChanged(bridgeState, "Dialing $gsmDestination")

        // Send 180 Ringing to SIP caller while GSM dials
        sipCall.originalInvite?.let { invite ->
            val ringing = com.callagent.gateway.sip.SipBuilder.ringing180(invite, sipCall.localTag)
            sipClient.sendTo(ringing, sipCall.remoteContactAddress ?: sipClient.serverAddress)
        }

        // Dial via GSM SIM
        GsmCallManager.makeCall(context, gsmDestination)
    }

    // ── RTP ─────────────────────────────────────────────

    private fun startRtp(localPort: Int, remoteAddr: String, remotePort: Int,
                         payloadType: Int = RtpPacket.PT_PCMA) {
        // Re-assert RECORD_AUDIO appops SYNCHRONOUSLY before AudioRecord
        // creation.  Must complete before RtpSession.start() so AudioFlinger
        // sees "allow" when the record thread begins reading.  Running async
        // caused a race: AudioRecord started reading silence (denied) before
        // the appops command finished.  RtpSession also periodically re-asserts
        // appops in its timeoutLoop for screen-off resilience.
        forceAllowRecordAudio()

        activeRtpSession?.stop()
        val session = RtpSession(context, localPort, remoteAddr, remotePort, payloadType)
        session.listener = object : RtpSession.Listener {
            override fun onRtpStarted() {
                Log.i(TAG, "RTP session started")
            }
            override fun onRtpStopped() {
                Log.i(TAG, "RTP session stopped")
            }
            override fun onRtpError(error: String) {
                Log.e(TAG, "RTP error: $error")
                listener?.onError("RTP: $error")
            }
            override fun onRtpTimeout() {
                Log.w(TAG, "RTP timeout — no audio from Asterisk, tearing down")
                tearDown("RTP timeout")
            }
            override fun onRtpStats(stats: String) {
                listener?.onRtpStats(stats)
            }
        }
        session.start()
        activeRtpSession = session
    }

    // ── Teardown ────────────────────────────────────────

    @Synchronized
    private fun tearDown(reason: String) {
        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) return
        bridgeState = BridgeState.TEARING_DOWN
        diallerInitiated = false
        Log.i(TAG, "Tearing down bridge: $reason")

        try {
            activeRtpSession?.stop()
            activeRtpSession = null

            activeSipCall?.let {
                try {
                    if (it.state != SipCall.State.TERMINATED) it.hangup()
                } catch (e: Exception) {
                    Log.e(TAG, "Error hanging up SIP: ${e.message}")
                }
                sipClient.removeCall(it.callId)
            }
            activeSipCall = null

            activeGsmCall?.let { call ->
                try {
                    // Always disconnect — not just when ACTIVE.  If the SIP
                    // call fails before GSM is answered, the ringing GSM call
                    // was left dangling (S4 Mini: "second call never answered").
                    // Call.disconnect() works for RINGING, DIALING, and ACTIVE.
                    call.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting GSM: ${e.message}")
                }
            }
            activeGsmCall = null
            pendingRtpAddr = null
        } finally {
            bridgeState = BridgeState.IDLE
            lastStateChangeTime = System.currentTimeMillis()
            listener?.onStateChanged(BridgeState.IDLE, reason)
            Log.i(TAG, "Bridge torn down: $reason")
        }
    }

    // ── Utility ─────────────────────────────────────────

    private fun allocateRtpPort(): Int {
        // Find a free UDP port in the 30000-40000 range
        for (port in 30000..40000 step 2) {
            try {
                DatagramSocket(null).use { sock ->
                    sock.reuseAddress = true
                    sock.bind(InetSocketAddress(port))
                    return port
                }
            } catch (_: Exception) {
                continue
            }
        }
        throw RuntimeException("No free RTP port available")
    }

    /**
     * Force-allow RECORD_AUDIO via appops using root (Magisk).
     *
     * Android's AppOpsService revokes RECORD_AUDIO (app op 27) for
     * foreground services when the screen is off.  This must be
     * re-asserted before EVERY call, not just at startup.
     *
     * CRITICAL: Must use --uid flag to set the UID-level mode.
     * `appops set <pkg>` sets the package mode, but AudioFlinger checks
     * the UID mode (set by PermissionController).  UID mode overrides
     * package mode, so without --uid the allow is ineffective on cold boot.
     */
    private fun forceAllowRecordAudio() {
        try {
            val pkg = context.packageName
            val t0 = System.currentTimeMillis()
            // Capture all output (2>&1) for diagnosis.  appops get is LAST
            // so exit code reflects verification, not a stray killall.
            val autoRevoke = if (Build.VERSION.SDK_INT >= 30)
                "appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>&1; " else ""
            val uidFlag = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
            val result = RootShell.execForOutput(
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "pm grant $pkg android.permission.RECORD_AUDIO 2>&1; " +
                autoRevoke +
                "appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                "appops set $pkg RECORD_AUDIO allow 2>&1; " +
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
            )
            val elapsed = System.currentTimeMillis() - t0
            val allowed = result.contains("allow", ignoreCase = true)
            Log.i(TAG, "appops RECORD_AUDIO: [$result] ok=$allowed (${elapsed}ms)")

            if (!allowed) {
                val fb = RootShell.execForOutput(
                    "cmd appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops set $pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
                )
                Log.w(TAG, "appops fallback cmd: [$fb]")
            } else {
                Log.d(TAG, "appops RECORD_AUDIO verified: allow")
            }
        } catch (e: Exception) {
            Log.w(TAG, "appops force-allow failed: ${e.message}")
        }
    }

    /** Force-reset bridge to IDLE, clearing all state.  Used to recover from
     *  stale states where the normal tearDown path was never triggered. */
    @Synchronized
    private fun forceReset(reason: String) {
        Log.w(TAG, "Force-resetting bridge: $reason")
        try {
            activeRtpSession?.stop()
        } catch (_: Exception) {}
        activeRtpSession = null
        try {
            activeSipCall?.let {
                if (it.state != SipCall.State.TERMINATED) it.hangup()
                sipClient.removeCall(it.callId)
            }
        } catch (_: Exception) {}
        activeSipCall = null
        try {
            activeGsmCall?.disconnect()
        } catch (_: Exception) {}
        activeGsmCall = null
        pendingRtpAddr = null
        diallerInitiated = false
        bridgeState = BridgeState.IDLE
        lastStateChangeTime = System.currentTimeMillis()
        listener?.onStateChanged(BridgeState.IDLE, reason)
        Log.i(TAG, "Bridge force-reset complete: $reason")
    }

    companion object {
        private const val TAG = "CallOrchestrator"
        private const val SIP_CALL_TIMEOUT_MS = 30_000L
        private const val GSM_DIAL_TIMEOUT_MS = 45_000L
        /** If bridge is non-IDLE for this long, consider it stale */
        private const val STALE_STATE_TIMEOUT_MS = 60_000L
    }
}
