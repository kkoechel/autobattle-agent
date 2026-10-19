package online.autobattle.agent.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * A 1990s console RPG dialogue window.
 *
 * Three things make the look, and all three are easy to leave out:
 *
 *  - THE FRAME IS LAYERED, not a border. A dark outer keyline, a bright
 *    inner rule inset from it, and a fill that is lighter at the top than
 *    the bottom. One stroke around a rectangle reads as a web panel.
 *  - THE TEXT HAS A HARD SHADOW, one pixel down and right, zero blur. Those
 *    fonts were drawn against busy backgrounds and the shadow is why they
 *    stayed readable; without it pixel type looks flat and cheap.
 *  - THE CHOICES SHARE ONE CURSOR. They are a list with a pointer, not a row
 *    of buttons -- the thing being chosen is which line the cursor is on.
 *
 * The cursor is DRAWN rather than typed. Of the four pixel fonts considered
 * only Press Start 2P has U+25B6, so a literal triangle renders as a tofu
 * box in the others.
 */

private val FRAME_OUT = Color(0xFF0A0A12)
private val FRAME_LIT = Color(0xFFF2EFE4)
private val FILL_TOP  = Color(0xFF2B2A4A)
private val FILL_BOT  = Color(0xFF14142A)

/** One logical pixel of the window's own grid, so the frame stays crisp. */
private val UNIT = 2.dp

/**
 * The inner box the options sit in.
 *
 * These games almost never mixed the speech and the answers in one frame --
 * the question is spoken, and the choices appear in their own smaller window
 * beneath it. Without the second frame the options read as more narration.
 */
@Composable
fun SnesChoiceBox(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        Canvas(Modifier.matchParentSize()) {
            val u = UNIT.toPx()
            drawRect(FRAME_OUT, size = size)
            drawRect(
                Brush.verticalGradient(listOf(FILL_TOP.copy(alpha = 0.55f), FILL_BOT)),
                topLeft = Offset(u, u),
                size = Size(size.width - 2 * u, size.height - 2 * u),
            )
            drawRect(
                FRAME_LIT.copy(alpha = 0.55f),
                topLeft = Offset(u * 2, u * 2),
                size = Size(size.width - 4 * u, size.height - 4 * u),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = u / 2),
            )
        }
        Column(
            Modifier.padding(horizontal = UNIT * 5, vertical = UNIT * 4),
            content = content,
        )
    }
}

@Composable
fun SnesWindow(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        Canvas(Modifier.matchParentSize()) {
            val u = UNIT.toPx()
            drawRect(FRAME_OUT, size = size)
            drawRect(
                Brush.verticalGradient(listOf(FILL_TOP, FILL_BOT)),
                topLeft = Offset(u, u),
                size = Size(size.width - 2 * u, size.height - 2 * u),
            )
            // The bright rule sits INSIDE the fill, which is what makes the
            // window look inlaid rather than outlined.
            val i = u * 2
            drawRect(
                FRAME_LIT,
                topLeft = Offset(i, i),
                size = Size(size.width - 2 * i, size.height - 2 * i),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = u),
            )
        }
        Column(
            Modifier.padding(horizontal = UNIT * 7, vertical = UNIT * 6),
            verticalArrangement = Arrangement.spacedBy(UNIT * 3),
            content = content,
        )
    }
}

/** Pale type with the hard one-pixel shadow the era always used. */
@Composable
fun snesText(size: Int = 15, color: Color = Color(0xFFF2EFE4)) = TextStyle(
    fontFamily = Pixel,
    fontSize = size.sp,
    lineHeight = (size + 5).sp,
    color = color,
    shadow = Shadow(Color(0xFF0A0A12), Offset(2f, 2f), 0f),
)

/**
 * The agent's line, revealed a character at a time.
 *
 * Tapping completes it instantly -- waiting out a reveal you have already
 * read is the one thing everybody hated about this convention.
 */
@Composable
fun TypedLine(text: String, modifier: Modifier = Modifier) {
    var shown by remember(text) { mutableStateOf(0) }
    LaunchedEffect(text) {
        shown = 0
        while (shown < text.length) { delay(18); shown++ }
    }
    Box(modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) { shown = text.length }) {
        // The full string is laid out invisibly underneath so the window does
        // not grow line by line as the text arrives, which makes everything
        // below it jump.
        Text(text, style = snesText().copy(color = Color.Transparent))
        Text(text.take(shown), style = snesText())
    }
}

/**
 * A progress bar in the same idiom: a sunken channel with a chunky fill.
 *
 * Material's indicator is a 4dp hairline with a rounded cap, which beside a
 * pixel window looks like a browser loading bar someone forgot to remove.
 */
@Composable
fun SnesProgress(done: Int, total: Int, modifier: Modifier = Modifier) {
    val known = total > 1
    val sweep by rememberInfiniteTransition(label = "bar").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "sweep",
    )
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val u = UNIT.toPx()
        drawRect(FRAME_OUT, size = size)
        drawRect(Color(0xFF191933), topLeft = Offset(u, u),
            size = Size(size.width - 2 * u, size.height - 2 * u))
        val inner = Size(size.width - 4 * u, size.height - 4 * u)
        if (known) {
            val f = (done.toFloat() / total).coerceIn(0f, 1f)
            drawRect(Color(0xFFFFE28C), topLeft = Offset(u * 2, u * 2),
                size = Size(inner.width * f, inner.height))
        } else {
            // No total to show, so a block travels instead of a bar filling:
            // a half-full static bar would be a lie about progress.
            val w = inner.width * 0.28f
            val x = (inner.width + w) * sweep - w
            drawRect(
                Color(0xFFFFE28C),
                topLeft = Offset(u * 2 + x.coerceAtLeast(0f), u * 2),
                size = Size(
                    (if (x < 0) w + x else minOf(w, inner.width - x)).coerceAtLeast(0f),
                    inner.height,
                ),
            )
        }
    }
}

/** One option. The cursor marks it; nothing else moves. */
@Composable
fun SnesChoice(
    label: String,
    detail: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lit = Color(0xFFFFE28C)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, interactionSource = interaction,
                indication = null, onClick = onClick)
            .padding(vertical = UNIT * 2),
        verticalAlignment = Alignment.Top,
    ) {
        Canvas(Modifier.padding(top = 5.dp, end = 6.dp).size(width = 9.dp, height = 11.dp)) {
            drawPath(
                Path().apply {
                    moveTo(0f, 0f); lineTo(size.width, size.height / 2f)
                    lineTo(0f, size.height); close()
                },
                if (pressed) lit else lit.copy(alpha = 0.45f),
            )
        }
        Column {
            Text(label, style = snesText(15, if (pressed) lit else Color(0xFFF2EFE4)))
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = snesText(13, Color(0xFFA8A8C8)))
            }
        }
    }
}
