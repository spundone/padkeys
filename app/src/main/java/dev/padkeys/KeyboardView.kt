package dev.padkeys

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** On-screen keyboard layouts (a subset of PadBridge's: the ones that make sense for typing into this phone). */
enum class KbLayout(val label: String, val blurb: String) {
    PHONE("Phone", "Ten big columns with a number row. ?123 for symbols; Esc, Tab, Ctrl, Alt and arrows on top"),
    LAPTOP("Laptop", "Five rows like a laptop. Fn + number row = F1–F12, Fn + Esc = `"),
    COMPACT_65("65%", "Laptop rows plus a Del, PgUp, PgDn, End column. Fn + number = F-key"),
    FULL("Full", "Separate F-row, numbers and arrows"),
    SPLIT("Split", "65% halves at each edge, for two thumbs (landscape, tablets)");

    companion object {
        fun from(name: String?, def: KbLayout) = entries.firstOrNull { it.name == name } ?: def
    }
}

/**
 * The keyboard, adapted from PadBridge's on-screen keyboard. It keeps that keyboard's feel (flick up for the shifted
 * character, drag the space bar to move the cursor, one-shot / locked modifiers, preview bubbles, multi-touch
 * rollover) but instead of HID reports it reports what was typed to a [Listener] (the input method service), which
 * turns it into text and key events for the focused app.
 *
 * Modifiers: tap = one-shot, tap again = lock, hold while pressing another key = chord. Keys sit on a unit grid, so
 * layouts can mix key sizes and leave gaps (split).
 */
class KeyboardView(ctx: Context) : View(ctx) {

    /** What the keys do, implemented by the input method service. */
    interface Listener {
        /** A character key: [text] is already shifted / flicked; [meta] holds Ctrl / Alt / Shift for shortcuts. */
        fun onText(text: String, meta: Int)
        /** A non-character key ([KeyEvent] key code: Backspace, Enter, arrows, Tab, Esc, F-keys…). */
        fun onKey(code: Int, meta: Int)
        /** One step of the space-bar cursor ([KeyEvent.KEYCODE_DPAD_LEFT] / RIGHT). */
        fun onCursor(code: Int, meta: Int)
    }

    var listener: Listener? = null

    /**
     * A key. [code] is an Android key code for keys that aren't characters (or one of the page codes); text keys
     * have code 0 and type [label] (or [shiftLabel] when shifted). With Fn held, a key sends [fnCode] if set,
     * otherwise types [fnLabel].
     */
    class Key(
        val label: String,
        val code: Int = 0,
        val w: Float = 1f,
        val shiftLabel: String? = null,
        /** Modifier keys: their [KeyEvent] META_* bits. */
        val mod: Int = 0,
        val fn: Boolean = false,
        val fnCode: Int = 0,
        val fnLabel: String? = null,
        val small: Boolean = false,
        val h: Float = 1f,
        /** Held down, it repeats (Backspace, Delete, arrows). */
        val repeat: Boolean = false,
    ) {
        val rect = RectF()
        var gx = 0f
        var gy = 0f
        val isModifier get() = mod != 0 || fn
        val isText get() = code == 0 && !isModifier
        val isLetter get() = label.length == 1 && label[0].isLetter()
    }

    private enum class ModState { OFF, ONESHOT, LOCKED }

    private val density = resources.displayMetrics.density
    private var keys: List<Key> = emptyList()
    private var gridW = 10f
    private var gridH = 5f
    private var layout = KbLayout.PHONE
    /** The phone layout's second page (digits and every ASCII symbol). */
    private var symbols = false

    private val pointerKeys = HashMap<Int, Key>()       // finger -> key it's holding down (typed on press)
    private val modState = HashMap<Key, ModState>()
    private val modHeld = HashMap<Key, Boolean>()        // finger currently on the modifier
    private val modUsed = HashSet<Key>()                 // another key was pressed while holding it
    /** Caps Lock (the Caps key on laptop layouts). Shift locked by a double tap works the same way. */
    private var capsLock = false

    /**
     * Shift for the next letter because the field wants a capital here (start of a sentence), set by the service
     * from the editor's caps mode. Cleared by typing; the service sets it again when the cursor lands somewhere that
     * needs one.
     */
    var autoShift = false
        set(v) { if (field != v) { field = v; redraw() } }

    /**
     * A character key (with [flick]) or the space bar (with [spaceCursor]) that hasn't typed yet: it types when
     * lifted, shifted if flicked up; held still it types the shifted character (like a long press); dragged, the
     * space bar moves the cursor instead.
     */
    private inner class Pending(val k: Key, val x0: Float, val y0: Float) {
        var flicked = false
        var cursor = false
        var lastX = x0
        val hold = Runnable { holdTimeout(this) }
    }
    private val pending = HashMap<Int, Pending>()
    /** Keys whose shifted character was typed by holding them: shown pressed until lifted, then do nothing. */
    private val heldDone = HashMap<Int, Key>()
    private var unitW = 1f
    private var unitH = 1f

    private val deckRect = RectF()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val bubble = RectF()

    /** Show an enlarged label above the pressed key (fingers hide small keys). Drawn by the parent, see [drawBubbles]. */
    var keyPreview = true
    /** Flick up on a key to type its shifted character. Keys with a flick type when lifted (or when held). */
    var flick = true
    /** Drag on the space bar to move the cursor. */
    var spaceCursor = true
    /** Height multiplier from settings. */
    var heightScale = 1f
        set(v) { if (field != v) { field = v; requestLayout() } }
    /** Enter's label: the field's action ("Go", "Send"…) or ↵. */
    var enterLabel = "↵"
        set(v) { if (field != v) { field = v; redraw() } }

    init { isHapticFeedbackEnabled = false }

    fun configure(l: KbLayout) {
        if (l == layout && keys.isNotEmpty()) return
        releaseEverything()
        layout = l
        symbols = false
        build(); layoutKeys(); requestLayout(); redraw()
    }

    /** Start on the symbols page (number fields) or the letters. Only the Phone layout has pages. */
    fun showSymbols(on: Boolean) {
        val s = on && layout == KbLayout.PHONE
        if (s == symbols && keys.isNotEmpty()) return
        releaseEverything()
        symbols = s
        build(); layoutKeys(); redraw()
    }

    // ------------------------------------------------------------------ layouts

    private class Grid {
        val keys = ArrayList<Key>()
        var w = 0f
        var h = 0f
        fun row(y: Float, x0: Float, list: List<Key>): Grid {
            var x = x0
            for (k in list) { put(k, x, y); x += k.w }
            return this
        }
        fun rowEndingAt(y: Float, xEnd: Float, list: List<Key>) = row(y, xEnd - list.sumOf { it.w.toDouble() }.toFloat(), list)
        fun put(k: Key, x: Float, y: Float) {
            k.gx = x; k.gy = y; keys += k
            w = max(w, x + k.w); h = max(h, y + k.h)
        }
    }

    private fun build() {
        val l = layout
        fun lt(c: Char, w: Float = 1f) = Key(c.toString(), 0, w, c.uppercase())
        fun s(lbl: String, shift: String?, w: Float = 1f, fnCode: Int = 0, fnLabel: String? = null) =
            Key(lbl, 0, w, shift, fnCode = fnCode, fnLabel = fnLabel)
        fun sm(lbl: String, code: Int, w: Float = 1f, fnCode: Int = 0, fnLabel: String? = null, h: Float = 1f, repeat: Boolean = false) =
            Key(lbl, code, w, small = true, fnCode = fnCode, fnLabel = fnLabel, h = h, repeat = repeat)
        val digits = "1234567890"
        val shifted = "!@#$%^&*()"
        fun fKey(n: Int) = KeyEvent.KEYCODE_F1 + n - 1
        fun digit(i: Int, fnKeys: Boolean = true) = s(digits[i].toString(), shifted[i].toString(),
            fnCode = if (fnKeys) fKey(i + 1) else 0, fnLabel = if (fnKeys) "F${i + 1}" else null)
        fun minus() = s("-", "_", fnCode = fKey(11), fnLabel = "F11")
        fun equal() = s("=", "+", fnCode = fKey(12), fnLabel = "F12")
        fun bksp(w: Float = 2f) = Key("⌫", KeyEvent.KEYCODE_DEL, w, fnCode = KeyEvent.KEYCODE_FORWARD_DEL, fnLabel = "Del", repeat = true)
        fun tab(w: Float = 1.5f) = sm("Tab", KeyEvent.KEYCODE_TAB, w)
        fun caps(w: Float = 1.75f) = sm("Caps", KeyEvent.KEYCODE_CAPS_LOCK, w)
        fun enter(w: Float = 2.25f) = sm(ENTER, KeyEvent.KEYCODE_ENTER, w)
        fun lshift(w: Float = 2.25f) = Key("⇧", mod = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON, w = w)
        fun rshift(w: Float = 2.75f) = Key("⇧", mod = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON, w = w)
        fun esc(w: Float = 1f, grave: Boolean = false) =
            if (grave) Key("Esc", KeyEvent.KEYCODE_ESCAPE, w, small = true, fnLabel = "`")
            else sm("Esc", KeyEvent.KEYCODE_ESCAPE, w)
        fun arrow(lbl: String, code: Int, fnCode: Int, fnLabel: String, w: Float = 1f, h: Float = 1f) =
            Key(lbl, code, w, fnCode = fnCode, fnLabel = fnLabel, h = h, repeat = true)
        fun left(w: Float = 1f, h: Float = 1f) = arrow("←", KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MOVE_HOME, "Home", w, h)
        fun up(w: Float = 1f, h: Float = 1f) = arrow("↑", KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP, "PgUp", w, h)
        fun down(w: Float = 1f, h: Float = 1f) = arrow("↓", KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, "PgDn", w, h)
        fun right(w: Float = 1f, h: Float = 1f) = arrow("→", KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MOVE_END, "End", w, h)
        fun space(w: Float) = Key(" ", KeyEvent.KEYCODE_SPACE, w)
        fun ctrl(right: Boolean = false, w: Float = 1.25f, h: Float = 1f) = Key("Ctrl", w = w, small = true, h = h,
            mod = KeyEvent.META_CTRL_ON or if (right) KeyEvent.META_CTRL_RIGHT_ON else KeyEvent.META_CTRL_LEFT_ON)
        fun alt(right: Boolean = false, w: Float = 1.25f, h: Float = 1f) = Key("Alt", w = w, small = true, h = h,
            mod = KeyEvent.META_ALT_ON or if (right) KeyEvent.META_ALT_RIGHT_ON else KeyEvent.META_ALT_LEFT_ON)
        fun fnKey(w: Float = 1f) = Key("Fn", fn = true, w = w, small = true)
        fun del(w: Float = 1f) = sm("Del", KeyEvent.KEYCODE_FORWARD_DEL, w, repeat = true)
        fun letters(str: String) = str.map { lt(it) }

        val g = Grid()
        when (l) {
            KbLayout.PHONE -> {
                // Ten columns like a phone keyboard, so letters are big enough to hit. Esc, Tab, Ctrl, Alt and the
                // cursor keys sit in a slim top row (shortcuts and terminals); flick up on a digit for its symbol,
                // ?123 for a page of every other symbol.
                val top = 0.7f
                fun t(lbl: String, code: Int) = Key(lbl, code, small = true, h = top)
                g.row(0f, 0f, listOf(
                    t("Esc", KeyEvent.KEYCODE_ESCAPE), t("Tab", KeyEvent.KEYCODE_TAB), ctrl(w = 1f, h = top), alt(w = 1f, h = top),
                    t("Home", KeyEvent.KEYCODE_MOVE_HOME), left(h = top), up(h = top), down(h = top), right(h = top),
                    t("End", KeyEvent.KEYCODE_MOVE_END),
                ))
                val y = top
                if (!symbols) {
                    g.row(y, 0f, (0..9).map { digit(it, fnKeys = false) })
                    g.row(y + 1, 0f, letters("qwertyuiop"))
                    g.row(y + 2, 0.5f, letters("asdfghjkl"))
                    g.row(y + 3, 0f, listOf(lshift(1.5f)) + letters("zxcvbnm") + bksp(1.5f))
                    g.row(y + 4, 0f, listOf(sm("?123", CODE_SYMBOLS, 1.5f), s(",", "!"), s("'", "\""), space(4f),
                        s(".", "?"), enter(1.5f)))
                } else {
                    fun row(str: String) = str.map { s(it.toString(), null) }
                    g.row(y, 0f, row("1234567890"))
                    g.row(y + 1, 0f, row("!@#$%^&*()"))
                    g.row(y + 2, 0f, row("`~-_=+[]{}"))
                    g.row(y + 3, 0f, row("\\|;:\"<>/") + bksp(2f))
                    g.row(y + 4, 0f, listOf(sm("ABC", CODE_LETTERS, 1.5f), s(",", null), s("?", null), space(4f),
                        s(".", null), enter(1.5f)))
                }
            }
            KbLayout.LAPTOP -> {
                // Like Windows' "traditional" touch keyboard: no separate F-row, so five taller rows.
                g.row(0f, 0f, listOf(esc(grave = true)) + (0..9).map { digit(it) } + minus() + equal() + bksp())
                g.row(1f, 0f, listOf(tab()) + letters("qwertyuiop") + s("[", "{") + s("]", "}") + s("\\", "|", 1.5f))
                g.row(2f, 0f, listOf(caps()) + letters("asdfghjkl") + s(";", ":") + s("'", "\"") + enter())
                g.row(3f, 0f, listOf(lshift()) + letters("zxcvbnm") + s(",", "<") + s(".", ">") + s("/", "?") + rshift())
                g.row(4f, 0f, listOf(ctrl(), fnKey(1.25f), alt(), space(5.25f), alt(true, 1f), ctrl(true, 1f),
                    left(), up(), down(), right()))
            }
            KbLayout.FULL -> {
                g.row(0f, 0f, listOf(esc()) + (1..12).map { sm("F$it", fKey(it)) } + del(2f))
                g.row(1f, 0f, listOf(s("`", "~")) + (0..9).map { digit(it) } + minus() + equal() + bksp())
                g.row(2f, 0f, listOf(tab()) + letters("qwertyuiop") + s("[", "{") + s("]", "}") + s("\\", "|", 1.5f))
                g.row(3f, 0f, listOf(caps()) + letters("asdfghjkl") + s(";", ":") + s("'", "\"") + enter())
                g.row(4f, 0f, listOf(lshift()) + letters("zxcvbnm") + s(",", "<") + s(".", ">") + s("/", "?") + rshift())
                g.row(5f, 0f, listOf(ctrl(), fnKey(1.25f), alt(), space(5.25f), alt(true, 1f), ctrl(true, 1f),
                    left(), up(), down(), right()))
            }
            KbLayout.COMPACT_65, KbLayout.SPLIT -> {
                // Rows of a 65% board, each as (left half, right half) so Split can pull them apart.
                val rows = listOf(
                    listOf(esc(grave = true)) + (0..5).map { digit(it) } to
                        (6..9).map { digit(it) } + minus() + equal() + bksp() + del(),
                    listOf(tab()) + letters("qwert") to
                        letters("yuiop") + s("[", "{") + s("]", "}") + s("\\", "|", 1.5f) + sm("PgUp", KeyEvent.KEYCODE_PAGE_UP),
                    listOf(caps()) + letters("asdfg") to
                        letters("hjkl") + s(";", ":") + s("'", "\"") + enter() + sm("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
                    listOf(lshift()) + letters("zxcvb") to
                        letters("nm") + s(",", "<") + s(".", ">") + s("/", "?") + rshift(1.75f) + up() + sm("End", KeyEvent.KEYCODE_MOVE_END),
                    listOf(ctrl(), fnKey(1.25f), alt()) to listOf(alt(true, 1f), ctrl(true, 1f)) + left() + down() + right(),
                )
                if (l == KbLayout.COMPACT_65) {
                    rows.forEachIndexed { i, (a, b) ->
                        val bottom = i == rows.lastIndex
                        val leftW = a.sumOf { it.w.toDouble() }.toFloat()
                        val rightW = b.sumOf { it.w.toDouble() }.toFloat()
                        g.row(i.toFloat(), 0f, if (bottom) a + space(16f - leftW - rightW) + b else a + b)
                    }
                } else {
                    val leftMax = 7.25f
                    val rightMax = 9.5f
                    val total = leftMax + SPLIT_GAP + rightMax
                    rows.forEachIndexed { i, (a, b) ->
                        val bottom = i == rows.lastIndex
                        val aa = if (bottom) a + space(leftMax - a.sumOf { it.w.toDouble() }.toFloat()) else a
                        val bb = if (bottom) listOf(space(rightMax - b.sumOf { it.w.toDouble() }.toFloat())) + b else b
                        g.row(i.toFloat(), 0f, aa)
                        g.rowEndingAt(i.toFloat(), total, bb)
                    }
                }
            }
        }
        keys = g.keys
        gridW = g.w
        gridH = g.h
    }

    // ------------------------------------------------------------------ size

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val screenH = resources.displayMetrics.heightPixels
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Rows are a comfortable thumb height; the whole keyboard never takes more than about half the screen,
        // so the app keeps room for the text being typed.
        val rowDp = if (layout == KbLayout.PHONE) 54f else 48f
        val want = gridH * rowDp * density * heightScale
        val cap = screenH * min(0.65f, (if (landscape) 0.5f else 0.48f) * heightScale)
        setMeasuredDimension(w, min(want, max(cap, 120 * density)).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    private fun layoutKeys() {
        if (width == 0 || keys.isEmpty()) return
        val gap = 4 * density
        unitW = (width - gap) / gridW
        unitH = (height - gap) / gridH
        for (k in keys) {
            k.rect.set(gap + k.gx * unitW, gap + k.gy * unitH, (k.gx + k.w) * unitW, (k.gy + k.h) * unitH)
        }
        val ref = minOf(unitH, unitW * 1.1f)
        labelPaint.textSize = (ref * 0.42f).coerceAtMost(24 * density)
        subPaint.textSize = (ref * 0.29f).coerceAtMost(15 * density)
        bubbleText.textSize = (ref * 0.62f).coerceAtMost(34 * density)
    }

    // ------------------------------------------------------------------ state

    private val fnActive get() = modState.entries.any { it.key.fn && it.value != ModState.OFF } ||
        modHeld.entries.any { it.key.fn && it.value }

    private fun activeMods(): Int {
        var m = 0
        for (k in keys) if (k.mod != 0) {
            if ((modState[k] ?: ModState.OFF) != ModState.OFF || modHeld[k] == true) m = m or k.mod
        }
        return m
    }

    private fun shiftLocked() = modState.any { it.key.mod and KeyEvent.META_SHIFT_ON != 0 && it.value == ModState.LOCKED }

    private fun keyAt(x: Float, y: Float): Key? {
        for (k in keys) if (k.rect.contains(x, y)) return k
        // Nearest key within half a key, so gaps never swallow a touch but empty areas (split gap) do.
        var best: Key? = null; var bestD = Float.MAX_VALUE
        for (k in keys) {
            val dx = max(0f, max(k.rect.left - x, x - k.rect.right))
            val dy = max(0f, max(k.rect.top - y, y - k.rect.bottom))
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = k }
        }
        val limit = 12 * density
        return if (bestD <= limit * limit) best else null
    }

    /** The character a text key types now. */
    private fun textFor(k: Key, flicked: Boolean): String {
        if (fnActive && k.fnLabel != null) return k.fnLabel
        val shift = flicked || activeMods() and KeyEvent.META_SHIFT_ON != 0 || (autoShift && k.isLetter)
        return if (k.isLetter) {
            if (shift xor capsLock) k.label.uppercase() else k.label
        } else if (shift && k.shiftLabel != null) k.shiftLabel else k.label
    }

    /** Sends what [k] does with the current modifiers (and Shift, when [flicked]); one-shot modifiers are used up. */
    private fun emit(k: Key, flicked: Boolean) {
        modHeld.filterValues { it }.keys.forEach { modUsed.add(it) }
        val l = listener
        val fn = fnActive
        // Shift as a modifier for shortcuts and selection; the typed character carries its own case.
        val meta = activeMods() or (if (flicked) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)
        when {
            k.code == CODE_SYMBOLS -> { showSymbols(true); return }
            k.code == CODE_LETTERS -> { showSymbols(false); return }
            k.code == KeyEvent.KEYCODE_CAPS_LOCK -> { capsLock = !capsLock; Haptics.click() }
            fn && k.fnCode != 0 -> l?.onKey(k.fnCode, meta)
            fn && k.fnLabel != null && !k.isText -> l?.onText(k.fnLabel, meta) // Fn + Esc = `
            k.code == KeyEvent.KEYCODE_SPACE -> l?.onText(" ", meta)
            k.code != 0 -> l?.onKey(k.code, meta)
            else -> {
                l?.onText(textFor(k, flicked), meta)
                autoShift = false
            }
        }
        // A held repeating key (Shift + arrow) keeps its one-shot modifiers until it's lifted.
        if (!k.repeat) usedOneShots()
        redraw()
    }

    private fun usedOneShots() {
        var changed = false
        for (e in modState.entries) if (e.value == ModState.ONESHOT) { e.setValue(ModState.OFF); changed = true }
        if (changed) redraw()
    }

    private fun pressModifier(k: Key) {
        modHeld[k] = true
        modUsed.remove(k)
    }

    private fun releaseModifier(k: Key) {
        modHeld[k] = false
        val prev = modState[k] ?: ModState.OFF
        modState[k] = if (modUsed.remove(k)) ModState.OFF else when (prev) {
            ModState.OFF -> ModState.ONESHOT
            ModState.ONESHOT -> ModState.LOCKED
            ModState.LOCKED -> ModState.OFF
        }
        // Shift and its twin (left / right) act as one key for locking, like Caps Lock.
        if (k.mod and KeyEvent.META_SHIFT_ON != 0) {
            for (o in keys) if (o !== k && o.mod and KeyEvent.META_SHIFT_ON != 0) modState[o] = ModState.OFF
        }
        // Locking a modifier (second tap) gets a firmer click, like a caps lock clunk.
        if (modState[k] == ModState.LOCKED) Haptics.click()
    }

    fun releaseEverything() {
        pending.values.forEach { removeCallbacks(it.hold) }; pending.clear()
        removeCallbacks(repeater); repeatKey = null
        pointerKeys.clear(); heldDone.clear(); modState.clear(); modHeld.clear(); modUsed.clear()
        redraw()
    }

    // ------------------------------------------------------------------ key repeat

    private var repeatKey: Key? = null
    private val repeater = object : Runnable {
        override fun run() {
            val k = repeatKey ?: return
            if (k !in pointerKeys.values) { repeatKey = null; return }
            emit(k, false)
            postDelayed(this, REPEAT_MS)
        }
    }

    // ------------------------------------------------------------------ touch

    /** Flick and the space-bar cursor: keys that wait for the finger to lift before typing. */
    private fun defers(k: Key) = !k.isModifier &&
        ((k.code == KeyEvent.KEYCODE_SPACE && spaceCursor) || (flick && k.isText && k.shiftLabel != null))

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val pid = e.getPointerId(i)
                val k = keyAt(e.getX(i), e.getY(i)) ?: return true
                // A new key types whatever is still waiting first, so fast rollover keeps its order.
                if (!k.isModifier) flushPending()
                Haptics.tick()
                when {
                    k.isModifier -> { pointerKeys[pid] = k; pressModifier(k) }
                    defers(k) -> {
                        val p = Pending(k, e.getX(i), e.getY(i))
                        pending[pid] = p
                        if (k.code != KeyEvent.KEYCODE_SPACE) postDelayed(p.hold, HOLD_MS)
                    }
                    else -> {
                        pointerKeys[pid] = k
                        emit(k, false)
                        if (k.repeat) { removeCallbacks(repeater); repeatKey = k; postDelayed(repeater, REPEAT_DELAY_MS) }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val p = pending[e.getPointerId(i)] ?: continue
                movePending(p, e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = e.getPointerId(e.actionIndex)
                heldDone.remove(pid)
                pending.remove(pid)?.let { commit(it) }
                pointerKeys.remove(pid)?.let { k ->
                    if (k.isModifier) releaseModifier(k)
                    else { if (repeatKey === k) { removeCallbacks(repeater); repeatKey = null }; usedOneShots() }
                }
            }
            MotionEvent.ACTION_CANCEL -> releaseEverything()
        }
        redraw()
        return true
    }

    private fun movePending(p: Pending, x: Float, y: Float) {
        if (p.k.code == KeyEvent.KEYCODE_SPACE) {
            // Space bar cursor: once the finger has slid, every step moves the cursor one character.
            val step = max(12 * density, unitW * 0.45f)
            if (!p.cursor && abs(x - p.x0) > step * 0.8f) { p.cursor = true; p.lastX = p.x0 }
            if (!p.cursor) return
            while (x - p.lastX >= step) { p.lastX += step; cursor(KeyEvent.KEYCODE_DPAD_RIGHT) }
            while (p.lastX - x >= step) { p.lastX -= step; cursor(KeyEvent.KEYCODE_DPAD_LEFT) }
        } else {
            val up = p.y0 - y
            val was = p.flicked
            p.flicked = up > max(16 * density, unitH * 0.4f) && up > abs(x - p.x0)
            if (p.flicked != was) Haptics.soft(0.6f) // felt as it switches to (or back from) the shifted character
        }
    }

    private fun cursor(code: Int) {
        modHeld.filterValues { it }.keys.forEach { modUsed.add(it) }
        listener?.onCursor(code, activeMods())
        Haptics.soft(0.35f) // a light detent per character the cursor moves
    }

    /** Held still: types the shifted character now, like a long press. */
    private fun holdTimeout(p: Pending) {
        val pid = pending.entries.firstOrNull { it.value === p }?.key ?: return
        pending.remove(pid)
        heldDone[pid] = p.k
        Haptics.click()
        emit(p.k, true)
    }

    private fun commit(p: Pending) {
        removeCallbacks(p.hold)
        if (p.cursor) { usedOneShots(); return }
        emit(p.k, p.flicked)
    }

    private fun flushPending() {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val p = it.next().value
            if (p.cursor) continue
            it.remove(); commit(p)
        }
    }

    override fun onDetachedFromWindow() {
        releaseEverything()
        super.onDetachedFromWindow()
    }

    /** Redraw the keys and the parent, which draws the preview bubbles over the suggestion strip too. */
    private fun redraw() {
        invalidate()
        (parent as? View)?.invalidate()
    }

    companion object {
        const val HOLD_MS = 380L
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_MS = 50L
        const val CODE_SYMBOLS = -1
        const val CODE_LETTERS = -2
        private const val SPLIT_GAP = 2f
        /** Placeholder label for Enter; replaced by [enterLabel] when drawn. */
        private const val ENTER = "\u0000enter"
    }

    // ------------------------------------------------------------------ drawing

    private fun labelFor(k: Key, flicked: Boolean = false): String {
        if (k.label == ENTER) return enterLabel
        if (k.code == CODE_SYMBOLS || k.code == CODE_LETTERS) return k.label
        if (k.mod and KeyEvent.META_SHIFT_ON != 0 && (shiftLocked() || capsLock)) return "⇪"
        val fn = fnActive
        if (fn && k.fnLabel != null) return k.fnLabel
        if (k.isText) return textFor(k, flicked)
        return k.label
    }

    private fun isPressed(k: Key) = pointerKeys.containsValue(k) || modHeld[k] == true ||
        pending.values.any { it.k === k } || heldDone.containsValue(k)

    override fun onDraw(c: Canvas) {
        val r = 9 * density
        val shift = activeMods() and KeyEvent.META_SHIFT_ON != 0
        val fn = fnActive
        val real = Real.active
        // Realistic: the keys sit in an aluminum (or space black) deck.
        if (real) { deckRect.set(0f, 0f, width.toFloat(), height.toFloat()); Real.deck(c, deckRect, 14 * density, density) }
        for (k in keys) {
            val pressed = isPressed(k)
            val st = modState[k] ?: ModState.OFF
            val locked = st == ModState.LOCKED || (k.code == KeyEvent.KEYCODE_CAPS_LOCK && capsLock)
            val label = labelFor(k)
            val smallText = (k.small || label.length > 2) && label != "↵"
            val paint = if (smallText) subPaint else labelPaint
            val cy: Float
            if (real) {
                Real.key(c, k.rect, r * 0.75f, density, pressed, k.isModifier || k.small)
                paint.color = if (k.small) C.MUTED else C.TEXT
                cy = Real.capCenterY(k.rect, density, pressed)
                // Status lights: Caps Lock, and latched (locked / one-shot) modifiers.
                if (k.code == KeyEvent.KEYCODE_CAPS_LOCK) Real.led(c, k.rect.left + 8 * density, k.rect.top + 8 * density, density, capsLock)
                else if (st != ModState.OFF) Real.led(c, k.rect.left + 8 * density, k.rect.top + 8 * density, density, true, st == ModState.ONESHOT)
            } else {
                keyPaint.color = when {
                    pressed || locked -> C.ACCENT
                    st == ModState.ONESHOT -> C.ACCENT_DIM
                    k.isModifier || k.small || k.code == KeyEvent.KEYCODE_DEL -> C.SURFACE2
                    else -> C.SURFACE3
                }
                val kr = if (pressed) r * 2.2f else r
                c.drawRoundRect(k.rect, kr, kr, keyPaint)
                paint.color = when {
                    pressed || locked -> C.ON_ACCENT
                    st == ModState.ONESHOT -> C.ON_ACCENT_DIM
                    k.small -> C.MUTED
                    else -> C.TEXT
                }
                cy = k.rect.centerY()
                if (k.code == KeyEvent.KEYCODE_CAPS_LOCK) {
                    ledPaint.color = if (capsLock) C.OK else C.STROKE
                    c.drawCircle(k.rect.left + 8 * density, k.rect.top + 8 * density, 3 * density, ledPaint)
                }
            }
            c.drawText(label, k.rect.centerX(), cy - (paint.ascent() + paint.descent()) / 2, paint)

            // The flick character, small in the corner, on keys tall enough to show it.
            if (!k.isLetter && k.isText && k.shiftLabel != null && !shift && !fn && flick && k.rect.height() > 34 * density) {
                subPaint.color = C.MUTED
                val old = subPaint.textSize
                subPaint.textSize = old * 0.8f
                c.drawText(k.shiftLabel, k.rect.right - subPaint.textSize * 0.7f, k.rect.top + subPaint.textSize + 2 * density, subPaint)
                subPaint.textSize = old
            }
        }
    }

    /**
     * Preview bubbles for pressed (and waiting) character keys, drawn by the parent above the finger, offset by
     * ([dx], [dy]) (this view's position in the parent), so a bubble over the top row may cover the suggestion strip.
     */
    fun drawBubbles(c: Canvas, dx: Float, dy: Float) {
        if (!keyPreview) return
        val bubbles = pointerKeys.values.filter { !it.isModifier }.map { it to false } +
            pending.values.filter { !it.cursor }.map { it.k to it.flicked } + heldDone.values.map { it to true }
        for ((k, flicked) in bubbles) {
            if (k.small || !k.isText) continue
            val label = labelFor(k, flicked)
            val w = max(k.rect.width() * 1.3f, bubbleText.measureText(label) + 24 * density)
            val h = k.rect.height() * 1.15f
            val top = max(-dy, k.rect.top - h - 6 * density)
            val left = (k.rect.centerX() - w / 2).coerceIn(0f, width - w)
            bubble.set(left + dx, top + dy, left + w + dx, top + h + dy)
            bubblePaint.color = C.ACCENT_DIM
            c.drawRoundRect(bubble, h / 3, h / 3, bubblePaint)
            bubbleText.color = C.ON_ACCENT_DIM
            c.drawText(label, bubble.centerX(), bubble.centerY() - (bubbleText.ascent() + bubbleText.descent()) / 2, bubbleText)
        }
    }
}
