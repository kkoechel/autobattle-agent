package online.autobattle.agent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import online.autobattle.agent.game.CardIndex
import online.autobattle.agent.game.CardInfo
import org.json.JSONObject

/**
 * The deck as a binder page rather than a list of names.
 *
 * A hundred-card deck is about twenty distinct cards, which is a page of
 * pockets -- and a player recognises their deck by its art long before they
 * read a line of it. The count sits under each pocket the way it would be
 * written under a sleeve.
 *
 * Two columns, not three: the card art is landscape (1536x1024 on the site),
 * so three across on a phone leaves each one too small to recognise, which
 * defeats the point of showing art at all.
 */
@Composable
fun BinderPage(
    deck: JSONObject,
    cardsById: Map<Int, CardInfo>,
    onOpen: (CardInfo) -> Unit,
) {
    val entries = remember(deck.toString(), cardsById.size) {
        val arr = deck.optJSONArray("cards") ?: return@remember emptyList()
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { it.optInt("card_id") to it.optInt("quantity") }
            // Most-played first: the shape of a deck is its playsets, and a
            // list sorted by id tells a player nothing about their own deck.
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }
                .thenBy { cardsById[it.first]?.name ?: "" })
    }
    if (entries.isEmpty()) return

    SnesWindow {
        Text("${deck.optString("name")} — revision ${deck.optInt("revision")}",
            style = snesText(14, Color(0xFFFFE28C)))
        Text("${entries.sumOf { it.second }} cards, ${entries.size} distinct",
            style = snesText(12, Color(0xFF8A8AA8)))
        Spacer(Modifier.height(2.dp))
        for (row in entries.chunked(2)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((id, qty) in row) {
                    Pocket(cardsById[id], qty, Modifier.weight(1f)) {
                        cardsById[id]?.let(onOpen)
                    }
                }
                // Keeps a final odd card the same width as the rest instead
                // of letting it stretch across the page.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Pocket(card: CardInfo?, qty: Int, modifier: Modifier, onClick: () -> Unit) {
    var art by remember(card?.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(card?.id) {
        val path = card?.imageUrl ?: return@LaunchedEffect
        art = withContext(Dispatchers.IO) { CardIndex.art(path) }
    }
    Column(modifier.clickable(enabled = card != null, onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.45f)
                .background(Color(0xFF0A0A12))
                .padding(2.dp)
                .background(Color(0xFF191933)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                art != null -> Image(art!!, null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop)
                // No art is a real state -- retired cards have none -- so it
                // says so quietly rather than spinning forever.
                card?.imageUrl == null -> Text("no art",
                    style = snesText(11, Color(0xFF55556A)))
                else -> Text("…", style = snesText(13, Color(0xFF55556A)))
            }
            // The count, written on the sleeve.
            Box(
                Modifier.align(Alignment.BottomEnd).padding(3.dp)
                    .background(Color(0xCC0A0A12))
                    .border(1.dp, Color(0x66F2EFE4))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text("×$qty", style = snesText(13, Color(0xFFFFE28C)))
            }
        }
        Text(
            card?.name ?: "loading…",
            Modifier.fillMaxWidth().padding(top = 3.dp),
            style = snesText(12, Color(0xFFD8D8E8)),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
