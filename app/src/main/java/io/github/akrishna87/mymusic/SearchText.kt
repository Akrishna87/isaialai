package io.github.akrishna87.mymusic

/**
 * Search that doesn't mind how a name is spelt in English letters. Tamil names are written many
 * ways (Ilaiyaraaja / Ilayaraja, Thalapathy / Talapathi, Azhagu / Alagu), and voice search spells
 * them however it likes, so both the search and the song's details are "folded" first: double
 * letters made single, long vowels made short, th → t, zh → l and so on.
 */
object SearchText {

    // Applied in order to each folded word.
    private val RULES = listOf(
        "zh" to "l", "th" to "t", "dh" to "d", "sh" to "s", "kh" to "k", "gh" to "g", "bh" to "b", "ph" to "p",
        "ee" to "i", "oo" to "u", "ai" to "a", "ae" to "e", "w" to "v",
    )

    /** [text] in a spelling-forgiving form: lower case, words of letters and digits only. */
    fun fold(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text.lowercase()) sb.append(if (Similar.isNamePart(c)) c else ' ')
        return sb.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { foldWord(it) }
    }

    private fun foldWord(word: String): String {
        var w = word
        for ((from, to) in RULES) w = w.replace(from, to)
        // Double letters as one ("aa" → "a", "nn" → "n"); "ai" → "a" can make new ones, hence after.
        val out = StringBuilder(w.length)
        for (c in w) if (out.isEmpty() || out.last() != c || !c.isLetter()) out.append(c)
        // A name ending in y or i is the same name (Thalapathy / Talapathi).
        if (out.length > 2 && out.last() == 'y') out.setCharAt(out.lastIndex, 'i')
        return out.toString()
    }

    /**
     * Whether every word of [query] is found in [folded] (text already passed through [fold]).
     * Words can be in any order: "raja ilaya" finds Ilaiyaraaja too, as part of a word. Spaces
     * don't matter either, so initials match however they're said: "AR Rahman" finds "A.R. Rahman".
     */
    fun matchesFolded(query: String, folded: String): Boolean {
        val q = fold(query)
        val words = q.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        return words.all { folded.contains(it) } || folded.replace(" ", "").contains(q.replace(" ", ""))
    }

    fun matches(query: String, vararg texts: String): Boolean =
        query.isNotBlank() &&
            (texts.any { it.contains(query.trim(), ignoreCase = true) } || matchesFolded(query, fold(texts.joinToString(" "))))
}
