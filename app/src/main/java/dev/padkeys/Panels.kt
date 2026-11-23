package dev.padkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** What the panels that replace the keys (emoji, clipboard, text editing) do, implemented by the service. */
interface PanelListener {
    fun onPanelText(text: String)
    /** A key ([KeyEvent] code) with [meta] (Shift for selecting). */
    fun onPanelKey(code: Int, meta: Int)
    /** Ctrl + [c]: select all, copy, cut, paste. */
    fun onPanelShortcut(c: Char)
    /** Back to the keys. */
    fun onPanelClose()
}

/**
 * A Canvas-drawn panel shown in the keys' place, the same size. Handles touch: press feedback, click, long press,
 * auto-repeat (Backspace, arrows) and, inside [scrollArea], vertical drag and fling scrolling. Subclasses map points
 * to ids ([hit]) and act on them ([click]).
 */
abstract class PanelView(ctx: Context) : View(ctx) {
    var listener: PanelListener? = null

    protected val density = resources.displayMetrics.density
    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val label = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.textAlign = Paint.Align.CENTER; it.typeface = Fonts.medium; it.textSize = 14 * density
    }
    protected val tmp = RectF()
    protected var pressed = NONE
    protected var scroll = 0f

    private val scroller = OverScroller(ctx)
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var longFired = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    /** The part that scrolls (null: nothing does). */
    protected open fun scrollArea(): RectF? = null
    protected open fun maxScroll() = 0f
    protected abstract fun hit(x: Float, y: Float): Int
    protected abstract fun click(id: Int)
    protected open fun longClick(id: Int) = false
    /** Fires on press and repeats while held (instead of on release). */
    protected open fun repeats(id: Int) = false

    /** The panel is being shown: reset what needs it. */
    open fun open() { scroll = 0f; scroller.forceFinished(true); pressed = NONE; invalidate() }

    private val longPress = Runnable {
        if (pressed != NONE && longClick(pressed)) { longFired = true; Haptics.click(); pressed = NONE; invalidate() }
    }
    private val repeater = object : Runnable {
        override fun run() {
            if (pressed != NONE && repeats(pressed)) { click(pressed); postDelayed(this, KeyboardView.REPEAT_MS) }
        }
    }

    private fun cancelTimers() { removeCallbacks(longPress); removeCallbacks(repeater) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; downY = e.y; lastY = e.y
                dragging = false; longFired = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                pressed = hit(e.x, e.y)
                if (pressed != NONE) {
                    Haptics.tick()
                    if (repeats(pressed)) { click(pressed); postDelayed(repeater, KeyboardView.REPEAT_DELAY_MS) }
                    else postDelayed(longPress, LONG_MS)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val area = scrollArea()
                if (!dragging && area != null && area.contains(downX, downY) && maxScroll() > 0 && abs(e.y - downY) > slop) {
                    dragging = true; cancelTimers(); pressed = NONE
                }
                if (dragging) { scroll = (scroll - (e.y - lastY)).coerceIn(0f, maxScroll()); invalidate() }
                else if (pressed != NONE && !repeats(pressed) && hit(e.x, e.y) != pressed) { cancelTimers(); pressed = NONE; invalidate() }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                cancelTimers()
                if (dragging) {
                    velocity?.let { v ->
                        v.computeCurrentVelocity(1000)
                        scroller.fling(0, scroll.toInt(), 0, -v.yVelocity.toInt(), 0, 0, 0, maxScroll().toInt())
                        postInvalidateOnAnimation()
                    }
                } else if (pressed != NONE && !longFired && !repeats(pressed) && hit(e.x, e.y) == pressed) click(pressed)
                pressed = NONE
                velocity?.recycle(); velocity = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTimers(); pressed = NONE; velocity?.recycle(); velocity = null; invalidate()
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() { cancelTimers(); super.onDetachedFromWindow() }

    /** A rounded button with an icon and / or a label, in the keys' colors ([function] = a function key's tone). */
    protected fun button(c: Canvas, r: RectF, icon: Icons.Id?, text: String?, down: Boolean, function: Boolean = true,
                         on: Boolean = false, pill: Boolean = false) {
        val f = when { down -> C.ACCENT_DIM; on -> C.ACTION; function -> C.KEY_FN; else -> C.KEY }
        val ink = when { down -> C.ON_ACCENT_DIM; on -> C.ON_ACTION; function -> C.ON_KEY_FN; else -> C.TEXT }
        fill.color = f
        val radius = if (pill || down) min(r.width(), r.height()) / 2 else min(r.width(), r.height()) * 0.3f
        c.drawRoundRect(r, radius, radius, fill)
        val size = min(22 * density, min(r.width(), r.height()) * 0.5f)
        if (icon != null && text != null && r.height() > 64 * density) {
            Icons.draw(c, icon, r.centerX(), r.centerY() - 10 * density, size, ink)
            label.color = ink
            c.drawText(text, r.centerX(), r.centerY() + 22 * density, label)
        } else if (icon != null && text != null) {
            label.color = ink
            val tw = label.measureText(text)
            val x0 = r.centerX() - (size + 8 * density + tw) / 2
            Icons.draw(c, icon, x0 + size / 2, r.centerY(), size, ink)
            c.drawText(text, x0 + size + 8 * density + tw / 2, r.centerY() - (label.ascent() + label.descent()) / 2, label)
        } else if (icon != null) {
            Icons.draw(c, icon, r.centerX(), r.centerY(), size, ink)
        } else if (text != null) {
            label.color = ink
            c.drawText(text, r.centerX(), r.centerY() - (label.ascent() + label.descent()) / 2, label)
        }
    }

    protected companion object {
        const val NONE = -1
        const val LONG_MS = 450L
    }
}

// ---------------------------------------------------------------- emoji

/**
 * Emoji: a scrolling grid of one category, category tabs along the bottom (Recent first, kept on the phone) between
 * ABC (back to the keys) and Backspace.
 */
class EmojiPanel(ctx: Context) : PanelView(ctx) {
    private var cat = 1
    /** The emoji shown: a snapshot, so recents don't reorder under the finger. */
    private var shown: List<String> = emptyList()
    private val emojiPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).also { it.textAlign = Paint.Align.CENTER }
    private val grid = RectF()

    private val barH get() = 52 * density
    private val cellH get() = 52 * density
    private val pad get() = 6 * density
    private val cols get() = max(6, (width / (52 * density)).toInt())
    private val cellW get() = width / cols.toFloat()
    private val sideW get() = 64 * density
    private val tabs get() = 1 + Emoji.categories.size

    private fun list(i: Int) = if (i == 0) Emoji.recent() else Emoji.categories[i - 1].list

    override fun open() {
        super.open()
        cat = if (Emoji.recent().isEmpty()) 1 else 0
        shown = list(cat)
    }

    override fun scrollArea(): RectF = grid.apply { set(0f, 0f, width.toFloat(), height - barH) }
    override fun maxScroll() = max(0f, ceil(shown.size / cols.toFloat()) * cellH + 2 * pad - (height - barH))

    private fun tabW() = (width - 2 * sideW) / tabs

    override fun hit(x: Float, y: Float): Int {
        if (y >= height - barH) return when {
            x < sideW -> ABC
            x > width - sideW -> DEL
            else -> TAB + ((x - sideW) / tabW()).toInt().coerceIn(0, tabs - 1)
        }
        val row = ((y + scroll - pad) / cellH).toInt()
        val col = (x / cellW).toInt().coerceIn(0, cols - 1)
        val i = row * cols + col
        return if (row >= 0 && i < shown.size) EMOJI + i else NONE
    }

    override fun repeats(id: Int) = id == DEL

    override fun click(id: Int) {
        when {
            id == ABC -> listener?.onPanelClose()
            id == DEL -> listener?.onPanelKey(KeyEvent.KEYCODE_DEL, 0)
            id >= EMOJI -> shown.getOrNull(id - EMOJI)?.let { Emoji.used(it); listener?.onPanelText(it) }
            id >= TAB -> { cat = id - TAB; shown = list(cat); scroll = 0f; invalidate() }
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(C.BG)
        val gridH = height - barH
        emojiPaint.textSize = min(cellH, cellW) * 0.56f
        c.save()
        c.clipRect(0f, 0f, width.toFloat(), gridH)
        if (shown.isEmpty()) {
            label.color = C.MUTED
            c.drawText(if (cat == 0) "Emoji you use show up here" else "", width / 2f, gridH / 2, label)
        }
        val first = max(0, ((scroll - pad) / cellH).toInt())
        val last = ((scroll + gridH) / cellH).toInt() + 1
        for (row in first..last) for (col in 0 until cols) {
            val i = row * cols + col
            if (i >= shown.size) break
            val cx = col * cellW + cellW / 2
            val top = pad + row * cellH - scroll
            if (pressed == EMOJI + i) {
                fill.color = C.ACCENT_DIM
                tmp.set(cx - cellW / 2 + 2 * density, top + 2 * density, cx + cellW / 2 - 2 * density, top + cellH - 2 * density)
                c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill)
            }
            c.drawText(shown[i], cx, top + cellH / 2 - (emojiPaint.ascent() + emojiPaint.descent()) / 2, emojiPaint)
        }
        c.restore()

        // Bottom bar: ABC, tabs, Backspace.
        val y0 = height - barH
        tmp.set(6 * density, y0 + 6 * density, sideW - 4 * density, height - 6 * density)
        button(c, tmp, null, "ABC", pressed == ABC, pill = true)
        tmp.set(width - sideW + 4 * density, y0 + 6 * density, width - 6 * density, height - 6 * density)
        button(c, tmp, Icons.Id.BACKSPACE, null, pressed == DEL, pill = true)
        val tw = tabW()
        val tabSize = min(tw * 0.5f, 22 * density)
        emojiPaint.textSize = tabSize * 0.9f
        for (i in 0 until tabs) {
            val cx = sideW + tw * i + tw / 2
            val cy = y0 + barH / 2
            if (i == cat || pressed == TAB + i) {
                fill.color = if (pressed == TAB + i) C.ACCENT_DIM else C.KEY_FN
                val half = min(tw / 2 - 1 * density, 22 * density)
                tmp.set(cx - half, y0 + 8 * density, cx + half, height - 8 * density)
                c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill)
            }
            if (i == 0) Icons.draw(c, Icons.Id.CLOCK, cx, cy, tabSize, if (cat == 0) C.ON_KEY_FN else C.MUTED)
            else {
                emojiPaint.alpha = if (i == cat) 255 else 150
                c.drawText(Emoji.categories[i - 1].icon, cx, cy - (emojiPaint.ascent() + emojiPaint.descent()) / 2, emojiPaint)
                emojiPaint.alpha = 255
            }
        }
    }

    private companion object {
        const val ABC = 1
        const val DEL = 2
        const val TAB = 100
        const val EMOJI = 1000
    }
}

