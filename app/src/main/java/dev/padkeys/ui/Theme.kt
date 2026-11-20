package dev.padkeys.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import dev.padkeys.C
import dev.padkeys.Prefs
import dev.padkeys.Real

/*
 * PadKeys' palette (PadBridge's), used when wallpaper (dynamic) color is off or unavailable (Android 9-11).
 * Generated with Material Color Utilities: SchemeVibrant from brand blue #3E64E5 (saturated blue
 * primary, blue-tinted surfaces instead of flat grey), a secondary on the same blue hue at chroma 30
 * so chips and the nav indicator stay in the family, and a warm coral tertiary (#FF7A50 hue) for
 * accents. Regenerate rather than hand-editing single values, so the tones stay consistent.
 */
private val BrandLight = lightColorScheme(
    primary = Color(0xFF004CED), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE1FF), onPrimaryContainer = Color(0xFF0038B6),
    inversePrimary = Color(0xFFB7C4FF), secondary = Color(0xFF525C88),
    onSecondary = Color(0xFFFFFFFF), secondaryContainer = Color(0xFFDCE1FF),
    onSecondaryContainer = Color(0xFF0D1840), tertiary = Color(0xFFA73A15),
    onTertiary = Color(0xFFFFFFFF), tertiaryContainer = Color(0xFFFFDBD0),
    onTertiaryContainer = Color(0xFF3A0A00), error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF), errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A), background = Color(0xFFFAF8FF),
    onBackground = Color(0xFF191B25), surface = Color(0xFFFAF8FF),
    onSurface = Color(0xFF191B25), surfaceVariant = Color(0xFFE0E1F3),
    onSurfaceVariant = Color(0xFF444654), surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F2FF), surfaceContainer = Color(0xFFEDEDFB),
    surfaceContainerHigh = Color(0xFFE7E7F5), surfaceContainerHighest = Color(0xFFE1E1EF),
    outline = Color(0xFF727483), outlineVariant = Color(0xFFC4C5D6),
    inverseSurface = Color(0xFF2E303A), inverseOnSurface = Color(0xFFF0EFFE),
    surfaceBright = Color(0xFFFAF8FF), surfaceDim = Color(0xFFD9D9E7),
)

private val BrandDark = darkColorScheme(
    primary = Color(0xFFB7C4FF), onPrimary = Color(0xFF002682),
    primaryContainer = Color(0xFF0038B6), onPrimaryContainer = Color(0xFFDCE1FF),
    inversePrimary = Color(0xFF004CED), secondary = Color(0xFFBAC4F6),
    onSecondary = Color(0xFF232E56), secondaryContainer = Color(0xFF3A456E),
    onSecondaryContainer = Color(0xFFDCE1FF), tertiary = Color(0xFFFFB59F),
    onTertiary = Color(0xFF5E1700), tertiaryContainer = Color(0xFF852400),
    onTertiaryContainer = Color(0xFFFFDBD0), error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005), errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6), background = Color(0xFF11131C),
    onBackground = Color(0xFFE1E1EF), surface = Color(0xFF11131C),
    onSurface = Color(0xFFE1E1EF), surfaceVariant = Color(0xFF444654),
    onSurfaceVariant = Color(0xFFC4C5D6), surfaceContainerLowest = Color(0xFF0C0E17),
    surfaceContainerLow = Color(0xFF191B25), surfaceContainer = Color(0xFF1D1F29),
    surfaceContainerHigh = Color(0xFF282934), surfaceContainerHighest = Color(0xFF32343F),
    outline = Color(0xFF8E909F), outlineVariant = Color(0xFF444654),
    inverseSurface = Color(0xFFE1E1EF), inverseOnSurface = Color(0xFF2E303A),
    surfaceBright = Color(0xFF373943), surfaceDim = Color(0xFF11131C),
)

/**
 * Realistic mode's backdrop: Apple-like neutral greys instead of the palette's tinted surfaces, so silver or space
 * black hardware sits on a plain desk. Accent colors (primary etc.) stay the palette's.
 */
private fun ColorScheme.neutral(dark: Boolean): ColorScheme = if (dark) copy(
    background = Color(0xFF000000), surface = Color(0xFF000000), surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF2C2C2E), surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF111113), surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E), surfaceContainerHighest = Color(0xFF3A3A3C),
    surfaceVariant = Color(0xFF2C2C2E), onSurface = Color(0xFFF2F2F7), onBackground = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFFAEAEB2), outline = Color(0xFF8E8E93), outlineVariant = Color(0xFF3A3A3C),
) else copy(
    background = Color(0xFFF2F2F7), surface = Color(0xFFF2F2F7), surfaceDim = Color(0xFFDCDCE1),
    surfaceBright = Color(0xFFFFFFFF), surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7FA), surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE9E9EE), surfaceContainerHighest = Color(0xFFDCDCE1),
    surfaceVariant = Color(0xFFE5E5EA), onSurface = Color(0xFF1D1D1F), onBackground = Color(0xFF1D1D1F),
    onSurfaceVariant = Color(0xFF6E6E73), outline = Color(0xFF8E8E93), outlineVariant = Color(0xFFC7C7CC),
)

/**
 * The color scheme for [dark]: wallpaper colors on Android 12+ (dynamic color), PadKeys' palette before that. Not a
 * composable, so the keyboard service (which has no Compose UI) uses the same colors as the settings screen.
 */
fun schemeFor(ctx: Context, dark: Boolean): ColorScheme = when {
    Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    dark -> BrandDark
    else -> BrandLight
}

/** The system is in dark mode (the keyboard follows the system, like the apps it types into). */
fun Context.isDark() =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** Sets the keyboard's view colors ([C], [Real]) from the system's light / dark mode and wallpaper. */
fun applyKeyboardColors(ctx: Context) {
    val dark = ctx.isDark()
    Real.on = Prefs.realistic
    Real.dark = dark
    syncViewColors(schemeFor(ctx, dark).let { if (Real.on) it.neutral(dark) else it })
}

/** Material 3 Expressive theme for the settings screen: wallpaper or PadKeys colors, following the system. */
@Composable
fun PadKeysTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = schemeFor(ctx, dark)
    SideEffect {
        // Status/navigation bar icons follow the mode.
        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        (ctx as? ComponentActivity)?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}

/** The Canvas-drawn keyboard and suggestion strip read their colors from [C]. */
internal fun syncViewColors(s: ColorScheme) {
    C.BG = s.surfaceContainer.toArgb()
    C.SURFACE = s.surfaceContainer.toArgb()
    C.SURFACE2 = s.surfaceContainerHigh.toArgb()
    C.SURFACE3 = s.surfaceContainerHighest.toArgb()
    C.STROKE = s.outlineVariant.toArgb()
    C.ACCENT = s.primary.toArgb()
    C.ON_ACCENT = s.onPrimary.toArgb()
    C.ACCENT_DIM = s.primaryContainer.toArgb()
    C.ON_ACCENT_DIM = s.onPrimaryContainer.toArgb()
    C.TEXT = s.onSurface.toArgb()
    C.MUTED = s.onSurfaceVariant.toArgb()
    val dark = s.surface.luminance() < 0.5f
    C.KEY = (if (dark) s.surfaceContainerHighest else s.surfaceContainerLowest).toArgb()
    C.KEY_FN = s.secondaryContainer.toArgb()
    C.ON_KEY_FN = s.onSecondaryContainer.toArgb()
    C.ACTION = s.primary.toArgb()
    C.ON_ACTION = s.onPrimary.toArgb()
    Real.applyColors()
}
