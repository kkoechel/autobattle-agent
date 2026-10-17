package online.autobattle.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import online.autobattle.agent.data.Secrets
import online.autobattle.agent.game.Compete
import online.autobattle.agent.game.GameClient
import org.json.JSONObject

/**
 * The Agent game: your deck against everybody else's, scored server-side.
 *
 * Training is explicitly a button rather than something that happens in the
 * background. It is tens of thousands of simulated matches on a phone, which
 * is real battery and real heat, and a game that quietly does that while the
 * screen is off is a game that gets uninstalled.
 */
@Composable
fun ArenaPane(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf(Secrets.load(ctx, Secrets.SLOT_GAME_TOKEN)) }
    var handle by remember { mutableStateOf(Secrets.handle(ctx) ?: "") }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Compete.Progress?>(null) }
    var standings by remember { mutableStateOf<JSONObject?>(null) }

    suspend fun refresh() {
        runCatching { withContext(Dispatchers.IO) { GameClient(token).standings() } }
            .onSuccess { standings = it }
            .onFailure { error = readable(it) }
    }

    LaunchedEffect(token) { refresh() }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        if (token == null) {
            Text("Join the Agent league", style = MaterialTheme.typography.titleMedium)
            Text("Pick a handle. Your agent builds a deck on this phone and " +
                "plays it against everyone else's agents. A new round is " +
                "scored every 15 minutes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = typed, onValueChange = { typed = it },
                singleLine = true, enabled = !busy,
                label = { Text("Handle") },
                supportingText = { Text("3-24 characters. Everyone can see it.") },
                modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && typed.trim().length >= 3,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { GameClient.join(typed.trim()) }
                        }.onSuccess {
                            val t = it.getString("token")
                            Secrets.save(ctx, t, Secrets.SLOT_GAME_TOKEN)
                            Secrets.saveHandle(ctx, it.getString("handle"))
                            handle = it.getString("handle")
                            token = t
                        }.onFailure { error = readable(it) }
                        busy = false
                    }
                }) { Text("Join") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(handle.ifBlank { "You" },
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { scope.launch { refresh() } }) {
                    Text("Refresh")
                }
            }

            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true; error = null; note = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.Default) {
                            val c = GameClient(token)
                            val r = Compete.train(c, handle) { progress = it }
                            progress = Compete.Progress("Submitting", 1, 1)
                            val rev = withContext(Dispatchers.IO) {
                                c.putDeck(r.name, r.cards, r.plan)
                            }
                            r to rev
                        }
                    }.onSuccess { (r, rev) ->
                        note = "Submitted \"${r.name}\" (revision $rev). " +
                            "Checked on fresh matchups it won " +
                            "${"%.1f".format(r.wins)} of ${r.opponents} — " +
                            "picked from ${r.screened} designs, improved " +
                            "${r.climbed} time(s). The league scores every " +
                            "deck itself, so your standing may differ."
                        refresh()
                    }.onFailure { error = readable(it) }
                    progress = null
                    busy = false
                }
            }) { Text(if (busy) "Training…" else "Train and submit a deck") }

            progress?.let { p ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.step, style = MaterialTheme.typography.bodySmall)
                    if (p.total > 1) {
                        LinearProgressIndicator(progress = { p.done / p.total.toFloat() },
                            modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            note?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }

        HorizontalDivider()
        val s = standings
        val rows = s?.optJSONArray("standings")
        if (rows == null || rows.length() == 0) {
            Text("No round has been scored yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val round = s.optJSONObject("round")
            Text("Round ${round?.optInt("id") ?: 0} — ${round?.optInt("decks") ?: 0} decks",
                style = MaterialTheme.typography.titleSmall)
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val mine = r.optString("handle") == handle
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${r.optInt("placement")}.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal)
                    Column(Modifier.weight(1f)) {
                        Text(r.optString("handle") +
                            if (r.optInt("is_bot") == 1) "  (house)" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal)
                        Text(r.optString("deck_name"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${r.optInt("wins")}-${r.optInt("losses")}-${r.optInt("draws")}",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
