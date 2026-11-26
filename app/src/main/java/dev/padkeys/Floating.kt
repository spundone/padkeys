package dev.padkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Floating mode (Gboard's floating keyboard): the keyboard is a smaller window that can be dragged anywhere. The
 * input method window then covers the whole screen, but only the keyboard (and the navigation bar strip) takes
 * touches, and the app isn't resized; see [PadKeysService.onComputeInsets].
 *
 * This host holds the [root]: docked, it's just the root's size; floating, it fills the window and places the root
 * at [fx], [fy] (fractions of the room left around it) at [widthPx] wide.
 */
@SuppressLint("ViewConstructor")
class FloatHost(ctx: Context, val root: KeyboardRoot) : FrameLayout(ctx) {
    private val density = resources.displayMetrics.density
    /** Space kept between the floating keyboard and the screen's edges. */
    private val margin = (8 * resources.displayMetrics.density).roundToInt()
    var floating = false
        private set
    var fx = 0.5f
    var fy = 1f
    var widthPx = 0
    /** System bars to keep clear of when floating. */
    private var insetTop = 0
    private var insetBottom = 0
    /** The navigation bar's strip at the bottom of the window (still touchable, so its buttons work). */
    val navStrip get() = insetBottom

    init {
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        clipChildren = false
    }

    fun setFloating(on: Boolean) {
        if (floating == on) return
        floating = on
        root.floating(on)
        requestLayout()
    }

    /** The default floating width: most of a phone, about a laptop keyboard's size on a tablet. */
    fun defaultWidth(): Int {
        val w = resources.displayMetrics.widthPixels
        return min(w * 0.92f, 600 * density).roundToInt()
    }

    private fun clampWidth(w: Int, avail: Int) = w.coerceIn(min((280 * density).roundToInt(), avail), avail)

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 30) {
            insetTop = insets.getInsets(WindowInsets.Type.statusBars()).top
            insetBottom = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar()).bottom
        } else {
            @Suppress("DEPRECATION")
            insetTop = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            insetBottom = insets.systemWindowInsetBottom
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (!floating) {
            root.measure(widthMeasureSpec, heightMeasureSpec)
            setMeasuredDimension(w, root.measuredHeight)
            return
        }
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val rw = clampWidth(if (widthPx > 0) widthPx else defaultWidth(), w - 2 * margin)
        root.measure(MeasureSpec.makeMeasureSpec(rw, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(max(0, h - insetTop - insetBottom - 2 * margin), MeasureSpec.AT_MOST))
        setMeasuredDimension(w, if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) root.measuredHeight else h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rw = root.measuredWidth; val rh = root.measuredHeight
        if (!floating) { root.layout(0, 0, rw, rh); return }
        val roomX = max(0, width - 2 * margin - rw)
        val roomY = max(0, height - insetTop - insetBottom - 2 * margin - rh)
        val x = margin + (fx.coerceIn(0f, 1f) * roomX).roundToInt()
        val y = insetTop + margin + (fy.coerceIn(0f, 1f) * roomY).roundToInt()
        root.layout(x, y, x + rw, y + rh)
    }

    /** Drag by ([dx], [dy]) px. */
    fun moveBy(dx: Float, dy: Float) {
        val roomX = width - 2 * margin - root.width
        val roomY = height - insetTop - insetBottom - 2 * margin - root.height
        if (roomX > 0) fx = ((fx * roomX + dx) / roomX).coerceIn(0f, 1f)
        if (roomY > 0) fy = ((fy * roomY + dy) / roomY).coerceIn(0f, 1f)
        requestLayout()
    }

    /** Resize from the right edge by [dx] px (the left edge stays put). */
    fun resizeBy(dx: Float) {
        val left = root.left
        val nw = clampWidth((root.width + dx).roundToInt(), width - margin - left)
        if (nw == root.width) return
        widthPx = nw
        val room = width - 2 * margin - nw
        fx = if (room > 0) ((left - margin).toFloat() / room).coerceIn(0f, 1f) else 0f
        requestLayout()
    }

    /** Where the keyboard is, in this view's coordinates. */
    fun keyboardRect(out: Rect) = out.set(root.left, root.top, root.right, root.bottom)
}

/**
 * The floating keyboard's top edge: drag the middle to move it, the grip on the right to resize, and the button on
 * the left to dock it again.
 */
@SuppressLint("ViewConstructor")
class FloatHandle(
    ctx: Context,
    private val onMove: (Float, Float) -> Unit,
    private val onResize: (Float) -> Unit,
    private val onDone: () -> Unit,
    private val onDock: () -> Unit,
) : View(ctx) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = RectF()
    private var mode = 0 // 1 dock, 2 move, 3 resize
    private var lastX = 0f
    private var lastY = 0f
    private val side get() = 44 * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (26 * density).roundToInt())

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = when {
                    e.x < side -> 1
                    e.x > width - side -> 3
                    else -> 2
                }
                lastX = e.rawX; lastY = e.rawY
                Haptics.tick(); invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - lastX; val dy = e.rawY - lastY
                lastX = e.rawX; lastY = e.rawY
                when (mode) { 2 -> onMove(dx, dy); 3 -> onResize(dx) }
            }
            MotionEvent.ACTION_UP -> {
                val m = mode
                mode = 0; invalidate()
                if (m == 1 && e.x < side) onDock() else if (m != 1) onDone()
            }
            MotionEvent.ACTION_CANCEL -> { if (mode > 1) onDone(); mode = 0; invalidate() }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(C.BG)
        val cy = height / 2f
        val ink = { on: Boolean -> if (on) C.TEXT else C.MUTED }
        // Dock: back to the bottom of the screen.
        Icons.draw(c, Icons.Id.DOCK, side / 2, cy, 18 * density, ink(mode == 1))
        // The drag pill.
        paint.color = ink(mode == 2)
        val pw = min(48 * density, width / 4f)
        pill.set(width / 2f - pw / 2, cy - 2.5f * density, width / 2f + pw / 2, cy + 2.5f * density)
        c.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, paint)
        // Resize grip.
        Icons.draw(c, Icons.Id.RESIZE, width - side / 2, cy, 18 * density, ink(mode == 3))
    }
}

/** Rounded corners (and a shadow) for the floating keyboard. */
class RoundOutline(private val radius: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
}

/** Sets every ancestor of [v] below the window's decor to [height], remembering the originals in [saved]. */
fun stretchAncestors(v: View, fill: Boolean, saved: MutableMap<View, Int>) {
    var p = v.parent
    while (p is ViewGroup) {
        val lp = p.layoutParams ?: break
        if (lp is android.view.WindowManager.LayoutParams) break
        if (fill) {
            saved.getOrPut(p) { lp.height }
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            lp.height = saved[p] ?: lp.height
        }
        p.layoutParams = lp
        p = p.parent
    }
    if (!fill) saved.clear()
}
