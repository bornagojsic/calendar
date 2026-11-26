package dev.bornagojsic.calendar.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// T3 Code-style tokens: layered near-black surfaces, hairline borders, one accent.
private val Canvas = Color(0xFF0A0A0B)
private val Surface = Color(0xFF111113)
private val SurfaceRaised = Color(0xFF18181B)
private val SurfaceOverlay = Color(0xFF1F1F23)
private val Text = Color(0xFFE8E8EA)
private val TextMuted = Color(0xFF9A9AA2)
private val Border = Color(0xFF26262B)
private val Accent = Color(0xFF346BF1)
private val Error = Color(0xFFEF4444)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Accent,
    onPrimaryContainer = Color.White,
    secondaryContainer = SurfaceOverlay,
    onSecondaryContainer = Text,
    background = Canvas,
    onBackground = Text,
    surface = Surface,
    onSurface = Text,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextMuted,
    surfaceContainerLowest = Canvas,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceRaised,
    surfaceContainerHighest = SurfaceOverlay,
    outline = Border,
    outlineVariant = Border,
    error = Error,
    onError = Color.White,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun CalendarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
}
