package com.tightxu.hermesremote.ui.theme

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.tightxu.hermesremote.R

/**
 * Selectable accent themes. Default YELLOW matches the gateway dashboard's
 * warmGlow accent (rgba(255,189,56,…) → #FFBD38); GREEN is the classic
 * terminal look this app shipped with. Labels are string resources so they
 * follow the app locale.
 */
enum class ThemeAccent(val key: String, @StringRes val labelRes: Int, val color: Color, val onColor: Color) {
    YELLOW("yellow", R.string.theme_yellow, Color(0xFFFFBD38), Color(0xFF1A1200)),
    GREEN("green", R.string.theme_green, Color(0xFF7EE787), Color(0xFF06210F)),
    BLUE("blue", R.string.theme_blue, Color(0xFF58A6FF), Color(0xFF06121F)),
    PURPLE("purple", R.string.theme_purple, Color(0xFFBC8CFF), Color(0xFF160A24)),
}

/** Look up an accent by its persisted key; unknown/absent → YELLOW. */
fun accentForKey(key: String?): ThemeAccent =
    ThemeAccent.entries.firstOrNull { it.key == key } ?: ThemeAccent.YELLOW

/**
 * The app is a terminal companion, so it ships dark-only: one dark scheme
 * close to the dashboard's engineering look, regardless of system setting.
 * The accent (primary color) follows the user's theme pick; status colors
 * (green=ok etc.) stay fixed.
 */
private fun hermesDarkColors(accent: ThemeAccent) = darkColorScheme(
    primary = accent.color,
    onPrimary = accent.onColor,
    primaryContainer = accent.color.copy(alpha = 0.26f),
    onPrimaryContainer = accent.color,
    secondary = SecondaryBlue,
    onSecondary = BgDark,
    tertiary = TertiaryCyan,
    onTertiary = BgDark,
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    outline = OutlineDark,
    error = ErrorRed,
    onError = BgDark,
)

@Composable
fun HermesRemoteTheme(
    accentKey: String? = null,
    content: @Composable () -> Unit,
) {
    // isSystemInDarkTheme() consulted (and ignored) to keep the intent explicit.
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = hermesDarkColors(accentForKey(accentKey)),
        typography = HermesTypography,
        content = content,
    )
}
