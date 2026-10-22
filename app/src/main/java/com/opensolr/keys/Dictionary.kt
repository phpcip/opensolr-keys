package com.opensolr.keys

import android.content.Context
import java.text.Normalizer

/** Offline word lists; corrections only for words that are not in the dictionary. */
object Dictionary {
    @Volatile private var words: HashMap<String, Int> = HashMap()
    @Volatile private var stripped: HashMap<String, String> = HashMap()
    @Volatile private var loadedLangs: Set<String> = emptySet()
    @Volatile private var loading = false

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzăâîșț'"

    fun ensureLoaded(ctx: Context, langs: Set<String>) {
        if (langs == loadedLangs || loading) return
        loading = true
        val app = ctx.applicationContext
        Thread {
            val w = HashMap<String, Int>(100_000)
            val s = HashMap<String, String>(100_000)
            val best = HashMap<String, Int>(100_000)
            for (lang in langs) {
                try {
                    app.assets.open("dict_$lang.txt").bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val sp = line.indexOf(' ')
                            if (sp <= 0) continue
                            val word = line.substring(0, sp)
                            val freq = line.substring(sp + 1).toIntOrNull() ?: continue
                            val prev = w[word]
                            if (prev == null || prev < freq) w[word] = freq
                            val key = strip(word)
                            if (key != word && (best[key] ?: 0) < freq) {
                                best[key] = freq
                                s[key] = word
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            words = w
            stripped = s
            loadedLangs = langs
            loading = false
        }.start()
    }

    fun strip(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    fun contains(word: String): Boolean = words.containsKey(word.lowercase())

    /** Returns the replacement for a mistyped word, with the typed capitalization, or null. */
    fun correct(typed: String): String? {
        if (words.isEmpty() || typed.length < 2) return null
        if (typed.length > 1 && typed.substring(1).any { it.isUpperCase() }) return null
        val lw = typed.lowercase()
        if (words.containsKey(lw)) return null
        if (!lw.all { it.isLetter() || it == '\'' }) return null
        val found = stripped[strip(lw)]?.takeIf { it != lw && words.containsKey(it) }
            ?: (if (lw.length >= 4) bestEdit(lw) else null)
            ?: return null
        return recase(typed, found)
    }

    private fun bestEdit(w: String): String? {
        var best: String? = null
        var bestFreq = 0
        fun consider(c: String) {
            val f = words[c] ?: return
            if (f > bestFreq) { bestFreq = f; best = c }
        }
        val n = w.length
        for (i in 0 until n) consider(w.substring(0, i) + w.substring(i + 1))
        for (i in 0 until n - 1) consider(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
        for (i in 0 until n) for (ch in ALPHABET) if (ch != w[i]) consider(w.substring(0, i) + ch + w.substring(i + 1))
        for (i in 0..n) for (ch in ALPHABET) consider(w.substring(0, i) + ch + w.substring(i))
        // a rare candidate is more likely a different word than a typo of this one
        return if (bestFreq >= 200) best else null
    }

    private fun recase(typed: String, word: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }
}
