package dev.padkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.inputmethodservice.InputMethodService
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import kotlin.math.min

/**
 * The strip beside the keys in one-handed mode: a button to go back to full width and one to move the keys to the
 * other side (Gboard's one-handed mode).
 */
@SuppressLint("ViewConstructor")
class OneHandBar(ctx: Context, private val onExpand: () -> Unit, private val onSwap: () -> Unit) : View(ctx) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressed = 0 // 1 expand, 2 swap
    /** The keys are to the right of this bar: the buttons sit at that edge, next to them. */
    var keysRight = true
        set(v) { field = v; invalidate() }

    private val r get() = min(24 * density, width / 2f - 6 * density)
    private val cx get() = if (keysRight) width - r - 14 * density else r + 14 * density
    private fun cy(i: Int) = height / 2f + (if (i == 1) -1 else 1) * (r + 10 * density)

    private fun hit(y: Float) = if (y < height / 2f) 1 else 2

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = hit(e.y); Haptics.tick(); invalidate() }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = 0; invalidate()
                if (p == hit(e.y)) if (p == 1) onExpand() else onSwap()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = 0; invalidate() }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(C.BG)
        for (i in 1..2) {
            val down = pressed == i
            fill.color = if (down) C.ACCENT_DIM else C.KEY_FN
            c.drawCircle(cx, cy(i), r, fill)
            Icons.draw(c, if (i == 1) Icons.Id.EXPAND else Icons.Id.SWAP, cx, cy(i), r * 0.9f,
                if (down) C.ON_ACCENT_DIM else C.ON_KEY_FN)
        }
    }
}

/**
 * Voice typing: hands over to the phone's voice input keyboard (an input method with a "voice" subtype, such as
 * Google's voice typing), which comes back to PadKeys when it's done. PadKeys itself never records audio.
 */
object Voice {
    /** The enabled voice input method and its voice subtype, or null if the phone has none turned on. */
    fun find(ctx: Context): Pair<String, InputMethodSubtype>? {
        val imm = ctx.getSystemService(InputMethodManager::class.java) ?: return null
        for (imi in imm.enabledInputMethodList) {
            if (imi.packageName == ctx.packageName) continue
            val subtypes = imm.getEnabledInputMethodSubtypeList(imi, true)
            subtypes.firstOrNull { it.mode == "voice" }?.let { return imi.id to it }
        }
        return null
    }

    /** Switch to the voice keyboard; false if there is none. */
    fun start(ims: InputMethodService): Boolean {
        val (id, subtype) = find(ims) ?: return false
        ims.switchInputMethod(id, subtype)
        return true
    }
}
