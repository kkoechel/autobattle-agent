package online.autobattle.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The game's furniture: framed panels and beveled gold buttons.
 *
 * Material's defaults are a filled pill and a flat card, which read as a
 * settings screen wrapped around a trading card. These match what the card
 * renderer already draws -- a dark panel, a gold edge, a warm vertical
 * gradient and serif capitals -- so the chrome and the cards look like one
 * thing. Nothing here is a new palette: every colour comes from Theme.kt.
 */

private val Frame = RoundedCornerShape(10.dp)

private fun plate(pressed: Boolean) = Brush.verticalGradient(
    // Lit from above, like an embossed plate. Pressing flattens the gradient
    // rather than moving the button, which on a phone just looks like a
    // rendering glitch.
    if (pressed) listOf(PanelFoot, PanelTop) else listOf(PanelTop, PanelFoot)
)

/**
 * The primary action. One per screen -- "send your agent to work" -- so it
 * carries the brightest edge and the widest letter spacing.
 */
@Composable
fun GameButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val edge = when {
        !enabled -> GoldDim.copy(alpha = 0.45f)
        pressed -> GoldBright
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier
            .clip(Frame)
            .background(plate(pressed))
            .border(BorderStroke(if (enabled) 1.5.dp else 1.dp, edge), Frame)
            .clickable(enabled = enabled, interactionSource = interaction,
                indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            color = if (enabled) (if (pressed) GoldBright else MaterialTheme.colorScheme.primary)
                    else Subtle.copy(alpha = 0.6f))
    }
}

/**
 * A choice in a dialogue: a title and the consequence under it. Left-aligned
 * and full width, because these are read before they are tapped -- a row of
 * centred pills would hide the one line that distinguishes them.
 */
@Composable
fun GameChoice(
    label: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .fillMaxWidth()
            .clip(Frame)
            .background(plate(pressed))
            .border(BorderStroke(1.dp, if (pressed) GoldBright else GoldDim), Frame)
            .clickable(enabled = enabled, interactionSource = interaction,
                indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A small secondary action -- Refresh, Theme, Re-brief. */
@Composable
fun GameLink(text: String, modifier: Modifier = Modifier, enabled: Boolean = true,
             onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .border(BorderStroke(1.dp, if (pressed) GoldBright else GoldDim),
                RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, interactionSource = interaction,
                indication = null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        // Single line, always. Letter-spaced capitals are wide, and three of
        // these sit in one row -- "Start over" wrapped to two lines and made
        // its own box taller than the button beside it.
        Text(text, style = MaterialTheme.typography.labelMedium,
            maxLines = 1, softWrap = false,
            color = if (enabled) MaterialTheme.colorScheme.primary
                    else Subtle.copy(alpha = 0.6f))
    }
}

/**
 * A framed panel. The hairline inside the gold edge is what makes it read as
 * a plate rather than a div with a border -- the same doubled edge the card
 * frame uses.
 */
@Composable
fun GamePanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Frame)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, GoldDim), Frame)
            .padding(1.dp)
            .border(BorderStroke(1.dp, Color(0x14FFFFFF)), Frame)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
