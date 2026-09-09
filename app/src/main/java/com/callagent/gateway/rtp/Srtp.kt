package com.callagent.gateway.rtp

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SRTP (RFC 3711) with SDES keying (RFC 4568) -- the combination FRITZ!Box and
 * most SIP providers use, and the only one that works without DTLS.
 *
 * The keys travel in the SDP, in the clear, as `a=crypto` lines.  That is the
 * whole design: SDES has no key agreement of its own and inherits every bit of
 * its security from the signalling channel.  Over plain UDP it is theatre --
 * anyone who can read the INVITE can read the key and therefore the audio.
 * This is why [SrtpKeys.generate] is only ever reached with TLS signalling on,
 * and why the setting says so.
 */

/** A crypto suite from RFC 4568 §6.2, named exactly as it appears in SDP. */
enum class SrtpCryptoSuite(
    val sdpName: String,
    val keyLen: Int,
    val saltLen: Int,
    val authTagLen: Int
) {
    AES_CM_128_HMAC_SHA1_80("AES_CM_128_HMAC_SHA1_80", 16, 14, 10),
    AES_CM_128_HMAC_SHA1_32("AES_CM_128_HMAC_SHA1_32", 16, 14, 4);

    /** Bytes carried in the `inline:` parameter: master key then master salt. */
    val inlineLen: Int get() = keyLen + saltLen

    companion object {
        /** Offered in this order; the first the peer also supports wins. */
        val preferred = listOf(AES_CM_128_HMAC_SHA1_80, AES_CM_128_HMAC_SHA1_32)

        fun byName(name: String): SrtpCryptoSuite? =
            entries.firstOrNull { it.sdpName.equals(name, ignoreCase = true) }
    }
}

/**
 * One master key and salt for one direction, plus the suite they belong to.
 *
 * RFC 4568 concatenates the two in a single base64 blob, so they are kept
 * together rather than passed around as a loose pair that could be mismatched.
 */
class SrtpKeys(
    val suite: SrtpCryptoSuite,
    val masterKey: ByteArray,
    val masterSalt: ByteArray
) {
    init {
        require(masterKey.size == suite.keyLen) { "master key must be ${suite.keyLen} bytes" }
        require(masterSalt.size == suite.saltLen) { "master salt must be ${suite.saltLen} bytes" }
    }

    /** The `inline:` value: base64 of key||salt, no line wrapping. */
    fun toInline(): String =
        Base64.encodeToString(masterKey + masterSalt, Base64.NO_WRAP)

    companion object {
        private val random = SecureRandom()

        /** Fresh random keying material for one call in one direction. */
        fun generate(suite: SrtpCryptoSuite): SrtpKeys {
            val key = ByteArray(suite.keyLen)
            val salt = ByteArray(suite.saltLen)
            random.nextBytes(key)
            random.nextBytes(salt)
            return SrtpKeys(suite, key, salt)
        }

        /**
         * Parse the `inline:` parameter of an `a=crypto` line.
         *
         * Everything after the first `|` is lifetime and MKI, which this does
         * not use: no key is ever rekeyed inside a call here, and an MKI would
         * have to be echoed in every packet.  Returns null rather than throwing
         * on anything malformed -- a bad crypto line is a call that must fall
         * back or fail, not a crash in the SIP receive thread.
         */
        fun fromInline(suite: SrtpCryptoSuite, inlineValue: String): SrtpKeys? {
            val b64 = inlineValue.substringBefore('|').trim()
            val raw = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (raw.size < suite.inlineLen) return null
            return SrtpKeys(
                suite,
                raw.copyOfRange(0, suite.keyLen),
                raw.copyOfRange(suite.keyLen, suite.inlineLen)
            )
        }
    }
}

/**
 * The crypto state for one SSRC in one direction.
 *
 * Not thread-safe by itself: the send context is touched only by the capture
 * loop and the receive context only by the receive loop, which is how RtpSession
 * already partitions its work.
 */
class SrtpContext(private val keys: SrtpKeys) {

    private val suite = keys.suite

    // Session keys, derived once.  The master key never encrypts a packet
    // itself -- RFC 3711 §4.3 puts a KDF in between so that the key actually
    // used can be changed without renegotiating, and so the encryption and
    // authentication keys are independent of each other.
    private val sessionKey = derive(LABEL_RTP_ENCR, suite.keyLen)
    private val sessionSalt = derive(LABEL_RTP_SALT, suite.saltLen)
    private val sessionAuth = derive(LABEL_RTP_AUTH, AUTH_KEY_LEN)

    private val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    private val mac = Mac.getInstance("HmacSHA1").apply {
        init(SecretKeySpec(sessionAuth, "HmacSHA1"))
    }
    private val aesKey = SecretKeySpec(sessionKey, "AES")

