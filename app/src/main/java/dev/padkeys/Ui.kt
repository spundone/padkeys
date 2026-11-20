package dev.padkeys

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Colors used by the Canvas-drawn keyboard and suggestion strip. [dev.padkeys.ui.syncViewColors] overwrites these
 * from the Material 3 color scheme (wallpaper colors on Android 12+, otherwise PadKeys' palette) each time the
 * keyboard shows, so it follows the system's light / dark mode.
 */
object C {
    var BG = 0xFF11131C.toInt()
    var SURFACE = 0xFF1D1F29.toInt()
    var SURFACE2 = 0xFF282934.toInt()
    var SURFACE3 = 0xFF32343F.toInt()
    var STROKE = 0xFF444654.toInt()
    var ACCENT = 0xFFB7C4FF.toInt()
    var ON_ACCENT = 0xFF002682.toInt()
    var ACCENT_DIM = 0xFF0038B6.toInt()
    var ON_ACCENT_DIM = 0xFFDCE1FF.toInt()
    var TEXT = 0xFFE1E1EF.toInt()
    var MUTED = 0xFFC4C5D6.toInt()
    var OK = 0xFF7DDC9A.toInt()
    /** Character keys: the brightest surface in light mode, the highest container in dark (Gboard-like M3E). */
    var KEY = 0xFF32343F.toInt()
    /** Function keys (Shift, Backspace, ?123, Tab…): the secondary container, a tonal step from the letters. */
    var KEY_FN = 0xFF3A456E.toInt()
    var ON_KEY_FN = 0xFFDCE1FF.toInt()
    /** The Enter key: the primary color, the one strong accent on the keyboard. */
    var ACTION = 0xFFB7C4FF.toInt()
    var ON_ACTION = 0xFF002682.toInt()
}

/** Blend two ARGB colors: [t] = 0 gives [a], 1 gives [b]. */
fun lerpColor(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t).toInt().coerceIn(0, 255) shl s
    return ch(24) or ch(16) or ch(8) or ch(0)
}

/**
 * The keyboard's typeface: Google Sans where the phone has it (Pixels and other Google-font phones), the system sans
 * otherwise. Taken from the system at run time, never bundled.
 */
object Fonts {
    private fun family(name: String, weight: Int): android.graphics.Typeface? {
        val t = android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL)
        if (t == android.graphics.Typeface.DEFAULT) return null // the family doesn't exist here
        return if (Build.VERSION.SDK_INT >= 28) android.graphics.Typeface.create(t, weight, false) else t
    }
    val regular: android.graphics.Typeface by lazy {
        family("google-sans-text", 400) ?: family("google-sans", 400) ?: android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    val medium: android.graphics.Typeface by lazy {
        family("google-sans-text", 500) ?: family("google-sans", 500) ?: android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density

/**
 * Key-press haptics, ported from PadBridge. Phone-class motors get Android's crisp preset effects and very light
 * primitives; basic motors (most tablets, budget phones), which can't play those, get plain short pulses instead.
 * The style can be forced in settings ([Prefs.hapticStyle]).
 */
object Haptics {
    private var vibrator: Vibrator? = null

    fun init(ctx: Context) {
        if (vibrator != null) return
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /** This device can vibrate at all (many tablets have no motor). */
    val available get() = vibrator?.hasVibrator() == true

    /** The motor understands Android's preset effects (tick, click) and fine amplitude: a phone-class motor. */
    val rich: Boolean by lazy {
        val v = vibrator ?: return@lazy false
        if (Build.VERSION.SDK_INT >= 30) {
            v.areAllEffectsSupported(VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_CLICK) ==
                Vibrator.VIBRATION_EFFECT_SUPPORT_YES
        } else v.hasAmplitudeControl()
    }

    private val useRich get() = when (Prefs.hapticStyle) { 1 -> true; 2 -> false; else -> rich }

    private var lastBasic = 0L

    /** Basic motor: a plain pulse of [ms]. Light ones are spaced out, so a texture is a gentle patter, not a buzz. */
    private fun basic(ms: Long, light: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (light && now - lastBasic < 45) return
        lastBasic = now
        try { vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
    }

    private fun play(predefined: Int, fallbackMs: Long, amp: Int, basicMs: Long) {
        if (!Prefs.haptics) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (!useRich) { basic(basicMs, false); return }
        try {
            if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(predefined))
            else v.vibrate(VibrationEffect.createOneShot(fallbackMs, amp))
        } catch (_: Exception) {}
    }

    /**
     * A very light primitive (Android 11+ on motors that support it), scaled by [scale] 0..1; falls back to a tick.
     * For textures: space-bar cursor steps, flick switches.
     */
    fun soft(scale: Float = 0.4f) {
        if (!Prefs.haptics) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (!useRich) { basic(if (scale >= 0.4f) 12 else 9, true); return }
        try {
            val low = Build.VERSION.SDK_INT >= 31 &&
                v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
            val tick = Build.VERSION.SDK_INT >= 30 &&
                v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
            val sc = scale.coerceIn(0f, 1f)
            if (low) {
                v.vibrate(VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, sc).compose())
            } else if (tick) {
                v.vibrate(VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, sc).compose())
            } else if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            else v.vibrate(VibrationEffect.createOneShot(5, (255 * scale).toInt().coerceIn(1, 255)))
        } catch (_: Exception) {}
    }

    // The effect ids are compile-time constants; play() only uses them on Android 10+.
    @SuppressLint("InlinedApi")
    fun tick() = play(VibrationEffect.EFFECT_TICK, 8, 60, 14)
    @SuppressLint("InlinedApi")
    fun click() = play(VibrationEffect.EFFECT_CLICK, 14, 140, 22)
}
