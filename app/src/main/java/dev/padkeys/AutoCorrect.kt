package dev.padkeys

import android.view.textservice.SuggestionsInfo
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/**
 * Autocorrect: when a space or punctuation ends a word that isn't in the dictionary, [correction] finds a close word
 * to put in its place. Sources, best first: a short list of common slips (missing apostrophes, "teh"), the phone's
 * spell checker when it is confident, then the nearest common word from the bundled list by a keyboard-aware edit
 * distance (neighboring keys, swapped and doubled letters cost less). Backspace right after a correction puts the
 * typed word back once ([undo]), and that word is then left alone for the rest of the session ([rejected]).
 */
object AutoCorrect {
    /** The last correction, until anything else is typed: Backspace reverts it. */
    class Undo(val typed: String, val fixed: String, val separator: String)

    var undo: Undo? = null
    /** Words the user reverted: never corrected again while the keyboard runs. */
    private val rejected = HashSet<String>()

    fun reject(word: String) { rejected += word.lowercase() }

    /** Slips the frequency list knows as "words" (they are common on the web), with what was meant. */
    private val common = mapOf(
        "i" to "I", "im" to "I'm", "ive" to "I've", "id" to "", "dont" to "don't", "cant" to "can't",
        "wont" to "won't", "didnt" to "didn't", "doesnt" to "doesn't", "isnt" to "isn't", "wasnt" to "wasn't",
        "arent" to "aren't", "werent" to "weren't", "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
        "couldnt" to "couldn't", "shouldnt" to "shouldn't", "wouldnt" to "wouldn't", "youre" to "you're",
        "theyre" to "they're", "thats" to "that's", "whats" to "what's", "youve" to "you've", "theyve" to "they've",
        "youll" to "you'll", "theyll" to "they'll", "itll" to "it'll", "shouldve" to "should've",
        "couldve" to "could've", "wouldve" to "would've", "teh" to "the", "adn" to "and", "taht" to "that",
        "waht" to "what", "thier" to "their", "recieve" to "receive", "beleive" to "believe",
        "definately" to "definitely", "seperate" to "separate", "occured" to "occurred", "untill" to "until",
        "wich" to "which", "becuase" to "because", "freind" to "friend", "alot" to "a lot", "tommorow" to "tomorrow",
        "goverment" to "government", "wierd" to "weird", "truely" to "truly", "basicly" to "basically",
    ).filterValues { it.isNotEmpty() }

    /** The correction for [word] (as typed), or null to leave it. */
    fun correction(word: String): String? {
        if (word.isEmpty() || word.length > 24 || word.any { it.isDigit() }) return null
        val lower = word.lowercase()
        if (lower in rejected) return null
        common[lower]?.let { return matchCase(word, it) }
        if (word.length < 3 || !word.all { it.isLetter() || it == '\'' || it == '’' }) return null
        // ACRONYMS and words with capitals inside (iPhone, McDonald) are deliberate.
        if (word.drop(1).any { it.isUpperCase() }) return null
        if (Predict.isLearned(lower)) return null
        val v = Predict.verdict(lower)
        if (v != null && v.attrs and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0) return null
        val rank = Predict.rankOf(lower)
        if (rank in 0 until RARE) return null
        if (rank >= RARE) {
            // The list's rarest entries include common misspellings ("realy", "recieve"): fix those only toward a
            // much more common word one small slip away (a doubled, swapped or neighboring letter).
            if (lower.length < 4) return null
            return nearest(lower, 0.7f, COMMON)?.let { matchCase(word, it) }
        }
        val max = when { lower.length <= 3 -> 0.7f; lower.length <= 5 -> 1.0f; else -> 1.6f }
        val fix = nearest(lower, max, MAX_RANK) ?: v?.takeIf {
            it.attrs and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0 &&
                it.attrs and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS != 0
        }?.first?.takeIf { ' ' !in it && it.isNotBlank() } ?: return null
        if (fix.equals(word, ignoreCase = true)) return null
        return matchCase(word, fix)
    }

    /** Keep the typed capital: "Teh" -> "The". "I" stays capital. */
    private fun matchCase(word: String, fix: String): String = when {
        fix == "I" || fix.startsWith("I'") -> fix
        word.first().isUpperCase() -> fix.replaceFirstChar { it.uppercaseChar() }
        else -> fix
    }

    // ---------------------------------------------------------------- nearest word

    /** Only words this common are offered as corrections; rarer entries of the list include misspellings. */
    private const val MAX_RANK = 30000
    /** Words rarer than this are left alone only if nothing much more common ([COMMON]) is a slip away. */
    private const val RARE = 25000
    private const val COMMON = 15000

    private fun nearest(w: String, max: Float, maxRank: Int): String? {
        val list = Predict.wordList
        if (list.isEmpty()) return null
        var best: String? = null
        var bestScore = Float.MAX_VALUE
        val n = min(list.size, maxRank)
        val lnN = ln(n.toFloat())
        for (rank in 0 until n) {
            val c = list[rank]
            if (abs(c.length - w.length) > 2 || c.length < 2) continue
            var d = distance(w, c, max)
            if (d > max) continue
            if (c[0] != w[0]) d += 0.25f // a slip on the first letter is rarer
            // Prefer common words between candidates about as close.
            val score = d + 0.4f * ln(1f + rank) / lnN
            if (score < bestScore) { bestScore = score; best = c }
        }
        return best
    }

    private var prev2 = FloatArray(32)
    private var prev = FloatArray(32)
    private var cur = FloatArray(32)

    /**
     * Weighted edit distance from the typed [a] to the word [b], giving up (returning > [max]) as soon as every path
     * costs more: neighboring-key substitutions 0.6, swapped letters 0.7, an extra or missing doubled letter 0.5.
     */
    private fun distance(a: String, b: String, max: Float): Float {
        val n = a.length; val m = b.length
        if (cur.size <= m) { prev2 = FloatArray(m + 8); prev = FloatArray(m + 8); cur = FloatArray(m + 8) }
        for (j in 0..m) prev[j] = j.toFloat()
        for (i in 1..n) {
            cur[0] = i.toFloat()
            var rowMin = cur[0]
            for (j in 1..m) {
                val ca = a[i - 1]; val cb = b[j - 1]
                val sub = if (ca == cb) 0f else if (near(ca, cb)) 0.6f else 1f
                val del = if (i > 1 && ca == a[i - 2]) 0.5f else 1f // a has an extra letter
                val ins = if (j > 1 && cb == b[j - 2]) 0.5f else 1f // a is missing a letter
                var v = min(prev[j - 1] + sub, min(prev[j] + del, cur[j - 1] + ins))
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) v = min(v, prev2[j - 2] + 0.7f)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return Float.MAX_VALUE
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    private const val ROWS = "qwertyuiop|asdfghjkl|zxcvbnm"
    private val pos: Map<Char, Pair<Float, Int>> = buildMap {
        ROWS.split('|').forEachIndexed { r, row ->
            row.forEachIndexed { i, ch -> put(ch, (i + r * 0.5f) to r) }
        }
    }

    /** Keys next to each other on QWERTY. */
    private fun near(a: Char, b: Char): Boolean {
        val pa = pos[a] ?: return false
        val pb = pos[b] ?: return false
        return abs(pa.second - pb.second) <= 1 && abs(pa.first - pb.first) <= 1f
    }
}
