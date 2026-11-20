package dev.padkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * The strip above the keys: up to three word suggestions in the middle, a layout button on the left (tap: next
 * layout, hold: pick another keyboard) and a settings button on the right. Canvas-drawn like the keys, so it shares
 * their colors and needs no view hierarchy.
 */
class SuggestionStrip(ctx: Context) : View(ctx) {

    interface Listener {
        fun onPick(word: String)
        fun onLayoutButton()
        fun onLayoutButtonLong()
        fun onSettingsButton()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private var words: List<String> = emptyList()
    // (Not "textSize = 17 * density" inside apply: TextPaint has its own density field, which would win.)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).also { it.textAlign = Paint.Align.CENTER; it.textSize = 17 * density }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val tmp = RectF()
    private var pressed = -1 // 0..2 a word, LAYOUT, SETTINGS
    private var longFired = false
    private val longPress = Runnable {
        if (pressed == LAYOUT) { longFired = true; Haptics.click(); listener?.onLayoutButtonLong() }
    }

    fun show(list: List<String>) {
        if (list == words) return
        words = list
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (44 * density).toInt())

    private val iconW get() = 48 * density
    private fun slotW() = (width - 2 * iconW) / 3f

    /** Which part of the strip is at [x]. */
    private fun hit(x: Float): Int = when {
        x < iconW -> LAYOUT
        x > width - iconW -> SETTINGS
        else -> ((x - iconW) / slotW()).toInt().coerceIn(0, 2)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit(e.x)
                if (pressed in 0..2 && wordAtSlot(pressed) >= words.size) pressed = -1
                longFired = false
                if (pressed == LAYOUT) postDelayed(longPress, 450)
                if (pressed != -1) Haptics.tick()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val p = pressed
                pressed = -1
                invalidate()
                if (p == -1 || hit(e.x) != p || longFired) return true
                when (p) {
                    LAYOUT -> listener?.onLayoutButton()
                    SETTINGS -> listener?.onSettingsButton()
                    else -> words.getOrNull(wordAtSlot(p))?.let { listener?.onPick(it) }
                }
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); pressed = -1; invalidate() }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(C.BG)
        val h = height.toFloat()
        // Pressed highlight.
        if (pressed != -1) {
            val l = when (pressed) { LAYOUT -> 0f; SETTINGS -> width - iconW; else -> iconW + pressed * slotW() }
            val w = if (pressed == LAYOUT || pressed == SETTINGS) iconW else slotW()
            tmp.set(l + 3 * density, 4 * density, l + w - 3 * density, h - 4 * density)
            fill.color = C.SURFACE3
            c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill)
        }
        icon.color = C.MUTED
        drawKeyboardIcon(c, iconW / 2, h / 2)
        drawGear(c, width - iconW / 2, h / 2)

        // Words: the middle (first) one is the best guess, so it's bold, like most phone keyboards.
        val sw = slotW()
        for ((i, word) in words.withIndex()) {
            if (i >= 3) break
            val slot = slotOf(i)
            val cx = iconW + slot * sw + sw / 2
            text.color = C.TEXT
            text.isFakeBoldText = i == 0
            val s = TextUtils.ellipsize(word, text, sw - 12 * density, TextUtils.TruncateAt.END).toString()
            c.drawText(s, cx, h / 2 - (text.ascent() + text.descent()) / 2, text)
        }
        // Dividers between the word slots.
        fill.color = C.STROKE
        for (i in 1..2) {
            val x = iconW + i * sw
            if (words.size > 1) c.drawRect(x - density / 2, h * 0.3f, x + density / 2, h * 0.7f, fill)
        }
    }

    /** On screen the order is (2nd, 1st, 3rd): the best guess sits in the middle, under the thumbs. */
    private fun slotOf(word: Int) = when (word) { 0 -> 1; 1 -> 0; else -> 2 }
    private fun wordAtSlot(slot: Int) = slotOf(slot) // the mapping is its own inverse

    /** A small keyboard: outline with two rows of dots and a space bar. */
    private fun drawKeyboardIcon(c: Canvas, cx: Float, cy: Float) {
        val w = 22 * density; val h = 15 * density
        tmp.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.drawRoundRect(tmp, 3 * density, 3 * density, icon)
        fill.color = C.MUTED
        for (row in 0..1) for (col in 0..3) {
            val x = cx - w / 2 + (col + 1) * w / 5
            val y = cy - h / 2 + (row + 1) * h / 4.2f
            c.drawCircle(x, y, 1.1f * density, fill)
        }
        c.drawLine(cx - w / 4, cy + h / 4, cx + w / 4, cy + h / 4, icon)
    }

    /** A gear: a ring with eight teeth. */
    private fun drawGear(c: Canvas, cx: Float, cy: Float) {
        val r = 6.5f * density
        c.drawCircle(cx, cy, r, icon)
        c.drawCircle(cx, cy, 2.4f * density, icon)
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            val x0 = cx + (r + 0.8f * density) * cos(a).toFloat(); val y0 = cy + (r + 0.8f * density) * sin(a).toFloat()
            val x1 = cx + (r + 3.2f * density) * cos(a).toFloat(); val y1 = cy + (r + 3.2f * density) * sin(a).toFloat()
            c.drawLine(x0, y0, x1, y1, icon)
        }
    }

    private companion object {
        const val LAYOUT = 10
        const val SETTINGS = 11
    }
}