// ---------------------------------------------------------------- clipboard

/**
 * Clipboard history ([Clips]): cards of copied text, pinned first. Tap a card to paste it; the pin and × buttons on
 * each card keep it for good or remove it.
 */
class ClipboardPanel(ctx: Context) : PanelView(ctx) {
    private var items: List<Clips.Item> = emptyList()
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).also { it.typeface = Fonts.regular; it.textSize = 14 * density }
    private val layouts = HashMap<String, StaticLayout>()
    private val grid = RectF()
    private val cardRect = RectF()

    private val barH get() = 52 * density
    private val pad get() = 8 * density
    private val gap get() = 8 * density
    private val cardH get() = 84 * density
    private val cols get() = if (width > 560 * density) 3 else 2
    private val cardW get() = (width - 2 * pad - (cols - 1) * gap) / cols
    private val btnW get() = 40 * density

    /** The history changed: show it again. */
    fun reload() { items = Clips.items(); scroll = scroll.coerceIn(0f, maxScroll()); invalidate() }

    override fun open() { super.open(); reload() }

    override fun scrollArea(): RectF = grid.apply { set(0f, 0f, width.toFloat(), height - barH) }
    override fun maxScroll() =
        max(0f, ceil(items.size / cols.toFloat()) * (cardH + gap) + pad - (height - barH))

    private fun card(i: Int, out: RectF): RectF {
        val col = i % cols; val row = i / cols
        val l = pad + col * (cardW + gap)
        val t = pad + row * (cardH + gap) - scroll
        return out.apply { set(l, t, l + cardW, t + cardH) }
    }

    override fun hit(x: Float, y: Float): Int {
        if (y >= height - barH) return if (x < 120 * density) ABC else NONE
        for (i in items.indices) {
            val r = card(i, tmp)
            if (!r.contains(x, y)) continue
            return CARD + i * 4 + when {
                x < r.right - btnW -> 0
                y < r.centerY() -> 1
                else -> 2
            }
        }
        return NONE
    }

    override fun click(id: Int) {
        if (id == ABC) { listener?.onPanelClose(); return }
        val item = items.getOrNull((id - CARD) / 4) ?: return
        when ((id - CARD) % 4) {
            0 -> listener?.onPanelText(item.text)
            1 -> Clips.pin(item.text, !item.pinned)
            2 -> Clips.delete(item.text)
        }
    }

    private fun layoutFor(s: String, w: Int): StaticLayout {
        val key = "$w\u0000$s"
        return layouts.getOrPut(key) {
            if (layouts.size > 64) layouts.clear()
            val t = if (s.length > 400) s.substring(0, 400) else s
            StaticLayout.Builder.obtain(t, 0, t.length, body, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(C.BG)
        val gridH = height - barH
        c.save()
        c.clipRect(0f, 0f, width.toFloat(), gridH)
        if (items.isEmpty()) {
            label.color = C.MUTED
            val msg = if (Prefs.clipboard) "Text you copy shows up here. Pin items to keep them"
            else "Clipboard history is off in PadKeys settings"
            c.drawText(msg, width / 2f, gridH / 2, label)
        }
        for (i in items.indices) {
            val r = card(i, cardRect)
            if (r.bottom < 0 || r.top > gridH) continue
            val base = CARD + i * 4
            fill.color = if (pressed == base) C.ACCENT_DIM else C.KEY
            c.drawRoundRect(r, 16 * density, 16 * density, fill)
            body.color = if (pressed == base) C.ON_ACCENT_DIM else C.TEXT
            val l = layoutFor(items[i].text, (r.width() - btnW - 20 * density).toInt().coerceAtLeast(10))
            c.save()
            c.translate(r.left + 14 * density, r.top + max(10 * density, (r.height() - l.height) / 2))
            l.draw(c)
            c.restore()
            val bx = r.right - btnW / 2 - 4 * density
            val pinOn = items[i].pinned
            if (pressed == base + 1 || pressed == base + 2) {
                fill.color = C.ACCENT_DIM
                val cy = if (pressed == base + 1) r.top + r.height() / 4 + 4 * density else r.bottom - r.height() / 4 - 4 * density
                c.drawCircle(bx, cy, 16 * density, fill)
            }
            Icons.draw(c, if (pinOn) Icons.Id.PINNED else Icons.Id.PIN, bx, r.top + r.height() / 4 + 4 * density, 18 * density,
                if (pinOn) C.ACCENT else C.MUTED)
            Icons.draw(c, Icons.Id.CLOSE, bx, r.bottom - r.height() / 4 - 4 * density, 16 * density, C.MUTED)
        }
        c.restore()

        val y0 = height - barH
        tmp.set(6 * density, y0 + 6 * density, 64 * density, height - 6 * density)
        button(c, tmp, null, "ABC", pressed == ABC, pill = true)
        label.color = C.MUTED
        c.drawText("Clipboard · tap to paste", width / 2f, y0 + barH / 2 - (label.ascent() + label.descent()) / 2, label)
    }

    private companion object {
        const val ABC = 1
        const val CARD = 1000
    }
}

// ---------------------------------------------------------------- text editing

/**
 * Text editing: arrows around a Select toggle (on: the arrows, Home and End extend the selection), Home / End,
 * Backspace / Delete, and Select all, Copy, Cut, Paste down the right.
 */
class EditPanel(ctx: Context) : PanelView(ctx) {
    private var selecting = false

    private class Cell(val id: Int, val col: Float, val row: Float, val w: Float, val h: Float, val icon: Icons.Id?, val text: String?)

    private val cells = listOf(
        Cell(HOME, 0f, 0f, 1f, 1f, Icons.Id.HOME, null),
        Cell(UP, 1f, 0f, 1f, 1f, Icons.Id.UP, null),
        Cell(END, 2f, 0f, 1f, 1f, Icons.Id.END, null),
        Cell(LEFT, 0f, 1f, 1f, 1f, Icons.Id.LEFT, null),
        Cell(SELECT, 1f, 1f, 1f, 1f, null, "Select"),
        Cell(RIGHT, 2f, 1f, 1f, 1f, Icons.Id.RIGHT, null),
        Cell(BKSP, 0f, 2f, 1f, 1f, Icons.Id.BACKSPACE, null),
        Cell(DOWN, 1f, 2f, 1f, 1f, Icons.Id.DOWN, null),
        Cell(FDEL, 2f, 2f, 1f, 1f, Icons.Id.FORWARD_DELETE, null),
        Cell(ABC, 0f, 3f, 3f, 1f, Icons.Id.KEYBOARD, "ABC"),
        Cell(ALL, 3f, 0f, 1f, 1f, Icons.Id.SELECT_ALL, "Select all"),
        Cell(COPY, 3f, 1f, 1f, 1f, Icons.Id.COPY, "Copy"),
        Cell(CUT, 3f, 2f, 1f, 1f, Icons.Id.CUT, "Cut"),
        Cell(PASTE, 3f, 3f, 1f, 1f, Icons.Id.PASTE, "Paste"),
    )

    override fun open() { super.open(); selecting = false }

    private fun rect(cell: Cell, out: RectF): RectF {
        val m = 6 * density
        // On wide screens the panel keeps a thumb-friendly width, centered.
        val w = min(width.toFloat(), 720 * density)
        val x0 = (width - w) / 2
        val gap = 8 * density
        val uw = (w - 2 * m - gap) / 4.4f
        val uh = (height - 2 * m) / 4
        val l = x0 + m + cell.col * uw + if (cell.col >= 3f) gap else 0f
        val wid = if (cell.col >= 3f) uw * 1.4f else cell.w * uw
        return out.apply { set(l + 3 * density, m + cell.row * uh + 3 * density, l + wid - 3 * density, m + (cell.row + cell.h) * uh - 3 * density) }
    }

    override fun hit(x: Float, y: Float): Int {
        for (c in cells) if (rect(c, tmp).contains(x, y)) return c.id
        return NONE
    }

    override fun repeats(id: Int) = id in setOf(UP, DOWN, LEFT, RIGHT, BKSP, FDEL)

    private val shift get() = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0

    override fun click(id: Int) {
        val l = listener ?: return
        when (id) {
            UP -> l.onPanelKey(KeyEvent.KEYCODE_DPAD_UP, shift)
            DOWN -> l.onPanelKey(KeyEvent.KEYCODE_DPAD_DOWN, shift)
            LEFT -> l.onPanelKey(KeyEvent.KEYCODE_DPAD_LEFT, shift)
            RIGHT -> l.onPanelKey(KeyEvent.KEYCODE_DPAD_RIGHT, shift)
            HOME -> l.onPanelKey(KeyEvent.KEYCODE_MOVE_HOME, shift)
            END -> l.onPanelKey(KeyEvent.KEYCODE_MOVE_END, shift)
            BKSP -> l.onPanelKey(KeyEvent.KEYCODE_DEL, 0)
            FDEL -> l.onPanelKey(KeyEvent.KEYCODE_FORWARD_DEL, 0)
            SELECT -> { selecting = !selecting; invalidate() }
            ALL -> { l.onPanelShortcut('a'); selecting = true; invalidate() }
            COPY -> { l.onPanelShortcut('c'); selecting = false; invalidate() }
            CUT -> { l.onPanelShortcut('x'); selecting = false; invalidate() }
            PASTE -> { l.onPanelShortcut('v'); selecting = false; invalidate() }
            ABC -> l.onPanelClose()
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(C.BG)
        for (cell in cells) {
            rect(cell, tmp)
            val letterTone = cell.id in setOf(UP, DOWN, LEFT, RIGHT, SELECT)
            button(c, tmp, cell.icon, cell.text, pressed == cell.id, function = !letterTone,
                on = cell.id == SELECT && selecting, pill = cell.id == ABC)
        }
    }

    private companion object {
        const val HOME = 1; const val UP = 2; const val END = 3; const val LEFT = 4; const val SELECT = 5
        const val RIGHT = 6; const val BKSP = 7; const val DOWN = 8; const val FDEL = 9; const val ABC = 10
        const val ALL = 11; const val COPY = 12; const val CUT = 13; const val PASTE = 14
    }
}
