package com.callagent.gateway.sms

import java.text.Normalizer

/**
 * Reduce message text to plain ASCII before it reaches the modem.
 *
 * This exists for one concrete failure.  The GSM 7-bit alphabet has its own
 * code points for the accented characters German needs -- `ü` is 0x7E, `ß` is
 * 0x1E -- and Android encodes them correctly.  Some radios and service
 * centres then hand those septets on as if they were ASCII, and the recipient
 * sees the ASCII glyph for the same byte: `ü` arrives as `~`, `ß` as an
 * invisible control character, `ä` as `{`, `ö` as `|`.  Nothing is lost in
 * transit; it is decoded against the wrong table, and neither end can tell.
 *
 * Sending only characters whose GSM7 and ASCII values agree sidesteps that
 * entirely.  The cost is spelling: `für` becomes `fuer`.  That is the
 * conventional ASCII spelling of German and reads correctly, which a message
 * full of `~` and `{` does not.
 *
 * The alternative would be forcing UCS-2, which preserves the text exactly --
 * but Android chooses the encoding itself and offers no way to ask, precisely
 * because these characters *are* representable in GSM7, and it costs 70
 * characters per part instead of 160.
 *
 * Which is also why this stops where GSM7 does.  A script that has no place
 * in the 7-bit alphabet at all -- Cyrillic, Hebrew, Arabic, Greek, CJK --
 * already forces Android to UCS-2 on its own, and UCS-2 is not affected by
 * the mis-decode: it never touches the table that gets read wrong.  Such text
 * is therefore left exactly as it came in.  See [toAsciiOrNull], which is what
 * callers working around the modem should use; [toAscii] folds unconditionally
 * and will spell an entire Russian message as '?'.
 */
object Transliterate {

    /**
     * Spellings that are conventions rather than accents, so
     * decomposition would get them wrong: it turns `ü` into `u`, not `ue`,
     * and cannot do anything at all with `ß`.  Applied first.
     */
    private val explicit = linkedMapOf(
        "ä" to "ae", "ö" to "oe", "ü" to "ue",
        "Ä" to "Ae", "Ö" to "Oe", "Ü" to "Ue",
        "ß" to "ss", "ẞ" to "Ss",
        "æ" to "ae", "Æ" to "Ae",
        "œ" to "oe", "Œ" to "Oe",
        "ø" to "oe", "Ø" to "Oe",
        "å" to "aa", "Å" to "Aa",
        "þ" to "th", "Þ" to "Th",
        "ð" to "d", "Ð" to "D",
        "ł" to "l", "Ł" to "L",
        // Punctuation that word processors and language models emit freely
        // and that no 7-bit alphabet has.
        "—" to "-", "–" to "-", "‑" to "-",
        "…" to "...",
        "‘" to "'", "’" to "'", "‚" to "'",
        "“" to "\"", "”" to "\"", "„" to "\"",
        "«" to "\"", "»" to "\"",
        "→" to "->", "←" to "<-", "⇒" to "=>",
        "•" to "*", "●" to "*", "·" to "-",
        "\u20ac" to "EUR", "\u00a3" to "GBP",
        // Spaces that are not the ASCII space: non-breaking, thin, narrow.
        "\u00a0" to " ", "\u2009" to " ", "\u202f" to " "
    )

    /** True when [text] would go out unchanged, so callers can skip the work. */
    fun isPlainAscii(text: String): Boolean = text.all { it.code in 32..126 || it == '\n' || it == '\r' }

    /**
     * ASCII-only rendering of [text].  Characters with no sensible spelling
     * become '?', which at least shows something was dropped rather than
     * silently deleting it.
     */
    fun toAscii(text: String): String {
        if (isPlainAscii(text)) return text
        val s = fold(text)
        return buildString(s.length) {
            for (c in s) append(if (sendable(c)) c else '?')
        }
    }

    /**
     * ASCII rendering of [text], or null when some of it has no ASCII
     * spelling at all.
     *
     * Null is the answer that matters.  The mis-decode this class exists for
     * can only happen to a message the modem encodes as GSM 7-bit, and a
     * single unspellable character forces the whole message to UCS-2 instead
     * — where every code point travels as itself and nothing is read against
     * the wrong table.  So text this cannot fold is text that was never at
     * risk, and folding it anyway would replace a message that would have
     * arrived intact with a row of '?'.  Cyrillic, Hebrew, Arabic, Greek and
     * CJK all land here, as does anything mixing them with Latin.
     */
    fun toAsciiOrNull(text: String): String? {
        if (isPlainAscii(text)) return text
        val s = fold(text)
        return if (s.all { sendable(it) }) s else null
    }

    /**
     * Explicit spellings first, then decomposition for anything merely
     * accented, so é becomes e.  Whatever is still not ASCII afterwards has
     * no ASCII spelling; the callers differ only in what they do about that.
     */
    private fun fold(text: String): String {
        var s = text
        for ((from, to) in explicit) s = s.replace(from, to)
        return Normalizer.normalize(s, Normalizer.Form.NFD).replace(COMBINING, "")
    }

    private fun sendable(c: Char): Boolean =
        c.code in 32..126 || c == '\n' || c == '\r' || c == '\t'

    private val COMBINING = Regex("\\p{Mn}+")
}
