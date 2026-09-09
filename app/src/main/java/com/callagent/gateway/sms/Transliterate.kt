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
        var s = text
        for ((from, to) in explicit) s = s.replace(from, to)
        // Everything else that is merely accented: decompose and drop the
        // combining marks, so é becomes e rather than '?'.
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(COMBINING, "")
        return buildString(s.length) {
            for (c in s) {
                append(if (c.code in 32..126 || c == '\n' || c == '\r' || c == '\t') c else '?')
            }
        }
    }

    private val COMBINING = Regex("\\p{Mn}+")
}
