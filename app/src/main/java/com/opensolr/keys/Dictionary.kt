package com.opensolr.keys

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import kotlin.math.ln

/** Offline word and word-pair tables; corrections are chosen by the words around them. */
object Dictionary {
    private class Model(
        val freq: HashMap<String, Int>,
        val ids: HashMap<String, Int>,
        val classes: HashMap<String, ArrayList<String>>,
        /** Sorted; each entry is (id1 << 44) | (id2 << 26) | count, so one primitive sort does it all. */
        val bi: LongArray
    )

    @Volatile private var model: Model? = null
    @Volatile private var loadedLangs: Set<String> = emptySet()
    @Volatile private var loading = false

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzăâîșț'"
    private const val UNI_WEIGHT = 0.2
    private const val MARGIN_CONTEXT = 0.8
    private const val MARGIN_REVISE = 0.3
    private const val MARGIN_ALONE = 0.46
    /** A typed word that only lacks diacritics (masina → mașina) switches on a smaller lead: both spellings are in the corpus. */
    private const val MARGIN_DIACRITICS = 0.25
    private const val PROTECTED_FREQ = 40_000

    /** Words that are all valid but often swapped; the context picks between them. */
    private val confusables = listOf(
        listOf("their", "there", "they're"), listOf("its", "it's"), listOf("your", "you're"),
        listOf("then", "than"), listOf("to", "too"), listOf("were", "we're", "where"),
        listOf("whose", "who's"), listOf("lose", "loose"), listOf("affect", "effect"), listOf("of", "off")
    ).flatMap { set -> set.map { it to set } }.toMap()

