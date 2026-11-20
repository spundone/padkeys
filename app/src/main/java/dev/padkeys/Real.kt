package dev.padkeys

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Optional "Realistic" look, ported from PadBridge: the keyboard is drawn as hardware instead of flat Material
 * surfaces. Light is silver (an aluminum deck with white key caps), dark is space black. The keyboard view calls
 * these instead of a flat fill when [active].
 */
object Real {
    @Volatile var on = false
    @Volatile var dark = false
    val active get() = on

    // ---------------------------------------------------------------- palette

    private val deckTop get() = if (dark) 0xFF2B2C30.toInt() else 0xFFDADBDF.toInt()
    private val deckBottom get() = if (dark) 0xFF1D1E21.toInt() else 0xFFC6C7CC.toInt()
    private val rim get() = if (dark) 0xFF4A4B51.toInt() else 0xFFB7B8BE.toInt()
    private val highlight get() = if (dark) 0x1FFFFFFF else 0xCCFFFFFF.toInt()
    private val dropShadow get() = if (dark) 0x99000000.toInt() else 0x33000000
    private val cap get() = if (dark) 0xFF2E2F33.toInt() else 0xFFFDFDFE.toInt()
    private val capMod get() = if (dark) 0xFF28292D.toInt() else 0xFFF3F3F6.toInt()
    private val capPressed get() = if (dark) 0xFF202124.toInt() else 0xFFE4E5E9.toInt()
    private val capShadow get() = if (dark) 0xFF0B0B0D.toInt() else 0xFFA3A5AB.toInt()
    private val capEdge get() = if (dark) 0xFF1A1B1E.toInt() else 0xFFE9EAEE.toInt()
    const val LED_ON = 0xFF34C759.toInt()

    /** Colors the views read from [C] (labels, suggestion strip), set after the theme syncs. */
    fun applyColors() {
        if (!on) return
        if (dark) {
            C.BG = 0xFF2B2C30.toInt()
            C.SURFACE = 0xFF26272B.toInt(); C.SURFACE2 = 0xFF2E2F33.toInt(); C.SURFACE3 = 0xFF3A3B40.toInt()
            C.STROKE = 0xFF4A4B51.toInt(); C.ACCENT = 0xFFAEAEB2.toInt(); C.ON_ACCENT = 0xFF000000.toInt()
            C.ACCENT_DIM = 0xFF3A3A3C.toInt(); C.ON_ACCENT_DIM = 0xFFF2F2F7.toInt()
            C.TEXT = 0xFFEDEDF0.toInt(); C.MUTED = 0xFFA1A1A6.toInt()
            C.KEY_FN = 0xFF3A3B40.toInt(); C.ON_KEY_FN = 0xFFEDEDF0.toInt()
        } else {
            C.BG = 0xFFDADBDF.toInt()
            C.SURFACE = 0xFFE6E7EA.toInt(); C.SURFACE2 = 0xFFF3F3F6.toInt(); C.SURFACE3 = 0xFFC9CACF.toInt()
            C.STROKE = 0xFFB7B8BE.toInt(); C.ACCENT = 0xFF6E6E73.toInt(); C.ON_ACCENT = 0xFFFFFFFF.toInt()
            C.ACCENT_DIM = 0xFFE5E5EA.toInt(); C.ON_ACCENT_DIM = 0xFF1C1C1E.toInt()
            C.TEXT = 0xFF1D1D1F.toInt(); C.MUTED = 0xFF6E6E73.toInt()
            C.KEY_FN = 0xFFFDFDFE.toInt(); C.ON_KEY_FN = 0xFF1D1D1F.toInt()
        }
    }

    // ---------------------------------------------------------------- drawing

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tmp = RectF()

    private var gradKey = ""
    private var grad: Shader? = null
    private fun vertical(top: Float, bottom: Float, a: Int, b: Int): Shader {
        val key = "$top|$bottom|$a|$b"
        if (key != gradKey || grad == null) {
            grad = LinearGradient(0f, top, 0f, bottom, a, b, Shader.TileMode.CLAMP); gradKey = key
        }
        return grad!!
    }

    /** A slab with a soft drop shadow, a top-to-bottom gradient, a hairline rim and a lit top edge. */
    private fun slab(c: Canvas, r: RectF, radius: Float, d: Float, top: Int, bottom: Int) {
        val lift = 1.5f * d
        tmp.set(r.left, r.top, r.right, r.bottom - lift)
        // Drop shadow just below the slab.
        fill.shader = null; fill.color = dropShadow
        c.drawRoundRect(tmp.left, tmp.top + lift, tmp.right, tmp.bottom + lift, radius, radius, fill)
        fill.color = 0xFF000000.toInt() // opaque: the shadow's alpha would otherwise fade the gradient
        fill.shader = vertical(tmp.top, tmp.bottom, top, bottom)
        c.drawRoundRect(tmp, radius, radius, fill)
        fill.shader = null
        stroke.strokeWidth = d; stroke.color = rim
        tmp.inset(d / 2, d / 2)
        c.drawRoundRect(tmp, radius, radius, stroke)
        // Light catching the top edge.
        stroke.color = highlight; stroke.strokeWidth = 1.2f * d
        c.save()
        c.clipRect(tmp.left, tmp.top, tmp.right, tmp.top + radius)
        tmp.inset(d, d)
        c.drawRoundRect(tmp, radius - d, radius - d, stroke)
        c.restore()
    }


    /** The keyboard's aluminum deck the keys sit in. */
    fun deck(c: Canvas, r: RectF, radius: Float, d: Float) = slab(c, r, radius, d, deckTop, deckBottom)

    /**
     * A key cap: a short shadow underneath (its side wall), a flat cap and a faint lower edge. Pressed, the cap
     * drops onto its shadow.
     */
    fun key(c: Canvas, r: RectF, radius: Float, d: Float, pressed: Boolean, modifier: Boolean) {
        val wall = 1.6f * d
        fill.shader = null
        if (!pressed) {
            fill.color = capShadow
            c.drawRoundRect(r.left, r.top + wall, r.right, r.bottom, radius, radius, fill)
        }
        val drop = if (pressed) wall * 0.8f else 0f
        tmp.set(r.left, r.top + drop, r.right, r.bottom - wall + drop)
        fill.color = capEdge
        c.drawRoundRect(tmp, radius, radius, fill)
        tmp.bottom -= 0.8f * d
        fill.color = if (pressed) capPressed else if (modifier) capMod else cap
        c.drawRoundRect(tmp, radius, radius, fill)
        if (dark && !pressed) {
            stroke.color = highlight; stroke.strokeWidth = d
            c.drawLine(tmp.left + radius, tmp.top + d / 2, tmp.right - radius, tmp.top + d / 2, stroke)
        }
    }

    /** The cap's own rect after [key] (where its label goes). */
    fun capCenterY(r: RectF, d: Float, pressed: Boolean): Float {
        val wall = 1.6f * d
        val drop = if (pressed) wall * 0.8f else 0f
        return (r.top + drop + r.bottom - wall + drop - 0.8f * d) / 2f
    }

    /** A small status light on a key (Caps Lock, latched modifiers), like the green one on a Mac keyboard. */
    fun led(c: Canvas, x: Float, y: Float, d: Float, lit: Boolean, dim: Boolean = false) {
        fill.shader = null
        fill.color = when {
            lit && dim -> 0x9934C759.toInt()
            lit -> LED_ON
            else -> if (dark) 0xFF1A1B1E.toInt() else 0xFFC9CACF.toInt()
        }
        c.drawCircle(x, y, 2.2f * d, fill)
    }
}
