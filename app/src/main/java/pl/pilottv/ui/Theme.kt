package pl.pilottv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PilotColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1B33),
    secondary = Color(0xFFB0BEC5),
    background = Color(0xFF0E0F12),
    onBackground = Color(0xFFE6E6EA),
    surface = Color(0xFF16181C),
    onSurface = Color(0xFFE6E6EA),
    surfaceVariant = Color(0xFF26292F),
    onSurfaceVariant = Color(0xFFE3E3E8),
)

val PowerRed = Color(0xFFC62828)

@Composable
fun PilotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PilotColors, content = content)
}
