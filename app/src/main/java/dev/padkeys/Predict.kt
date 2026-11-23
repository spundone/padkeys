package dev.padkeys

import android.annotation.SuppressLint
import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.util.Locale
import java.util.PriorityQueue

/**
 * Word suggestions, PadBridge's approach: completions from a bundled list of common English words, corrections
 * from the phone's spell checker (its dictionaries, the personal dictionary included), and optionally words learned
 * from typing on this keyboard ([Prefs.learn], off by default, stored only on the device).
 *
 * Unlike PadBridge, which had to follow the keys it sent to a computer, the keyboard asks the editor for the word
 * before the cursor ([Words.ending]) and passes it to [lookup], so suggestions stay right after the cursor moves,
 * text is pasted or the app changes the text itself.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object Predict {
    /** Receives suggestions for [word] (up to three, best first). Called on the main thread. */
    fun interface Listener { fun onSuggestions(word: String, list: List<String>) }

    var listener: Listener? = null

    private var session: SpellCheckerSession? = null
    /** The word last looked up; spell checker answers for older words are dropped. */
    private var asked = ""
    /** Words sent to the spell checker, oldest first; its answers come back in the same order. */
    private val checking = ArrayDeque<String>()
    private lateinit var app: Context

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        Thread { loadWords() }.start()
    }

    // ---------------------------------------------------------------- bundled word list

    /** The 40,000 most common English words, most common first (wordfreq, CC BY-SA 4.0). */
    @Volatile private var words: Array<String> = emptyArray()
    /** Indexes into [words], sorted alphabetically, for prefix lookups. */
    @Volatile private var sorted: IntArray = IntArray(0)

    private fun loadWords() {
        try {
            val w = app.assets.open("words_en.txt").bufferedReader().readLines().filter { it.isNotBlank() }.toTypedArray()
            val idx = w.indices.sortedWith { a, b -> w[a].compareTo(w[b]) }.toIntArray()
            words = w; sorted = idx
        } catch (_: Exception) {}
    }

    /** The [n] most common words that start with [prefix] (lowercase) and are longer than it. */
    private fun completions(prefix: String, n: Int): List<String> {
        val w = words; val s = sorted
        if (w.isEmpty() || prefix.isEmpty()) return emptyList()
        // Binary search the first word >= prefix, then walk the run of words sharing the prefix.
        var lo = 0; var hi = s.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (w[s[mid]] < prefix) lo = mid + 1 else hi = mid }
        val best = PriorityQueue<Int>(compareByDescending { it })
        var i = lo
        while (i < s.size && w[s[i]].startsWith(prefix)) {
            val r = s[i]
            if (w[r].length > prefix.length) { best.add(r); if (best.size > n) best.poll() }
            i++
        }
        return best.sorted().map { w[it] }
    }

    /** The bundled list, most common first (empty while it loads). For the glide decoder. */
    fun wordList(): Array<String> = words

    /** [word] is in the bundled list (so it's spelled right and needs no correction). */
    private fun known(word: String): Boolean {
        val w = words; val s = sorted
        if (w.isEmpty()) return false
        var lo = 0; var hi = s.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (w[s[mid]] < word) lo = mid + 1 else hi = mid }
        return lo < s.size && w[s[lo]] == word
    }

    // ---------------------------------------------------------------- spell checker

    private fun session(): SpellCheckerSession? {
        session?.let { return it }
        if (!::app.isInitialized) return null
        val tsm = app.getSystemService(TextServicesManager::class.java) ?: return null
        session = try {
            @Suppress("DEPRECATION")
            tsm.newSpellCheckerSession(null, Locale.US, checkerListener, true)
        } catch (_: Exception) { null }
        return session
    }

    private val checkerListener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {}
        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            val found = ArrayList<String>()
            results?.forEach { s ->
                for (i in 0 until s.suggestionsCount) {
                    val info = s.getSuggestionsInfoAt(i) ?: continue
                    for (j in 0 until info.suggestionsCount) info.getSuggestionAt(j)?.let { found += it }
                }
            }
            val forWord = checking.removeFirstOrNull()
            if (forWord != null && forWord == asked) show(forWord, found)
        }
    }

    /** The keyboard went away: close the spell checker session (it holds a binding to the checker's service). */
    fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        asked = ""
        checking.clear()
    }

    // ---------------------------------------------------------------- lookups

    /** Suggest for [word], the word before the cursor ("" clears the strip). */
    fun lookup(word: String) {
        if (word.isEmpty() || word.all { it.isDigit() }) { asked = ""; listener?.onSuggestions("", emptyList()); return }
        if (word == asked) return
        asked = word
        // Completions and learned words at once; the spell checker's answer extends them when it arrives.
        show(word, emptyList())
        if (word.length >= 2 && !known(word.lowercase())) try {
            session()?.let { it.getSentenceSuggestions(arrayOf(TextInfo(word)), 5); checking.addLast(word) }
            if (checking.size > 20) checking.removeFirst() // a checker that stopped answering
        } catch (_: Exception) {}
    }

    /** Forget the current word, so the next [lookup] of the same word asks again. */
    fun reset() { asked = "" }

    private fun show(word: String, fromChecker: List<String>) {
        val prefix = word.lowercase()
        val learned = learnedWords().filter { it.length > prefix.length && it.startsWith(prefix) }.take(3)
        // Typo (no word starts like this): the spell checker's corrections lead; otherwise completions do.
        val complete = completions(prefix, 3)
        val ordered = if (complete.isEmpty()) learned + fromChecker else learned + complete + fromChecker
        val all = LinkedHashSet<String>()
        ordered.forEach { s ->
            if (s.isNotBlank() && !s.equals(word, ignoreCase = true) && ' ' !in s) all += matchCase(word, s)
        }
        listener?.onSuggestions(word, all.take(3))
    }

    /** Keep the user's capitalization: "Hel" -> "Hello", "HEL" -> "HELLO". */
    private fun matchCase(word: String, s: String): String = when {
        s == "i" || s.startsWith("i'") -> s.replaceFirstChar { 'I' }
        word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() } -> s.uppercase()
        word.firstOrNull()?.isUpperCase() == true -> s.replaceFirstChar { it.uppercaseChar() }
        else -> s
    }

    // ---------------------------------------------------------------- learned words (opt-in)

    private const val MAX_WORDS = 3000
    private var cache: MutableMap<String, Int>? = null

    private fun counts(): MutableMap<String, Int> {
        cache?.let { return it }
        val m = HashMap<String, Int>()
        Prefs.learnedWords.split('\n').forEach { line ->
            val i = line.lastIndexOf('\t')
            if (i > 0) m[line.substring(0, i)] = line.substring(i + 1).toIntOrNull() ?: 1
        }
        cache = m
        return m
    }

    private fun learnedWords(): List<String> =
        if (!Prefs.learn) emptyList() else counts().entries.sortedByDescending { it.value }.map { it.key }

    /** Learned words and how often each was typed, when learning is on (glide typing favors them). */
    fun learnedCounts(): Map<String, Int> = if (!Prefs.learn) emptyMap() else counts()

    /** How many words have been learned. */
    val learnedCount get() = counts().size

    /**
     * A word was typed (or picked) in a field that allows learning. Only kept when learning is on; words shorter
     * than three letters or containing digits are skipped (codes, numbers).
     */
    fun learn(word: String) {
        if (!Prefs.learn || word.length < 3 || word.any { it.isDigit() }) return
        val m = counts()
        val k = word.lowercase()
        m[k] = (m[k] ?: 0) + 1
        if (m.size > MAX_WORDS) m.entries.minByOrNull { it.value }?.let { m.remove(it.key) }
        Prefs.learnedWords = m.entries.joinToString("\n") { "${it.key}\t${it.value}" }
    }

    /** Forget every learned word. */
    fun forget() { cache = HashMap(); Prefs.learnedWords = "" }
}
