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

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz'"
    private const val MIN_FIX_FREQ = 200
    /** A typo two slips away is fixed only in longer words, only to common ones, and only when the slips are likely ones. */
    private const val FAR_MIN_LENGTH = 6
    private const val FAR_MIN_FREQ = 3000
    private const val FAR_MAX_COST = 3.2
    /** Two words typed as one (thequick): both common and seen together. */
    private const val SPLIT_MIN_FREQ = 5000
    private const val SPLIT_MIN_PAIR = 20
    private const val COST_SPLIT = 1.2
    private const val SPLIT_PAIR_WEIGHT = 0.1
    /** A one-letter word is split off only when it is one of the commonest words there are (a lot, o zi). */
    private const val SPLIT_LETTER_FREQ = 1_000_000
    private const val SPLIT_LETTER_PAIR = 100
    /** Typos of three letters are fixed only when the slip is a likely one (teh, yuo). */
    private const val SHORT_MAX_COST = 1.0
    /** A word the corpus has seen only a handful of times, one likely slip from a word hundreds of times commoner, is a typo (helo, vreai). */
    private const val MARKED_MIN_LENGTH = 5
    private const val MARKED_MAX_COST = 1.0
    private const val SPLIT_LETTER_COST = 0.6
    private const val RARE_FREQ = 300
    private const val RARE_RATIO = 500L
    private const val RARE_MAX_COST = 1.6
    private const val COST_SWAP = 0.5
    private const val COST_NEAR = 1.0
    private const val COST_FAR = 3.0
    private const val COST_EXTRA = 1.5
    private const val COST_EXTRA_DOUBLE = 0.8
    private const val COST_MISSING = 1.6
    private const val COST_MISSING_DOUBLE = 0.7
    private const val COST_APOSTROPHE = 0.4

    /** For each letter a..z, the keys around it on the keyboard: the letters a slipping finger types instead. */
    private val near: Array<String> = run {
        val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
        Array(26) { i ->
            val c = 'a' + i
            val r = rows.indexOfFirst { c in it.first }
            val x = rows[r].first.indexOf(c) + rows[r].second
            buildString {
                for ((rr, row) in rows.withIndex()) for ((j, o) in row.first.withIndex())
                    if (o != c && kotlin.math.abs(rr - r) <= 1 && kotlin.math.abs(j + row.second - x) <= 1.1f) append(o)
            }
        }
    }

    private fun subCost(typed: Char, meant: Char): Double =
        if (typed in 'a'..'z' && meant in near[typed - 'a']) COST_NEAR else COST_FAR

    /** English contractions typed without the apostrophe; none of them is a word of its own in either language. */
    private val contractions: Map<String, String> = listOf(
        "don't", "doesn't", "didn't", "can't", "won't", "isn't", "aren't", "wasn't", "weren't", "hasn't", "haven't", "hadn't",
        "couldn't", "shouldn't", "wouldn't", "mustn't", "i'm", "i've", "you're", "you've", "you'll", "you'd", "they're",
        "they've", "they'll", "we've", "that's", "what's", "he's", "she's", "it'll", "ain't", "where's", "here's", "there's",
        "who's", "how's", "y'all"
    ).associateBy { it.replace("'", "") } - "whos"
    private const val UNI_WEIGHT = 0.2
    private const val MARGIN_CONTEXT = 0.8
    private const val MARGIN_REVISE = 0.3
    private const val MARGIN_ALONE = 0.46
    /** A typed word that only lacks diacritics (masina → mașina) switches on a smaller lead: both spellings are in the corpus. */
    private const val MARGIN_DIACRITICS = 0.25
    private const val PROTECTED_FREQ = 40_000
    private const val EXTRA_FREQ = 1000
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
        Thread { load(langs) { app.assets.open(it).readBytes() } }.start()
    }

    internal fun load(langs: Set<String>, open: (String) -> ByteArray) {
        run {
            // words of every language merged by spelling (counts added), then sorted: the id is the sorted index
            val freqOf = HashMap<String, Int>(700_000)
            val pairs = ArrayList<LongArray>()
            val locals = ArrayList<Array<String>>()
            for (lang in langs) {
                try {
                    val d = ByteBuffer.wrap(open("dict_$lang.bin")).order(ByteOrder.LITTLE_ENDIAN)
                    val count = d.int
                    val local = Array(count) { "" }
                    for (i in 0 until count) {
                        val len = d.get().toInt() and 0xff
                        val bytes = ByteArray(len); d.get(bytes)
                        val word = String(bytes, Charsets.UTF_8)
                        local[i] = word
                        freqOf[word] = (freqOf[word] ?: 0) + d.int
                    }
                    val b = ByteBuffer.wrap(open("bigrams_$lang.bin")).order(ByteOrder.LITTLE_ENDIAN)
                    val m = b.int
                    val raw = LongArray(m) { b.long }
                    pairs.add(raw); locals.add(local)
                } catch (_: Exception) {
                }
            }
            // the hand-kept list (slang, regional and old words): known words, never taken for a typo of a commoner one
            for (lang in langs) {
                try {
                    for (w in String(open("words_$lang.txt"), Charsets.UTF_8).lineSequence()) {
                        if (w.isNotEmpty()) freqOf[w] = maxOf(freqOf[w] ?: 0, EXTRA_FREQ)
                    }
                } catch (_: Exception) {
                }
            }
            // Protected words are never rare: a typo near one reaches it, and no commoner word wins over it
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
            // a pair both languages have is one entry, its counts added
            var kept = 0
            for (v in bi) {
                if (kept > 0 && (bi[kept - 1] ushr 24) == (v ushr 24)) {
                    val sum = minOf((bi[kept - 1] and 0xffffff) + (v and 0xffffff), 0xffffffL)
                    bi[kept - 1] = (v and (-1L shl 24)) or sum
                } else bi[kept++] = v
            }
            val pairsTable = if (kept == bi.size) bi else bi.copyOf(kept)
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
            model = Model(words, freq, pairsTable, keys.toTypedArray(), starts.toIntArray(), members, sums.toIntArray())
            loadedLangs = langs
            memoKey = null; completeKey = null
            loading = false
        }
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

    /** The other readings of a known word: the same letters with other diacritics, and the words it is often swapped with. */
    private fun candidates(m: Model, lw: String): List<String> {
        val out = LinkedHashSet<String>()
        out.addAll(m.family(strip(lw)))
        confusables[lw]?.let { out.addAll(it) }
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

    @Volatile private var memoKey: String? = null
    @Volatile private var memoValue: String? = null

    fun choose(prev: String?, typed: String, next: String?, revising: Boolean = false): String? {
        // the bar and the space key ask about the same word one after the other
        if (next != null || revising) return pick(prev, typed, next, revising)
        val key = (prev ?: "") + "\u0001" + typed
        if (key == memoKey) return memoValue
        val v = pick(prev, typed, null, false)
        memoValue = v; memoKey = key
        return v
    }

    private fun pick(prev: String?, typed: String, next: String?, revising: Boolean): String? {
        val m = model ?: return null
        if (typed == "i" && loadedLangs.size == 1 && "en" in loadedLangs) return "I"
        if (typed.length < 2) return null
        if (typed.length > 1 && typed.substring(1).any { it.isUpperCase() }) return null
        val lw = typed.lowercase()
        if (!lw.all { it.isLetter() || it == '\'' }) return null
        if (Fun.protectedWord(lw)) return null
        // a known word typed with its diacritics is what was meant; an unknown one may still hide a slip (mâncarw)
        if (!revising && lw != strip(lw) && m.contains(lw)) return null
        if ("en" in loadedLangs) contractions[lw]?.let { return recase(typed, it) }
        val p = prev?.lowercase(); val n = next?.let { canonical(it) }
        val plain = strip(lw)
        if (m.contains(lw)) {
            var best: String? = null; var bestScore = Double.NEGATIVE_INFINITY
            for (c in candidates(m, lw)) {
                val s = score(m, p, c, n)
                if (s > bestScore) { bestScore = s; best = c }
            }
            if (best != null) {
                val margin = when {
                    revising -> MARGIN_REVISE
                    strip(best) == plain -> MARGIN_DIACRITICS
                    p == null && n == null -> MARGIN_ALONE
                    else -> MARGIN_CONTEXT
                }
                if (bestScore - score(m, p, lw, n) > margin) return recase(typed, best)
            }
            val own = m.freqOf(lw)
            if (revising || own >= RARE_FREQ || lw != plain || plain.length < 4) return null
            val costs = HashMap<String, Double>()
            edits(m, plain, costs, false)
            if (plain.length >= FAR_MIN_LENGTH) far(m, plain, costs)
            val floor = own * RARE_RATIO
            val fix = best(m, costs.filter { it.value <= RARE_MAX_COST && m.freqOf(it.key) >= floor }, p, n, MIN_FIX_FREQ)
            return (fix?.first ?: split(m, plain, p)?.first)?.let { recase(typed, it) }
        }
        // letters held down on purpose (nuuuu, hahaha) stay as typed
        if (stretched(plain)) return null
        // an unknown word: its diacritics, then one slip of the finger, two words typed as one, two slips
        val costs = HashMap<String, Double>()
        for (f in m.family(plain)) costs[f] = 0.0
        if (plain.length >= 3) edits(m, plain, costs, plain.length < 4)
        if (lw != plain) {
            // typed with diacritics: only a longer word, only a likely slip, and the fix keeps its diacritics
            if (plain.length < MARKED_MIN_LENGTH) return null
            val marks = lw.count { it.code > 0x7f }
            val fix = best(m, costs.filter { it.value in 0.01..MARKED_MAX_COST && it.key.count { c -> c.code > 0x7f } >= marks }, p, n, MIN_FIX_FREQ)
            return fix?.let { recase(typed, it.first) }
        }
        val one = best(m, costs, p, n, MIN_FIX_FREQ)
        val two = split(m, plain, p)
        if (one != null || two != null) {
            val pickTwo = two != null && (one == null || two.second > one.second)
            return recase(typed, if (pickTwo) two!!.first else one!!.first)
        }
        if (plain.length >= FAR_MIN_LENGTH) {
            far(m, plain, costs)
            best(m, costs, p, n, FAR_MIN_FREQ)?.let { return recase(typed, it.first) }
        }
        return null
    }

    private val laughter = Regex("^(?:(?:ha|he|hi|ho|ah|la|na|bla|mua|mwa)+h?|a+h+|o+h+|m+h*m+|z+)$")

    private fun stretched(w: String): Boolean {
        var run = 1
        for (i in 1 until w.length) { run = if (w[i] == w[i - 1]) run + 1 else 1; if (run >= 4) return true }
        return laughter.matches(w)
    }

    private fun best(m: Model, costs: Map<String, Double>, p: String?, n: String?, minFreq: Int): Pair<String, Double>? {
        var best: String? = null; var bestScore = Double.NEGATIVE_INFINITY
        for ((c, cost) in costs) {
            if (m.freqOf(c) < minFreq) continue
            val s = score(m, p, c, n) - cost
            if (s > bestScore) { bestScore = s; best = c }
        }
        return best?.let { it to bestScore }
    }

    private fun keep(m: Model, plain: String, cost: Double, shortOnly: Boolean, out: HashMap<String, Double>) {
        if (shortOnly && cost > SHORT_MAX_COST) return
        for (f in m.family(plain)) {
            if (shortOnly && m.freqOf(f) < SHORT_TYPO_FREQ) continue
            val old = out[f]
            if (old == null || cost < old) out[f] = cost
        }
    }

    /**
     * The words one slip away from [w] (letters without diacritics, so mancarw reaches mâncare as well as mancare), each with
     * the cost of its slip: a neighbouring key is a likelier mistake than a far one. Typos of three letters reach only the commonest words.
     */
    private fun edits(m: Model, w: String, out: HashMap<String, Double>, shortOnly: Boolean) {
        val n = w.length
        for (i in 0 until n) {
            val doubled = (i > 0 && w[i - 1] == w[i]) || (i < n - 1 && w[i + 1] == w[i])
            keep(m, w.substring(0, i) + w.substring(i + 1), if (doubled) COST_EXTRA_DOUBLE else COST_EXTRA, shortOnly, out)
        }
        for (i in 0 until n - 1) if (w[i] != w[i + 1]) keep(m, w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2), COST_SWAP, shortOnly, out)
        for (i in 0 until n) for (ch in ALPHABET) if (ch != w[i]) keep(m, w.substring(0, i) + ch + w.substring(i + 1), subCost(w[i], ch), shortOnly, out)
        for (i in 0..n) for (ch in ALPHABET) {
            val cost = when {
                ch == '\'' -> COST_APOSTROPHE
                (i > 0 && w[i - 1] == ch) || (i < n && w[i] == ch) -> COST_MISSING_DOUBLE
                else -> COST_MISSING
            }
            keep(m, w.substring(0, i) + ch + w.substring(i), cost, shortOnly, out)
        }
    }

    /** Two common words typed as one, with the space missed or a bottom-row key hit instead of it; scored like a one-word fix. */
    private fun split(m: Model, w: String, p: String?): Pair<String, Double>? {
        if (w.length < 4) return null
        var best: String? = null; var bestScore = Double.NEGATIVE_INFINITY
        fun consider(a: String, b: String) {
            if (a == b) return
            // each half in the spelling the two are most often seen together in (in the, not în the)
            var wa = ""; var wb = ""; var pair = -1
            for (ca in m.family(a)) for (cb in m.family(b)) {
                val c = bigram(m, ca, cb)
                if (c > pair) { pair = c; wa = ca; wb = cb }
            }
            if (pair < 0) return
            val fa = m.freqOf(wa); val fb = m.freqOf(wb)
            val letter = a.length < 2 || b.length < 2
            if (fa < SPLIT_MIN_FREQ || fb < SPLIT_MIN_FREQ) return
            if (letter && (if (a.length < 2) fa else fb) < SPLIT_LETTER_FREQ) return
            if (pair < (if (letter) SPLIT_LETTER_PAIR else SPLIT_MIN_PAIR)) return
            val s = ln(1.0 + bigram(m, p, wa)) + UNI_WEIGHT * ln(1.0 + minOf(fa, fb)) + SPLIT_PAIR_WEIGHT * ln(1.0 + pair) - COST_SPLIT - (if (letter) SPLIT_LETTER_COST else 0.0)
            if (s > bestScore) { bestScore = s; best = "$wa $wb" }
        }
        for (i in 1..w.length - 1) consider(w.substring(0, i), w.substring(i))
        if (best == null) for (i in 2..w.length - 3) if (w[i] in "cvbnm") consider(w.substring(0, i), w.substring(i + 1))
        return best?.let { it to bestScore }
    }

    /**
     * The common words two slips away: every word with the same first letter and nearly the same length is measured
     * against [w] (weighted edit distance, the costs of [edits]); the ones within reach are added to [out].
     */
    private fun far(m: Model, w: String, out: HashMap<String, Double>) {
        val first = w[0]
        var lo = 0; var hi = m.plainKeys.size
        val from = first.toString()
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (m.plainKeys[mid] < from) lo = mid + 1 else hi = mid }
        val n = w.length
        var prev2 = DoubleArray(n + 4); var prev = DoubleArray(n + 4); var cur = DoubleArray(n + 4)
        var k = lo
        while (k < m.plainKeys.size) {
            val c = m.plainKeys[k]
            if (c.isEmpty() || c[0] != first) break
            val len = c.length
            if (m.plainFreq[k] >= FAR_MIN_FREQ && len >= n - 2 && len <= n + 2) {
                // rows run over the typed letters, columns over the candidate's
                for (j in 0..len) prev[j] = j * COST_MISSING
                var reach = true
                var i = 1
                while (i <= n && reach) {
                    cur[0] = i * COST_EXTRA
                    var rowMin = cur[0]
                    val t = w[i - 1]
                    for (j in 1..len) {
                        val d = c[j - 1]
                        var v = prev[j - 1] + (if (t == d) 0.0 else subCost(t, d))
                        val extra = prev[j] + (if (i > 1 && w[i - 2] == t) COST_EXTRA_DOUBLE else COST_EXTRA)
                        if (extra < v) v = extra
                        val missing = cur[j - 1] + (if (d == '\'') COST_APOSTROPHE else if (j > 1 && c[j - 2] == d) COST_MISSING_DOUBLE else COST_MISSING)
                        if (missing < v) v = missing
                        if (i > 1 && j > 1 && t == c[j - 2] && w[i - 2] == d && t != d) {
                            val swap = prev2[j - 2] + COST_SWAP
                            if (swap < v) v = swap
                        }
                        cur[j] = v
                        if (v < rowMin) rowMin = v
                    }
                    if (rowMin > FAR_MAX_COST) reach = false
                    val tmp = prev2; prev2 = prev; prev = cur; cur = tmp
                    i++
                }
                if (reach && prev[len] <= FAR_MAX_COST && prev[len] > 0.0) {
                    for (id in m.plainStart[k] until m.plainStart[k + 1]) {
                        val f = m.words[m.members[id]]
                        val old = out[f]
                        if (old == null || prev[len] < old) out[f] = prev[len]
                    }
                }
            }
            k++
        }
    }

    @Volatile private var completeKey: String? = null
    @Volatile private var completeValue: List<String> = emptyList()

    /**
     * The words [typed] is the start of, the likeliest after [prev] first, in the typed capitalization.
     * One pass over the words sharing the prefix (a binary search finds them), then the best few are weighed with the word before.
     */
    fun complete(prev: String?, typed: String, limit: Int): List<String> {
        val m = model ?: return emptyList()
        if (typed.length < 2 || (typed.length > 1 && typed.substring(1).any { it.isUpperCase() })) return emptyList()
        val lw = typed.lowercase()
        if (!lw.all { it.isLetter() || it == '\'' }) return emptyList()
        val key = (prev ?: "") + "\u0001" + typed + "\u0001" + limit
        if (key == completeKey) return completeValue
        val pre = strip(lw)
        var lo = 0; var hi = m.plainKeys.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (m.plainKeys[mid] < pre) lo = mid + 1 else hi = mid }
        // the commonest families with this prefix, kept in a small sorted list
        val top = IntArray(POOL); var size = 0
        var k = lo
        while (k < m.plainKeys.size && m.plainKeys[k].startsWith(pre)) {
            val f = m.plainFreq[k]
            if (m.plainKeys[k].length > pre.length && f >= MIN_FIX_FREQ && (size < POOL || f > m.plainFreq[top[size - 1]])) {
                var at = if (size < POOL) size++ else size - 1
                while (at > 0 && m.plainFreq[top[at - 1]] < f) { top[at] = top[at - 1]; at-- }
                top[at] = k
            }
            k++
        }
        val p = prev?.lowercase()
        val scored = ArrayList<Pair<String, Double>>(size)
        for (t in 0 until size) {
            val fam = top[t]
            // the commonest spelling of the family that continues what was typed, diacritics included
            var word: String? = null
            for (id in m.plainStart[fam] until m.plainStart[fam + 1]) {
                val c = m.words[m.members[id]]
                if (lw == pre || c.startsWith(lw)) { word = c; break }
            }
            val c = word ?: continue
            if (Fun.protectedWord(c) && !Fun.protectedWord(lw)) continue
            scored.add(c to (ln(1.0 + bigram(m, p, c)) + COMPLETE_UNI_WEIGHT * ln(1.0 + m.freqOf(c))))
        }
        scored.sortByDescending { it.second }
        val out = scored.take(limit).map { recase(typed, it.first) }
        completeValue = out; completeKey = key
        return out
    }

    private const val POOL = 24
    private const val COMPLETE_UNI_WEIGHT = 0.5

    private fun recase(typed: String, word: String): String = when {
        word.startsWith("i'") -> "I" + word.substring(1)
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }
}
