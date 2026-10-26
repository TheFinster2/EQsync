package com.eqsync

/**
 * 10-band EQ curves (dB) tuned for open-ear buds like the OpenFit 2+, which leak bass
 * because they sit outside the ear canal. Every curve therefore carries some low-end lift.
 */
object Presets {
    val FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    class Preset(val name: String, val gains: FloatArray, val keywords: List<String>)

    private fun p(name: String, vararg gains: Number, keywords: List<String>) =
        Preset(name, gains.map { it.toFloat() }.toFloatArray(), keywords)

    val DEFAULT = p("Balanced", 4, 3.5, 2, 0, 0, 0, 0, 0.5, 0.5, 0, keywords = emptyList())

    val ALL = listOf(
        p("Hip-Hop", 7, 6, 4, 1, -1, -1, 0, 1, 1, 1,
            keywords = listOf("hip hop", "hip-hop", "hiphop", "rap", "trap", "drill", "grime", "boom bap")),
        p("Electronic", 6, 6, 3, 0, -1, -1, 0, 2, 3, 3,
            keywords = listOf("electronic", "edm", "house", "techno", "trance", "dubstep", "drum and bass",
                "dnb", "electro", "dance", "garage", "bass music", "synthwave", "hardstyle", "phonk")),
        p("R&B", 6, 5, 3, 1, 0, 0, 1, 1, 1, 1,
            keywords = listOf("r&b", "rnb", "rhythm and blues", "neo-soul", "neo soul", "funk")),
        p("Rock", 4, 3, 1, -1, -1, 0, 2, 3, 3, 2,
            keywords = listOf("rock", "metal", "punk", "grunge", "hardcore", "emo", "post-hardcore",
                "shoegaze", "indie rock", "alternative")),
        p("Pop", 4, 3, 1, 0, -1, 0, 1, 2, 2, 2,
            keywords = listOf("pop", "k-pop", "kpop", "j-pop", "dance pop", "synthpop")),
        p("Latin", 5, 5, 3, 0, -1, 0, 1, 2, 2, 1,
            keywords = listOf("latin", "reggaeton", "reggae", "dancehall", "afrobeats", "afrobeat", "salsa", "bachata")),
        p("Jazz & Soul", 3, 3, 2, 1, 0, 0, 1, 2, 2, 1,
            keywords = listOf("jazz", "blues", "soul", "swing", "bossa nova", "lounge")),
        p("Acoustic", 3, 2, 1, 0, 0, 1, 2, 2, 1, 1,
            keywords = listOf("acoustic", "folk", "country", "singer-songwriter", "bluegrass", "americana", "indie folk")),
        p("Classical", 3, 3, 1, 0, 0, 0, 0, 1, 2, 2,
            keywords = listOf("classical", "orchestral", "piano", "ambient", "soundtrack", "score",
                "instrumental", "chamber", "opera", "neoclassical", "lo-fi", "lofi")),
        p("Spoken", -2, -2, -1, 0, 1, 3, 4, 3, 1, 0,
            keywords = listOf("podcast", "spoken word", "audiobook", "comedy", "talk")),
    )

    fun byName(name: String): Preset? = (ALL + DEFAULT).firstOrNull { it.name == name }

    /** Scores each preset against weighted tags (weight 0..100) and returns the best match. */
    fun pick(tags: List<Pair<String, Int>>): Preset {
        var best = DEFAULT
        var bestScore = 0.0
        for (preset in ALL) {
            var score = 0.0
            for ((tag, weight) in tags) {
                val t = tag.lowercase()
                for (k in preset.keywords) {
                    // Exact tag match counts more than a substring hit ("pop" vs "pop punk").
                    if (t == k) score += weight * 1.5
                    else if (Regex("\\b${Regex.escape(k)}\\b").containsMatchIn(t)) score += weight.toDouble()
                }
            }
            if (score > bestScore) { bestScore = score; best = preset }
        }
        return best
    }
}
