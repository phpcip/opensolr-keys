package com.opensolr.keys

enum class KType { CHAR, SHIFT, DELETE, MODE, EMOJI, SPACE, ENTER }

class Key(val type: KType, val text: String = "", val label: String = text, val weight: Float = 1f) {
    var x = 0f; var y = 0f; var w = 0f; var h = 0f
    fun hit(px: Float, py: Float) = px >= x && px < x + w && py >= y && py < y + h
}

enum class Mode { LETTERS, NUMBERS, SYMBOLS }

/** Long-press alternatives, Romanian first. */
object Accents {
    private val map = mapOf(
        'a' to "ăâáàäãåā", 'e' to "éèêëēę", 'i' to "îíìïī", 'o' to "öóòôõøō", 'u' to "üúùûū",
        's' to "șßśš", 't' to "ț", 'c' to "çćč", 'n' to "ñń", 'z' to "žźż", 'y' to "ÿ", 'l' to "ł",
        'g' to "ğ", 'd' to "đ", '-' to "–—•", '$' to "€£¥₩₽", '"' to "„“”«»", '\'' to "‘’`", '.' to "…",
        '?' to "¿", '!' to "¡", '0' to "°"
    )
    fun of(ch: String, shifted: Boolean): String? {
        if (ch.length != 1) return null
        val alts = map[ch[0].lowercaseChar()] ?: return null
        return if (shifted) alts.uppercase() else alts
    }
}

object Layouts {
    private fun chars(s: String) = s.map { Key(KType.CHAR, it.toString()) }

    fun rows(mode: Mode): List<List<Key>> = when (mode) {
        Mode.LETTERS -> listOf(
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
        Mode.SYMBOLS -> listOf(
            chars("[]{}#%^*+="),
            chars("_\\|~<>€£¥•"),
            listOf(Key(KType.MODE, "123", weight = 1.3f)) + chars(".,?!'") + Key(KType.DELETE, weight = 1.3f),
            bottom("ABC")
        )
    }

    private fun bottom(modeLabel: String) = listOf(
        Key(KType.MODE, modeLabel, weight = 1.3f),
        Key(KType.EMOJI, weight = 1.3f),
        Key(KType.SPACE, " ", "space", weight = 0f),
        Key(KType.ENTER, "\n", "return", weight = 1.3f)
    )
}