    /** Rollover counter: the high 32 bits of the 48-bit packet index. */
    private var roc = 0L

    /** Highest sequence number seen, for rollover detection. */
    private var highestSeq = -1

    /** Sliding replay window, one bit per packet, ending at [highestSeq]. */
    private var replayWindow = 0L

    // ── Key derivation (RFC 3711 §4.3.1) ────────────────

    /**
     * PRF_n(master_key, x) where x is derived from the label and master salt.
     *
     * The counter block is `x || 0x0000`, and the keystream is AES-CTR over
     * zeros -- which is what "encrypt the all-zero block sequence" means in the
     * spec.  key_derivation_rate is 0 throughout, so the index term is zero and
     * only the label distinguishes the three keys.
     */
    private fun derive(label: Byte, length: Int): ByteArray {
        val iv = ByteArray(16)
        System.arraycopy(keys.masterSalt, 0, iv, 0, keys.masterSalt.size)
        // key_id = label || (index DIV kdr), right-aligned in the 112-bit salt.
        // With a 14-byte salt the label lands on byte 7 and the 48-bit index
        // on bytes 8..13, where it is zero and changes nothing.
        iv[7] = (iv[7].toInt() xor label.toInt()).toByte()

        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.masterKey, "AES"), IvParameterSpec(iv))
        return c.doFinal(ByteArray(length))
    }

    // ── Per-packet IV (RFC 3711 §4.1.1) ─────────────────

    /**
     * IV = (salt * 2^16) XOR (SSRC * 2^64) XOR (index * 2^16).
     *
     * Laid out over 16 bytes that puts the salt in 0..13, the SSRC in 4..7 and
     * the 48-bit index in 8..13.  The index is what makes the keystream unique
     * per packet, and reusing one with the same key is the classic way to
     * destroy a stream cipher's security outright -- which is why the rollover
     * counter is tracked rather than assumed to stay at zero.
     */
    private fun packetIv(ssrc: Long, index: Long): ByteArray {
        val iv = ByteArray(16)
        System.arraycopy(sessionSalt, 0, iv, 0, sessionSalt.size)
        iv[4] = (iv[4].toInt() xor ((ssrc ushr 24).toInt() and 0xFF)).toByte()
        iv[5] = (iv[5].toInt() xor ((ssrc ushr 16).toInt() and 0xFF)).toByte()
        iv[6] = (iv[6].toInt() xor ((ssrc ushr 8).toInt() and 0xFF)).toByte()
        iv[7] = (iv[7].toInt() xor (ssrc.toInt() and 0xFF)).toByte()
        iv[8] = (iv[8].toInt() xor ((index ushr 40).toInt() and 0xFF)).toByte()
        iv[9] = (iv[9].toInt() xor ((index ushr 32).toInt() and 0xFF)).toByte()
        iv[10] = (iv[10].toInt() xor ((index ushr 24).toInt() and 0xFF)).toByte()
        iv[11] = (iv[11].toInt() xor ((index ushr 16).toInt() and 0xFF)).toByte()
        iv[12] = (iv[12].toInt() xor ((index ushr 8).toInt() and 0xFF)).toByte()
        iv[13] = (iv[13].toInt() xor (index.toInt() and 0xFF)).toByte()
        return iv
    }

    private fun keystreamXor(ssrc: Long, index: Long, data: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, IvParameterSpec(packetIv(ssrc, index)))
        val ks = cipher.doFinal(ByteArray(len))
        for (i in 0 until len) data[off + i] = (data[off + i].toInt() xor ks[i].toInt()).toByte()
    }

    /** HMAC-SHA1 over the packet plus the ROC, truncated to the suite's tag. */
    private fun authTag(packet: ByteArray, len: Int, rocValue: Long): ByteArray {
        mac.reset()
        mac.update(packet, 0, len)
        mac.update(byteArrayOf(
            ((rocValue ushr 24) and 0xFF).toByte(),
            ((rocValue ushr 16) and 0xFF).toByte(),
            ((rocValue ushr 8) and 0xFF).toByte(),
            (rocValue and 0xFF).toByte()
        ))
        return mac.doFinal().copyOf(suite.authTagLen)
    }

    // ── Send ────────────────────────────────────────────

    /**
     * Encrypt and authenticate one RTP packet, returning the SRTP packet.
     *
     * The header travels in the clear -- SRTP encrypts only the payload, so
     * sequence numbers and SSRC stay readable to anything that has to route or
     * measure the stream.  The tag covers the header as well, so it cannot be
     * altered undetected.
     */
    fun protect(rtp: ByteArray): ByteArray? {
        if (rtp.size < RTP_HEADER_LEN) return null
        val ssrc = readSsrc(rtp)
        val seq = readSeq(rtp)

        // Our own sequence numbers only ever move forward, so a wrap is simply
        // a step from 0xFFFF back to a small number.
        if (highestSeq >= 0 && seq < highestSeq && highestSeq - seq > 0x8000) roc++
        highestSeq = seq
        val index = (roc shl 16) or seq.toLong()

        val out = rtp.copyOf(rtp.size + suite.authTagLen)
        keystreamXor(ssrc, index, out, RTP_HEADER_LEN, rtp.size - RTP_HEADER_LEN)
        val tag = authTag(out, rtp.size, roc)
        System.arraycopy(tag, 0, out, rtp.size, tag.size)
        return out
    }

    // ── Receive ─────────────────────────────────────────

    /**
     * Verify and decrypt one SRTP packet, or null if it fails.
     *
     * Null covers a forged or corrupted packet, a replay, and a packet that is
     * simply too short.  The caller drops it: there is nothing useful to do
     * with audio that failed authentication, and reporting it upward per packet
     * would turn a burst of loss into a log flood.
     */
    fun unprotect(srtp: ByteArray, length: Int = srtp.size): ByteArray? {
        val tagLen = suite.authTagLen
        if (length < RTP_HEADER_LEN + tagLen) return null

        val ssrc = readSsrc(srtp)
        val seq = readSeq(srtp)
        val payloadEnd = length - tagLen

        // Guess which rollover this packet belongs to before authenticating,
        // because the ROC is an input to the tag.  A wrong guess fails the
        // check and the packet is dropped, so a forgery cannot move the
        // counter -- only an authenticated packet is allowed to.
        val guessedRoc = estimateRoc(seq)
        val expected = authTag(srtp, payloadEnd, guessedRoc)
        var diff = 0
        for (i in 0 until tagLen) {
            diff = diff or (expected[i].toInt() xor srtp[payloadEnd + i].toInt())
        }
        // Constant-time: comparing byte by byte and returning early leaks how
        // much of the tag matched, which is enough to forge one a byte at a time.
        if (diff != 0) return null

        if (!replayCheck(seq, guessedRoc)) return null

        val index = (guessedRoc shl 16) or seq.toLong()
        val out = srtp.copyOfRange(0, payloadEnd)
        keystreamXor(ssrc, index, out, RTP_HEADER_LEN, out.size - RTP_HEADER_LEN)

        replayUpdate(seq, guessedRoc)
        return out
    }

    /** RFC 3711 §3.3.1: pick the rollover this sequence number belongs to. */
    private fun estimateRoc(seq: Int): Long {
        if (highestSeq < 0) return roc
        return when {
            highestSeq < 0x8000 ->
                if (seq - highestSeq > 0x8000) (roc - 1).coerceAtLeast(0) else roc
            highestSeq - 0x8000 > seq -> roc + 1
            else -> roc
        }
    }

    private fun replayCheck(seq: Int, packetRoc: Long): Boolean {
        if (highestSeq < 0) return true
        val index = (packetRoc shl 16) or seq.toLong()
        val highest = (roc shl 16) or highestSeq.toLong()
        val delta = index - highest
        return when {
            delta > 0 -> true
            -delta >= REPLAY_WINDOW -> false          // too old to judge
            else -> (replayWindow shr (-delta).toInt()) and 1L == 0L
        }
    }

    private fun replayUpdate(seq: Int, packetRoc: Long) {
        val index = (packetRoc shl 16) or seq.toLong()
        val highest = (roc shl 16) or highestSeq.toLong()
        if (highestSeq < 0 || index > highest) {
            val shift = if (highestSeq < 0) 0 else (index - highest).toInt()
            replayWindow = if (shift >= 64) 1L else (replayWindow shl shift) or 1L
            highestSeq = seq
            roc = packetRoc
        } else {
            replayWindow = replayWindow or (1L shl (highest - index).toInt())
        }
    }

    private fun readSeq(p: ByteArray): Int =
        ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)

    private fun readSsrc(p: ByteArray): Long =
        (((p[8].toInt() and 0xFF).toLong() shl 24) or
            ((p[9].toInt() and 0xFF).toLong() shl 16) or
            ((p[10].toInt() and 0xFF).toLong() shl 8) or
            (p[11].toInt() and 0xFF).toLong())

    companion object {
        const val RTP_HEADER_LEN = 12
        private const val AUTH_KEY_LEN = 20
        private const val REPLAY_WINDOW = 64

        private const val LABEL_RTP_ENCR: Byte = 0x00
        private const val LABEL_RTP_AUTH: Byte = 0x01
        private const val LABEL_RTP_SALT: Byte = 0x02
    }
}
