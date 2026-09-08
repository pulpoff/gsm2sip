package com.callagent.gateway.sip

/**
 * Lightweight SIP message parser and builder.
 * Ported from the Python SIP implementation in sip/sip.py
 */
class SipMessage private constructor(
    val startLine: String,
    val headers: Map<String, String>,
    val body: String
) {

    val isRequest: Boolean get() = !startLine.startsWith("SIP/")
    val isResponse: Boolean get() = startLine.startsWith("SIP/")

    val method: String?
        get() = if (isRequest) startLine.split(" ").firstOrNull() else null

    val statusCode: Int?
        get() = if (isResponse) startLine.split(" ").getOrNull(1)?.toIntOrNull() else null

    val requestUri: String?
        get() = if (isRequest) startLine.split(" ").getOrNull(1) else null

    fun header(name: String): String? =
        headers[name.lowercase()]

    val callId: String? get() = header("call-id")
    val cseq: String? get() = header("cseq")
    val via: String? get() = header("via")
    val from: String? get() = header("from")
    val to: String? get() = header("to")
    val contact: String? get() = header("contact")
    val contentType: String? get() = header("content-type")

    val fromTag: String?
        get() = from?.let { extractTag(it) }

    val toTag: String?
        get() = to?.let { extractTag(it) }

    /** Extract SIP URI user part from a header value like <sip:user@host> */
    fun extractUser(headerValue: String): String? {
        val sipIdx = headerValue.indexOf("sip:")
        if (sipIdx < 0) return null
        val atIdx = headerValue.indexOf('@', sipIdx)
        if (atIdx < 0) return null
        return headerValue.substring(sipIdx + 4, atIdx)
    }

    val callerNumber: String? get() = from?.let { extractUser(it) }
    val dialedNumber: String? get() = to?.let { extractUser(it) }

    /** Extract display name from From header, e.g. "Display" <sip:...> */
    val callerDisplayName: String?
        get() {
            val f = from ?: return null
            val q1 = f.indexOf('"')
            if (q1 < 0) return null
            val q2 = f.indexOf('"', q1 + 1)
            if (q2 <= q1) return null
            return f.substring(q1 + 1, q2).trim().ifEmpty { null }
        }

    /** Extract Contact URI, e.g. <sip:user@host:port> → sip:user@host:port */
    val contactUri: String?
        get() {
            val c = contact ?: return null
            val s = c.indexOf('<')
            val e = c.indexOf('>', s + 1)
            return if (s >= 0 && e > s) c.substring(s + 1, e) else c.split(";")[0].trim()
        }

    /** Parse host:port from a SIP URI */
    val contactAddress: Pair<String, Int>?
        get() {
            val uri = contactUri ?: return null
            val hostPart = uri.removePrefix("sip:").substringAfter('@')
            val parts = hostPart.split(":")
            val host = parts[0]
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 5060
            return Pair(host, port)
        }

    /** Extract RTP port from SDP body (m=audio PORT ...) */
    val sdpRtpPort: Int?
        get() {
            val mLine = body.lineSequence().firstOrNull { it.startsWith("m=audio") } ?: return null
            return mLine.split(" ").getOrNull(1)?.toIntOrNull()
        }

    /** Extract IP from SDP body (c=IN IP4 x.x.x.x) */
    val sdpAddress: String?
        get() {
            val cLine = body.lineSequence().firstOrNull { it.startsWith("c=IN IP4") } ?: return null
            return cLine.split(" ").getOrNull(2)?.trim()
        }

    /** Check which codecs the SDP offers */
    val sdpCodecs: List<Pair<Int, String>>
        get() {
            val result = mutableListOf<Pair<Int, String>>()
            body.lineSequence().forEach { line ->
                if (line.startsWith("a=rtpmap:")) {
                    // a=rtpmap:9 G722/8000
                    val parts = line.removePrefix("a=rtpmap:").split(" ", limit = 2)
                    val pt = parts.getOrNull(0)?.toIntOrNull()
                    val codec = parts.getOrNull(1)
                    if (pt != null && codec != null) {
                        result.add(Pair(pt, codec))
                    }
                }
            }
            return result
        }

    /** Get the preferred payload type from the remote SDP.
     *  Collects all payload types offered in the remote m=audio line,
     *  then picks OUR preferred codec: G.722 > PCMA > PCMU. */
    val sdpPreferredPayloadType: Int
        get() {
            val mLine = body.lineSequence().firstOrNull {
                it.startsWith("m=audio")
            } ?: return 8
            val parts = mLine.split(" ")
            // m=audio PORT RTP/AVP pt1 pt2 pt3 ...
            val offered = mutableSetOf<Int>()
            for (i in 3 until parts.size) {
                parts[i].trim().toIntOrNull()?.let { offered.add(it) }
            }
            // Follow the configured preference, but never fail a call over it:
            // if the remote offers nothing we prefer, take what it does offer
            // rather than answering with a codec it cannot speak.
            val g711 = when {
                8 in offered -> 8   // PCMA (G.711 A-law, 8 kHz)
                0 in offered -> 0   // PCMU (G.711 mu-law, 8 kHz)
                else -> null
            }
            return when (SipBuilder.codecMode) {
                "g711" -> g711 ?: if (9 in offered) 9 else 8
                else -> if (9 in offered) 9 else (g711 ?: 9)
            }
        }

    /** Check for custom gateway header: X-GSM-Forward */
    val gsmForwardNumber: String?
        get() = header("x-gsm-forward")?.trim()

    /** Serialize this message back to a SIP packet string */
    fun encode(): String {
        val sb = StringBuilder()
        sb.append(startLine).append("\r\n")
        for ((_, value) in headers) {
            // headers stored lowercase key, but we need proper casing — use raw lines
        }
        // We store raw header lines for serialization
        return raw ?: buildString {
            append(startLine).append("\r\n")
            headers.forEach { (k, v) ->
                append("${k}: ${v}\r\n")
            }
            append("\r\n")
            append(body)
        }
    }

    private var raw: String? = null

    companion object {
        /** Parse a raw SIP message string */
        fun parse(data: String): SipMessage? {
            val headerBodySplit = data.indexOf("\r\n\r\n")
            if (headerBodySplit < 0) return null

            val headerSection = data.substring(0, headerBodySplit)
            val body = data.substring(headerBodySplit + 4)
            val lines = headerSection.split("\r\n")
            if (lines.isEmpty()) return null

            val startLine = lines[0]
            val headers = LinkedHashMap<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase()
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }

            return SipMessage(startLine, headers, body).also { it.raw = data }
        }

        private fun extractTag(header: String): String? {
            val idx = header.indexOf(";tag=")
            if (idx < 0) return null
            return header.substring(idx + 5).split(";")[0].trim()
        }
    }
}

