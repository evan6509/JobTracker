package com.evanchubbuck.jobtracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8FD9B5), onPrimary = Color(0xFF063820),
    primaryContainer = Color(0xFF224C3B), onPrimaryContainer = Color(0xFFBDEBD3),
    secondaryContainer = Color(0xFF273E38), onSecondaryContainer = Color(0xFFE4EDF0),
    background = Color(0xFF101A22), onBackground = Color(0xFFE4EDF0),
    surface = Color(0xFF1B2933), onSurface = Color(0xFFE4EDF0),
    surfaceVariant = Color(0xFF283944), onSurfaceVariant = Color(0xFFB5C7CE)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF176B51), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEEE5), onPrimaryContainer = Color(0xFF063820),
    secondaryContainer = Color(0xFFE4EEE8), onSecondaryContainer = Color(0xFF18304A),
    background = Color(0xFFF6F8F7), onBackground = Color(0xFF18304A),
    surface = Color.White, onSurface = Color(0xFF18304A),
    surfaceVariant = Color(0xFFE7EFED), onSurfaceVariant = Color(0xFF566A73)
)

@Composable
fun JobTrackerTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
