package com.callagent.gateway.sip

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SIP client: handles UDP transport, registration, and call routing.
 * Ported from the Python SIPClient in sip/sip.py
 */
class SipClient(
    val username: String,
    private val password: String,
    val serverDomain: String,
    val serverPort: Int = 5060,
    var localIp: String = "0.0.0.0",
    val localPort: Int = 5060,
    /** Public IP discovered via STUN — used in Contact headers and SDP for NAT traversal */
    var publicIp: String = localIp
) {
    val serverAddress: Pair<String, Int> get() = Pair(serverDomain, serverPort)

    private var socket: DatagramSocket? = null
    /** Pre-resolved server address — avoids DNS on main thread */
    @Volatile private var resolvedServerAddr: InetAddress? = null
    private val cseq = AtomicInteger(1)
    private var callIdBase = "${System.currentTimeMillis() / 1000}@$publicIp"
    private val running = AtomicBoolean(false)
    @Volatile var registered = false; private set
    @Volatile private var lastRegisterTime = 0L
    /** Tracks last time we got ANY response from server (REGISTER, OPTIONS, etc.) */
    @Volatile private var lastServerResponseTime = 0L
    @Volatile private var registrationLatch: CountDownLatch? = null

    private val activeCalls = ConcurrentHashMap<String, SipCall>()
    /** Single-thread executor for all socket sends — avoids NetworkOnMainThreadException */
    private var sendExecutor: ExecutorService? = null

    @Volatile var listener: Listener? = null
    /** Log callback — forwards key SIP events to the UI */
    @Volatile var logListener: ((String) -> Unit)? = null

    private fun uiLog(msg: String) {
        Log.i(TAG, msg)
        logListener?.invoke(msg)
    }

    interface Listener {
        fun onRegistered()
        fun onRegistrationFailed()
        /** Incoming INVITE from Asterisk — either a new call or a GSM-forward request */
        fun onIncomingCall(call: SipCall)
        fun onCallTerminated(call: SipCall)
    }

    // ── Socket ──────────────────────────────────────────

    @Synchronized
    fun start() {
        if (running.get()) return
        running.set(true)
        callIdBase = "${System.currentTimeMillis() / 1000}@$publicIp"
        createSocket()

        Thread({
            try { receiveLoop() }
            catch (e: Exception) { uiLog("Receive loop crashed: ${e.message}") }
        }, "SIP-Recv").start()
        Thread({
            try { monitorLoop() }
            catch (e: Exception) { uiLog("Monitor loop crashed: ${e.message}") }
        }, "SIP-Monitor").start()
        Thread({
            try { register() }
            catch (e: Exception) { uiLog("Register thread crashed: ${e.message}") }
        }, "SIP-Register").start()
    }

    @Synchronized
    fun stop() {
        running.set(false)
        registered = false
        activeCalls.values.forEach { it.hangup() }
        activeCalls.clear()
        sendExecutor?.shutdownNow()
        sendExecutor = null
        socket?.close()
        socket = null
        resolvedServerAddr = null
    }

    private fun createSocket() {
        socket?.close()
        sendExecutor?.shutdownNow()
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(localPort))
        s.soTimeout = 5000
        s.receiveBufferSize = 65535
        s.sendBufferSize = 65535
        socket = s
        sendExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "SIP-Send") }
        // Resolve server DNS now (background thread) so sendTo never blocks on DNS
        resolvedServerAddr = InetAddress.getByName(serverDomain)
        uiLog("Socket bound to $localIp:$localPort")
    }

    // ── Send ────────────────────────────────────────────

    fun sendTo(data: String, address: Pair<String, Int>) {
        val callerThread = Thread.currentThread().name
        val executor = sendExecutor
        if (executor == null) {
            uiLog("sendTo: executor is NULL, caller=$callerThread — sending on new thread")
            Thread({
                doSend(data, address)
            }, "SIP-FallbackSend").start()
            return
        }
        executor.execute {
            Log.d(TAG, "sendTo: caller=$callerThread, sender=${Thread.currentThread().name}")
            doSend(data, address)
        }
    }

    private fun doSend(data: String, address: Pair<String, Int>) {
        try {
            // Explicitly permit network on this thread (belt-and-suspenders)
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder().permitAll().build()
            )
            val bytes = data.toByteArray()
            // Use cached address for server to avoid DNS on main thread
            val addr = if (address.first == serverDomain) {
                resolvedServerAddr ?: InetAddress.getByName(address.first)
            } else {
                InetAddress.getByName(address.first)
            }
            val packet = DatagramPacket(bytes, bytes.size, addr, address.second)
            socket?.send(packet)
        } catch (e: Exception) {
            uiLog("Send error to ${address.first}:${address.second} [thread=${Thread.currentThread().name}]: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun sendResponse(data: String, address: Pair<String, Int>) = sendTo(data, address)

    // ── Receive Loop ────────────────────────────────────

    private fun receiveLoop() {
        val buf = ByteArray(4096)
        while (running.get()) {
            try {
                val s = socket ?: break
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                val data = String(packet.data, 0, packet.length)
                val address = Pair(packet.address.hostAddress ?: "", packet.port)
                handlePacket(data, address)
            } catch (_: SocketTimeoutException) {
                // normal
            } catch (e: Exception) {
                if (running.get()) uiLog("Receive error: ${e.message}")
            }
        }
    }

    private fun handlePacket(data: String, address: Pair<String, Int>) {
        val msg = SipMessage.parse(data) ?: return

        // OPTIONS keepalive from server
        if (msg.isRequest && msg.method == "OPTIONS") {
            val resp = SipBuilder.optionsResponse(msg, username, publicIp, localPort)
            sendTo(resp, address)
            return
        }

        // Registration responses
        if (msg.isResponse && msg.cseq?.contains("REGISTER") == true) {
            handleRegisterResponse(msg)
            return
        }

        // OPTIONS response (for our keepalive) — update server liveness tracker
        if (msg.isResponse && msg.cseq?.contains("OPTIONS") == true) {
            lastServerResponseTime = System.currentTimeMillis()
            return
        }

        // Route to existing call
        val callId = msg.callId
        if (callId != null) {
            val call = activeCalls[callId]
            if (call != null) {
                // Log non-trivial SIP responses for debugging
                if (msg.isResponse && msg.statusCode != 100) {
                    uiLog("SIP ${msg.statusCode} for INVITE (call $callId)")
                }
                val handled = call.handleMessage(msg)
                if (!handled) {
                    Log.w(TAG, "Unhandled SIP message for call $callId: ${msg.startLine}")
                }
                if (call.state == SipCall.State.TERMINATED) {
                    activeCalls.remove(callId)
                    listener?.onCallTerminated(call)
                }
                return
            }
        }

        // New INVITE
        if (msg.isRequest && msg.method == "INVITE") {
            handleIncomingInvite(msg, address)
            return
        }

        // Stray response for unknown call
        if (msg.isResponse) {
            Log.w(TAG, "Stray SIP response ${msg.statusCode} for unknown call-id=$callId (${msg.cseq})")
            return
        }

        // Stray ACK
        if (msg.isRequest && msg.method == "ACK") {
            // route to call if exists
            if (callId != null) activeCalls[callId]?.handleMessage(msg)
            return
        }

        // Stray BYE
        if (msg.isRequest && msg.method == "BYE") {
            if (callId != null) {
                activeCalls[callId]?.handleMessage(msg)
                activeCalls.remove(callId)
            }
            // Send 200 OK even for unknown BYE
            val ok = SipBuilder.ok200(msg, username, publicIp, localPort)
            sendTo(ok, address)
            return
        }
    }

    // ── Registration ────────────────────────────────────

    /**
     * Send REGISTER and wait for receiveLoop() to handle the response.
     * Uses a CountDownLatch so only receiveLoop() reads from the socket
     * (eliminates the old race condition with waitForRegistration).
     */
    private fun register(): Boolean {
        for (attempt in 1..3) {
            if (!running.get()) return false
            uiLog("REGISTER attempt $attempt/3")
            registrationLatch = CountDownLatch(1)
            sendRegister()
            // Wait up to 10s for receiveLoop → handleRegisterResponse to signal
            try {
                registrationLatch?.await(10, TimeUnit.SECONDS)
            } catch (_: InterruptedException) { /* stop requested */ }
            if (registered) return true
            if (!running.get()) return false
            Thread.sleep(2000)
        }
        uiLog("Registration failed after 3 attempts")
        listener?.onRegistrationFailed()
        return false
    }

    private fun sendRegister(auth: String? = null) {
        val msg = SipBuilder.register(
            username, serverDomain, serverPort,
            publicIp, localPort,
            callIdBase, cseq.getAndIncrement(),
            auth
        )
        sendTo(msg, serverAddress)
    }

    /** Called by receiveLoop() — no socket reads here. */
    private fun handleRegisterResponse(msg: SipMessage): Boolean {
        return when (msg.statusCode) {
            200 -> {
                registered = true
                lastRegisterTime = System.currentTimeMillis()
                lastServerResponseTime = lastRegisterTime
                uiLog("Registered with $serverDomain")
                registrationLatch?.countDown()
                listener?.onRegistered()
                true
            }
            401, 407 -> {
                uiLog("REGISTER ${msg.statusCode} challenge, sending auth")
                val authParams = SipAuth.parseChallenge(msg)
                if (authParams != null) {
                    val uri = "sip:$serverDomain:$serverPort"
                    val auth = SipAuth.buildAuthHeader("REGISTER", uri, username, password, authParams)
                    sendRegister(auth)
                } else {
                    uiLog("Failed to parse auth challenge")
                    registrationLatch?.countDown()
                }
                false
            }
            else -> {
                uiLog("REGISTER unexpected response: ${msg.statusCode}")
                registrationLatch?.countDown()
                false
            }
        }
    }

    // ── Incoming INVITE ─────────────────────────────────

    private fun handleIncomingInvite(msg: SipMessage, address: Pair<String, Int>) {
        val callId = msg.callId ?: return
        Log.i(TAG, "Incoming INVITE call-id=$callId from=${msg.callerNumber}")

        // Send 100 Trying
        sendTo(SipBuilder.trying100(msg), address)

        val call = SipCall(callId, SipCall.Direction.INBOUND, this)
        call.originalInvite = msg
        call.callerNumber = msg.callerNumber
        call.callerDisplayName = msg.callerDisplayName
        call.remoteContactUri = msg.contactUri
        call.remoteContactAddress = msg.contactAddress ?: address
        call.remoteCseq = msg.cseq?.split(" ")?.firstOrNull()?.toIntOrNull() ?: 1
        call.fromHeader = msg.from
        call.toHeader = msg.to
        call.remoteTag = msg.fromTag

        // Parse SDP
        msg.sdpRtpPort?.let { call.remoteRtpPort = it }
        msg.sdpAddress?.let { call.remoteRtpAddress = it }
        call.negotiatedPayloadType = msg.sdpPreferredPayloadType
        Log.i(TAG, "Incoming INVITE codec: pt=${call.negotiatedPayloadType} codecs=${msg.sdpCodecs}")

        // Check for GSM-forward header
        call.gsmForwardNumber = msg.gsmForwardNumber

        activeCalls[callId] = call
        listener?.onIncomingCall(call)
    }

    // ── Outbound INVITE ─────────────────────────────────

    /** Place an outbound SIP call (gateway → Asterisk) */
    fun makeCall(
        targetExtension: String,
        localRtpPort: Int,
        callerIdNumber: String? = null,
        callerIdName: String? = null
    ): SipCall {
        val callId = "${System.currentTimeMillis()}call@$publicIp"
        val call = SipCall(callId, SipCall.Direction.OUTBOUND, this)
        call.localRtpPort = localRtpPort
        call.outboundCallerIdNumber = callerIdNumber
        call.outboundCallerIdName = callerIdName
        val fromUser = callerIdNumber ?: username
        val fromDisplay = if (callerIdName != null) "\"$callerIdName\" " else ""
        call.fromHeader = "$fromDisplay<sip:$fromUser@$serverDomain>;tag=${call.localTag}"
        call.toHeader = "<sip:$targetExtension@$serverDomain>"

        val targetUri = "sip:$targetExtension@$serverDomain"
        val invite = SipBuilder.invite(
            targetUri, username, serverDomain,
            publicIp, localPort,
            callId, call.localCseq++,
            localRtpPort,
            fromTag = call.localTag,
            callerIdNumber = callerIdNumber,
            callerIdName = callerIdName
        )

        activeCalls[callId] = call
        sendTo(invite, serverAddress)
        Log.i(TAG, "Sent INVITE to $targetExtension (call-id=$callId)")

        // RFC 3261 Timer A: retransmit INVITE over UDP until any response is received.
        // Intervals: 500ms, 1s, 2s, 4s (capped at T2=4s). Stops immediately when
        // any SIP response arrives (including 401 auth challenge).
        Thread({
            var delay = 500L
            val maxDelay = 4000L
            val maxRetransmits = 7
            for (i in 1..maxRetransmits) {
                Thread.sleep(delay)
                if (call.responseReceived) break
                if (!activeCalls.containsKey(callId)) break
                Log.i(TAG, "INVITE retransmit #$i for $callId (${delay}ms)")
                sendTo(invite, serverAddress)
                delay = minOf(delay * 2, maxDelay)
            }
        }, "INVITE-Retransmit").start()

        return call
    }

    /** Re-send INVITE with authentication */
    fun resendInviteWithAuth(call: SipCall, authParams: SipAuth.AuthParams) {
        val toHeader = call.toHeader ?: return
        // Extract target URI from To header
        val uriStart = toHeader.indexOf("sip:")
        val uriEnd = toHeader.indexOf('>', uriStart).let { if (it < 0) toHeader.length else it }
        val targetUri = if (uriStart >= 0) toHeader.substring(uriStart, uriEnd) else return

        val auth = SipAuth.buildInviteAuthHeader(targetUri, username, password, authParams)
        val invite = SipBuilder.invite(
            targetUri, username, serverDomain,
            publicIp, localPort,
            call.callId, call.localCseq++,
            call.localRtpPort,
            fromTag = call.localTag,
            callerIdNumber = call.outboundCallerIdNumber,
            callerIdName = call.outboundCallerIdName,
            auth = auth
        )
        sendTo(invite, serverAddress)
    }

    fun removeCall(callId: String) {
        activeCalls.remove(callId)
    }

    // ── Monitor / Keepalive ─────────────────────────────

    /** Consecutive keepalive failures (OPTIONS sent with no response) */
    @Volatile private var keepaliveFailures = 0
    private val MAX_KEEPALIVE_FAILURES = 3

    /** Called when the connection appears dead and needs a full reconnect */
    @Volatile var onConnectionLost: (() -> Unit)? = null

    private fun monitorLoop() {
        Thread.sleep(15_000)
        var reregBackoff = 10_000L // start at 10s, backoff on repeated failures
        while (running.get()) {
            try {
                if (!registered) {
                    uiLog("Not registered, attempting re-registration (backoff ${reregBackoff / 1000}s)")
                    val ok = register()
                    if (ok) {
                        reregBackoff = 10_000L
                        keepaliveFailures = 0
                    } else {
                        // Exponential backoff: 10s, 20s, 40s, cap at 60s
                        Thread.sleep(reregBackoff)
                        reregBackoff = (reregBackoff * 2).coerceAtMost(60_000L)
                        // After repeated failures, signal that we need a full reconnect
                        if (reregBackoff >= 60_000L) {
                            uiLog("Registration failed repeatedly, requesting reconnect")
                            onConnectionLost?.invoke()
                        }
                        continue
                    }
                } else {
                    // Send OPTIONS keepalive regardless of active calls
                    // to prevent NAT binding expiration on the SIP port
                    sendOptions()
                    Thread.sleep(5_000)
                    // Check if we got any response from the server recently
                    if (registered && System.currentTimeMillis() - lastServerResponseTime > 60_000) {
                        keepaliveFailures++
                        if (keepaliveFailures >= MAX_KEEPALIVE_FAILURES) {
                            uiLog("Keepalive failed $keepaliveFailures times, connection lost")
                            registered = false
                            onConnectionLost?.invoke()
                            continue
                        }
                    } else {
                        keepaliveFailures = 0
                    }
                }
                // Re-register every 50 minutes
                if (registered && System.currentTimeMillis() - lastRegisterTime > 50 * 60 * 1000) {
                    uiLog("Periodic re-registration")
                    registered = false
                    register()
                }
            } catch (e: Exception) {
                uiLog("Monitor error: ${e.message}")
                registered = false
            }
            Thread.sleep(10_000)
        }
    }

    private fun sendOptions() {
        val branch = "z9hG4bK${(100000..999999).random()}"
        val msg = buildString {
            append("OPTIONS sip:$serverDomain:$serverPort SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $publicIp:$localPort;branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            append("To: <sip:$username@$serverDomain>\r\n")
            append("From: <sip:$username@$publicIp>;tag=49583\r\n")
            append("Call-ID: $callIdBase\r\n")
            append("CSeq: ${cseq.getAndIncrement()} OPTIONS\r\n")
            append("Contact: <sip:$username@$publicIp:$localPort>\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
        sendTo(msg, serverAddress)
    }

    companion object {
        private const val TAG = "SipClient"
    }
}
