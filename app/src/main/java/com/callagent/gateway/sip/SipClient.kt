package com.callagent.gateway.sip

import android.os.Build
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
import kotlin.random.Random

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

    /** Guards against two threads registering at once: start() launches a
     *  SIP-Register thread while SIP-Monitor retries on its own schedule. */
    private val registering = AtomicBoolean(false)

    /** Consecutive real (not backed-off) registration failures. */
    @Volatile private var registerFailures = 0

    private val activeCalls = ConcurrentHashMap<String, SipCall>()
    /** Single-thread executor for all socket sends — avoids NetworkOnMainThreadException */
    private var sendExecutor: ExecutorService? = null

    @Volatile var listener: Listener? = null
    /** Log callback — forwards key SIP events to the UI */
    @Volatile var logListener: ((String) -> Unit)? = null

    /**
     * Page-mode MESSAGE from the server — a request to send an SMS.
     *
     * Returns the status to answer with: 202 once the request is safely
     * stored, or a 4xx the server should not retry.  Kept off SipClient's
     * Listener, which is about calls, and invoked on the receive thread — so
     * whatever it does must be quick.
     */
    @Volatile var onSmsRequest: ((SipMessage) -> Pair<Int, List<String>>)? = null

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
        // Identify the handset, not the server: with several gateways
        // registered to the same PBX the server domain is the one thing every
        // one of them has in common, so it told the CDRs nothing.  Build.DEVICE
        // is the short codename — "serranolte", "surya" — which is what
        // distinguishes them.  Whitespace is stripped because a User-Agent
        // value has to stay a single header token.
        val handset = (Build.DEVICE?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() }
            ?: "unknown").trim().replace(Regex("\\s+"), "-")
        SipBuilder.userAgent =
            "gsm2sip v${com.callagent.gateway.BuildConfig.VERSION_NAME} $handset"
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
        // A stopped client must not be able to drive the service any more.
        // monitorLoop can be up to 10s into a sleep or blocked on the
        // registration latch when stop() lands, and the callback it fired on
        // the way out tore down the *replacement* client.
        onConnectionLost = null
        listener = null
        // Release anyone blocked waiting for a REGISTER response so the
        // thread reaches its running.get() check instead of sitting out the
        // full 10s timeout.
        registrationLatch?.countDown()
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
        sendExecutor = Executors.newSingleThreadExecutor { r ->
            Thread({
                // Once for the thread, not once per packet: this was building
                // a ThreadPolicy object on every SIP message sent.
                android.os.StrictMode.setThreadPolicy(
                    android.os.StrictMode.ThreadPolicy.Builder().permitAll().build()
                )
                r.run()
            }, "SIP-Send")
        }
        // Resolve server DNS now (background thread) so sendTo never blocks on DNS
        resolvedServerAddr = InetAddress.getByName(serverDomain)
        uiLog("Socket bound to $localIp:$localPort")
    }

    // ── Send ────────────────────────────────────────────

    fun sendTo(data: String, address: Pair<String, Int>) {
        val executor = sendExecutor
        if (executor == null) {
            uiLog("sendTo: executor is NULL — sending on new thread")
            Thread({
                android.os.StrictMode.setThreadPolicy(
                    android.os.StrictMode.ThreadPolicy.Builder().permitAll().build()
                )
                doSend(data, address)
            }, "SIP-FallbackSend").start()
            return
        }
        executor.execute { doSend(data, address) }
    }

    private fun doSend(data: String, address: Pair<String, Int>) {
        try {
            // Explicit, not the platform default.  It happens to be UTF-8 on
            // Android, so this changes nothing today -- but SIP bodies carry
            // the message text, and an implicit charset is the one thing that
            // would silently turn every umlaut into mojibake.
            val bytes = data.toByteArray(Charsets.UTF_8)
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
                val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
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

        // Responses to a MESSAGE we sent (an SMS handed to the server).
        // Checked before call routing: a page-mode MESSAGE has no dialog, so
        // its Call-ID must not be mistaken for a call's.
        if (msg.isResponse && msg.cseq?.contains("MESSAGE") == true) {
            if (handleMessageResponse(msg)) return
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

        // Page-mode MESSAGE from the server — an SMS to send.
        if (msg.isRequest && msg.method == "MESSAGE") {
            val (code, extra) = try {
                onSmsRequest?.invoke(msg) ?: (405 to emptyList())
            } catch (e: Exception) {
                Log.e(TAG, "onSmsRequest failed: ${e.message}", e)
                500 to emptyList()
            }
            val reason = when (code) {
                200 -> "OK"
                202 -> "Accepted"
                400 -> "Bad Request"
                405 -> "Method Not Allowed"
                404 -> "Not Found"
                415 -> "Unsupported Media Type"
                503 -> "Service Unavailable"
                else -> "Error"
            }
            sendTo(SipBuilder.statusResponse(msg, code, reason, extra), address)
            return
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
     * Send ONE REGISTER and wait for receiveLoop() to handle the response.
     * Uses a CountDownLatch so only receiveLoop() reads from the socket
     * (eliminates the old race condition with waitForRegistration).
     *
     * Rate-limited by [RegisterBackoff]: while a previous failure's cooldown
     * is still running this returns false without putting a packet on the
     * wire.  Returns false immediately if another thread is already
     * registering, so the startup and monitor threads cannot overlap.
     */
    private fun register(): Boolean {
        if (!running.get()) return false
        if (!registering.compareAndSet(false, true)) {
            Log.d(TAG, "REGISTER already in flight — skipping duplicate")
            return false
        }
        try {
            val hold = RegisterBackoff.holdOffMs(serverDomain)
            if (hold > 0) {
                Log.d(TAG, "REGISTER held off for ${hold / 1000}s more (backoff)")
                return false
            }
            uiLog("REGISTER → $serverDomain")
            registrationLatch = CountDownLatch(1)
            sendRegister()
            // Wait for receiveLoop → handleRegisterResponse to signal
            try {
                registrationLatch?.await(REGISTER_TIMEOUT_SEC, TimeUnit.SECONDS)
            } catch (_: InterruptedException) { return false }
            if (registered) {
                RegisterBackoff.onSuccess()
                registerFailures = 0
                return true
            }
            if (!running.get()) return false
            val next = RegisterBackoff.onFailure()
            registerFailures++
            uiLog("REGISTER failed — next attempt in ${next / 1000}s")
            listener?.onRegistrationFailed()
            return false
        } finally {
            registering.set(false)
        }
    }

    /**
     * Register now, ignoring the backoff cooldown.  For an explicit retry from
     * the UI, where waiting out an exponential delay is not what was asked for.
     */
    fun retryNow() {
        RegisterBackoff.reset()
        registerFailures = 0
        Thread({
            try {
                uiLog("Manual retry requested")
                register()
            } catch (e: Exception) {
                uiLog("Manual retry failed: ${e.message}")
            }
        }, "SIP-ManualRetry").start()
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
        // Log both halves the server routes on: the Request-URI it turns into
        // EXTEN, and the From user it turns into caller ID.
        Log.i(TAG, "Sent INVITE RURI=$targetUri From=<sip:$fromUser@$serverDomain> (call-id=$callId)")

        // RFC 3261 Timer A: retransmit INVITE over UDP until any response is received.
        // Intervals: 500ms, 1s, 2s, 4s (capped at T2=4s). Stops immediately when
        // any SIP response arrives (including 401 auth challenge).
        Thread({
            var delay = 500L
            val maxDelay = 4000L
            val maxRetransmits = 7
            var elapsed = 0L
            var unanswered = false
            for (i in 1..maxRetransmits) {
                Thread.sleep(delay)
                elapsed += delay
                if (call.responseReceived) break
                if (!activeCalls.containsKey(callId)) break
                Log.i(TAG, "INVITE retransmit #$i for $callId (${delay}ms)")
                // One line, not seven: silence from the server is the fact
                // worth recording, and without it an unanswered INVITE showed
                // up in the app only as a call that never got picked up.
                if (i == 1) uiLog("No response to INVITE — retransmitting")
                sendTo(invite, serverAddress)
                unanswered = true
                delay = minOf(delay * 2, maxDelay)
            }
            if (unanswered && !call.responseReceived && activeCalls.containsKey(callId)) {
                uiLog("Server never answered the INVITE (${elapsed / 1000}s, $maxRetransmits sends)")
            }
        }, "INVITE-Retransmit").start()

        return call
    }

    // ── Page-mode MESSAGE (RFC 3428) ────────────────────

    /** One in-flight outbound MESSAGE, keyed by Call-ID. */
    private class MessageTxn {
        val latch = CountDownLatch(1)
        @Volatile var status = 0
        @Volatile var challenge: SipAuth.AuthParams? = null
    }

    private val pendingMessages = ConcurrentHashMap<String, MessageTxn>()

    /**
     * Send a SIP MESSAGE and wait for its final response.
     *
     * Blocking, so call it off the main thread.  Returns the status code the
     * server answered with (202 and 200 both mean accepted), or 0 if nothing
     * came back — the caller keeps the message queued and tries again rather
     * than dropping it.
     *
     * A 401/407 challenge is answered once with credentials, the way REGISTER
     * and INVITE are: Asterisk may or may not require auth on MESSAGE
     * depending on how the endpoint is configured, and guessing wrong in
     * either direction loses messages.
     */
    fun sendSipMessage(
        targetUri: String,
        fromUser: String,
        body: String,
        extraHeaders: List<String> = emptyList(),
        contentType: String = "text/plain;charset=UTF-8"
    ): Int {
        if (!running.get()) return 0
        val callId = "${System.currentTimeMillis()}msg@$publicIp"
        val fromTag = "gw${(100000000..999999999).random()}"
        val txn = MessageTxn()
        pendingMessages[callId] = txn
        try {
            var cseq = 1
            sendTo(
                SipBuilder.message(
                    targetUri, fromUser, serverDomain, publicIp, localPort,
                    callId, cseq, body, contentType, extraHeaders, fromTag
                ),
                serverAddress
            )
            if (!txn.latch.await(MESSAGE_TIMEOUT_SEC, TimeUnit.SECONDS)) return 0

            val challenge = txn.challenge
            if (challenge != null) {
                // Same transaction identity, next CSeq — a fresh Call-ID would
                // read as an unrelated message to the server.
                val retry = MessageTxn()
                pendingMessages[callId] = retry
                cseq++
                val auth = SipAuth.buildAuthHeader(
                    "MESSAGE", targetUri, username, password, challenge
                )
                sendTo(
                    SipBuilder.message(
                        targetUri, fromUser, serverDomain, publicIp, localPort,
                        callId, cseq, body, contentType, extraHeaders, fromTag, auth
                    ),
                    serverAddress
                )
                if (!retry.latch.await(MESSAGE_TIMEOUT_SEC, TimeUnit.SECONDS)) return 0
                return retry.status
            }
            return txn.status
        } catch (e: Exception) {
            uiLog("SIP MESSAGE failed: ${e.message}")
            return 0
        } finally {
            pendingMessages.remove(callId)
        }
    }

    private fun handleMessageResponse(msg: SipMessage): Boolean {
        val callId = msg.callId ?: return false
        val txn = pendingMessages[callId] ?: return false
        val code = msg.statusCode ?: 0
        if (code == 100) return true          // provisional, keep waiting
        if (code == 401 || code == 407) {
            txn.challenge = SipAuth.parseChallenge(msg)
        }
        txn.status = code
        txn.latch.countDown()
        return true
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

    /** When the last NAT keepalive went out, so its interval is independent
     *  of how often the monitor loop wakes up. */
    @Volatile private var lastKeepaliveTime = 0L
    private val MAX_KEEPALIVE_FAILURES = 3

    /** Called when the connection appears dead and needs a full reconnect */
    @Volatile var onConnectionLost: (() -> Unit)? = null

    private fun monitorLoop() {
        Thread.sleep(15_000)
        while (running.get()) {
            try {
                if (!registered) {
                    // register() rate-limits itself — while the cooldown is
                    // running it returns without sending anything, so polling
                    // once per loop costs no traffic.
                    if (register()) {
                        keepaliveFailures = 0
                    } else if (registerFailures >= MAX_REGISTER_FAILURES) {
                        // Rebuild the socket in case the local IP changed.
                        // Safe to repeat: the backoff schedule lives in the
                        // companion, so the replacement SipClient inherits the
                        // cooldown instead of restarting the retry cycle.
                        uiLog("Registration failing repeatedly, requesting reconnect")
                        registerFailures = 0
                        if (running.get()) onConnectionLost?.invoke()
                    }
                } else {
                    // Keeping the NAT binding open needs a packet every 20-30s,
                    // but it does not need to be a SIP transaction: a bare CRLF
                    // refreshes the mapping and the server neither answers it
                    // nor logs it.  Timed on its own clock rather than once per
                    // iteration, so the loop can poll faster than the binding
                    // needs packets.
                    val now = System.currentTimeMillis()
                    if (now - lastKeepaliveTime >= NAT_KEEPALIVE_INTERVAL_MS) {
                        sendNatKeepalive()
                        lastKeepaliveTime = now
                    }

                    // The registration refresh is the only SIP request this
                    // gateway makes while idle.  It is authenticated, it is
                    // exactly what a registrar expects, and it doubles as the
                    // liveness check — so there is nothing left for OPTIONS to
                    // do.  Polling with OPTIONS every 15s meant 240
                    // unauthenticated requests an hour, each answered 401 and
                    // recorded as an auth failure, which is the pattern
                    // fail2ban counts; it banned this gateway's IP repeatedly.
                    if (System.currentTimeMillis() - lastRegisterTime > REREGISTER_INTERVAL_MS) {
                        uiLog("Refreshing registration")
                        registered = false
                        if (!register()) {
                            // Failed refresh means the server or the path is
                            // gone; the retry loop above takes over from here.
                            keepaliveFailures++
                            if (keepaliveFailures >= MAX_KEEPALIVE_FAILURES) {
                                uiLog("Registration refresh failed $keepaliveFailures times")
                                keepaliveFailures = 0
                                if (running.get()) onConnectionLost?.invoke()
                            }
                        } else {
                            keepaliveFailures = 0
                        }
                    }
                }
            } catch (e: Exception) {
                uiLog("Monitor error: ${e.message}")
                registered = false
            }
            // Half the shortest thing this loop has to be on time for.  The
            // keepalive interval is enforced by comparing timestamps, but the
            // comparison only happens when this wakes, so the period sets the
            // granularity: at 10s a 25s keepalive actually goes out every 30s.
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    /**
     * Refresh the NAT binding without generating a SIP transaction.
     *
     * A bare CRLF is the standard SIP keepalive (RFC 5626 §3.5.1).  It costs
     * the server nothing, is not a request, and cannot be counted as a failed
     * authentication — unlike the OPTIONS this replaces.
     */
    private fun sendNatKeepalive() {
        try {
            sendTo("\r\n\r\n", serverAddress)
        } catch (e: Exception) {
            Log.w(TAG, "NAT keepalive failed: ${e.message}")
        }
    }


    companion object {
        private const val TAG = "SipClient"

        /** Seconds to wait for a REGISTER response before calling it failed. */
        private const val REGISTER_TIMEOUT_SEC = 10L

        /** How long to wait for a MESSAGE's final response before treating it
         *  as unsent and leaving it queued for the next attempt. */
        private const val MESSAGE_TIMEOUT_SEC = 10L

        /** Consecutive failures before asking GatewayService for a new socket. */
        private const val MAX_REGISTER_FAILURES = 3

        /**
         * How often to refresh the registration.
         *
         * The REGISTER advertises `expires=3600` and the server grants it --
         * verified against callagent.pro, whose 200 OK carries `Expires: 3600`
         * (chan_sip's default).  Half the granted lifetime is the conventional
         * refresh point: it leaves a full 30 minutes to notice a failure and
         * retry before the binding actually lapses.
         *
         * The previous ten minutes was not derived from anything the server
         * said -- nothing here reads the granted expiry -- and refreshed six
         * times an hour where twice will do.  Each refresh is challenged, so
         * it also put six 401s an hour in the registrar's auth log per device.
         */
        private const val REREGISTER_INTERVAL_MS = 30 * 60 * 1000L

        /**
         * How often to refresh the NAT binding.
         *
         * Consumer routers time out an idle UDP mapping somewhere around
         * 30-60s; 25s stays under the low end with room to spare.  This is
         * deliberately separate from the monitor loop's own period, which is
         * about how fast a lost registration is noticed and has no business
         * setting how often a packet goes on the wire.  Sending one per
         * 10s iteration meant 360 packets an hour to hold a binding that
         * needs 144.
         *
         * Getting this wrong is not subtle: the server qualifies this peer
         * with OPTIONS every 60s, and those only arrive while the mapping is
         * open.  Too long an interval and Asterisk marks the peer UNREACHABLE
         * and inbound calls stop.
         */
        private const val NAT_KEEPALIVE_INTERVAL_MS = 25_000L

        /**
         * How often the monitor loop wakes.
         *
         * It must divide [NAT_KEEPALIVE_INTERVAL_MS], because the keepalive
         * fires on the first tick at or past the interval -- with a 10s period
         * a 25s keepalive went out every 30s, measured on the wire.  Waking is
         * only a couple of timestamp comparisons; it sends nothing on its own.
         */
        private const val POLL_INTERVAL_MS = 5_000L

        /**
         * Exponential backoff for REGISTER, shared by every SipClient.
         *
         * It lives in the companion rather than on the instance because a run
         * of failures ends in onConnectionLost() → GatewayService.reconnect(),
         * which discards the SipClient and builds a new one.  With per-instance
         * state the replacement restarted at the shortest delay, so an
         * unreachable server produced a steady REGISTER flood — three packets
         * per attempt, a fresh attempt every ~10s, a full reconnect every ~2
         * minutes — until the server's fail2ban banned the gateway's IP.
         * Keeping the schedule here means a new client picks the cooldown up
         * where the old one left off.
         */
        private object RegisterBackoff {
            private const val MIN_MS = 15_000L

            /** Ceiling on the retry interval.
             *
             *  Half an hour was too cautious in the other direction: a gateway
             *  that loses its registration would sit unreachable for up to
             *  30 minutes, and someone has to notice and re-register by hand.
             *  Five minutes recovers on its own within a few attempts while
             *  still being about a dozen REGISTERs an hour at worst, which is
             *  well under anything that looks like abuse. */
            private const val MAX_MS = 5 * 60 * 1000L

            private var target = ""
            private var delayMs = MIN_MS
            private var nextAttemptAt = 0L

            /** Millis still to wait before a REGISTER may be sent (0 = now). */
            @Synchronized fun holdOffMs(server: String): Long {
                if (server != target) {
                    // Different server (or first use) — start clean.
                    target = server
                    delayMs = MIN_MS
                    nextAttemptAt = 0L
                }
                return (nextAttemptAt - System.currentTimeMillis()).coerceAtLeast(0L)
            }

            /** Clear the cooldown for a deliberate, user-initiated retry.
             *  Backing off exists to stop the gateway hammering a server on
             *  its own; someone pressing "retry" is not that. */
            @Synchronized fun reset() {
                delayMs = MIN_MS
                nextAttemptAt = 0L
            }

            @Synchronized fun onSuccess() {
                delayMs = MIN_MS
                nextAttemptAt = 0L
            }

            /** Doubles the delay (capped) and returns the wait just applied.
             *  Jittered so several gateways on one server don't retry in step. */
            @Synchronized fun onFailure(): Long {
                val wait = (delayMs * (0.8 + Random.nextDouble() * 0.4)).toLong()
                nextAttemptAt = System.currentTimeMillis() + wait
                delayMs = (delayMs * 2).coerceAtMost(MAX_MS)
                return wait
            }
        }
    }
}