/** Builder for constructing SIP messages */
object SipBuilder {
    /**
     * How this gateway identifies itself in SIP traffic, the way a Fritz!Box
     * announces itself as "AVM FRITZ!Box 7530 AX".  Without it the device is
     * anonymous in the server's logs and CDRs.  SipClient sets it once at
     * startup so the configured server domain can be included.
     */
    @Volatile
    var userAgent: String = "gsm2sip"

    /** Which codecs to offer and accept: "g722", "g711" or "both".
     *
     *  G.722 alone is the default and the reason audio is wideband: when G.711
     *  is offered alongside it, servers routinely pick PCMA and every call ends
     *  up narrowband regardless of what both ends support. */
    @Volatile
    var codecMode: String = "g722"

    private fun branch(): String = "z9hG4bK${(100000000..999999999).random()}"
    private fun tag(): String = "gw${(100000000..999999999).random()}"

    fun register(
        username: String, domain: String, serverPort: Int,
        localIp: String, localPort: Int,
        callId: String, cseq: Int,
        auth: String? = null
    ): String {
        val uri = "sip:$domain:$serverPort"
        return buildString {
            append("REGISTER $uri SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:$localPort;branch=${branch()};rport\r\n")
            append("Max-Forwards: 70\r\n")
            append("User-Agent: $userAgent\r\n")
            append("To: <sip:$username@$domain>\r\n")
            append("From: <sip:$username@$localIp>;tag=${tag()}\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseq REGISTER\r\n")
            append("Contact: <sip:$username@$localIp:$localPort>;expires=3600\r\n")
            if (auth != null) append(auth)
            append("Content-Length: 0\r\n\r\n")
        }
    }

