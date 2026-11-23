package dev.padkeys

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * What a glided word does to the text, like Gboard:
 *
 * - the best word is committed, with a space before it unless the cursor is after a space, at the start, or after
 *   opening punctuation; no space after it, so punctuation typed next sticks to the word and the next gesture adds
 *   its own space,
 * - the next candidates go to the suggestion strip; picking one replaces the glided word,
 * - Backspace right after deletes the whole glided word.
 *
 * All of that holds only while the cursor stays right after the word; any other edit or cursor move ends it.
 */
class GlideInput(
    private val connection: () -> InputConnection?,
    /** Shows words in the suggestion strip. */
    private val show: (List<String>) -> Unit,
    /** Learning is on for this field. */
    private val learning: () -> Boolean,
) : Glide.Listener {

    /** The glided word, as committed; null when the cursor has moved on. */
    private var word: String? = null
    private var others: List<String> = emptyList()
    /** The cursor after the commit, -1 until the editor reports it. */
    private var at = -1

    /** The strip shows the glided word's alternatives (the service's own suggestions wait). */
    val showing get() = word != null

    override fun onGlide(words: List<String>) {
        val ic = connection() ?: return
        val best = words.firstOrNull() ?: return
        val selected = !ic.getSelectedText(0).isNullOrEmpty()
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        val space = !selected && spaceBefore(before)
        // The word before is finished: learn it (opt-in), as a typed space would.
        if (space && learning()) Predict.learn(Words.ending(ic.getTextBeforeCursor(48, 0)))
        ic.beginBatchEdit()
        ic.commitText(if (space) " $best" else best, 1)
        ic.endBatchEdit()
        word = best; others = words.drop(1).take(3); at = -1
        show(others)
        Haptics.soft(0.4f)
    }

    /** The editor's cursor moved to ([start], [end]). */
    fun onSelection(start: Int, end: Int) {
        if (word == null) return
        if (start != end) { clear(); return }
        if (at == -1) at = end else if (end != at) clear()
    }

    /** A suggestion was picked: if it's one of the glided word's alternatives, it replaces that word. */
    fun pick(w: String): Boolean {
        val cur = word ?: return false
        if (w !in others) return false
        val ic = connection() ?: return false
        if (ic.getTextBeforeCursor(cur.length, 0)?.toString() != cur) { clear(); return false }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(cur.length, 0)
        ic.commitText(w, 1)
        ic.endBatchEdit()
        if (learning()) Predict.learn(w)
        others = others.map { if (it == w) cur else it }
        word = w; at = -1
        show(others)
        Haptics.soft(0.4f)
        return true
    }

    /** Backspace right after a glided word deletes all of it. */
    fun backspace(): Boolean {
        val cur = word ?: return false
        clear()
        val ic = connection() ?: return false
        if (ic.getTextBeforeCursor(cur.length, 0)?.toString() != cur) return false
        ic.deleteSurroundingText(cur.length, 0)
        return true
    }

    fun clear() { word = null; others = emptyList(); at = -1 }

    companion object {
        /** Glide typing in this field: text fields, never passwords. */
        fun allowed(info: EditorInfo?): Boolean {
            val type = info?.inputType ?: return false
            val cls = type and InputType.TYPE_MASK_CLASS
            return cls == InputType.TYPE_CLASS_TEXT && !Field.isPassword(cls, type and InputType.TYPE_MASK_VARIATION)
        }

        /** A glided word after [before] (the text before the cursor) needs a space first. */
        fun spaceBefore(before: String): Boolean {
            val c = before.lastOrNull() ?: return false
            if (c.isWhitespace()) return false
            if (c in "([{<¿¡/@#$‘“-_") return false
            // A quote opens after a space or at the start, and closes after a word.
            if (c == '"' || c == '\'') return before.length > 1 && !before[before.length - 2].isWhitespace()
            return true
        }
    }
}
