package com.xs.chat.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ChatGreen = Color(0xFF10A37F)

private val LightColors = lightColorScheme(
    primary = ChatGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F3E9),
    onPrimaryContainer = Color(0xFF074B38),
    secondary = Color(0xFF5E6B66),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFF2F3F3),
    onSurfaceVariant = Color(0xFF5F6663),
    outlineVariant = Color(0xFFE0E3E2)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2BC7A0),
    onPrimary = Color(0xFF002A1F),
    primaryContainer = Color(0xFF0E5745),
    onPrimaryContainer = Color(0xFFD0F5E9),
    secondary = Color(0xFFB2BDB8),
    background = Color(0xFF212121),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF212121),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF2E2F2F),
    onSurfaceVariant = Color(0xFFB8BEBB),
    outlineVariant = Color(0xFF3D3F3F)
)

@Composable
fun XSChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
