package dev.padkeys

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** What holding a key offers (Gboard-like): accented letters, and punctuation on the period. */
object Alternates {
    private val letters = mapOf(
        'a' to "à á â ä æ ã å ā",
        'c' to "ç ć č",
        'd' to "ď ð",
        'e' to "è é ê ë ē ę ė",
        'g' to "ğ",
        'i' to "î ï í ī į ì",
        'l' to "ł",
        'n' to "ñ ń",
        'o' to "ô ö ò ó œ ø ō õ",
        'r' to "ř",
        's' to "ß ś š",
        't' to "þ ť",
        'u' to "û ü ù ú ū",
        'y' to "ÿ ý",
        'z' to "ž ź ż",
    ).mapValues { it.value.split(' ') }

    /** The period's popup: the punctuation a sentence needs, most used first. */
    private val period = "? ! , ' \" : ; - ( ) / @ & # % …".split(' ')

    /** Alternates for [label] (a key's lowercase label), or null if holding it does something else. */
    fun of(label: String): List<String>? = when {
        label == "." -> period
        label.length == 1 -> letters[label[0]]
        else -> null
    }

    /** The alternates in the case the key would type now. */
    fun cased(list: List<String>, upper: Boolean): List<String> =
        if (!upper) list else list.map { if (it == "ß") "ẞ" else it.uppercase() }
}

/**
 * A long-press popup over [key]: a row (or rows) of [items] above it. The finger that opened it slides to pick one
 * ([move]); lifting types the [selected] item. Geometry is in the keyboard view's coordinates; [minTop] lets it rise
 * above the view into the suggestion strip.
 */
class KeyPopup(
    val pid: Int,
    val key: KeyboardView.Key,
    val items: List<String>,
    viewWidth: Float,
    minTop: Float,
    private val density: Float,
) {
    private val cols = min(items.size, MAX_COLS)
    private val rows = ceil(items.size / cols.toFloat()).toInt()
    private val cellW = key.rect.width().coerceIn(40 * density, 58 * density)
    private val cellH = min(key.rect.height(), 56 * density).coerceAtLeast(40 * density)
    private val pad = 6 * density
    /** The popup's outline. */
    val box = RectF()
    var selected = 0
        private set
    /** Near the right edge the items run right to left, so the first one still sits over the key. */
    private var flip = false

    init {
        val w = cols * cellW + 2 * pad
        val h = rows * cellH + 2 * pad
        // The first item sits right over the key, the rest run to the right (or left at the right edge).
        var left = key.rect.centerX() - cellW / 2 - pad
        if (left + w > viewWidth) { left = key.rect.centerX() + cellW / 2 + pad - w; flip = true }
        left = left.coerceIn(0f, max(0f, viewWidth - w))
        val top = max(minTop, key.rect.top - h - 4 * density)
        box.set(left, top, left + w, top + h)
        selected = 0
    }

    /** Rows fill from the bottom (nearest the finger) up, like Gboard: row 0 is the bottom one. */
    private fun cell(i: Int, out: RectF): RectF {
        val row = i / cols
        val col = if (flip) cols - 1 - i % cols else i % cols
        val l = box.left + pad + col * cellW
        val b = box.bottom - pad - row * cellH
        return out.apply { set(l, b - cellH, l + cellW, b) }
    }

    /** The finger moved to ([x], [y]): select the item under it (the nearest one when outside the popup). */
    fun move(x: Float, y: Float): Boolean {
        var col = ((x - box.left - pad) / cellW).toInt().coerceIn(0, cols - 1)
        if (flip) col = cols - 1 - col
        val row = ((box.bottom - pad - y) / cellH).toInt().coerceIn(0, rows - 1)
        val i = min(row * cols + col, items.lastIndex)
        if (i == selected) return false
        selected = i
        return true
    }

    val text get() = items[selected]

    private val tmp = RectF()

    fun draw(c: Canvas, dx: Float, dy: Float, fill: Paint, text: Paint) {
        val r = min(cellW, cellH) * 0.42f
        tmp.set(box); tmp.offset(dx, dy)
        fill.color = 0x33000000
        c.drawRoundRect(tmp.left, tmp.top + 2 * density, tmp.right, tmp.bottom + 2 * density, r + pad, r + pad, fill)
        fill.color = C.SURFACE3
        c.drawRoundRect(tmp, r + pad, r + pad, fill)
        val size = text.textSize
        text.textSize = min(size, cellH * 0.5f)
        for (i in items.indices) {
            cell(i, tmp); tmp.offset(dx, dy)
            if (i == selected) {
                fill.color = C.ACTION
                c.drawRoundRect(tmp, r, r, fill)
            }
            text.color = if (i == selected) C.ON_ACTION else C.TEXT
            c.drawText(items[i], tmp.centerX(), tmp.centerY() - (text.ascent() + text.descent()) / 2, text)
        }
        text.textSize = size
    }

    private companion object {
        const val MAX_COLS = 8
    }
}