    fun invite(
        targetUri: String,
        username: String, domain: String,
        localIp: String, localPort: Int,
        callId: String, cseq: Int,
        localRtpPort: Int,
        fromTag: String = tag(),
        callerIdNumber: String? = null,
        callerIdName: String? = null,
        auth: String? = null
    ): String {
        val fromDisplay = if (callerIdName != null) "\"$callerIdName\" " else ""
        val fromUser = callerIdNumber ?: username
        val sdp = buildSdp(localIp, localRtpPort)
        return buildString {
            append("INVITE $targetUri SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:$localPort;branch=${branch()};rport\r\n")
            append("Max-Forwards: 70\r\n")
            append("User-Agent: $userAgent\r\n")
            append("To: <$targetUri>\r\n")
            append("From: $fromDisplay<sip:$fromUser@$domain>;tag=$fromTag\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseq INVITE\r\n")
            append("Contact: <sip:$username@$localIp:$localPort>\r\n")
            if (auth != null) append(auth)
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: ${sdp.length}\r\n\r\n")
            append(sdp)
        }
    }

    /**
     * A page-mode SIP MESSAGE (RFC 3428) — one request, one response, no
     * dialog.  Used to hand a received SMS to the server.
     *
     * Content-Length counts *bytes*: SMS text is UTF-8 and a message with any
     * non-ASCII character in it would otherwise declare a length shorter than
     * what goes on the wire, and the server would truncate the body.
     */
    fun message(
        targetUri: String,
        fromUser: String, domain: String,
        localIp: String, localPort: Int,
        callId: String, cseq: Int,
        body: String,
        contentType: String = "text/plain;charset=UTF-8",
        extraHeaders: List<String> = emptyList(),
        fromTag: String = tag(),
        auth: String? = null
    ): String = buildString {
        append("MESSAGE $targetUri SIP/2.0\r\n")
        append("Via: SIP/2.0/UDP $localIp:$localPort;branch=${branch()};rport\r\n")
        append("Max-Forwards: 70\r\n")
        append("User-Agent: $userAgent\r\n")
        append("To: <$targetUri>\r\n")
        append("From: <sip:$fromUser@$domain>;tag=$fromTag\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq MESSAGE\r\n")
        for (h in extraHeaders) append("$h\r\n")
        if (auth != null) append(auth)
        append("Content-Type: $contentType\r\n")
        append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n")
        append(body)
    }

