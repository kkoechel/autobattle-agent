package online.autobattle.agent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import online.autobattle.agent.game.CardIndex
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import online.autobattle.agent.game.CardInfo
import online.autobattle.agent.game.Keyword

/**
 * A card, drawn the way the main game draws it.
 *
 * Deliberately a port of game/card.php's .card-frame rather than a loose
 * impression of it: the rarity bar, the cost badge, full-bleed art, the type
 * line with its rarity dot, and the stat chips, with the same palette. A
 * player who knows the main game should recognise this instantly, and the
 * agent's "do you want me to build around X?" is only answerable if the card
 * reads the way they expect.
 */
private val RARITY = mapOf(
    "common" to 0xFF9CA3AF, "uncommon" to 0xFF4ADE80, "rare" to 0xFF60A5FA,
    "mythic" to 0xFFC084FC, "legendary" to 0xFFFBBF24, "infinite" to 0xFF22D3EE,
    // token and manifest are here because the main game once omitted them and
    // every token card rendered in common grey. Reproducing a bug they had
    // already found and fixed would be a poor way to match their look.
    "token" to 0xFFF472B6, "manifest" to 0xFFC4B5FD,
)

private const val FRAME_BG = 0xFF141418
private const val FRAME_BORDER = 0xFF252530

@Composable
fun CardSheet(
    card: CardInfo,
    onDismiss: () -> Unit,
    keywords: Map<String, Keyword> = emptyMap(),
    tokens: Map<String, CardInfo> = emptyMap(),
    onOpenCard: (CardInfo) -> Unit = {},
) {
    var defining by remember { mutableStateOf<Keyword?>(null) }
    var art by remember(card.id) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(card.id) { mutableStateOf(false) }
    LaunchedEffect(card.id) {
        card.imageUrl?.let {
            art = withContext(Dispatchers.IO) { CardIndex.art(it) }
            failed = art == null
        }
    }
    val rarity = Color(RARITY[card.rarity.lowercase()] ?: 0xFF9CA3AF)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 330.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(FRAME_BG))
                .border(1.dp, Color(FRAME_BORDER), RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .clickable(onClick = onDismiss)
        ) {
            // .cf-bar
            Box(Modifier.fillMaxWidth().height(6.dp).background(rarity))

            // .cf-header
            Row(
                Modifier.fillMaxWidth().padding(start = 17.dp, end = 17.dp,
                    top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(card.name, Modifier.weight(1f), color = Color.White,
                    fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 25.sp)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x80000000))
                        .padding(horizontal = 11.dp, vertical = 5.dp)
                ) {
                    Text("⚡${card.cost}", color = rarity, fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold)
                }
            }

            // .cf-art -- shown only when there IS art. A card with no image
            // was rendering as a tall black void, which reads as a broken
            // image rather than as a card that simply has none.
            if (card.imageUrl != null) {
                Box(
                    Modifier.fillMaxWidth().height(172.dp).background(Color(0x47000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        art != null -> Image(art!!, card.name, Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop)
                        failed -> Text("✦", color = Color(0xFF3A3A44), fontSize = 44.sp)
                        else -> Text("…", color = Color(0xFF3A3A44), fontSize = 28.sp)
                    }
                }
            }

            // .cf-typeline
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(rarity))
                Text(card.typeLine, Modifier.weight(1f), color = Color(0xFF8A8A93),
                    fontSize = 12.sp)
                Text(card.rarity.replaceFirstChar { it.uppercase() },
                    color = Color(0xFF55555F), fontSize = 12.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x11FFFFFF)))

            // .cf-body -- rules_text arrives already joined with " · ", and a
            // leading "When:" is split out so triggers read the way they do on
            // the card page instead of as one run-on line.
            Column(
                Modifier.fillMaxWidth().padding(start = 17.dp, end = 17.dp,
                    top = 14.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (line in card.rules.split(" · ").map { it.trim() }
                    .filter { it.isNotBlank() }) {
                    val cut = line.indexOf(": ")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (cut in 1..28) {
                            Text(line.take(cut + 1), color = Color(0xFF6B6B75),
                                fontSize = 12.sp, lineHeight = 20.sp)
                            RichRules(line.substring(cut + 2), keywords, tokens,
                                onKeyword = { defining = it }, onCard = onOpenCard)
                        } else {
                            Text("✦", color = Color(0xFF6B6B75), fontSize = 12.sp,
                                lineHeight = 20.sp)
                            RichRules(line, keywords, tokens,
                                onKeyword = { defining = it }, onCard = onOpenCard)
                        }
                    }
                }

                // .cf-stats
                val chips = buildList {
                    card.melee?.takeIf { it > 0 }?.let { add("⚔ $it melee") }
                    card.armor?.takeIf { it > 0 }?.let { add("🛡 $it armor") }
                    card.health?.takeIf { it > 0 }?.let { add("♥ $it health") }
                }
                if (chips.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in chips) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0x12FFFFFF))
                                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(c, color = Color.White, fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DFFFFFF)))
            Text("Tap to close", Modifier.fillMaxWidth()
                .padding(horizontal = 17.dp, vertical = 9.dp),
                color = Color(0xFF55555F), fontSize = 11.sp,
                style = MaterialTheme.typography.labelSmall)
        }
    }
    defining?.let { k -> KeywordSheet(k) { defining = null } }
}


