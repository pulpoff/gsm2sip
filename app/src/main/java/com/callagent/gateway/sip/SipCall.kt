package com.callagent.gateway.sip

import android.util.Log

/**
 * Represents one SIP call dialog.
 * Tracks dialog state (tags, CSeq, contact) and provides methods to
 * send in-dialog requests (ACK, BYE).
 */
class SipCall(
    val callId: String,
    val direction: Direction,
    private val sipClient: SipClient
) {
    enum class Direction { INBOUND, OUTBOUND }
    enum class State { TRYING, RINGING, ANSWERED, TERMINATED }

    var state: State = State.TRYING
        private set

    /** Set when any SIP response is received — stops INVITE retransmission (Timer A) */
    @Volatile var responseReceived = false

    // Dialog identifiers
    var localTag: String = "gw${(100000000..999999999).random()}"
    var remoteTag: String? = null
    var fromHeader: String? = null
    var toHeader: String? = null

    // Remote endpoint
    var remoteContactUri: String? = null
    var remoteContactAddress: Pair<String, Int>? = null

    // CSeq tracking
    var localCseq: Int = 1
    var remoteCseq: Int = 0

    // Auth: prevent re-sending credentials on duplicate/retransmitted 401s.
    // UDP can retransmit the server's 401, causing us to send a SECOND
    // authenticated INVITE (CSeq N+1) which gets 491 (Request Pending)
    // because the server is already processing the first one (CSeq N).
    @Volatile var authHandled = false

    // RTP endpoints
    var localRtpPort: Int = 0
    var remoteRtpPort: Int = 0
    var remoteRtpAddress: String? = null
    var negotiatedPayloadType: Int = 8 // default PCMA, updated from SDP

    // Caller info (for inbound and outbound caller-ID)
    var callerNumber: String? = null
    var callerDisplayName: String? = null
    // Outbound caller-ID (preserved for auth re-INVITE)
    var outboundCallerIdNumber: String? = null
    var outboundCallerIdName: String? = null

    // GSM forward target (for outbound from server)
    var gsmForwardNumber: String? = null

    // Original INVITE (for building responses)
    var originalInvite: SipMessage? = null

    var listener: Listener? = null

    interface Listener {
        fun onCallAnswered(call: SipCall)
        fun onCallTerminated(call: SipCall)
        fun onRtpReady(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int)
    }

    /** Process incoming SIP message for this dialog */
    fun handleMessage(msg: SipMessage): Boolean {
        // Stop INVITE retransmission as soon as any response arrives
        if (msg.isResponse) responseReceived = true

        when {
            // 200 OK to our INVITE
            msg.isResponse && msg.statusCode == 200 && msg.cseq?.contains("INVITE") == true -> {
                remoteTag = msg.toTag
                toHeader = msg.to
                msg.contactUri?.let { remoteContactUri = it }
                msg.contactAddress?.let { remoteContactAddress = it }
                msg.sdpRtpPort?.let { remoteRtpPort = it }
                msg.sdpAddress?.let { remoteRtpAddress = it }
                negotiatedPayloadType = msg.sdpPreferredPayloadType

                // ACK must use the same CSeq as the INVITE being acknowledged
                val ackCseq = msg.cseq?.split(" ")?.firstOrNull()?.toIntOrNull() ?: localCseq
                sendAck(ackCseq)

                // Guard against duplicate 200 OK (Asterisk retransmits until ACK)
                if (state == State.ANSWERED) {
                    Log.i(TAG, "Duplicate 200 OK for call $callId (already answered), ACKed")
                    return true
                }

                Log.i(TAG, "SDP codec: pt=$negotiatedPayloadType codecs=${msg.sdpCodecs}")
                state = State.ANSWERED

                val addr = remoteRtpAddress ?: remoteContactAddress?.first
                Log.i(TAG, "200 OK RTP: addr=$addr port=$remoteRtpPort listener=${listener != null}")
                if (addr != null && remoteRtpPort > 0) {
                    listener?.onRtpReady(this, addr, remoteRtpPort, negotiatedPayloadType)
                } else {
                    Log.w(TAG, "200 OK missing RTP info — addr=$addr port=$remoteRtpPort, cannot bridge")
                    sipClient.logListener?.invoke("200 OK missing RTP: addr=$addr port=$remoteRtpPort")
                }
                listener?.onCallAnswered(this)
                return true
            }

            // 100 Trying
            msg.isResponse && msg.statusCode == 100 -> {
                state = State.TRYING
                return true
            }

            // 180 Ringing
            msg.isResponse && msg.statusCode == 180 -> {
                state = State.RINGING
                remoteTag = msg.toTag
                return true
            }

            // 401/407 Auth required for INVITE
            msg.isResponse && (msg.statusCode == 401 || msg.statusCode == 407) -> {
                if (authHandled) {
                    // UDP retransmission of the original 401 — ignore it.
                    // We already sent an authenticated re-INVITE; sending another
                    // would create a duplicate CSeq that gets 491 (Request Pending).
                    Log.i(TAG, "Ignoring duplicate ${msg.statusCode} — already re-sent with credentials")
                    return true
                }
                val authParams = SipAuth.parseChallenge(msg)
                if (authParams != null) {
                    authHandled = true
                    Log.i(TAG, "INVITE auth challenge, re-sending with credentials")
                    sipClient.resendInviteWithAuth(this, authParams)
                }
                return true
            }

            // Incoming BYE
            msg.isRequest && msg.method == "BYE" -> {
                Log.i(TAG, "Received BYE for call $callId")
                // Send 200 OK to BYE
                sipClient.sendResponse(
                    SipBuilder.ok200(msg, sipClient.username, sipClient.publicIp, sipClient.localPort),
                    remoteContactAddress ?: sipClient.serverAddress
                )
                state = State.TERMINATED
                listener?.onCallTerminated(this)
                return true
            }

            // 183 Session Progress (early media)
            msg.isResponse && msg.statusCode == 183 -> {
                state = State.RINGING
                remoteTag = msg.toTag
                Log.i(TAG, "183 Session Progress for call $callId")
                return true
            }

            // 4xx/5xx/6xx error responses — log and terminate
            msg.isResponse && msg.statusCode != null && msg.statusCode!! >= 300 -> {
                Log.e(TAG, "SIP error ${msg.statusCode} for call $callId (CSeq: ${msg.cseq})")
                // ACK the error response (required by RFC 3261 for INVITE transactions)
                if (msg.cseq?.contains("INVITE") == true) {
                    val ackCseq = msg.cseq?.split(" ")?.firstOrNull()?.toIntOrNull() ?: localCseq
                    val uri = remoteContactUri ?: "sip:${sipClient.serverDomain}:${sipClient.serverPort}"
                    val ack = SipBuilder.ack(
                        uri, null, msg.to, fromHeader,
                        callId, ackCseq,
                        sipClient.username, sipClient.publicIp, sipClient.localPort
                    )
                    sipClient.sendResponse(ack, remoteContactAddress ?: sipClient.serverAddress)
                }
                // Ignore error responses once the dialog is established (200 OK
                // received).  This handles the case where a duplicate INVITE
                // (CSeq N+1, sent because a retransmitted 401 was treated as a
                // new challenge) gets a 491 (Request Pending) AFTER the original
                // INVITE's 200 OK already established the dialog.  Without this
                // guard, the 491 tears down an active call.
                if (state == State.ANSWERED) {
                    Log.i(TAG, "Ignoring ${msg.statusCode} — call already answered (CSeq: ${msg.cseq})")
                    return true
                }
                sipClient.logListener?.invoke("INVITE rejected: ${msg.statusCode} (call $callId)")
                state = State.TERMINATED
                listener?.onCallTerminated(this)
                return true
            }

            // ACK (for our 200 OK)
            msg.isRequest && msg.method == "ACK" -> {
                Log.d(TAG, "Received ACK for call $callId")
                return true
            }

            else -> {
                Log.w(TAG, "Unhandled SIP message for call $callId: ${msg.startLine}")
                return false
            }
        }
    }

    /** Accept an inbound INVITE: send 200 OK with SDP */
    fun accept(localRtpPort: Int) {
        this.localRtpPort = localRtpPort
        val invite = originalInvite ?: return
        val toTag = localTag

        val ok = SipBuilder.ok200(
            invite, sipClient.username, sipClient.publicIp, sipClient.localPort,
            localRtpPort = localRtpPort, toTag = toTag
        )

        val address = invite.contactAddress ?: sipClient.serverAddress
        sipClient.sendResponse(ok, address)

        state = State.ANSWERED
        Log.i(TAG, "Sent 200 OK for inbound call $callId (RTP port: $localRtpPort)")
    }

    /** Send ACK for a received 200 OK */
    private fun sendAck(cseq: Int) {
        val uri = remoteContactUri ?: return
        val ack = SipBuilder.ack(
            uri, null, toHeader, fromHeader,
            callId, cseq,
            sipClient.username, sipClient.publicIp, sipClient.localPort
        )
        sipClient.sendResponse(ack, remoteContactAddress ?: sipClient.serverAddress)
        Log.d(TAG, "Sent ACK for call $callId (CSeq: $cseq)")
    }

    /** Send BYE to terminate the call */
    fun hangup() {
        if (state == State.TERMINATED) return
        val uri = remoteContactUri ?: "sip:${sipClient.serverDomain}:${sipClient.serverPort}"

        val bye = SipBuilder.bye(
            uri, fromHeader, toHeader,
            callId, localCseq++,
            sipClient.username, sipClient.publicIp, sipClient.localPort
        )
        sipClient.sendResponse(bye, remoteContactAddress ?: sipClient.serverAddress)
        state = State.TERMINATED
        Log.i(TAG, "Sent BYE for call $callId")
        listener?.onCallTerminated(this)
    }

    companion object {
        private const val TAG = "SipCall"
    }
}
