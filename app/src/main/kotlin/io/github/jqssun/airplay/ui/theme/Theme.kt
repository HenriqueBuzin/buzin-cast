package io.github.jqssun.airplay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun AirPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF7CF4CD), onPrimary = Color(0xFF07392E),
        primaryContainer = Color(0xFF164F43), onPrimaryContainer = Color(0xFFADFFE3),
        secondary = Color(0xFFA9BCFF), background = Color(0xFF080E1A),
        secondaryContainer = Color(0xFF193B35), onSecondaryContainer = Color(0xFF7CF4CD),
        surface = Color(0xFF101A2A), surfaceVariant = Color(0xFF172638),
        onBackground = Color(0xFFF1F5FD), onSurface = Color(0xFFF1F5FD),
        onSurfaceVariant = Color(0xFFB8C8DC)
    ), content = content)
}
