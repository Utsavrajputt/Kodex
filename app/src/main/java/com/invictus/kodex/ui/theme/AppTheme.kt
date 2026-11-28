package com.invictus.kodex.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Linear blend of two colors (used to generate expressive containers). */
internal fun mix(a: Color, b: Color, fraction: Float): Color {
    val fa = (1f - fraction).coerceIn(0f, 1f)
    val fb = 1f - fa
    return Color(
        red = (a.red * fa + b.red * fb).coerceIn(0f, 1f),
        green = (a.green * fa + b.green * fb).coerceIn(0f, 1f),
        blue = (a.blue * fa + b.blue * fb).coerceIn(0f, 1f),
        alpha = (a.alpha * fa + b.alpha * fb).coerceIn(0f, 1f),
    )
}

/**
 * App themes (ported from xmd / Aniyomi-style design).
 * Listed Default first; Aurora remains the initially selected theme (see ThemeSettings).
 * Catppuccin, Nord, and Rose Pine round out the set of five.
 */
enum class AppTheme(
    val title: String,
    val primaryLight: Color,
    val primaryDark: Color,
    val secondaryLight: Color,
    val secondaryDark: Color,
    val tertiaryLight: Color,
    val tertiaryDark: Color,
    val backgroundLight: Color,
    val backgroundDark: Color,
    val isDynamic: Boolean = false,
) {
    Default(
        title = "Default",
        primaryLight = Color(0xFF00658F),
        primaryDark = Color(0xFF7CD4FF),
        secondaryLight = Color(0xFF4B6373),
        secondaryDark = Color(0xFFB7CAD6),
        tertiaryLight = Color(0xFF984836),
        tertiaryDark = Color(0xFFFFB4A0),
        backgroundLight = Color(0xFFF4F6F9),
        backgroundDark = Color(0xFF0E1521),
    ),
    Aurora(
        title = "Aurora",
        primaryLight = Color(0xFF0B3FA0),
        primaryDark = Color(0xFF5B93FF),
        secondaryLight = Color(0xFF5C6B8C),
        secondaryDark = Color(0xFF9FAEC9),
        tertiaryLight = Color(0xFF3648A6),
        tertiaryDark = Color(0xFF97A8FF),
        backgroundLight = Color(0xFFF3F6FF),
        backgroundDark = Color(0xFF04070F),
    ),
    Catppuccin(
        title = "Catppuccin",
        primaryLight = Color(0xFF8839EF),
        primaryDark = Color(0xFFCBA6F7),
        secondaryLight = Color(0xFF179299),
        secondaryDark = Color(0xFF94E2D5),
        tertiaryLight = Color(0xFFFE640B),
        tertiaryDark = Color(0xFFFAB387),
        backgroundLight = Color(0xFFEFF1F5),
        backgroundDark = Color(0xFF1E1E2E),
    ),
    Nord(
        title = "Nord",
        primaryLight = Color(0xFF5E81AC),
        primaryDark = Color(0xFF88C0D0),
        secondaryLight = Color(0xFF4C566A),
        secondaryDark = Color(0xFFD8DEE9),
        tertiaryLight = Color(0xFFB48EAD),
        tertiaryDark = Color(0xFFD8A9C4),
        backgroundLight = Color(0xFFECEFF4),
        backgroundDark = Color(0xFF2E3440),
    ),
    RosePine(
        title = "Rose Pine",
        primaryLight = Color(0xFF907AA9),
        primaryDark = Color(0xFFC4A7E7),
        secondaryLight = Color(0xFFB4637A),
        secondaryDark = Color(0xFFEBBCBA),
        tertiaryLight = Color(0xFF56949F),
        tertiaryDark = Color(0xFF9CCFD8),
        backgroundLight = Color(0xFFFAF4ED),
        backgroundDark = Color(0xFF191724),
    ),
    TokyoNight(
        title = "Tokyo Night",
        primaryLight = Color(0xFF2E7DE9),
        primaryDark = Color(0xFF7AA2F7),
        secondaryLight = Color(0xFF9854F1),
        secondaryDark = Color(0xFFBB9AF7),
        tertiaryLight = Color(0xFF007197),
        tertiaryDark = Color(0xFF7DCFFF),
        backgroundLight = Color(0xFFE1E2E7),
        backgroundDark = Color(0xFF1A1B26),
    ),
    Gruvbox(
        title = "Gruvbox",
        primaryLight = Color(0xFFAF3A03),
        primaryDark = Color(0xFFFE8019),
        secondaryLight = Color(0xFF79740E),
        secondaryDark = Color(0xFFB8BB26),
        tertiaryLight = Color(0xFFB57614),
        tertiaryDark = Color(0xFFFABD2F),
        backgroundLight = Color(0xFFFBF1C7),
        backgroundDark = Color(0xFF282828),
    ),
    ;

    fun getLightColorScheme(): ColorScheme = lightColorScheme(
        primary = primaryLight,
        onPrimary = Color.White,
        primaryContainer = mix(primaryLight, backgroundLight, 0.72f),
        onPrimaryContainer = mix(primaryLight, Color.Black, 0.72f),
        inversePrimary = primaryDark,
        secondary = secondaryLight,
        onSecondary = Color.White,
        secondaryContainer = mix(secondaryLight, backgroundLight, 0.75f),
        onSecondaryContainer = mix(secondaryLight, Color.Black, 0.7f),
        tertiary = tertiaryLight,
        onTertiary = Color.White,
        tertiaryContainer = mix(tertiaryLight, backgroundLight, 0.75f),
        onTertiaryContainer = mix(tertiaryLight, Color.Black, 0.7f),
        background = backgroundLight,
        onBackground = mix(backgroundLight, Color.Black, 0.87f),
        surface = backgroundLight,
        onSurface = mix(backgroundLight, Color.Black, 0.87f),
        surfaceVariant = mix(backgroundLight, secondaryLight, 0.15f),
        onSurfaceVariant = mix(backgroundLight, Color.Black, 0.6f),
        surfaceTint = primaryLight,
        inverseSurface = mix(backgroundLight, Color.Black, 0.85f),
        inverseOnSurface = backgroundLight,
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = mix(backgroundLight, Color.Black, 0.45f),
        outlineVariant = mix(backgroundLight, Color.Black, 0.2f),
        scrim = Color.Black,
        surfaceBright = mix(backgroundLight, Color.White, 0.6f),
        surfaceDim = mix(backgroundLight, Color.Black, 0.08f),
        surfaceContainerLowest = mix(backgroundLight, Color.White, 0.35f),
        surfaceContainerLow = backgroundLight,
        surfaceContainer = mix(backgroundLight, primaryLight, 0.04f),
        surfaceContainerHigh = mix(backgroundLight, primaryLight, 0.08f),
        surfaceContainerHighest = mix(backgroundLight, primaryLight, 0.12f),
    )

    fun getDarkColorScheme(): ColorScheme = darkColorScheme(
        primary = primaryDark,
        onPrimary = mix(backgroundDark, Color.Black, 0.55f),
        primaryContainer = mix(primaryDark, backgroundDark, 0.55f),
        onPrimaryContainer = mix(primaryDark, Color.White, 0.85f),
        inversePrimary = primaryLight,
        secondary = secondaryDark,
        onSecondary = mix(backgroundDark, Color.Black, 0.55f),
        secondaryContainer = mix(secondaryDark, backgroundDark, 0.55f),
        onSecondaryContainer = mix(secondaryDark, Color.White, 0.85f),
        tertiary = tertiaryDark,
        onTertiary = mix(backgroundDark, Color.Black, 0.55f),
        tertiaryContainer = mix(tertiaryDark, backgroundDark, 0.55f),
        onTertiaryContainer = mix(tertiaryDark, Color.White, 0.85f),
        background = backgroundDark,
        onBackground = mix(backgroundDark, Color.White, 0.92f),
        surface = backgroundDark,
        onSurface = mix(backgroundDark, Color.White, 0.92f),
        surfaceVariant = mix(backgroundDark, secondaryDark, 0.18f),
        onSurfaceVariant = mix(backgroundDark, Color.White, 0.7f),
        surfaceTint = primaryDark,
        inverseSurface = mix(backgroundDark, Color.White, 0.9f),
        inverseOnSurface = mix(backgroundDark, Color.Black, 0.8f),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = mix(backgroundDark, Color.White, 0.4f),
        outlineVariant = mix(backgroundDark, Color.White, 0.2f),
        scrim = Color.Black,
        surfaceBright = mix(backgroundDark, Color.White, 0.25f),
        surfaceDim = backgroundDark,
        surfaceContainerLowest = mix(backgroundDark, Color.Black, 0.4f),
        surfaceContainerLow = backgroundDark,
        surfaceContainer = mix(backgroundDark, primaryDark, 0.05f),
        surfaceContainerHigh = mix(backgroundDark, primaryDark, 0.10f),
        surfaceContainerHighest = mix(backgroundDark, primaryDark, 0.16f),
    )

    /** Pure-black AMOLED variant of the dark scheme. */
    fun getAmoledColorScheme(): ColorScheme = getDarkColorScheme().copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0A0A0A),
        surfaceContainer = Color(0xFF121212),
        surfaceContainerHigh = Color(0xFF1A1A1A),
        surfaceContainerHighest = Color(0xFF222222),
    )
}
