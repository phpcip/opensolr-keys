package com.opensolr.keys

import android.content.Context

/** Offline emoji list with keywords, for the picker and for word → emoji suggestions. */
object Emoji {
    val CATEGORIES = listOf(
        "Smileys & Emotion" to "😀", "People & Body" to "👋", "Animals & Nature" to "🐻",
        "Food & Drink" to "🍔", "Travel & Places" to "🚗", "Activities" to "⚽",
        "Objects" to "💡", "Symbols" to "🔣", "Flags" to "🏁"
    )

    @Volatile var byCategory: Map<String, List<String>> = emptyMap(); private set
    @Volatile private var keywords: HashMap<String, ArrayList<String>> = HashMap()
    @Volatile private var loaded = false

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val app = ctx.applicationContext
        Thread {
            val cats = LinkedHashMap<String, ArrayList<String>>()
            for ((c, _) in CATEGORIES) cats[c] = ArrayList()
            val kw = HashMap<String, ArrayList<String>>(8000)
            try {
                app.assets.open("emoji.txt").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val p = line.split('\t')
                        if (p.size < 3) continue
                        cats.getOrPut(p[1]) { ArrayList() }.add(p[0])
                        for (k in p[2].split(',')) if (k.isNotEmpty()) kw.getOrPut(k) { ArrayList() }.add(p[0])
                    }
                }
            } catch (_: Exception) {
            }
            byCategory = cats
            keywords = kw
        }.start()
    }

    /** Free search for the emoji picker: exact keyword, then prefix, then contains. */
    fun search(query: String): List<String> {
        val q = query.trim().lowercase().removePrefix(":")
        if (q.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        keywords[q]?.let { out.addAll(it) }
        for ((k, v) in keywords) if (k.startsWith(q)) { out.addAll(v); if (out.size >= 30) break }
        if (out.size < 30) for ((k, v) in keywords) if (k.contains(q)) { out.addAll(v); if (out.size >= 30) break }
        return out.take(30)
    }

    /** Suggestions for the word being typed: exact keyword, or ":prefix" search. */
    fun suggest(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        val lw = word.lowercase()
        if (lw.startsWith(":")) {
            val p = lw.substring(1)
            if (p.length < 2) return emptyList()
            val out = LinkedHashSet<String>()
            keywords[p]?.let { out.addAll(it) }
            for ((k, v) in keywords) if (k.startsWith(p)) { out.addAll(v); if (out.size >= 12) break }
            return out.take(12)
        }
        if (lw.length < 2) return emptyList()
        return keywords[lw]?.distinct()?.take(12) ?: emptyList()
    }
}