    /**
     * A bare status response for a page-mode request.
     *
     * 202 for a MESSAGE we have taken responsibility for, 4xx to refuse one.
     * No Contact: there is no dialog to route anything into.
     */
    fun statusResponse(
        msg: SipMessage,
        code: Int,
        reason: String,
        extraHeaders: List<String> = emptyList(),
        toTag: String = tag()
    ): String {
        val to = msg.to ?: ""
        val toWithTag = if (to.contains(";tag=")) to else "$to;tag=$toTag"
        return buildString {
            append("SIP/2.0 $code $reason\r\n")
            append("Via: ${msg.via}\r\n")
            append("To: $toWithTag\r\n")
            append("From: ${msg.from}\r\n")
            append("Call-ID: ${msg.callId}\r\n")
            append("CSeq: ${msg.cseq}\r\n")
            append("User-Agent: $userAgent\r\n")
            for (h in extraHeaders) append("$h\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
    }

    fun ok200(
        msg: SipMessage,
        username: String, localIp: String, localPort: Int,
        localRtpPort: Int? = null,
        toTag: String = tag()
    ): String {
        val to = msg.to ?: ""
        val toWithTag = if (to.contains(";tag=")) to else "$to;tag=$toTag"
        val sdp = if (localRtpPort != null) buildSdp(localIp, localRtpPort) else null
        return buildString {
            append("SIP/2.0 200 OK\r\n")
            append("Via: ${msg.via}\r\n")
            append("To: $toWithTag\r\n")
            append("From: ${msg.from}\r\n")
            append("Call-ID: ${msg.callId}\r\n")
            append("CSeq: ${msg.cseq}\r\n")
            append("Contact: <sip:$username@$localIp:$localPort>\r\n")
            if (sdp != null) {
                append("Content-Type: application/sdp\r\n")
                append("Content-Length: ${sdp.length}\r\n\r\n")
                append(sdp)
            } else {
                append("Content-Length: 0\r\n\r\n")
            }
        }
    }

    fun trying100(msg: SipMessage): String = buildString {
        append("SIP/2.0 100 Trying\r\n")
        append("Via: ${msg.via}\r\n")
        append("To: ${msg.to}\r\n")
        append("From: ${msg.from}\r\n")
        append("Call-ID: ${msg.callId}\r\n")
        append("CSeq: ${msg.cseq}\r\n")
        append("Server: $userAgent\r\n")
        append("Content-Length: 0\r\n\r\n")
    }

    fun ringing180(msg: SipMessage, toTag: String = tag()): String {
        val to = msg.to ?: ""
        val toWithTag = if (to.contains(";tag=")) to else "$to;tag=$toTag"
        return buildString {
            append("SIP/2.0 180 Ringing\r\n")
            append("Via: ${msg.via}\r\n")
            append("To: $toWithTag\r\n")
            append("From: ${msg.from}\r\n")
            append("Call-ID: ${msg.callId}\r\n")
            append("CSeq: ${msg.cseq}\r\n")
            append("Server: $userAgent\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
    }

    fun ack(
        targetUri: String,
        via: String?, toHeader: String?, fromHeader: String?,
        callId: String?, cseq: Int,
        username: String, localIp: String, localPort: Int
    ): String = buildString {
        append("ACK $targetUri SIP/2.0\r\n")
        append("Via: SIP/2.0/UDP $localIp:$localPort;branch=${branch()};rport\r\n")
        append("Max-Forwards: 70\r\n")
            append("User-Agent: $userAgent\r\n")
        append("To: $toHeader\r\n")
        append("From: $fromHeader\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq ACK\r\n")
        append("Contact: <sip:$username@$localIp:$localPort>\r\n")
        append("Content-Length: 0\r\n\r\n")
    }

    fun bye(
        targetUri: String,
        fromHeader: String?, toHeader: String?,
        callId: String?, cseq: Int,
        username: String, localIp: String, localPort: Int
    ): String = buildString {
        append("BYE $targetUri SIP/2.0\r\n")
        append("Via: SIP/2.0/UDP $localIp:$localPort;branch=${branch()};rport\r\n")
        append("Max-Forwards: 70\r\n")
            append("User-Agent: $userAgent\r\n")
        append("From: $fromHeader\r\n")
        append("To: $toHeader\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq BYE\r\n")
        append("Contact: <sip:$username@$localIp:$localPort>\r\n")
        append("Content-Length: 0\r\n\r\n")
    }

    fun cancel(
        targetUri: String,
        viaHeader: String?, fromHeader: String?, toHeader: String?,
        callId: String?, cseq: Int,
        username: String, localIp: String, localPort: Int
    ): String = buildString {
        append("CANCEL $targetUri SIP/2.0\r\n")
        append("Via: $viaHeader\r\n")
        append("Max-Forwards: 70\r\n")
            append("User-Agent: $userAgent\r\n")
        append("From: $fromHeader\r\n")
        append("To: $toHeader\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq CANCEL\r\n")
        append("Content-Length: 0\r\n\r\n")
    }

    fun optionsResponse(msg: SipMessage, username: String, localIp: String, localPort: Int): String =
        buildString {
            append("SIP/2.0 200 OK\r\n")
            append("Via: ${msg.via}\r\n")
            append("To: ${msg.to}\r\n")
            append("From: ${msg.from}\r\n")
            append("Call-ID: ${msg.callId}\r\n")
            append("CSeq: ${msg.cseq}\r\n")
            append("Contact: <sip:$username@$localIp:$localPort>\r\n")
            append("Allow: INVITE, ACK, CANCEL, OPTIONS, BYE\r\n")
            append("Content-Length: 0\r\n\r\n")
        }

    private fun buildSdp(localIp: String, rtpPort: Int): String = buildString {
        // G.722 is wideband (16 kHz sampling) but its SDP clock rate is
        // written as 8000 per RFC 3551 — a historical quirk, not a typo.
        // telephone-event is always offered: it carries DTMF, not voice.
        val payloads = when (codecMode) {
            "g711" -> "8 0 101"
            "both" -> "9 8 0 101"
            else -> "9 101"
        }
        append("v=0\r\n")
        append("o=gateway 0 0 IN IP4 $localIp\r\n")
        append("s=SIP Call\r\n")
        append("c=IN IP4 $localIp\r\n")
        append("t=0 0\r\n")
        append("m=audio $rtpPort RTP/AVP $payloads\r\n")
        if (codecMode != "g711") append("a=rtpmap:9 G722/8000\r\n")
        if (codecMode != "g722") {
            append("a=rtpmap:8 PCMA/8000\r\n")
            append("a=rtpmap:0 PCMU/8000\r\n")
        }
        append("a=rtpmap:101 telephone-event/8000\r\n")
        append("a=fmtp:101 0-16\r\n")
        append("a=ptime:20\r\n")
        append("a=sendrecv\r\n")
    }
}
