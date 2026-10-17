package io.ofloo.pagerapp.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.ofloo.pagerapp.ThemeMode

/**
 * PagerDuty-inspired palette: a confident green on a near-black / near-white canvas.
 * Dark and light are explicit so the brand stays consistent rather than following Material You.
 */
private val PagerLight = lightColorScheme(
    primary = Color(0xFF00874A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA7F3CB),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4B6355),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE9D5),
    onSecondaryContainer = Color(0xFF082015),
    background = Color(0xFFF6FAF6),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFF6FAF6),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFC0C9C2),
    // Explicit neutral containers; otherwise Material3 falls back to its purple baseline.
    surfaceDim = Color(0xFFD5DAD6),
    surfaceBright = Color(0xFFF6FAF6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F4F0),
    surfaceContainer = Color(0xFFEAEFEA),
    surfaceContainerHigh = Color(0xFFE4E9E4),
    surfaceContainerHighest = Color(0xFFDEE3DE),
    surfaceTint = Color(0xFF00874A),
    inverseSurface = Color(0xFF2E3230),
    inverseOnSurface = Color(0xFFEFF1EE),
    inversePrimary = Color(0xFF3EDD95),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val PagerDark = darkColorScheme(
    primary = Color(0xFF3EDD95),
    onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF00522D),
    onPrimaryContainer = Color(0xFFA7F3CB),
    secondary = Color(0xFFB1CCB9),
    onSecondary = Color(0xFF1D3527),
    secondaryContainer = Color(0xFF334C3C),
    onSecondaryContainer = Color(0xFFCDE9D5),
    background = Color(0xFF0E1511),
    onBackground = Color(0xFFDEE4DE),
    surface = Color(0xFF0E1511),
    onSurface = Color(0xFFDEE4DE),
    surfaceVariant = Color(0xFF3F4943),
    onSurfaceVariant = Color(0xFFBFC9C1),
    outline = Color(0xFF89938C),
    outlineVariant = Color(0xFF3F4943),
    surfaceDim = Color(0xFF0E1511),
    surfaceBright = Color(0xFF343B36),
    surfaceContainerLowest = Color(0xFF090F0B),
    surfaceContainerLow = Color(0xFF161D18),
    surfaceContainer = Color(0xFF1A211C),
    surfaceContainerHigh = Color(0xFF242B26),
    surfaceContainerHighest = Color(0xFF2F3631),
    surfaceTint = Color(0xFF3EDD95),
    inverseSurface = Color(0xFFDEE4DE),
    inverseOnSurface = Color(0xFF2B322D),
    inversePrimary = Color(0xFF00874A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Applies the colour scheme for [mode].
 *
 * "Follow system" also adopts the system's palette (Material You, Android 12+) so the accents match
 * the rest of the phone; an explicit Light/Dark choice keeps the PagerDuty-inspired green brand.
 */
@Composable
fun PagerTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val useSystemPalette = mode == ThemeMode.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = when {
        useSystemPalette && dark -> dynamicDarkColorScheme(context)
        useSystemPalette -> dynamicLightColorScheme(context)
        dark -> PagerDark
        else -> PagerLight
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
