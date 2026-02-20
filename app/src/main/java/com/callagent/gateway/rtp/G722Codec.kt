package com.callagent.gateway.rtp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ITU-T G.722 codec (mode 1 = 64 kbps).
 *
 * Wideband audio: 16 kHz input/output, 7 kHz audio bandwidth.
 * Sub-band ADPCM: 24-tap QMF splits into lower (0-4 kHz) + upper (4-8 kHz)
 * bands, encoded with 6-bit + 2-bit ADPCM respectively.
 *
 * RTP: payload type 9, clock rate 8000 (historical), 160 bytes per 20ms.
 *
 * Ported from spandsp/ITU-T G.722 reference implementation.
 * Tables and algorithms match the ITU-T G.722 recommendation exactly.
 */
class G722Codec {

    // ── Per-band state ───────────────────────────────────

    private class Band {
        var s: Int = 0              // Reconstructed signal (PREDIC: sp + sz)
        var sz: Int = 0             // Zero-section predictor output (FILTEZ)
        val r = IntArray(3)         // Reconstructed signal memory (RECONS)
        val p = IntArray(3)         // Partial reconstruction memory (PARREC)
        val a = IntArray(3)         // Pole predictor coefficients
        val b = IntArray(7)         // Zero predictor coefficients
        val ap = IntArray(3)        // New pole coefficients (committed in DELAYA)
        val bp = IntArray(7)        // New zero coefficients (committed in DELAYA)
        val d = IntArray(7)         // Quantized difference memory
        val sg = IntArray(7)        // Sign buffer
        var nb: Int = 0             // Log step size
        var det: Int = 32           // Linear step size
    }

    // ── Encoder state ────────────────────────────────────

    private val encBand = arrayOf(Band(), Band().apply { det = 8 })
    private val encX = IntArray(24)     // QMF analysis filter buffer

    // ── Decoder state ────────────────────────────────────

    private val decBand = arrayOf(Band(), Band().apply { det = 8 })
    private val decX = IntArray(24)     // QMF synthesis filter buffer

    // ── Encode ───────────────────────────────────────────

