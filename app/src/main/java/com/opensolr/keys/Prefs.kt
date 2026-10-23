package com.opensolr.keys

import android.content.Context
import android.content.res.Resources

/** All user settings. Nothing here ever leaves the phone. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("keys", Context.MODE_PRIVATE)

    var sizePercent: Int
        get() = sp.getInt("size", 100)
        set(v) = sp.edit().putInt("size", v).apply()

    var bottomOffsetDp: Int
        get() = sp.getInt("offset", 0)
        set(v) = sp.edit().putInt("offset", v).apply()

    /** Long-press delay in ms, 50..550 in steps of 50. */
    var longPressMs: Int
        get() = sp.getInt("longpress", 200)
        set(v) = sp.edit().putInt("longpress", v).apply()

    var haptic: Boolean
        get() = sp.getBoolean("haptic", false)
        set(v) = sp.edit().putBoolean("haptic", v).apply()

    var hapticStrength: Int
        get() = sp.getInt("haptic_strength", 8)
        set(v) = sp.edit().putInt("haptic_strength", v).apply()

    /** The joke replacements (politician → parasite, European Union → EURSS). */
    var funWords: Boolean
        get() = sp.getBoolean("fun", true)
        set(v) = sp.edit().putBoolean("fun", v).apply()

    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()

    var volume: Int
        get() = sp.getInt("volume", 50)
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
        sp.edit().remove("size").remove("offset").remove("longpress").remove("haptic").remove("haptic_strength")
            .remove("sound").remove("volume").remove("langs").remove("fun").apply()
    }

    fun learn(word: String) { learned = learned + word.lowercase() }
    fun forget(word: String) { learned = learned - word.lowercase() }

    companion object {
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
