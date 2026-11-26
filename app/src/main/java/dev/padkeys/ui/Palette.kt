package dev.padkeys.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/*
 * Material-style tonal palettes from one seed color, without a library: tones are CIELAB lightness (which is
 * what Material's HCT "tone" is), hue and chroma come from the seed in LCh. Close enough to Material You that
 * the roles keep their contrast; chroma is reduced as needed to stay inside sRGB.
 */
private object Lab {
    private fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun gam(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
    private const val XN = 0.95047
    private const val ZN = 1.08883
    private fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
    private fun fi(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) / (24389.0 / 27)

    /** (L, C, h in radians) of an ARGB color. */
    fun lch(argb: Int): DoubleArray {
        val r = lin(((argb shr 16) and 0xFF) / 255.0)
        val g = lin(((argb shr 8) and 0xFF) / 255.0)
        val b = lin((argb and 0xFF) / 255.0)
        val x = 0.4124 * r + 0.3576 * g + 0.1805 * b
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = 0.0193 * r + 0.1192 * g + 0.9505 * b
        val fx = f(x / XN); val fy = f(y); val fz = f(z / ZN)
        val l = 116 * fy - 16; val a = 500 * (fx - fy); val bb = 200 * (fy - fz)
        return doubleArrayOf(l, hypot(a, bb), atan2(bb, a))
    }

    /** Linear RGB of an LCh color, or null when it's outside sRGB. */
    private fun rgb(l: Double, c: Double, h: Double): DoubleArray? {
        val fy = (l + 16) / 116
        val fx = fy + c * cos(h) / 500
        val fz = fy - c * sin(h) / 200
        val x = XN * fi(fx); val y = fi(fy); val z = ZN * fi(fz)
        val r = 3.2406 * x - 1.5372 * y - 0.4986 * z
        val g = -0.9689 * x + 1.8758 * y + 0.0415 * z
        val b = 0.0557 * x - 0.2040 * y + 1.0570 * z
        val e = 1e-4
        return if (r < -e || g < -e || b < -e || r > 1 + e || g > 1 + e || b > 1 + e) null else doubleArrayOf(r, g, b)
    }

    /** The color at [tone] (L*) with [hue] and as much of [chroma] as fits in sRGB. */
    fun color(hue: Double, chroma: Double, tone: Double): Color {
        var lo = 0.0; var hi = chroma
        var best = rgb(tone, 0.0, hue) ?: doubleArrayOf(tone / 100, tone / 100, tone / 100)
        rgb(tone, chroma, hue)?.let { best = it; lo = hi }
        repeat(18) {
            if (hi - lo < 0.05) return@repeat
            val mid = (lo + hi) / 2
            val v = rgb(tone, mid, hue)
            if (v != null) { best = v; lo = mid } else hi = mid
        }
        fun ch(v: Double) = (gam(v.coerceIn(0.0, 1.0)) * 255 + 0.5).toInt().coerceIn(0, 255)
        return Color(ch(best[0]), ch(best[1]), ch(best[2]))
    }
}

private class Tonal(val hue: Double, val chroma: Double) {
    operator fun invoke(tone: Int) = Lab.color(hue, chroma, tone.toDouble())
}

/** A full Material color scheme grown from one [seed] color, like wallpaper colors but from your pick. */
fun seedScheme(seed: Int, dark: Boolean): ColorScheme {
    val (_, c, h) = Lab.lch(seed).let { Triple(it[0], it[1], it[2]) }
    val p = Tonal(h, c.coerceIn(30.0, 70.0))
    val s = Tonal(h, (c / 3).coerceIn(8.0, 20.0))
    val t = Tonal(h + Math.toRadians(60.0), (c / 2).coerceIn(16.0, 36.0))
    val n = Tonal(h, 3.0)
    val nv = Tonal(h, 7.0)
    return if (dark) darkColorScheme(
        primary = p(80), onPrimary = p(20), primaryContainer = p(30), onPrimaryContainer = p(90), inversePrimary = p(40),
        secondary = s(80), onSecondary = s(20), secondaryContainer = s(30), onSecondaryContainer = s(90),
        tertiary = t(80), onTertiary = t(20), tertiaryContainer = t(30), onTertiaryContainer = t(90),
        background = n(6), onBackground = n(90), surface = n(6), onSurface = n(90),
        surfaceVariant = nv(30), onSurfaceVariant = nv(80), outline = nv(60), outlineVariant = nv(30),
        surfaceContainerLowest = n(4), surfaceContainerLow = n(10), surfaceContainer = n(12),
        surfaceContainerHigh = n(17), surfaceContainerHighest = n(22),
        inverseSurface = n(90), inverseOnSurface = n(20), surfaceDim = n(6), surfaceBright = n(24),
        surfaceTint = p(80),
    ) else lightColorScheme(
        primary = p(40), onPrimary = p(100), primaryContainer = p(90), onPrimaryContainer = p(30), inversePrimary = p(80),
        secondary = s(40), onSecondary = s(100), secondaryContainer = s(90), onSecondaryContainer = s(30),
        tertiary = t(40), onTertiary = t(100), tertiaryContainer = t(90), onTertiaryContainer = t(30),
        background = n(98), onBackground = n(10), surface = n(98), onSurface = n(10),
        surfaceVariant = nv(90), onSurfaceVariant = nv(30), outline = nv(50), outlineVariant = nv(80),
        surfaceContainerLowest = n(100), surfaceContainerLow = n(96), surfaceContainer = n(94),
        surfaceContainerHigh = n(92), surfaceContainerHighest = n(90),
        inverseSurface = n(20), inverseOnSurface = n(95), surfaceDim = n(87), surfaceBright = n(98),
        surfaceTint = p(40),
    )
}

/**
 * Two-tone: the whole app in two colors, like a duotone print. [bg] fills backgrounds; [fg] is used for text,
 * keys' labels, accents and filled buttons. Containers and outlines are blends of the two.
 */
fun twoToneScheme(bg: Int, fg: Int): ColorScheme {
    val b = Color(bg); val f = Color(fg)
    fun mix(t: Float) = lerp(b, f, t)
    val base = if (b.luminance() < 0.5f) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = f, onPrimary = b, primaryContainer = mix(0.28f), onPrimaryContainer = f, inversePrimary = b,
        secondary = f, onSecondary = b, secondaryContainer = mix(0.2f), onSecondaryContainer = f,
        tertiary = f, onTertiary = b, tertiaryContainer = mix(0.2f), onTertiaryContainer = f,
        background = b, onBackground = f, surface = b, onSurface = f,
        surfaceVariant = mix(0.14f), onSurfaceVariant = mix(0.75f), outline = mix(0.5f), outlineVariant = mix(0.25f),
        surfaceContainerLowest = b, surfaceContainerLow = mix(0.05f), surfaceContainer = mix(0.08f),
        surfaceContainerHigh = mix(0.12f), surfaceContainerHighest = mix(0.16f),
        inverseSurface = f, inverseOnSurface = b, surfaceDim = b, surfaceBright = mix(0.1f), surfaceTint = f,
        error = f, onError = b, errorContainer = mix(0.28f), onErrorContainer = f,
    )
}