    /**
     * Encode 16-bit PCM at 16 kHz to G.722.
     * @param pcm 16-bit signed PCM samples at 16 kHz
     * @return G.722 encoded bytes (1 byte per 2 input samples)
     */
    fun encode(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size / 2)
        var outIdx = 0
        var j = 0
        while (j < pcm.size - 1) {
            // QMF analysis: 2 PCM samples → xlow, xhigh
            val xin0 = pcm[j++].toInt()
            val xin1 = pcm[j++].toInt()
            val (xlow, xhigh) = txQmf(xin0, xin1)

            // ── Lower band encode (BLOCK 1L) ──
            val el = sat(xlow - encBand[0].s)
            val sil = el shr 15
            val wd = if (sil == 0) el else -(el + 1)      // ones-complement abs
            var mil = 0
            for (i in 1 until 30) {
                if (wd < (q6[i].toLong() * encBand[0].det shr 12).toInt()) {
                    mil = i
                    break
                }
            }
            if (mil == 0) mil = 30
            val ilow = if (sil == 0) ilp[mil] else iln[mil]

            // ── Lower band inverse quantize (BLOCK 2L) ──
            val ril = ilow shr 2     // 4-bit for adaptation
            val dlow = (encBand[0].det.toLong() * qm4[ril] shr 15).toInt()

            // ── Lower band predictor update ──
            block4(encBand[0], dlow)

            // ── Lower band step size (BLOCK 3L) ──
            block3l(encBand[0], ril)

            // ── Upper band encode (BLOCK 1H) ──
            val eh = sat(xhigh - encBand[1].s)
            val sih = eh shr 15
            val wdh = if (sih == 0) eh else -(eh + 1)
            val wdh1 = (564L * encBand[1].det shr 12).toInt()
            val mih = if (wdh >= wdh1) 2 else 1
            val ihigh = if (sih == 0) ihp[mih] else ihn[mih]

            // ── Upper band inverse quantize (BLOCK 2H) ──
            val dhigh = (encBand[1].det.toLong() * qm2[ihigh] shr 15).toInt()

            // ── Upper band predictor update ──
            block4(encBand[1], dhigh)

            // ── Upper band step size (BLOCK 3H) ──
            block3h(encBand[1], ihigh)

            // Pack: bits 7-6 = upper (IH), bits 5-0 = lower (IL)
            out[outIdx++] = ((ihigh shl 6) or ilow).toByte()
        }
        return out
    }

    /** Encode from raw byte array (little-endian PCM16). */
    fun encode(pcmBytes: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        val shorts = ShortArray(pcmBytes.size / 2)
        buf.asShortBuffer().get(shorts)
        return encode(shorts)
    }

    // ── Decode ───────────────────────────────────────────

    /**
     * Decode G.722 to 16-bit PCM at 16 kHz.
     * @param g722 encoded bytes (mode 1 = 64 kbps)
     * @return PCM samples (2 per input byte)
     */
    fun decode(g722: ByteArray): ShortArray {
        val out = ShortArray(g722.size * 2)
        var outIdx = 0

        for (byte in g722) {
            val code = byte.toInt() and 0xFF
            // Bits 5-0 = lower band (IL), bits 7-6 = upper band (IH)
            val ilow = code and 0x3F
            val ihigh = (code shr 6) and 0x03

            // ── Lower band decode (BLOCK 5L, mode 1 = 64kbps) ──
            // Output path: full 6-bit resolution via qm6
            val dlow = (decBand[0].det.toLong() * qm6[ilow] shr 15).toInt()
            // Adaptation path: reduce to 4-bit via qm4
            val ril = ilow shr 2
            val dlowt = (decBand[0].det.toLong() * qm4[ril] shr 15).toInt()

            // Reconstructed lower: OLD prediction + output-path difference
            // Must be computed BEFORE block4 overwrites band.s
            val rlow = sat(decBand[0].s + dlow)

            // Predictor update uses adaptation path (dlowt)
            block4(decBand[0], dlowt)
            block3l(decBand[0], ril)

            // ── Upper band decode (BLOCK 5H) ──
            val dhigh = (decBand[1].det.toLong() * qm2[ihigh] shr 15).toInt()

            // Reconstructed upper: OLD prediction + difference
            val rhigh = sat(decBand[1].s + dhigh)

            block4(decBand[1], dhigh)
            block3h(decBand[1], ihigh)

            // ── QMF synthesis: rlow, rhigh → 2 PCM samples ──
            val (xout1, xout2) = rxQmf(rlow, rhigh)
            out[outIdx++] = sat(xout1).toShort()
            out[outIdx++] = sat(xout2).toShort()
        }
        return out
    }

    // Pre-allocated decode buffer for zero-alloc path.
    // 160 G.722 bytes → 320 samples → 640 PCM bytes (20ms at 16kHz).
    private var decodeBuf: ByteArray = ByteArray(PCM_BYTES_20MS)

    /** Decode to raw byte array (little-endian PCM16). */
    fun decodeToBytes(g722: ByteArray): ByteArray {
        val shorts = decode(g722)
        val needed = shorts.size * 2
        if (decodeBuf.size < needed) decodeBuf = ByteArray(needed)
        for (i in shorts.indices) {
            val s = shorts[i].toInt()
            decodeBuf[i * 2] = (s and 0xFF).toByte()
            decodeBuf[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return decodeBuf
    }

    // ── QMF Analysis Filter (encoder) ────────────────────

    private fun txQmf(xin0: Int, xin1: Int): Pair<Int, Int> {
        // Shift buffer left by 2, insert new samples at end
        for (i in 0 until 22) encX[i] = encX[i + 2]
        encX[22] = xin0
        encX[23] = xin1

        var sumEven = 0L
        var sumOdd = 0L
        for (i in 0 until 12) {
            sumOdd += encX[2 * i].toLong() * QMF_COEFFS[i]
            sumEven += encX[2 * i + 1].toLong() * QMF_COEFFS[11 - i]
        }
        val xlow = ((sumEven + sumOdd) shr 14).toInt()
        val xhigh = ((sumEven - sumOdd) shr 14).toInt()
        return Pair(xlow, xhigh)
    }

    // ── QMF Synthesis Filter (decoder) ───────────────────

    private fun rxQmf(rlow: Int, rhigh: Int): Pair<Int, Int> {
        for (i in 0 until 22) decX[i] = decX[i + 2]
        decX[22] = rlow + rhigh
        decX[23] = rlow - rhigh

        var sumEven = 0L
        var sumOdd = 0L
        for (i in 0 until 12) {
            sumOdd += decX[2 * i].toLong() * QMF_COEFFS[i]
            sumEven += decX[2 * i + 1].toLong() * QMF_COEFFS[11 - i]
        }
        val xout1 = (sumEven shr 11).toInt()
        val xout2 = (sumOdd shr 11).toInt()
        return Pair(xout1, xout2)
    }

    // ── Predictor + Reconstruction (block4) ──────────────
    // Combines RECONS, PARREC, UPPOL2, UPPOL1, UPZERO,
    // FILTEZ, FILTEP, PREDIC, and DELAYA from the ITU spec.

    private fun block4(band: Band, d: Int) {
        // ── RECONS: reconstruct signal ──
        band.d[0] = d
        band.r[0] = sat(band.s + d)

        // ── PARREC: partial reconstruction ──
        band.p[0] = sat(band.sz + d)

        // ── UPPOL2: update 2nd-order pole coefficient ──
        for (i in 0 until 3) band.sg[i] = band.p[i] shr 15

        var wd1 = sat(band.a[1] shl 2)
        var wd2 = if (band.sg[0] == band.sg[1]) -wd1 else wd1
        if (wd2 > 32767) wd2 = 32767

        var wd3 = if (band.sg[0] == band.sg[2]) 128 else -128
        wd3 += wd2 shr 7
        wd3 += (band.a[2].toLong() * 32512 shr 15).toInt()
        band.ap[2] = wd3.coerceIn(-12288, 12288)

        // ── UPPOL1: update 1st-order pole coefficient ──
        band.sg[0] = band.p[0] shr 15
        band.sg[1] = band.p[1] shr 15

        wd1 = if (band.sg[0] == band.sg[1]) 192 else -192
        wd2 = (band.a[1].toLong() * 32640 shr 15).toInt()
        band.ap[1] = sat(wd1 + wd2)

        wd3 = sat(15360 - band.ap[2])
        if (band.ap[1] > wd3) band.ap[1] = wd3
        else if (band.ap[1] < -wd3) band.ap[1] = -wd3

        // ── UPZERO: update zero-section coefficients ──
        wd1 = if (d == 0) 0 else 128
        band.sg[0] = d shr 15
        for (i in 1 until 7) {
            band.sg[i] = band.d[i] shr 15
            wd2 = if (band.sg[i] == band.sg[0]) wd1 else -wd1
            wd3 = (band.b[i].toLong() * 32640 shr 15).toInt()
            band.bp[i] = sat(wd2 + wd3)
        }

        // ── DELAYA: shift delay lines and commit new coefficients ──
        for (i in 6 downTo 1) {
            band.d[i] = band.d[i - 1]
            band.b[i] = band.bp[i]
        }
        for (i in 2 downTo 1) {
            band.r[i] = band.r[i - 1]
            band.p[i] = band.p[i - 1]
            band.a[i] = band.ap[i]
        }

        // ── FILTEP: pole section of predictor ──
        wd1 = sat(band.r[1] + band.r[1])
        wd1 = (band.a[1].toLong() * wd1 shr 15).toInt()
        wd2 = sat(band.r[2] + band.r[2])
        wd2 = (band.a[2].toLong() * wd2 shr 15).toInt()
        val sp = sat(wd1 + wd2)

        // ── FILTEZ: zero section of predictor ──
        var sz = 0
        for (i in 6 downTo 1) {
            wd1 = sat(band.d[i] + band.d[i])
            sz += (band.b[i].toLong() * wd1 shr 15).toInt()
        }
        band.sz = sat(sz)

        // ── PREDIC: final prediction ──
        band.s = sat(sp + band.sz)
    }

    // ── Step Size Adaptation ─────────────────────────────

    // BLOCK 3L: lower band log scale + step size
    private fun block3l(band: Band, ril: Int) {
        val il4 = rl42[ril]
        var wd = (band.nb.toLong() * 127 shr 7).toInt()        // leak
        band.nb = (wd + wl[il4]).coerceIn(0, 18432)
        band.det = scalel(band.nb)
    }

    // BLOCK 3H: upper band log scale + step size
    private fun block3h(band: Band, ihigh: Int) {
        val ih2 = rh2[ihigh]
        var wd = (band.nb.toLong() * 127 shr 7).toInt()        // leak
        band.nb = (wd + wh[ih2]).coerceIn(0, 22528)
        band.det = scaleh(band.nb)
    }

    // SCALEL: log-to-linear step size, low band
    private fun scalel(nb: Int): Int {
        val wd1 = (nb shr 6) and 31
        val wd2 = 8 - (nb shr 11)
        val wd3 = if (wd2 < 0) ilb[wd1] shl -wd2 else ilb[wd1] shr wd2
        return wd3 shl 2
    }

    // SCALEH: log-to-linear step size, high band (exponent base 10)
    private fun scaleh(nb: Int): Int {
        val wd1 = (nb shr 6) and 31
        val wd2 = 10 - (nb shr 11)
        val wd3 = if (wd2 < 0) ilb[wd1] shl -wd2 else ilb[wd1] shr wd2
        return wd3 shl 2
    }

    // ── Utilities ────────────────────────────────────────

    private fun sat(x: Int): Int = x.coerceIn(-32768, 32767)

    companion object {
        // QMF filter coefficients (ITU-T G.722 Table 1)
        private val QMF_COEFFS = intArrayOf(
            3, -11, 12, 32, -210, 951, 3876, -805, 362, -156, 53, -11
        )

        // Lower band quantizer decision levels (q6, 30 entries used)
        private val q6 = intArrayOf(
               0,   35,   72,  110,  150,  190,  233,  276,
             323,  370,  422,  473,  530,  587,  650,  714,
             786,  858,  940, 1023, 1121, 1219, 1339, 1458,
            1612, 1765, 1980, 2195, 2557, 2919,    0,    0
        )

        // Encoder: positive el → 6-bit code mapping
        private val ilp = intArrayOf(
             0, 61, 60, 59, 58, 57, 56, 55,
            54, 53, 52, 51, 50, 49, 48, 47,
            46, 45, 44, 43, 42, 41, 40, 39,
            38, 37, 36, 35, 34, 33, 32,  0
        )

        // Encoder: negative el → 6-bit code mapping
        private val iln = intArrayOf(
             0, 63, 62, 31, 30, 29, 28, 27,
            26, 25, 24, 23, 22, 21, 20, 19,
            18, 17, 16, 15, 14, 13, 12, 11,
            10,  9,  8,  7,  6,  5,  4,  0
        )

        // Lower band inverse quantizer, 4-bit (qm4) — used for adaptation path
        private val qm4 = intArrayOf(
                 0, -20456, -12896, -8968,
             -6288,  -4240,  -2584, -1200,
             20456,  12896,   8968,  6288,
              4240,   2584,   1200,     0
        )

        // Lower band inverse quantizer, 6-bit (qm6) — decoder output path
        private val qm6 = intArrayOf(
              -136,   -136,   -136,   -136,
            -24808, -21904, -19008, -16704,
            -14984, -13512, -12280, -11192,
            -10232,  -9360,  -8576,  -7856,
             -7192,  -6576,  -6000,  -5456,
             -4944,  -4464,  -4008,  -3576,
             -3168,  -2776,  -2400,  -2032,
             -1688,  -1360,  -1040,   -728,
             24808,  21904,  19008,  16704,
             14984,  13512,  12280,  11192,
             10232,   9360,   8576,   7856,
              7192,   6576,   6000,   5456,
              4944,   4464,   4008,   3576,
              3168,   2776,   2400,   2032,
              1688,   1360,   1040,    728,
               432,    136,   -432,   -136
        )

        // Upper band quantizer: positive eh → code
        private val ihp = intArrayOf(0, 3, 2)
        // Upper band quantizer: negative eh → code
        private val ihn = intArrayOf(0, 1, 0)

        // Upper band inverse quantizer (qm2)
        private val qm2 = intArrayOf(-7408, -1616, 7408, 1616)

        // Lower band step size adaptation: wl (8 entries)
        private val wl = intArrayOf(-60, -30, 58, 172, 334, 538, 1198, 3042)

        // Lower band: 4-bit code → wl index mapping
        private val rl42 = intArrayOf(0, 7, 6, 5, 4, 3, 2, 1, 7, 6, 5, 4, 3, 2, 1, 0)

        // Upper band step size adaptation: wh (3 entries)
        private val wh = intArrayOf(0, -214, 798)

        // Upper band: 2-bit code → wh index mapping
        private val rh2 = intArrayOf(2, 1, 2, 1)

        // Log-to-linear step size table (ilb)
        private val ilb = intArrayOf(
            2048, 2093, 2139, 2186, 2233, 2282, 2332, 2383,
            2435, 2489, 2543, 2599, 2656, 2714, 2774, 2834,
            2896, 2960, 3025, 3091, 3158, 3228, 3298, 3371,
            3444, 3520, 3597, 3676, 3756, 3838, 3922, 4008
        )

        const val FRAME_SIZE_20MS = 160
        const val PCM_SAMPLES_20MS = 320
        const val PCM_BYTES_20MS = 640
    }
}
