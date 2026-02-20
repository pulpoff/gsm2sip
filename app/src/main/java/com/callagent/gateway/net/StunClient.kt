package com.callagent.gateway.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Minimal STUN client (RFC 5389) — discovers the public IP:port via a
 * STUN Binding Request.  Used for NAT traversal in SIP/RTP.
 */
object StunClient {
    private const val TAG = "StunClient"

    // STUN servers — telecom-friendly servers first (port 3478)
    private val STUN_SERVERS = listOf(
        "stun.counterpath.com" to 3478,
        "stun.services.mozilla.com" to 3478,
        "stun.sipgate.net" to 3478,
        "stun.jappix.com" to 3478,
        "stun.1und1.de" to 3478,
        "stun.gmx.net" to 3478,
        "stun.ekiga.net" to 3478,
        "stun.ideasip.com" to 3478,
        "stun.iptel.org" to 3478,
        "stun.rixtelecom.se" to 3478,
        "stun.schlund.de" to 3478,
        "stunserver.org" to 3478
    )

    private const val STUN_BINDING_REQUEST: Short = 0x0001
    private const val STUN_MAGIC_COOKIE = 0x2112A442.toInt()
    private const val ATTR_MAPPED_ADDRESS: Int = 0x0001
    private const val ATTR_XOR_MAPPED_ADDRESS: Int = 0x0020

    data class StunResult(val publicIp: String, val publicPort: Int)

    /**
     * Discover public IP by sending a STUN Binding Request.
     * Tries multiple servers; returns null on failure.
     *
     * @param localSocket optional existing socket to reuse (discovers mapping for that socket)
     */
    fun discover(localSocket: DatagramSocket? = null): StunResult? {
        for ((host, port) in STUN_SERVERS) {
            try {
                val result = queryServer(host, port, localSocket)
                if (result != null) {
                    Log.i(TAG, "STUN result from $host: ${result.publicIp}:${result.publicPort}")
                    return result
                }
            } catch (e: Exception) {
                Log.w(TAG, "STUN $host failed: ${e.message}")
            }
        }
        Log.w(TAG, "All STUN servers failed")
        return null
    }

    private fun queryServer(host: String, port: Int, reuseSocket: DatagramSocket?): StunResult? {
        val sock = reuseSocket ?: DatagramSocket()
        val oldTimeout = sock.soTimeout
        try {
            sock.soTimeout = 3000

            // Build STUN Binding Request (20 bytes header, no attributes)
            val txId = ByteArray(12)
            java.security.SecureRandom().nextBytes(txId)

            val request = ByteBuffer.allocate(20)
            request.putShort(STUN_BINDING_REQUEST)
            request.putShort(0) // message length (no attributes)
            request.putInt(STUN_MAGIC_COOKIE)
            request.put(txId)
            val reqBytes = request.array()

            val addr = InetAddress.getByName(host)
            sock.send(DatagramPacket(reqBytes, reqBytes.size, addr, port))

            // Receive response
            val buf = ByteArray(512)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)

            return parseResponse(buf, resp.length, txId)
        } finally {
            sock.soTimeout = oldTimeout
            if (reuseSocket == null) sock.close()
        }
    }

    private fun parseResponse(data: ByteArray, length: Int, expectedTxId: ByteArray): StunResult? {
        if (length < 20) return null
        val bb = ByteBuffer.wrap(data, 0, length)

        val msgType = bb.short.toInt() and 0xFFFF
        if (msgType != 0x0101) return null // not a Binding Success Response

        val msgLen = bb.short.toInt() and 0xFFFF
        val cookie = bb.int
        if (cookie != STUN_MAGIC_COOKIE) return null

        // Verify transaction ID
        val txId = ByteArray(12)
        bb.get(txId)
        if (!txId.contentEquals(expectedTxId)) return null

        // Parse attributes
        var remaining = msgLen
        while (remaining >= 4) {
            val attrType = bb.short.toInt() and 0xFFFF
            val attrLen = bb.short.toInt() and 0xFFFF
            remaining -= 4

            if (attrLen > remaining) break

            when (attrType) {
                ATTR_XOR_MAPPED_ADDRESS -> {
                    return parseXorMappedAddress(bb, attrLen)
                }
                ATTR_MAPPED_ADDRESS -> {
                    return parseMappedAddress(bb, attrLen)
                }
                else -> {
                    // Skip attribute (with 4-byte padding alignment)
                    val padded = (attrLen + 3) and 3.inv()
                    bb.position(bb.position() + padded)
                    remaining -= padded
                }
            }
        }
        return null
    }

    private fun parseXorMappedAddress(bb: ByteBuffer, len: Int): StunResult? {
        if (len < 8) {
            bb.position(bb.position() + ((len + 3) and 3.inv()))
            return null
        }
        bb.get() // reserved
        val family = bb.get().toInt() and 0xFF
        if (family != 0x01) { // IPv4 only
            bb.position(bb.position() + len - 2)
            return null
        }
        val xorPort = bb.short.toInt() and 0xFFFF
        val port = xorPort xor (STUN_MAGIC_COOKIE ushr 16)
        val xorAddr = bb.int
        val addr = xorAddr xor STUN_MAGIC_COOKIE
        val ip = "${(addr ushr 24) and 0xFF}.${(addr ushr 16) and 0xFF}.${(addr ushr 8) and 0xFF}.${addr and 0xFF}"
        return StunResult(ip, port)
    }

    private fun parseMappedAddress(bb: ByteBuffer, len: Int): StunResult? {
        if (len < 8) {
            bb.position(bb.position() + ((len + 3) and 3.inv()))
            return null
        }
        bb.get() // reserved
        val family = bb.get().toInt() and 0xFF
        if (family != 0x01) {
            bb.position(bb.position() + len - 2)
            return null
        }
        val port = bb.short.toInt() and 0xFFFF
        val b1 = bb.get().toInt() and 0xFF
        val b2 = bb.get().toInt() and 0xFF
        val b3 = bb.get().toInt() and 0xFF
        val b4 = bb.get().toInt() and 0xFF
        return StunResult("$b1.$b2.$b3.$b4", port)
    }
}