    fun ensureLoaded(ctx: Context, langs: Set<String>) {
        if (langs == loadedLangs || loading) return
        loading = true
        val app = ctx.applicationContext
        Thread {
            val freq = HashMap<String, Int>(150_000)
            val ids = HashMap<String, Int>(150_000)
            val classes = HashMap<String, ArrayList<String>>(150_000)
            var bi = LongArray(0)
            var n = 0
            for (lang in langs) {
                try {
                    // words: count, then (len, utf8 bytes, freq) per word; local index = position
                    val d = ByteBuffer.wrap(app.assets.open("dict_$lang.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                    val count = d.int
                    val local = IntArray(count)
                    for (i in 0 until count) {
                        val len = d.get().toInt() and 0xff
                        val bytes = ByteArray(len); d.get(bytes)
                        val word = String(bytes, Charsets.UTF_8)
                        val c = d.int
                        var id = ids[word]
                        if (id == null) {
                            id = ids.size; ids[word] = id
                            classes.getOrPut(strip(word)) { ArrayList(2) }.add(word)
                        }
                        freq[word] = (freq[word] ?: 0) + c
                        local[i] = id
                    }
                    // pairs: count, then packed (id1 << 44 | id2 << 26 | freq) with local ids
                    val b = ByteBuffer.wrap(app.assets.open("bigrams_$lang.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                    val m = b.int
                    if (bi.size < n + m) bi = bi.copyOf(n + m)
                    for (i in 0 until m) {
                        val v = b.long
                        val g1 = local[(v ushr 44).toInt()].toLong()
                        val g2 = local[((v ushr 26) and 0x3ffff).toInt()].toLong()
                        bi[n++] = (g1 shl 44) or (g2 shl 26) or (v and 0x3ffffff)
                    }
                } catch (_: Exception) {
                }
            }
            // Rude words are never rare: a typo near one reaches it, and no tamer word wins over it
            for (w in Fun.PROTECTED) {
                var id = ids[w]
                if (id == null) {
                    id = ids.size; ids[w] = id
                    classes.getOrPut(strip(w)) { ArrayList(2) }.add(w)
                }
                freq[w] = maxOf(freq[w] ?: 0, PROTECTED_FREQ)
            }
            val packed = bi.copyOf(n)
            packed.sort()
            model = Model(freq, ids, classes, packed)
            loadedLangs = langs
            loading = false
        }.start()
    }

    private val marks = Regex("\\p{M}+")

    fun strip(s: String): String {
        var ascii = true
        for (ch in s) if (ch.code > 0x7f) { ascii = false; break }
        if (ascii) return s
        return Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "")
    }

    fun contains(word: String): Boolean = model?.freq?.containsKey(word.lowercase()) == true

    private fun bigram(m: Model, a: String?, b: String?): Int {
        if (a == null || b == null) return 0
        val i1 = m.ids[a] ?: return 0
        val i2 = m.ids[b] ?: return 0
        val key = (i1.toLong() shl 44) or (i2.toLong() shl 26)
        var lo = 0; var hi = m.bi.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = m.bi[mid] and (-1L shl 26)
            when {
                k < key -> lo = mid + 1
                k > key -> hi = mid - 1
                else -> return (m.bi[mid] and 0x3ffffff).toInt()
            }
        }
        return 0
    }

    private fun score(m: Model, prev: String?, c: String, next: String?): Double =
        ln(1.0 + bigram(m, prev, c)) + ln(1.0 + bigram(m, c, next)) + UNI_WEIGHT * ln(1.0 + (m.freq[c] ?: 0))

    /** All the words the typed one could have meant: same letters without diacritics, confusables, close typos. */
    private fun candidates(m: Model, lw: String): List<String> {
        val out = LinkedHashSet<String>()
        m.classes[strip(lw)]?.let { out.addAll(it) }
        confusables[lw]?.let { out.addAll(it) }
        if (!m.freq.containsKey(lw) && lw.length >= 4) out.addAll(edits(m, lw))
        out.remove(lw)
        return out.toList()
    }

    /** True when the context could change this word later (several readings exist). */
    fun isAmbiguous(typed: String): Boolean {
        val m = model ?: return false
        val lw = typed.lowercase()
        return (m.classes[strip(lw)]?.size ?: 0) > 1 || confusables.containsKey(lw)
    }

    /**
     * The word to write instead of [typed] given the word before and (when known) after it,
     * with the typed capitalization, or null to leave it alone.
     */
    /** The most common spelling of a word's family (si → și), used as context for its neighbours. */
    fun canonical(word: String): String {
        val m = model ?: return word
        val lw = word.lowercase()
        return m.classes[strip(lw)]?.maxByOrNull { m.freq[it] ?: 0 } ?: lw
    }

    fun choose(prev: String?, typed: String, next: String?, revising: Boolean = false): String? {
        val m = model ?: return null
        if (typed.length < 2) return null
        if (typed.length > 1 && typed.substring(1).any { it.isUpperCase() }) return null
        val lw = typed.lowercase()
        if (!lw.all { it.isLetter() || it == '\'' }) return null
        if (Fun.protectedWord(lw)) return null
        if (!revising && lw != strip(lw)) return null
        val known = m.freq.containsKey(lw)
        val cands = candidates(m, lw)
        if (cands.isEmpty()) return null
        val p = prev?.lowercase(); val n = next?.let { canonical(it) }
        var best: String? = null; var bestScore = Double.NEGATIVE_INFINITY
        for (c in cands) {
            val s = score(m, p, c, n)
            if (s > bestScore) { bestScore = s; best = c }
        }
        val b = best ?: return null
        if (known) {
            val margin = when {
                revising -> MARGIN_REVISE
                strip(b) == strip(lw) -> MARGIN_DIACRITICS
                p == null && n == null -> MARGIN_ALONE
                else -> MARGIN_CONTEXT
            }
            if (bestScore - score(m, p, lw, n) <= margin) return null
        } else if ((m.freq[b] ?: 0) < 200) return null
        return recase(typed, b)
    }

    /** Close typos of [w], found through the letters without diacritics, so mancarw reaches mâncare as well as mancare. */
    private fun edits(m: Model, w: String): List<String> {
        val found = HashMap<String, Int>()
        fun consider(c: String) {
            val forms = m.classes[strip(c)] ?: return
            for (f in forms) found[f] = m.freq[f] ?: 0
        }
        val n = w.length
        for (i in 0 until n) consider(w.substring(0, i) + w.substring(i + 1))
        for (i in 0 until n - 1) consider(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
        for (i in 0 until n) for (ch in ALPHABET) if (ch != w[i]) consider(w.substring(0, i) + ch + w.substring(i + 1))
        for (i in 0..n) for (ch in ALPHABET) consider(w.substring(0, i) + ch + w.substring(i))
        return found.entries.sortedByDescending { it.value }.take(6).map { it.key }
    }

    private fun recase(typed: String, word: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }
}
