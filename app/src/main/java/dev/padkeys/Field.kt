package dev.padkeys

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * What the focused text field allows, read once from its [EditorInfo] when the keyboard starts on it. Suggestions and
 * learning are decided here, in one place, so a password can never reach the word list: password fields, fields
 * that ask for no suggestions and non-text fields (numbers, phone, dates) get neither, and fields that ask for no
 * personalized learning (incognito tabs, for example) still get suggestions but never teach the keyboard.
 */
data class Field(
    /** Show word suggestions. */
    val suggest: Boolean,
    /** Misspelled words may be corrected (text you write in prose: not names, addresses, filters). */
    val autocorrect: Boolean,
    /** Number pad kind: [PAD_NONE], [PAD_NUMBER], [PAD_PHONE] or [PAD_DATE]. */
    val pad: Int,
    /** Typed words may be learned (if the user turned learning on). */
    val learn: Boolean,
    /** The field wants sentences capitalized ([EditorInfo.inputType] has a CAP flag). */
    val autoCap: Boolean,
    /** Digits matter more than letters (number, phone, date fields): start on the symbols page. */
    val numeric: Boolean,
    /** No editable text behind the connection (TYPE_NULL, e.g. terminals): send key events, not text. */
    val raw: Boolean,
    val multiLine: Boolean,
    /** Label for the Enter key: the editor action ("Go", "Send"…) or ↵ for a plain newline. */
    val enterLabel: String,
) {
    companion object {
        const val PAD_NONE = 0
        const val PAD_NUMBER = 1
        const val PAD_PHONE = 2
        const val PAD_DATE = 3

        val NONE = Field(suggest = false, autocorrect = false, pad = PAD_NONE, learn = false, autoCap = false, numeric = false, raw = true, multiLine = false, enterLabel = "↵")

        fun of(info: EditorInfo?): Field {
            if (info == null) return NONE
            val type = info.inputType
            val cls = type and InputType.TYPE_MASK_CLASS
            val variation = type and InputType.TYPE_MASK_VARIATION
            val flags = type and InputType.TYPE_MASK_FLAGS
            val text = cls == InputType.TYPE_CLASS_TEXT
            val password = isPassword(cls, variation)
            val noSuggest = flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
            // Addresses and URLs aren't dictionary words: suggest nothing there either.
            val notWords = text && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_URI,
            )
            val suggest = text && !password && !noSuggest && !notWords
            val noLearning = info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
            val caps = text && !password &&
                flags and (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                    InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0
            // Names, addresses and list filters are full of words no dictionary knows: don't "fix" them.
            val prose = suggest && variation !in setOf(
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME, InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_FILTER, InputType.TYPE_TEXT_VARIATION_PHONETIC,
            )
            return Field(
                suggest = suggest,
                autocorrect = prose,
                pad = when {
                    cls == InputType.TYPE_CLASS_NUMBER -> PAD_NUMBER // PINs too
                    cls == InputType.TYPE_CLASS_PHONE -> PAD_PHONE
                    cls == InputType.TYPE_CLASS_DATETIME -> PAD_DATE
                    else -> PAD_NONE
                },
                learn = suggest && !noLearning,
                autoCap = caps,
                numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
                    cls == InputType.TYPE_CLASS_DATETIME,
                raw = cls == InputType.TYPE_NULL,
                multiLine = text && flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0,
                enterLabel = enterLabel(info),
            )
        }

        fun isPassword(cls: Int, variation: Int) = when (cls) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }

        /** The action Enter performs, or null for a plain newline / Enter key. */
        fun editorAction(info: EditorInfo): Int? {
            if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
            val a = info.imeOptions and EditorInfo.IME_MASK_ACTION
            return if (a == EditorInfo.IME_ACTION_NONE || a == EditorInfo.IME_ACTION_UNSPECIFIED) null else a
        }

        private fun enterLabel(info: EditorInfo): String {
            if (editorAction(info) == null) return "↵"
            info.actionLabel?.toString()?.takeIf { it.isNotBlank() && it.length <= 8 }?.let { return it }
            return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_GO -> "Go"
                EditorInfo.IME_ACTION_SEARCH -> "Search"
                EditorInfo.IME_ACTION_SEND -> "Send"
                EditorInfo.IME_ACTION_NEXT -> "Next"
                EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
                EditorInfo.IME_ACTION_DONE -> "Done"
                else -> "↵"
            }
        }
    }
}

/** Finding the word around the cursor from the editor's own text (not from the keys pressed). */
object Words {
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’'

    /** The word ending at the end of [before] (text before the cursor), without leading apostrophes. */
    fun ending(before: CharSequence?): String {
        if (before.isNullOrEmpty()) return ""
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        return before.substring(i).trimStart('\'', '’')
    }

    /** The rest of the word that starts at the beginning of [after] (text after the cursor). */
    fun starting(after: CharSequence?): String {
        if (after.isNullOrEmpty()) return ""
        var i = 0
        while (i < after.length && isWordChar(after[i])) i++
        return after.substring(0, i)
    }

    /** How many characters Ctrl + Backspace removes: spaces before the cursor, then the word (or run of symbols). */
    fun deleteWordLength(before: CharSequence?): Int {
        if (before.isNullOrEmpty()) return 0
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        if (i > 0 && isWordChar(before[i - 1])) {
            while (i > 0 && isWordChar(before[i - 1])) i--
        } else {
            while (i > 0 && !before[i - 1].isWhitespace() && !isWordChar(before[i - 1])) i--
        }
        return before.length - i
    }
}
