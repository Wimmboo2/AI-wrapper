package dev.wimmboo.omni.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable
fun OmniTheme(
    incognito: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = if (incognito) OmniIncognitoPalette else OmniDarkPalette

    val colorScheme = darkColorScheme(
        primary = palette.accent,
        onPrimary = palette.bg,
        secondary = palette.textSecondary,
        onSecondary = palette.bg,
        background = palette.bg,
        onBackground = palette.text,
        surface = palette.bgElevated,
        onSurface = palette.text,
        surfaceVariant = palette.bgComposer,
        onSurfaceVariant = palette.textSecondary,
        outline = palette.borderSoft,
        outlineVariant = palette.border,
        error = palette.danger,
        onError = palette.bg,
    )

    val typography = Typography(
        displayLarge = TextStyle(
            fontFamily = InstrumentSerifFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 32.sp,
            lineHeight = 38.sp,
            letterSpacing = (-0.5).sp,
            color = palette.text,
        ),
        headlineMedium = TextStyle(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            color = palette.text,
        ),
        titleMedium = TextStyle(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            color = palette.text,
        ),
        bodyLarge = TextStyle(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 25.5.sp,
            color = palette.text,
        ),
        bodyMedium = TextStyle(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
            color = palette.text,
        ),
        bodySmall = TextStyle(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = palette.textSecondary,
        ),
        labelMedium = TextStyle(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = palette.textSecondary,
        ),
        labelSmall = TextStyle(
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = palette.textSecondary,
        ),
    )

    CompositionLocalProvider(LocalOmniColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}

object OmniTheme {
    val colors: OmniColorPalette
        @Composable get() = LocalOmniColors.current
}
