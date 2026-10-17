package online.autobattle.agent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import online.autobattle.agent.game.CardIndex
import online.autobattle.agent.game.CardInfo

/**
 * The card, as a card.
 *
 * The agent proposes cards by name, and a name is not enough to decide on:
 * "do you want me to build around Rabbit Mob?" is unanswerable if you have
 * never seen it. Art, cost, stats and text, the same things the main game
 * shows, so the question can actually be answered.
 */
@Composable
fun CardSheet(card: CardInfo, onDismiss: () -> Unit) {
    var art by remember(card.id) { mutableStateOf<ImageBitmap?>(null) }
    var tried by remember(card.id) { mutableStateOf(false) }

    LaunchedEffect(card.id) {
        card.imageUrl?.let { art = withContext(Dispatchers.IO) { CardIndex.art(it) } }
        tried = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(card.name, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium)
                Text("${card.cost}", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    art != null -> Image(art!!, card.name,
                        Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        contentScale = ContentScale.Fit)
                    // Absent art is not an error worth shouting about -- the
                    // text below is the part that answers the question.
                    !tried -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> {}
                }
                Text(card.typeLine, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                card.statLine?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                if (card.rules.isNotBlank()) {
                    Text(card.rules, style = MaterialTheme.typography.bodyMedium)
                }
                Text(card.rarity.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
}
