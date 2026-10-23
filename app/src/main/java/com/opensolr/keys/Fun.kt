package com.opensolr.keys

/**
 * The words autocorrect must never touch, whatever the dictionary says of them, and the words it is glad to
 * replace: a small joke, switched off in Settings.
 */
object Fun {
    /** Rude words, English and Romanian: kept exactly as typed, and reachable from a typo. */
    val PROTECTED: Set<String> = setOf(
        "fuck", "fucking", "fucked", "fucker", "fuckers", "motherfucker", "motherfucking", "shit", "shitty", "bullshit",
        "cunt", "cunts", "dick", "dicks", "dickhead", "cock", "cocks", "pussy", "pussies", "asshole", "assholes", "ass",
        "bitch", "bitches", "bastard", "bastards", "whore", "whores", "slut", "sluts", "wanker", "twat", "prick",
        "damn", "goddamn", "crap", "piss", "pissed", "tits", "boobs", "balls", "bollocks", "jerk", "douche", "douchebag",
        "pula", "pulă", "pulii", "pizda", "pizdă", "pizdei", "pizde", "muie", "muia", "muist", "muistă", "fut", "futu",
        "futui", "futai", "futut", "futută", "fute", "cur", "curu", "curul", "curva", "curvă", "curve", "curvele",
        "sugi", "suge", "căcat", "cacat", "căcatul", "morții", "mortii", "dracu", "dracului", "coaie", "coaiele",
        "jigodie", "jigodia", "bulangiu", "labă", "laba", "labagiu", "panarama", "pulos", "sula", "sulă", "zdreanță",
        "zdreanta", "bou", "boule", "prost", "proasta", "proastă", "idiot", "idioata", "idioată", "cretin", "cretina",
        "cretină", "nenorocit", "nenorocita", "nenorocită", "dumnezei", "dumnezeii", "cristosu", "cristosul",
    )

    /** One word to another, English and Romanian, lower case. */
    private val words: Map<String, String> = mapOf(
        "politician" to "parasite", "politicians" to "parasites",
        "government" to "the Regime", "governments" to "the Regimes",
        "parliament" to "the Circus", "bureaucrat" to "apparatchik", "bureaucrats" to "apparatchiks",
        "taxes" to "tribute", "taxpayer" to "serf", "taxpayers" to "serfs",
        "politician" to "parasite",
        "politicianul" to "parazitul", "politicienii" to "paraziții", "politicieni" to "paraziți",
        "guvern" to "Regim", "guvernul" to "Regimul", "guvernului" to "Regimului",
        "parlament" to "circ", "parlamentul" to "circul", "parlamentului" to "circului",
        "birocrat" to "aparatcic", "birocrații" to "aparatcicii", "birocrati" to "aparatcici",
        "taxe" to "tribut", "taxele" to "tributul", "impozite" to "tribut", "impozitele" to "tributul",
    )

    /** Two words to one: the second word is being typed, the first is already there. */
    private val pairs: Map<String, String> = mapOf(
        "european union" to "EURSS", "uniunea europeană" to "EURSS", "uniunea europeana" to "EURSS",
        "european commission" to "the Politburo", "comisia europeană" to "Politburo", "comisia europeana" to "Politburo",
        "world economic" to "Davos cult",
    )

    fun protectedWord(word: String): Boolean = word.lowercase() in PROTECTED

    /** The replacement for [word] (after [prev]), or null. The pair match replaces both words. */
    fun replace(prev: String?, word: String): Replacement? {
        val lw = word.lowercase()
        if (prev != null) {
            val key = prev.lowercase() + " " + Dictionary.strip(lw)
            val keyExact = prev.lowercase() + " " + lw
            (pairs[keyExact] ?: pairs[key])?.let { return Replacement(it, both = true) }
        }
        words[lw]?.let { return Replacement(recase(word, it), both = false) }
        return null
    }

    private fun recase(typed: String, word: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    class Replacement(val text: String, val both: Boolean)
}
