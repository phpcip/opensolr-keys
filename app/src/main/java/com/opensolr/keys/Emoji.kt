package com.opensolr.keys

import android.content.Context

/** Offline emoji list with keywords, for the picker and for word and emoticon → emoji suggestions. */
object Emoji {
    const val RECENT = "Recent"
    val CATEGORIES = listOf(
        RECENT to "🕘",
        "Smileys & Emotion" to "😀", "People & Body" to "👋", "Animals & Nature" to "🐻",
        "Food & Drink" to "🍔", "Travel & Places" to "🚗", "Activities" to "⚽",
        "Objects" to "💡", "Symbols" to "🔣", "Flags" to "🏁"
    )
    private const val MAX = 12

    @Volatile var byCategory: Map<String, List<String>> = emptyMap(); private set
    /** Keywords without diacritics, sorted; values[i] is the emoji of keys[i], best first, space separated. */
    @Volatile private var keys: Array<String> = emptyArray()
    @Volatile private var values: Array<String> = emptyArray()
    @Volatile private var emoticons: Map<String, List<String>> = emptyMap()
    /** The skin tone forms of an emoji, and the emoji each of them is a form of. */
    @Volatile private var variants: Map<String, List<String>> = emptyMap()
    @Volatile private var baseOf: Map<String, String> = emptyMap()
    /** The form chosen for an emoji with a long press in the picker; it is the one offered from then on. Set by the service from the prefs. */
    @Volatile var tones: Map<String, String> = emptyMap()

    /** [e] in the skin tone chosen for it, or as it is. */
    fun toned(e: String): String = tones[e] ?: e
    /** The emoji without a skin tone that [e] is a form of ([e] itself when it has none). */
    fun plain(e: String): String = baseOf[e] ?: e
    /** The other forms of [e], lightest first; empty when it has none. */
    fun variantsOf(e: String): List<String> = variants[plain(e)] ?: emptyList()
    @Volatile private var loaded = false

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val app = ctx.applicationContext
        Thread {
            val cats = LinkedHashMap<String, ArrayList<String>>()
            for ((c, _) in CATEGORIES) cats[c] = ArrayList()
            val words = ArrayList<Pair<String, String>>(13500)
            val faces = HashMap<String, List<String>>()
            val forms = HashMap<String, List<String>>()
            val bases = HashMap<String, String>()
            // an emoji this phone has no picture for is left out: newer ones than its font
            val paint = android.graphics.Paint()
            val known = HashSet<String>(2500)
            try {
                app.assets.open("emoji.txt").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val p = line.split('\t')
                        if (p.size < 2 || !paint.hasGlyph(p[0])) continue
                        known.add(p[0])
                        cats.getOrPut(p[1]) { ArrayList() }.add(p[0])
                        if (p.size > 2 && p[2].isNotEmpty()) {
                            val v = p[2].split(' ').filter { paint.hasGlyph(it) }
                            if (v.isNotEmpty()) { forms[p[0]] = v; for (f in v) bases[f] = p[0] }
                        }
                    }
                }
                app.assets.open("emoji_index.txt").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val t = line.indexOf('\t')
                        if (t <= 0) continue
                        val k = line.substring(0, t)
                        val shown = line.substring(t + 1).split(' ').filter { it in known }
                        if (shown.isEmpty()) continue
                        if (k.all { it in 'a'..'z' || it in '0'..'9' }) words.add(k to shown.joinToString(" "))
                        else faces[k] = shown
                    }
                }
            } catch (_: Exception) {
            }
            words.sortBy { it.first }
            byCategory = cats
            values = Array(words.size) { words[it].second }
            keys = Array(words.size) { words[it].first }
            emoticons = faces
            variants = forms
            baseOf = bases
        }.start()
    }

    private fun exact(k: Array<String>, word: String): List<String> {
        val i = k.binarySearch(word)
        return if (i >= 0) values[i].split(' ') else emptyList()
    }

    /** Adds the emoji of every keyword starting with [prefix] and at most [extra] letters longer, the shortest keywords first. */
    private fun addPrefixed(k: Array<String>, prefix: String, extra: Int, max: Int, out: LinkedHashSet<String>) {
        var lo = 0; var hi = k.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (k[mid] < prefix) lo = mid + 1 else hi = mid }
        val hits = ArrayList<Int>()
        var i = lo
        while (i < k.size && k[i].startsWith(prefix)) { if (k[i].length <= prefix.length + extra) hits.add(i); i++ }
        hits.sortBy { k[it].length }
        for (h in hits) { if (out.size >= max) return; out.addAll(values[h].split(' ')) }
    }

    /** Free search for the emoji picker: exact keyword, then prefix, then contains. */
    fun search(query: String): List<String> {
        val q = Dictionary.strip(query.trim().lowercase().removePrefix(":")).filter { it in 'a'..'z' || it in '0'..'9' }
        if (q.isEmpty()) return emptyList()
        val k = keys
        val out = LinkedHashSet<String>()
        out.addAll(exact(k, q))
        addPrefixed(k, q, 99, 30, out)
        if (out.size < 30) for (i in k.indices) if (k[i].contains(q)) { out.addAll(values[i].split(' ')); if (out.size >= 30) break }
        return out.take(30).map { toned(it) }
    }

    /** Suggestions for the word being typed: its keyword, the keywords it nearly spells (think → thinking), or a ":prefix" search. */
    fun suggest(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        val k = keys
        val lw = Dictionary.strip(word.lowercase())
        val out = LinkedHashSet<String>()
        if (lw.startsWith(":")) {
            val p = lw.substring(1)
            if (p.length < 2) return emptyList()
            out.addAll(exact(k, p))
            addPrefixed(k, p, 99, MAX, out)
            return out.take(MAX).map { toned(it) }
        }
        if (lw.length < 2) return emptyList()
        out.addAll(exact(k, lw))
        if (lw.length >= 4 && out.size < MAX) addPrefixed(k, lw, 3, MAX, out)
        return out.take(MAX).map { toned(it) }
    }

    /**
     * The emoticon the text ends with, as (its length in the text, its emoji): `:)`, `:))`, `<3`, `:P`.
     * It stands alone or right after a word (ok:)), and a longer run of its last character counts as two (:)))) is :))).
     */
    fun emoticon(before: String): Pair<Int, List<String>>? {
        val table = emoticons
        if (table.isEmpty()) return null
        var start = before.length
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        val raw = before.substring(start)
        if (raw.length < 2 || raw.length > 24 || raw.all { it.isLetterOrDigit() }) return null
        for (s in 0..raw.length - 2) {
            val sub = raw.substring(s).lowercase()
            if (sub.length > 8) continue
            if (s > 0) {
                if (sub[0] !in ":;=" || !raw[s - 1].isLetterOrDigit() || sub.startsWith(":/") || sub.startsWith(":\\")) continue
            }
            val last = sub.last()
            var run = 0
            while (run < sub.length && sub[sub.length - 1 - run] == last) run++
            val stem = sub.substring(0, sub.length - run)
            val hit = (if (run >= 2) table[stem + last + last] else null) ?: (if (stem.isNotEmpty() || run == 1) table[stem + last] else null) ?: table[sub]
            if (hit != null) return (raw.length - s) to hit.map { toned(it) }
        }
        return null
    }
}
