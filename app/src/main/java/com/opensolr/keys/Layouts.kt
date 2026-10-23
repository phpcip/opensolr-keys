package com.opensolr.keys

enum class KType { CHAR, SHIFT, DELETE, MODE, EMOJI, SPACE, ENTER }

class Key(val type: KType, val text: String = "", val label: String = text, val weight: Float = 1f, val special: Boolean = false, val heightScale: Float = 1f) {
    var x = 0f; var y = 0f; var w = 0f; var h = 0f
    fun hit(px: Float, py: Float) = px >= x && px < x + w && py >= y && py < y + h
}

enum class Mode {
    LETTERS, NUMBERS, SYMBOLS,
    /** Number pads, opened by the field itself: a PIN or plain number, a number with sign or decimals, a phone number, a date or time. */
    PIN, DIGITS, PHONE, DATETIME;
    val pad: Boolean get() = this == PIN || this == DIGITS || this == PHONE || this == DATETIME
}

/** Long-press alternatives: the Gboard symbol for the key first, then accents (Romanian first). */
object Accents {
    private val symbols = mapOf(
        'q' to '%', 'w' to '\\', 'e' to '|', 'r' to '=', 't' to '[', 'y' to ']', 'u' to '<', 'i' to '>', 'o' to '{', 'p' to '}',
        'a' to '@', 's' to '#', 'd' to '$', 'f' to '_', 'g' to '&', 'h' to '-', 'j' to '+', 'k' to '(', 'l' to ')',
        'z' to '*', 'x' to '"', 'c' to '\'', 'v' to ':', 'b' to ';', 'n' to '!', 'm' to '?'
    )
    private val accents = mapOf(
        'a' to "ăâáàäãåā", 'e' to "éèêëēę", 'i' to "îíìïī", 'o' to "öóòôõøō", 'u' to "üúùûū",
        's' to "șşßśš", 't' to "țţ", 'c' to "çćč", 'n' to "ñń", 'z' to "žźż", 'y' to "ÿý", 'l' to "ł",
        'g' to "ğ", 'd' to "đ", '-' to "–—•", '$' to "€£¥₩₽", '"' to "„“”«»", '\'' to "‘’`", '.' to "@\"'_-?!;/:,.",
        '?' to "¿", '!' to "¡", ',' to "@\"'_-?!;/:,.",
        '1' to "¹½⅓¼⅛", '2' to "²⅔", '3' to "³¾⅜", '4' to "⁴", '5' to "⁵⅝", '6' to "⁶", '7' to "⁷⅞", '8' to "⁸", '9' to "⁹", '0' to "⁰ⁿ∅°"
    )
    /** Which alternative starts selected: the comma on the period and comma keys, the first one elsewhere. */
    fun defaultIndex(ch: String, alts: String): Int = if (ch == "," || ch == ".") alts.indexOf(',').coerceAtLeast(0) else 0

    /** The small symbol drawn in the key's corner. */
    fun hint(ch: String): String? = if (ch.length == 1) symbols[ch[0]]?.toString() else null

    fun of(ch: String, shifted: Boolean): String? {
        if (ch.length != 1) return null
        val c = ch[0].lowercaseChar()
        val sym = symbols[c]?.toString() ?: ""
        val acc = accents[c] ?: ""
        val alts = sym + (if (shifted) acc.uppercase() else acc)
        return alts.ifEmpty { null }
    }
}

object Layouts {
    private fun chars(s: String) = s.map { Key(KType.CHAR, it.toString()) }

    fun rows(mode: Mode): List<List<Key>> = when (mode) {
        Mode.LETTERS -> listOf(
            chars("1234567890"),
            chars("qwertyuiop"),
            chars("asdfghjkl"),
            listOf(Key(KType.SHIFT, weight = 1.3f)) + chars("zxcvbnm") + Key(KType.DELETE, weight = 1.3f),
            bottom("123")
        )
        Mode.NUMBERS -> listOf(
            chars("1234567890"),
            chars("-/:;()$&@\""),
            listOf(Key(KType.MODE, "#+=", weight = 1.3f)) + chars(".,?!'") + Key(KType.DELETE, weight = 1.3f),
            bottom("ABC")
        )
        // Pads: big keys in columns; only the characters the field takes, return and delete
        Mode.PIN -> listOf(chars("123"), chars("456"), chars("789"),
            listOf(Key(KType.DELETE, special = true), Key(KType.CHAR, "0"), Key(KType.ENTER, "\n", "return")))
        Mode.DIGITS -> pad("-+", ".,")
        Mode.PHONE -> pad("-+", "*#")
        Mode.DATETIME -> pad("/:", "-.")
        Mode.SYMBOLS -> listOf(
            chars("[]{}#%^*+="),
            chars("_\\|~<>€£¥•"),
            listOf(Key(KType.MODE, "123", weight = 1.3f)) + chars(".,?!'") + Key(KType.DELETE, weight = 1.3f),
            bottom("ABC")
        )
    }

    // The bottom row is taller (the first key's heightScale sets the row) and its side keys narrower, so the space bar is as big as it gets
    /** 1-9 with [side] down the right, then [bottom] around 0 and return; delete under the side column. */
    private fun pad(side: String, bottom: String) = listOf(
        chars("123") + Key(KType.CHAR, side[0].toString(), special = true),
        chars("456") + Key(KType.CHAR, side[1].toString(), special = true),
        chars("789") + Key(KType.DELETE, special = true),
        listOf(Key(KType.CHAR, bottom[0].toString(), special = true), Key(KType.CHAR, "0"), Key(KType.CHAR, bottom[1].toString(), special = true), Key(KType.ENTER, "\n", "return"))
    )

    private fun bottom(modeLabel: String) = listOf(
        Key(KType.MODE, modeLabel, weight = 1.1f, heightScale = 1.3f),
        Key(KType.SPACE, " ", "space", weight = 0f),
        Key(KType.CHAR, ".", weight = 0.85f, special = true),
        Key(KType.ENTER, "\n", "return", weight = 1.1f)
    )
}