/**
 * Card text with its keywords and token references made tappable.
 *
 * The main game links these and says each definition once in a glossary; a
 * player reading "Shatter" or "Gravecast" here for the first time has no
 * other way to find out what it means, and the agent's proposal is only
 * answerable if the card reads completely.
 */
@Composable
private fun RichRules(
    line: String,
    keywords: Map<String, Keyword>,
    tokens: Map<String, CardInfo>,
    onKeyword: (Keyword) -> Unit,
    onCard: (CardInfo) -> Unit,
) {
    val spans = remember(line, keywords.size, tokens.size) {
        CardIndex.spans(line, keywords, tokens)
    }
    if (spans.isEmpty()) {
        Text(line, color = Color(0xFFD8D8E0), fontSize = 13.sp, lineHeight = 20.sp)
        return
    }
    val text = buildAnnotatedString {
        var at = 0
        spans.forEachIndexed { i, (range, kw, tok) ->
            if (range.first > at) append(line.substring(at, range.first))
            pushStringAnnotation("ref", i.toString())
            withStyle(SpanStyle(color = Color(0xFFD0A028),
                textDecoration = TextDecoration.Underline)) {
                append(line.substring(range.first, range.last + 1))
            }
            pop()
            at = range.last + 1
        }
        if (at < line.length) append(line.substring(at))
    }
    ClickableText(text,
        style = TextStyle(color = Color(0xFFD8D8E0), fontSize = 13.sp,
            lineHeight = 20.sp)) { off ->
        text.getStringAnnotations("ref", off, off).firstOrNull()?.let { a ->
            val (_, kw, tok) = spans[a.item.toInt()]
            when {
                tok != null -> onCard(tok)
                kw != null -> onKeyword(kw)
            }
        }
    }
}

/** One keyword, said once -- the same shape the main game's glossary uses. */
@Composable
fun KeywordSheet(k: Keyword, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(FRAME_BG))
                .border(1.dp, Color(FRAME_BORDER), RoundedCornerShape(14.dp))
                .clickable(onClick = onDismiss)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(k.label, color = Color(0xFFD0A028), fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold)
            Text(k.short, color = Color(0xFFD8D8E0), fontSize = 13.sp, lineHeight = 20.sp)
            Text("Tap to close", color = Color(0xFF55555F), fontSize = 11.sp,
                fontStyle = FontStyle.Italic)
        }
    }
}
