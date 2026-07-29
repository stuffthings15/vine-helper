package com.example.vinehelper.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VineDarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    secondary = SecondaryDark,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onBackground = VineTextPrimary,
    onSurface = VineTextPrimary,
    onSurfaceVariant = VineTextSecondary
)

@Composable
fun VineHelperTheme(
    darkTheme: Boolean = true, // Default to dark theme for premium styling
    content: @Composable () -> Unit
) {
    // We enforce our custom color scheme for premium dark mode aesthetics
    val colorScheme = if (darkTheme) VineDarkColorScheme else VineDarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