/** WCAG contrast ratio of two colors. */
fun contrast(a: Int, b: Int): Float {
    val la = Color(a).luminance(); val lb = Color(b).luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

val SwatchColors = listOf(
    0xFF3E64E5, 0xFF6750A4, 0xFFB5179E, 0xFFE53935, 0xFFFF7A50, 0xFFF9A825,
    0xFF7CB342, 0xFF00897B, 0xFF0097A7, 0xFF546E7A, 0xFF000000, 0xFFFFFFFF,
).map { it.toInt() }

/**
 * Pick a color: preset swatches, then hue, saturation and brightness sliders. [onChange] fires on every
 * move (for a live preview), [onDone] once a swatch is tapped or a slider is let go (to save and re-theme).
 */
@Composable
fun ColorPicker(color: Int, onChange: (Int) -> Unit, onDone: (Int) -> Unit, modifier: Modifier = Modifier) {
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
    var hue by remember(color) { mutableFloatStateOf(hsv[0]) }
    var sat by remember(color) { mutableFloatStateOf(hsv[1]) }
    var value by remember(color) { mutableFloatStateOf(hsv[2]) }
    fun current() = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (sw in SwatchColors) {
                val selected = sw == color
                Box(
                    Modifier.size(36.dp)
                        .border(if (selected) 3.dp else 1.dp, if (selected) onSurface else outline, CircleShape)
                        .padding(if (selected) 5.dp else 1.dp)
                        .background(Color(sw), CircleShape)
                        .clickable { onDone(sw) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(Color(current()), CircleShape).border(1.dp, outline, CircleShape))
            Text("#%06X".format(current() and 0xFFFFFF), style = MaterialTheme.typography.titleMedium)
        }
        Text("Hue", style = MaterialTheme.typography.labelLarge)
        Slider(value = hue, valueRange = 0f..360f, onValueChange = { hue = it; onChange(current()) },
            onValueChangeFinished = { onDone(current()) })
        Text("Saturation", style = MaterialTheme.typography.labelLarge)
        Slider(value = sat, onValueChange = { sat = it; onChange(current()) }, onValueChangeFinished = { onDone(current()) })
        Text("Brightness", style = MaterialTheme.typography.labelLarge)
        Slider(value = value, onValueChange = { value = it; onChange(current()) }, onValueChangeFinished = { onDone(current()) })
    }
}
