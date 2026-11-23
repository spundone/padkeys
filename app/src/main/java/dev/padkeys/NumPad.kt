package dev.padkeys

import android.view.KeyEvent
import dev.padkeys.KeyboardView.Key

/**
 * Callbacks for the keyboard's extra gestures, kept apart from [KeyboardView.Listener] (the keys themselves).
 * Implemented by the input method service.
 */
interface KeyActions {
    /** Delete-key swipe: select [words] words before the cursor (0 = none, back where it started). */
    fun onDeleteSwipe(words: Int)
    /** The delete-key swipe ended: delete the selection when [delete], otherwise leave the text as it was. */
    fun onDeleteSwipeEnd(delete: Boolean)
    /** Holding the comma (Phone layout): open the emoji panel. */
    fun onEmojiKey()
}

/**
 * Number and phone pads (Gboard-like), used instead of the chosen layout in number, phone and date fields. Digits in
 * three big columns, symbols the field may need on the left, space / Backspace / Enter on the right; ABC goes to the
 * full keyboard.
 */
object NumPad {
    const val ROWS = 4f

    fun keys(kind: Int, enter: Key): List<Key> {
        fun d(c: String) = Key(c)
        fun sym(c: String) = Key(c, small = true)
        val left = when (kind) {
            Field.PAD_PHONE -> listOf(sym("+"), sym("("), sym(")"))
            Field.PAD_DATE -> listOf(sym("/"), sym(":"), sym("-"))
            else -> listOf(sym("+"), sym("*"), sym("/"))
        }
        val bottom = when (kind) {
            Field.PAD_PHONE -> listOf(sym("*"), d("0"), sym("#"))
            Field.PAD_DATE -> listOf(sym(","), d("0"), sym("."))
            else -> listOf(sym(","), d("0"), sym("."))
        }
        val right = listOf(
            Key("-", small = true),
            Key(" ", KeyEvent.KEYCODE_SPACE),
            Key("⌫", KeyEvent.KEYCODE_DEL, repeat = true),
        )
        val rows = listOf(
            listOf(left[0], d("1"), d("2"), d("3"), right[0]),
            listOf(left[1], d("4"), d("5"), d("6"), right[1]),
            listOf(left[2], d("7"), d("8"), d("9"), right[2]),
            listOf(Key("ABC", KeyboardView.CODE_FULL_KEYBOARD, small = true)) + bottom + enter,
        )
        val out = ArrayList<Key>()
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, k -> k.gx = x.toFloat(); k.gy = y.toFloat(); out += k } }
        return out
    }
}
