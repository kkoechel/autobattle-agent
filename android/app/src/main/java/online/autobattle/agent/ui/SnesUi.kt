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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import online.autobattle.agent.game.CardInfo
import androidx.compose.ui.text.style.TextOverflow
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
// THE WINDOWS ARE TRANSLUCENT so the Paragon standing behind them is not cut
// in half. The frame and the bright inlay stay opaque -- they are what keeps
// a window reading as a window rather than as a stain on the picture.
//
// WHY THE BORDER IS NOW A BORDER. Both frames used to begin by filling their
// WHOLE rect with opaque FRAME_OUT and then painting the fill over it, so an
// alpha on the fill bought almost nothing: at 90% only four percent of the
// picture came through, and the box still read as solid. The outline is
// drawn as an outline now, and the interior is the only thing carrying a
// fill, so its alpha means what it says.
private val FILL_TOP  = Color(0xCC2B2A4A)
private val FILL_BOT  = Color(0xCC14142A)

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
            drawRect(FRAME_OUT.copy(alpha = 0.8f), size = size,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = u * 2))
            // LIGHTER THAN THE WINDOW BECAUSE IT SITS INSIDE ONE. Two 80%
            // layers stacked are 96% between the viewer and the figure, so
            // the choices -- which cover most of the screen -- would stay
            // solid however transparent the window became.
            drawRect(
                Brush.verticalGradient(
                    listOf(FILL_TOP.copy(alpha = 0.30f), FILL_BOT.copy(alpha = 0.42f))),
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
            // The dark edge only, not a backing plate: a filled rect here
            // would sit under the translucent interior and undo it.
            drawRect(FRAME_OUT, size = size,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = u * 2))
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
 * The agent's line, revealed a character at a time, with any card it names
 * tappable in place.
 *
 * The candidate names are passed in rather than taken from the whole
 * catalogue on purpose. Matching all 1,066 would turn ordinary words into
 * links -- there are cards called Book, Brick and Anthem -- so the caller
 * supplies the cards actually in play: the one the plan is built around,
 * the one being proposed, and the deck's own contents. A name the agent
 * says is nearly always one of those.
 */
