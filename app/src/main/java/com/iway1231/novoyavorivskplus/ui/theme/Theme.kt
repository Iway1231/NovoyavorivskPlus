package com.iway1231.novoyavorivskplus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFF9D28),
    onPrimary = Color(0xFF171717),
    secondary = Color(0xFFB8B8B8),
    tertiary = Color(0xFFFFB74D),
    background = Color(0xFF090909),
    surface = Color(0xFF171717),
    surfaceVariant = Color(0xFF262626),
    onBackground = Color(0xFFF5F5F5),
    onSurface = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFFA6A6A6)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2864DC),
    onPrimary = Color.White,
    secondary = Color(0xFF536B91),
    tertiary = Color(0xFFE29A18),
    background = Color(0xFFF5F7FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F3F8),
    onBackground = Color(0xFF14243A),
    onSurface = Color(0xFF14243A),
    onSurfaceVariant = Color(0xFF68778A)
)

@Composable
fun НовояворівськTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
