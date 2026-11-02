package pl.pilottv.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import pl.pilottv.data.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1B33),
    secondary = Color(0xFFB0BEC5),
    background = Color(0xFF0E0F12),
    onBackground = Color(0xFFE6E6EA),
    surface = Color(0xFF16181C),
    onSurface = Color(0xFFE6E6EA),
    surfaceVariant = Color(0xFF26292F),
    onSurfaceVariant = Color(0xFFC9CAD1),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A5FD0),
    onPrimary = Color.White,
    secondary = Color(0xFF546E7A),
    background = Color(0xFFF4F5F8),
    onBackground = Color(0xFF1B1D22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1D22),
    surfaceVariant = Color(0xFFE1E3EA),
    onSurfaceVariant = Color(0xFF3A3D45),
)

val PowerRed = Color(0xFFC62828)

@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun PilotTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        // Surface ustawia kolor tekstu (LocalContentColor). Bez niego Text jest domyślnie czarny.
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}