@Composable
fun TypedLine(
    text: String,
    cards: Map<String, CardInfo>,
    onOpenCard: (CardInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    var shown by remember(text) { mutableStateOf(0) }
    LaunchedEffect(text) {
        shown = 0
        while (shown < text.length) { delay(18); shown++ }
    }
    // Whole-word, longest first, no overlaps -- the same rule the card text
    // uses, so "Mill" cannot match inside "Miller".
    val hits = remember(text, cards.keys) {
        val taken = BooleanArray(text.length)
        val out = ArrayList<Pair<IntRange, CardInfo>>()
        for (name in cards.keys.sortedByDescending { it.length }) {
            var from = 0
            while (true) {
                val i = text.indexOf(name, from, ignoreCase = true)
                if (i < 0) break
                from = i + 1
                val end = i + name.length
                val okStart = i == 0 || !text[i - 1].isLetterOrDigit()
                val okEnd = end >= text.length || !text[end].isLetterOrDigit()
                if (!okStart || !okEnd) continue
                if ((i until end).any { taken[it] }) continue
                for (j in i until end) taken[j] = true
                out.add((i until end) to cards.getValue(name))
            }
        }
        out.sortedBy { it.first.first }
    }

    val body = snesText()
    val annotated = buildAnnotatedString {
        val visible = text.take(shown)
        var at = 0
        for ((range, card) in hits) {
            if (range.last >= visible.length) break
            if (range.first > at) append(visible.substring(at, range.first))
            pushStringAnnotation("card", card.id.toString())
            withStyle(SpanStyle(color = Color(0xFFA8C8E8),
                textDecoration = TextDecoration.Underline)) {
                append(visible.substring(range.first, range.last + 1))
            }
            pop()
            at = range.last + 1
        }
        if (at < visible.length) append(visible.substring(at))
    }

    Box(modifier) {
        // The full string laid out invisibly underneath, so the window does
        // not grow line by line as the text arrives.
        //
        // SHADOW NULLED, NOT JUST THE COLOUR. snesText carries a drop shadow,
        // and a Text whose colour is transparent still draws it -- so the
        // words not yet typed were sitting there as dark ghosts, spoiling the
        // one effect this composable exists for. It went unnoticed while the
        // window behind was opaque and nearly the same colour as the shadow;
        // the moment the window became translucent over a bright locale, the
        // whole remaining line was legible before it was typed.
        Text(text, style = body.copy(color = Color.Transparent, shadow = null))
        ClickableText(annotated, style = body) { off ->
            val hit = annotated.getStringAnnotations("card", off, off).firstOrNull()
            if (hit != null) cards.values.firstOrNull { it.id.toString() == hit.item }
                ?.let(onOpenCard)
            else shown = text.length
        }
    }
}

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


/**
 * The places a player can be.
 *
 * ONE JOB EACH, which is why there are four and not five. The old AGENT tab
 * did two unrelated jobs -- building a deck and explaining the game -- and
 * paid for it with a second row of buttons under the tabs holding three
 * controls with three different purposes. Brew is everything that changes
 * the deck; Talk is everything that explains one, which is why the binder
 * and the ban list live there too: both are reference, which is what "how
 * does this work" means when the answer is a card rather than a rule.
 *
 * LEAGUE was added for the same reason the AGENT tab was removed. The Dojo
 * is where a player watches their own rounds back, and the season ladder is
 * not that -- it is where everyone stands, which is a different question
 * asked at a different time. Hanging it off the Dojo would have put it below
 * two screens of replay history, which this app has already been bitten by
 * once: a result rendered past the control that produced it reads as a
 * control that does nothing, and was reported as exactly that.
 */
enum class Place(val label: String) {
    BREW("Brew"), TALK("Talk"), DOJO("Dojo"), LEAGUE("League"),
}

/**
 * The destination bar: one framed strip, the current place marked.
 *
 * Deliberately not a Material tab row. A tab row says "this is an app with
 * sections"; a command strip with a cursor says "this is somewhere you are
 * standing", which is the whole point of splitting the screen up.
 */
@Composable
fun SnesMenu(current: Place, onGo: (Place) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(UNIT * 3),
    ) {
        for (p in Place.entries) {
            val here = p == current
            Box(
                Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onGo(p) },
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val u = UNIT.toPx()
                    drawRect(FRAME_OUT, size = size)
                    drawRect(
                        if (here) Brush.verticalGradient(listOf(FILL_TOP, FILL_BOT))
                        else Brush.verticalGradient(listOf(Color(0xFF191932), Color(0xFF101020))),
                        topLeft = Offset(u, u),
                        size = Size(size.width - 2 * u, size.height - 2 * u),
                    )
                    if (here) {
                        drawRect(
                            FRAME_LIT,
                            topLeft = Offset(u * 2, u * 2),
                            size = Size(size.width - 4 * u, size.height - 4 * u),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = u),
                        )
                    }
                }
                Text(
                    p.label,
                    Modifier.align(Alignment.Center).padding(vertical = UNIT * 4),
                    style = snesText(13, if (here) Color(0xFFFFE28C) else Color(0xFF8A8AA8)),
                    maxLines = 1, softWrap = false,
                )
            }
        }
    }
}


/** Who you are, on a plate. */
@Composable
fun Nameplate(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(FRAME_OUT)
            .padding(UNIT)
            .background(Brush.verticalGradient(listOf(FILL_TOP, FILL_BOT)))
            .padding(horizontal = UNIT * 3, vertical = UNIT * 3),
    ) {
        // 13, not 14: the plate shares its row with two buttons, and at 14
        // a nine-character handle already ellipsised.
        Text(name, style = snesText(13, Color(0xFFF2EFE4)),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
