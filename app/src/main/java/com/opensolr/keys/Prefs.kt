package com.opensolr.keys

import android.content.Context
import android.content.res.Resources

/** All user settings. Nothing here ever leaves the phone. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("keys", Context.MODE_PRIVATE)

    var sizePercent: Int
        get() = sp.getInt("size", 88)
        set(v) = sp.edit().putInt("size", v).apply()

    var bottomOffsetDp: Int
        get() = sp.getInt("offset", 0)
        set(v) = sp.edit().putInt("offset", v).apply()

    /** Long-press delay in ms, 50..550 in steps of 50. */
    var longPressMs: Int
        get() = sp.getInt("longpress", 200)
        set(v) = sp.edit().putInt("longpress", v).apply()

    var balloonOn: Boolean
        get() = sp.getBoolean("balloon_on", true)
        set(v) = sp.edit().putBoolean("balloon_on", v).apply()

    /** How long the key balloon stays after the finger lifts, 10..110 ms. */
    var previewMs: Int
        get() = sp.getInt("preview", 40)
        set(v) = sp.edit().putInt("preview", v).apply()

    /** Key balloon size in percent, 80..180: bigger also lifts it higher above the key. */
    var balloonPercent: Int
        get() = sp.getInt("balloon", 100)
        set(v) = sp.edit().putInt("balloon", v).apply()

    var haptic: Boolean
        get() = sp.getBoolean("haptic", false)
        set(v) = sp.edit().putBoolean("haptic", v).apply()

    var hapticStrength: Int
        get() = sp.getInt("haptic_strength", 88)
        set(v) = sp.edit().putInt("haptic_strength", v).apply()

    /** The key tap, 0..3 from lightest to heaviest. */
    var hapticLevel: Int
        get() = ((hapticStrength - 1) / 25).coerceIn(0, 3)
        set(v) { hapticStrength = v.coerceIn(0, 3) * 25 + 13 }

    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()

    var volume: Int
        get() = sp.getInt("volume", 15)
        set(v) = sp.edit().putInt("volume", v).apply()

    /** Autocorrect languages; default = the system languages we have a dictionary for, else English. */
    var langs: Set<String>
        get() = sp.getStringSet("langs", null) ?: systemLangs()
        set(v) = sp.edit().putStringSet("langs", v).apply()

    /** Words the user kept as typed; never corrected again. Editable in settings. */
    var learned: Set<String>
        get() = sp.getStringSet("learned", null) ?: emptySet()
        set(v) = sp.edit().putStringSet("learned", v).apply()

    /** Every setting back to its default; the words kept as typed stay. */
    fun restoreDefaults() {
        sp.edit().remove("size").remove("offset").remove("longpress").remove("preview").remove("balloon").remove("balloon_on").remove("haptic").remove("haptic_strength")
            .remove("sound").remove("volume").remove("langs").apply()
    }

    /** Every emoji ever picked, the latest first; shown in the Recent tab of the picker. */
    var recentEmoji: List<String>
        get() = sp.getString("recent_emoji", "")!!.split('\u0001').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("recent_emoji", v.joinToString("\u0001")).apply()

    fun useEmoji(e: String) { recentEmoji = listOf(e) + recentEmoji.filter { it != e } }

    fun learn(word: String) { learned = learned + word.lowercase() }
    fun forget(word: String) { learned = learned - word.lowercase() }

    /** Every setting, the words kept as typed and the recent emoji, as one JSON file for another phone. */
    fun export(): String {
        val o = org.json.JSONObject()
        o.put("size", sizePercent).put("offset", bottomOffsetDp).put("longpress", longPressMs).put("preview", previewMs)
            .put("balloon", balloonPercent).put("haptic_strength", hapticStrength).put("volume", volume)
            .put("balloon_on", balloonOn).put("haptic", haptic).put("sound", sound)
            .put("langs", org.json.JSONArray(langs.sorted()))
        o.put("learned", org.json.JSONArray(learned.sorted()))
        o.put("recent_emoji", org.json.JSONArray(recentEmoji))
        return org.json.JSONObject().put("app", FILE_APP).put("format", FILE_FORMAT).put("settings", o).toString(2)
    }

    /** Loads a file made by [export]. The whole file is checked first: anything wrong and nothing is changed. */
    fun import(text: String): Boolean {
        val root = try { org.json.JSONObject(text) } catch (e: org.json.JSONException) { return false }
        if (root.optString("app") != FILE_APP || root.optInt("format", -1) != FILE_FORMAT) return false
        val o = root.optJSONObject("settings") ?: return false
        val known = INTS.keys + BOOLS + listOf("langs", "learned", "recent_emoji")
        for (k in o.keys()) if (k !in known) return false
        val ints = HashMap<String, Int>()
        for ((k, range) in INTS) if (o.has(k)) {
            val v = o.get(k)
            if (v !is Int || v !in range) return false
            ints[k] = v
        }
        val bools = HashMap<String, Boolean>()
        for (k in BOOLS) if (o.has(k)) bools[k] = o.get(k) as? Boolean ?: return false
        fun strings(k: String, max: Int, ok: (String) -> Boolean): List<String>? {
            val a = o.optJSONArray(k) ?: return null
            if (a.length() > max) throw IllegalArgumentException()
            return (0 until a.length()).map { i -> (a.get(i) as? String)?.takeIf(ok) ?: throw IllegalArgumentException() }
        }
        val newLangs: List<String>?; val newLearned: List<String>?; val newRecent: List<String>?
        try {
            newLangs = strings("langs", SUPPORTED.size) { it in SUPPORTED }
            newLearned = strings("learned", 100_000) { it.isNotBlank() && it.length <= 64 && '\n' !in it }
            newRecent = strings("recent_emoji", 10_000) { it.isNotEmpty() && it.length <= 64 && '\u0001' !in it }
        } catch (e: IllegalArgumentException) { return false }
        if (o.has("langs") && newLangs == null || o.has("learned") && newLearned == null || o.has("recent_emoji") && newRecent == null) return false

        // a setting missing from the file goes back to its default, so the phone ends up exactly as the file says
        val e = sp.edit()
        for (k in INTS.keys + BOOLS + listOf("langs", "learned", "recent_emoji")) e.remove(k)
        for ((k, v) in ints) e.putInt(k, v)
        for ((k, v) in bools) e.putBoolean(k, v)
        if (newLangs != null) e.putStringSet("langs", newLangs.toSet())
        if (newLearned != null) e.putStringSet("learned", newLearned.map { it.trim().lowercase() }.toSet())
        if (newRecent != null) e.putString("recent_emoji", newRecent.distinct().joinToString("\u0001"))
        return e.commit()
    }

    companion object {
        private const val FILE_APP = "opensolr-keys"
        private const val FILE_FORMAT = 1
        // the ranges the settings sliders can produce
        private val INTS = linkedMapOf(
            "size" to 80..130, "offset" to 0..80, "longpress" to 50..550, "preview" to 10..110,
            "balloon" to 80..180, "haptic_strength" to 0..100, "volume" to 1..100
        )
        private val BOOLS = listOf("balloon_on", "haptic", "sound")

        val SUPPORTED = listOf("en", "ro")

        fun systemLangs(): Set<String> {
            val locales = Resources.getSystem().configuration.locales
            val out = LinkedHashSet<String>()
            for (i in 0 until locales.size()) {
                val l = locales[i].language
                if (l in SUPPORTED) out.add(l)
            }
            if (out.isEmpty()) out.add("en")
            return out
        }
    }
}
