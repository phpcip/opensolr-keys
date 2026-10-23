package com.opensolr.keys

/** The words autocorrect must never touch, whatever the dictionary says of them. */
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

    fun protectedWord(word: String): Boolean = word.lowercase() in PROTECTED
}
