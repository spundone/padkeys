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
import kotlin.math.max
import kotlin.math.min
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
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).also { it.textAlign = Paint.Align.CENTER; it.textSize = 16 * density; it.typeface = Fonts.regular }
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

    /** The toolbar's buttons and the paste chip. */
    var tools: ToolbarListener? = null
    /** Tools shown when there are no suggestions. */
    var toolset: List<Tool> = emptyList()
        set(v) { if (field != v) { field = v; invalidate() } }
    /** The tool whose panel is open: highlighted, and the toolbar stays up while it is. */
    var activeTool: Tool? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    private var clip: String? = null
    private val toolbarShown get() = activeTool != null || words.isEmpty()
    /** The paste chip sits left of the tools while there are no suggestions and no panel is open. */
    private val chipShown get() = activeTool == null && words.isEmpty() && clip != null

    /** Offer [text] (just copied) as a paste chip while there are no suggestions; null removes it. */
    fun offerClip(text: String?) {
        if (text == clip) return
        clip = text
        invalidate()
    }

    fun show(list: List<String>) {
        if (list == words) return
        words = list
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (48 * density).toInt())

    private val iconW get() = 52 * density
    private fun slotW() = (width - 2 * iconW) / 3f

    /** Which part of the strip is at [x]. */
    private fun hit(x: Float): Int = when {
        x < iconW -> LAYOUT
        x > width - iconW -> SETTINGS
        chipShown && x < toolsLeft() -> CLIP
        toolbarShown -> toolAt(x)?.let { TOOL + it } ?: -1
        else -> ((x - iconW) / slotW()).toInt().coerceIn(0, 2)
    }

    private fun toolsLeft() = if (chipShown) chip(tmpChip).right + 4 * density else iconW
    private fun toolW() = min((width - iconW - toolsLeft()) / toolset.size.coerceAtLeast(1), 76 * density)
    private fun toolX0() = toolsLeft() + (width - iconW - toolsLeft() - toolset.size * toolW()) / 2

    private fun toolAt(x: Float): Int? {
        val i = ((x - toolX0()) / toolW()).toInt()
        return if (x >= toolX0() && i in toolset.indices) i else null
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
                    CLIP -> clip?.let { tools?.onClipChip(it) }
                    in TOOL until TOOL + toolset.size -> tools?.onTool(toolset[p - TOOL])
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
        // Round tonal buttons at each end (secondary container), the accent container while pressed.
        val r = 18 * density
        for ((which, cx) in listOf(LAYOUT to iconW / 2 + 2 * density, SETTINGS to width - iconW / 2 - 2 * density)) {
            fill.color = if (pressed == which) C.ACCENT_DIM else C.KEY_FN
            c.drawCircle(cx, h / 2, r, fill)
            icon.color = if (pressed == which) C.ON_ACCENT_DIM else C.ON_KEY_FN
            if (which == LAYOUT) drawKeyboardIcon(c, cx, h / 2) else drawGear(c, cx, h / 2)
        }

        if (toolbarShown) { if (chipShown) drawClip(c); drawTools(c); return }

        // Words: the best guess sits in the middle on a pill, like a selected chip; the others are plain text.
        val sw = slotW()
        for ((i, word) in words.withIndex()) {
            if (i >= 3) break
            val slot = slotOf(i)
            val cx = iconW + slot * sw + sw / 2
            text.typeface = if (i == 0) Fonts.medium else Fonts.regular
            val s = TextUtils.ellipsize(word, text, sw - 28 * density, TextUtils.TruncateAt.END).toString()
            val tw = text.measureText(s)
            val isPressed = pressed == slot
            if (i == 0 || isPressed) {
                val pw = max(tw + 32 * density, min(sw - 8 * density, 72 * density))
                tmp.set(cx - pw / 2, 6 * density, cx + pw / 2, h - 6 * density)
                fill.color = if (isPressed) C.ACCENT_DIM else C.KEY
                c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill)
            }
            text.color = if (isPressed) C.ON_ACCENT_DIM else C.TEXT
            c.drawText(s, cx, h / 2 - (text.ascent() + text.descent()) / 2, text)
        }
    }

    /** Toolbar: round icon buttons, the open panel's one filled. */
    private fun drawTools(c: Canvas) {
        val h = height.toFloat()
        val tw = toolW()
        val half = min(tw / 2 - 2 * density, 26 * density)
        for ((i, t) in toolset.withIndex()) {
            val cx = toolX0() + tw * i + tw / 2
            val on = activeTool == t
            val down = pressed == TOOL + i
            if (on || down) {
                fill.color = if (down) C.ACCENT_DIM else C.KEY_FN
                tmp.set(cx - half, 6 * density, cx + half, h - 6 * density)
                c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill)
            }
            Icons.draw(c, t.icon, cx, h / 2, 22 * density, if (down) C.ON_ACCENT_DIM else if (on) C.ON_KEY_FN else C.MUTED)
        }
    }

    private val tmpChip = RectF()
    private var chipFor: String? = null
    private var chipText = ""
    private var chipW = 0f

    /** The paste chip's outline (left of the tools); its text is the start of the copied text, on one line. */
    private fun chip(out: RectF): RectF {
        val s = clip ?: ""
        val maxW = (width - 2 * iconW) * 0.42f
        if ("$width\u0000$s" != chipFor) {
            text.typeface = Fonts.regular
            val one = s.replace(whitespace, " ").trim()
            chipText = TextUtils.ellipsize(one, text, min(maxW - 56 * density, 280 * density), TextUtils.TruncateAt.END).toString()
            chipW = text.measureText(chipText) + 56 * density
            chipFor = "$width\u0000$s"
        }
        return out.apply { set(iconW + 6 * density, 6 * density, iconW + 6 * density + chipW, height - 6 * density) }
    }

    /** The paste chip: a pill with a clipboard icon and the start of the copied text. */
    private fun drawClip(c: Canvas) {
        val h = height.toFloat()
        text.typeface = Fonts.regular
        chip(tmp)
        fill.color = if (pressed == CLIP) C.ACCENT_DIM else C.KEY
        c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill)
        val ink = if (pressed == CLIP) C.ON_ACCENT_DIM else C.TEXT
        Icons.draw(c, Icons.Id.PASTE, tmp.left + 22 * density, h / 2, 18 * density, ink)
        text.color = ink
        c.drawText(chipText, tmp.left + 36 * density + text.measureText(chipText) / 2, h / 2 - (text.ascent() + text.descent()) / 2, text)
    }

    private val whitespace = Regex("\\s+")

    /** On screen the order is (2nd, 1st, 3rd): the best guess sits in the middle, under the thumbs. */
    private fun slotOf(word: Int) = when (word) { 0 -> 1; 1 -> 0; else -> 2 }
    private fun wordAtSlot(slot: Int) = slotOf(slot) // the mapping is its own inverse

    /** A small keyboard: outline with two rows of dots and a space bar. */
    private fun drawKeyboardIcon(c: Canvas, cx: Float, cy: Float) {
        val w = 18 * density; val h = 12.5f * density
        tmp.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.drawRoundRect(tmp, 3 * density, 3 * density, icon)
        fill.color = icon.color
        for (row in 0..1) for (col in 0..3) {
            val x = cx - w / 2 + (col + 1) * w / 5
            val y = cy - h / 2 + (row + 1) * h / 4.2f
            c.drawCircle(x, y, 1.1f * density, fill)
        }
        c.drawLine(cx - w / 4, cy + h / 4, cx + w / 4, cy + h / 4, icon)
    }

    /** A gear: a ring with eight teeth. */
    private fun drawGear(c: Canvas, cx: Float, cy: Float) {
        val r = 5f * density
        c.drawCircle(cx, cy, r, icon)
        c.drawCircle(cx, cy, 1.9f * density, icon)
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            val x0 = cx + (r + 0.8f * density) * cos(a).toFloat(); val y0 = cy + (r + 0.8f * density) * sin(a).toFloat()
            val x1 = cx + (r + 2.6f * density) * cos(a).toFloat(); val y1 = cy + (r + 2.6f * density) * sin(a).toFloat()
            c.drawLine(x0, y0, x1, y1, icon)
        }
    }

    private companion object {
        const val LAYOUT = 10
        const val SETTINGS = 11
        const val CLIP = 12
        const val TOOL = 20
    }
}
