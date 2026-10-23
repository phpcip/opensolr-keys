package com.opensolr.keys

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import kotlin.math.ln

/** Offline word and word-pair tables; corrections are chosen by the words around them. */
object Dictionary {
    /**
     * Compact tables: every word once, in sorted arrays, so six hundred thousand forms fit in tens of MB.
     * An id is a word's index in [words]; a family (the same letters without diacritics) is a range of [members].
     */
    private class Model(
        val words: Array<String>,
        val freq: IntArray,
        /** Sorted; each entry is (id1 << 44) | (id2 << 24) | count (ids up to a million, counts up to 16M), so one primitive sort does it all. */
        val bi: LongArray,
        /** Every distinct form without diacritics, sorted; the family of plainKeys[k] is members[plainStart[k] until plainStart[k+1]], by count. */
        val plainKeys: Array<String>,
        val plainStart: IntArray,
        val members: IntArray,
        val plainFreq: IntArray
    ) {
        fun idOf(word: String): Int { val i = words.binarySearch(word); return if (i >= 0) i else -1 }
        fun freqOf(word: String): Int { val i = idOf(word); return if (i >= 0) freq[i] else 0 }
        fun contains(word: String) = idOf(word) >= 0
        fun family(plain: String): List<String> {
            val k = plainKeys.binarySearch(plain)
            if (k < 0) return emptyList()
            return (plainStart[k] until plainStart[k + 1]).map { words[members[it]] }
        }
    }

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
    private const val SHORT_TYPO_FREQ = 1_000_000

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
            // words of every language merged by spelling (counts added), then sorted: the id is the sorted index
            val freqOf = HashMap<String, Int>(700_000)
            val pairs = ArrayList<LongArray>()
            val locals = ArrayList<Array<String>>()
            for (lang in langs) {
                try {
                    val d = ByteBuffer.wrap(app.assets.open("dict_$lang.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                    val count = d.int
                    val local = Array(count) { "" }
                    for (i in 0 until count) {
                        val len = d.get().toInt() and 0xff
                        val bytes = ByteArray(len); d.get(bytes)
                        val word = String(bytes, Charsets.UTF_8)
                        local[i] = word
                        freqOf[word] = (freqOf[word] ?: 0) + d.int
                    }
                    val b = ByteBuffer.wrap(app.assets.open("bigrams_$lang.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                    val m = b.int
                    val raw = LongArray(m) { b.long }
                    pairs.add(raw); locals.add(local)
                } catch (_: Exception) {
                }
            }
            // Rude words are never rare: a typo near one reaches it, and no tamer word wins over it
            for (w in Fun.PROTECTED) freqOf[w] = maxOf(freqOf[w] ?: 0, PROTECTED_FREQ)
            val words = freqOf.keys.toTypedArray(); words.sort()
            val freq = IntArray(words.size) { freqOf.getValue(words[it]) }
            freqOf.clear()
            // pairs: each file's local ids become sorted ids; the merged table is sorted once
            var n = 0
            for (raw in pairs) n += raw.size
            val bi = LongArray(n); n = 0
            for ((f, raw) in pairs.withIndex()) {
                val local = locals[f]
                val ids = IntArray(local.size) { words.binarySearch(local[it]) }
                for (v in raw) {
                    val g1 = ids[(v ushr 44).toInt()].toLong()
                    val g2 = ids[((v ushr 24) and 0xfffff).toInt()].toLong()
                    bi[n++] = (g1 shl 44) or (g2 shl 24) or (v and 0xffffff)
                }
            }
            bi.sort()
            // families: ids grouped by their form without diacritics, each family by count
            val plainOf = Array(words.size) { strip(words[it]) }
            val order = (0 until words.size).sortedWith(compareBy<Int> { plainOf[it] }.thenByDescending { freq[it] })
            val keys = ArrayList<String>(words.size); val starts = ArrayList<Int>(words.size); val sums = ArrayList<Int>(words.size)
            val members = IntArray(words.size)
            var last: String? = null
            for ((pos, id) in order.withIndex()) {
                members[pos] = id
                val k = plainOf[id]
                if (k != last) { keys.add(k); starts.add(pos); sums.add(0); last = k }
                sums[sums.size - 1] = sums[sums.size - 1] + freq[id]
            }
            starts.add(words.size)
            model = Model(words, freq, bi, keys.toTypedArray(), starts.toIntArray(), members, sums.toIntArray())
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

    /**
     * How likely each letter a..z is to follow [prefix], from the words that start with it, as a share of 1;
     * null when nothing is known (no prefix, no words). Binary search for the range, one pass over it.
     */
    fun nextLetters(prefix: String): FloatArray? {
        val m = model ?: return null
        val pre = strip(prefix.lowercase())
        if (pre.isEmpty() || pre.any { it !in 'a'..'z' }) return null
        var lo = 0; var hi = m.plainKeys.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (m.plainKeys[mid] < pre) lo = mid + 1 else hi = mid }
        val out = FloatArray(26)
        var total = 0.0
        var i = lo
        while (i < m.plainKeys.size && m.plainKeys[i].startsWith(pre)) {
            val w = m.plainKeys[i]
            if (w.length > pre.length) {
                val c = w[pre.length]
                if (c in 'a'..'z') { val f = m.plainFreq[i].toFloat(); out[c - 'a'] += f; total += f }
            }
            i++
        }
        if (total <= 0.0) return null
        for (j in 0 until 26) out[j] = (out[j] / total).toFloat()
        return out
    }

    fun contains(word: String): Boolean = model?.contains(word.lowercase()) == true

    private fun bigram(m: Model, a: String?, b: String?): Int {
        if (a == null || b == null) return 0
        val i1 = m.idOf(a); if (i1 < 0) return 0
        val i2 = m.idOf(b); if (i2 < 0) return 0
        val key = (i1.toLong() shl 44) or (i2.toLong() shl 24)
        var lo = 0; var hi = m.bi.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = m.bi[mid] and (-1L shl 24)
            when {
                k < key -> lo = mid + 1
                k > key -> hi = mid - 1
                else -> return (m.bi[mid] and 0xffffff).toInt()
            }
        }
        return 0
    }

    private fun score(m: Model, prev: String?, c: String, next: String?): Double =
        ln(1.0 + bigram(m, prev, c)) + ln(1.0 + bigram(m, c, next)) + UNI_WEIGHT * ln(1.0 + m.freqOf(c))

    /** All the words the typed one could have meant: same letters without diacritics, confusables, close typos. */
    private fun candidates(m: Model, lw: String): List<String> {
        val out = LinkedHashSet<String>()
        out.addAll(m.family(strip(lw)))
        confusables[lw]?.let { out.addAll(it) }
        // typos of three letters reach only the commonest words (teh → the); longer ones any word
        if (!m.contains(lw) && lw.length >= 3) out.addAll(edits(m, lw).filter { lw.length >= 4 || m.freqOf(it) >= SHORT_TYPO_FREQ })
        out.remove(lw)
        return out.toList()
    }

    /** True when the context could change this word later (several readings exist). */
    fun isAmbiguous(typed: String): Boolean {
        val m = model ?: return false
        val lw = typed.lowercase()
        return m.family(strip(lw)).size > 1 || confusables.containsKey(lw)
    }

    /**
     * The word to write instead of [typed] given the word before and (when known) after it,
     * with the typed capitalization, or null to leave it alone.
     */
    /** The most common spelling of a word's family (si → și), used as context for its neighbours. */
    fun canonical(word: String): String {
        val m = model ?: return word
        val lw = word.lowercase()
        return m.family(strip(lw)).firstOrNull() ?: lw
    }

    fun choose(prev: String?, typed: String, next: String?, revising: Boolean = false): String? {
        val m = model ?: return null
        if (typed.length < 2) return null
        if (typed.length > 1 && typed.substring(1).any { it.isUpperCase() }) return null
        val lw = typed.lowercase()
        if (!lw.all { it.isLetter() || it == '\'' }) return null
        if (Fun.protectedWord(lw)) return null
        if (!revising && lw != strip(lw)) return null
        val known = m.contains(lw)
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
        } else if (m.freqOf(b) < 200) return null
        return recase(typed, b)
    }

    /** Close typos of [w], found through the letters without diacritics, so mancarw reaches mâncare as well as mancare. */
    private fun edits(m: Model, w: String): List<String> {
        val found = HashMap<String, Int>()
        fun consider(c: String) {
            for (f in m.family(strip(c))) found[f] = m.freqOf(f)
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
