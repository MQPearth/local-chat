package com.localchat.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 与桌面版一致的浅色调色板:强调色 #171717,背景 #F7F8FA
private val LightColors = lightColorScheme(
    primary = Color(0xFF171717),
    onPrimary = Color.White,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1F2328),
    surface = Color.White,
    onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFF1F2F5),
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFE7E9EC),
    error = Color(0xFFD5423F)
)

@Composable
fun LocalChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
