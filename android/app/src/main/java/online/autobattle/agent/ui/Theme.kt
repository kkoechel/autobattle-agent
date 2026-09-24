package online.autobattle.agent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The whole palette, in one place.
 *
 * Neutral dark on purpose: this is a placeholder for a visual direction, not a
 * design. Everything downstream reads MaterialTheme.colorScheme, so replacing
 * these values restyles the app without touching a screen.
 */
private val Ink = Color(0xFF12131A)
private val Slate = Color(0xFF1C1E27)
private val Parchment = Color(0xFFE8E4D9)
private val Muted = Color(0xFF9A9AAB)
private val Gold = Color(0xFFC8A560)
private val Danger = Color(0xFFCE6A5C)

val Good = Color(0xFF6FA86A)
val Warn = Color(0xFFC8A560)
val Bad = Danger
val Subtle = Muted

private val scheme = darkColorScheme(
    primary = Gold,
    onPrimary = Ink,
    background = Ink,
    onBackground = Parchment,
    surface = Slate,
    onSurface = Parchment,
    surfaceVariant = Slate,
    onSurfaceVariant = Muted,
    error = Danger,
)

@Composable
fun AgentTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
