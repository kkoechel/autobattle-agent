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
import online.autobattle.agent.game.Directive
import online.autobattle.agent.game.GameClient
import online.autobattle.agent.game.Memory
import online.autobattle.agent.game.Questions
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
    var myDeck by remember { mutableStateOf<JSONObject?>(null) }
    var names by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var showDeck by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var directive by remember { mutableStateOf(Directive.load(ctx)) }
    var mem by remember { mutableStateOf(Memory.load(ctx)) }
    var idea by remember { mutableStateOf<JSONObject?>(null) }

    // Standings and the player's own deck only. Card NAMES are a ~1MB
    // catalogue, and fetching them here made the whole screen wait on a
    // download it only needs if the deck list is expanded -- for twenty
    // seconds the app showed "No round has been scored yet", which is not
    // slow, it is wrong.
    suspend fun refresh() {
        runCatching {
            withContext(Dispatchers.IO) {
                val c = GameClient(token)
                c.standings() to (if (token != null) runCatching { c.me() }.getOrNull() else null)
            }
        }.onSuccess { (st, mine) ->
            standings = st
            myDeck = mine?.optJSONObject("deck")
            // Remember where we stood, so next time the agent can notice it
            // has CHANGED. This is the whole basis of the triggered
            // questions -- without it the agent cannot tell a slip from a
            // steady position.
            val rows = st.optJSONArray("standings")
            val all = (0 until (rows?.length() ?: 0)).map { rows!!.getJSONObject(it) }
            val mineRow = all.firstOrNull { it.optString("handle") == handle }
            val lead = all.firstOrNull { it.optString("handle") != handle }
            mem = mem.copy(
                lastPlacement = mineRow?.optInt("placement") ?: mem.lastPlacement,
                lastLeader = lead?.optString("handle") ?: mem.lastLeader)
            Memory.save(ctx, mem)
        }.onFailure { error = readable(it) }
        if (idea == null && token != null) {
            runCatching { withContext(Dispatchers.IO) { GameClient(token).idea() } }
                .onSuccess { idea = it }
        }
        loaded = true
    }

    /** Names are pulled the first time the player actually opens the list. */
    suspend fun ensureNames() {
        if (names.isNotEmpty()) return
        runCatching { withContext(Dispatchers.IO) { GameClient(token).cardNames() } }
            .onSuccess { names = it }
    }

    LaunchedEffect(token) { refresh() }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        if (token == null) {
            Text("Join the Agent league", style = MaterialTheme.typography.titleMedium)
            Text("Pick a handle. Your agent designs and tests decks on this " +
                "phone, then plays the best one against everyone else's " +
                "agents. A new round is scored every 15 minutes.",
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
                TextButton(enabled = !busy, onClick = { confirmReset = true }) {
                    Text("Start over")
                }
            }

            // The agent asks; the player answers. This is the whole decision
            // surface of the game, so it sits above the button rather than
            // behind a settings icon.
            val deckIds = myDeck?.optJSONArray("cards")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).optInt("card_id") }.toSet()
            } ?: emptySet()
            val q = if (busy) null else
                Questions.next(directive, mem, handle, standings, deckIds, idea)
            if (q != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(q.text, style = MaterialTheme.typography.bodyLarge)
                        for (c in q.choices) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val before = directive
                                    directive = c.apply(directive)
                                    Directive.save(ctx, directive)
                                    // An answer that changed nothing is a
                                    // decline: remember it so the same
                                    // trigger does not ask again next
                                    // refresh. Onboarding questions are not
                                    // dismissible, so they just get marked
                                    // answered either way.
                                    val declined = directive == before && q.dismissible
                                    mem = mem.copy(
                                        answered = mem.answered + q.id,
                                        dismissed = if (declined) mem.dismissed + q.id
                                                    else mem.dismissed - q.id,
                                        triedThemes = mem.triedThemes +
                                            listOfNotNull(directive.theme),
                                        lastIdeaRound = if (q.id.startsWith("idea"))
                                            (standings?.optJSONObject("round")
                                                ?.optInt("id") ?: 0)
                                            else mem.lastIdeaRound,
                                        barrenRuns = if (q.id == "stuck") 0
                                                     else mem.barrenRuns)
                                    Memory.save(ctx, mem)
                                    if (q.id.startsWith("idea")) idea = null
                                }) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(c.label,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(c.blurb,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            } else if (!busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(directive.summary(), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        mem = mem.copy(answered = emptySet(), dismissed = emptySet())
                        Memory.save(ctx, mem)
                    }) { Text("Re-brief") }
                }
            }

            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true; error = null; note = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.Default) {
                            val c = GameClient(token)
                            val r = Compete.train(c, handle, directive) { progress = it }
                            progress = Compete.Progress("Submitting", 1, 1)
                            val rev = withContext(Dispatchers.IO) {
                                c.putDeck(r.name, r.cards, r.plan)
                            }
                            r to rev
                        }
                    }.onSuccess { (r, rev) ->
                        mem = mem.copy(
                            barrenRuns = if (r.climbed == 0) mem.barrenRuns + 1 else 0)
                        Memory.save(ctx, mem)
                        note = "Submitted \"${r.name}\" (revision $rev). " +
                            "Checked on fresh matchups it won " +
                            "${"%.1f".format(r.wins)} of ${r.opponents} — " +
                            "picked from ${r.screened} designs, improved " +
                            "${r.climbed} time(s). The league scores every " +
                            "deck itself, so your standing may differ." +
                            if (r.relaxed) " (It could not avoid the field's " +
                                "cards this time and built the best deck it " +
                                "could instead.)" else ""
                        refresh()
                    }.onFailure { error = readable(it) }
                    progress = null
                    busy = false
                }
            }) { Text(if (busy) "Your agent is working…" else "Send your agent to work") }

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

        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text("Start over?") },
                text = { Text("Your agent throws away its current deck and " +
                    "takes a fresh one. Your handle and past results are kept.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmReset = false; busy = true; error = null; note = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { GameClient(token).resetDeck() }
                            }.onSuccess {
                                note = "Started over with \"${it.optString("name")}\"."
                                mem = Memory()
                                Memory.save(ctx, mem)
                                directive = Directive()
                                Directive.save(ctx, directive)
                                refresh()
                            }.onFailure { error = readable(it) }
                            busy = false
                        }
                    }) { Text("Start over") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
                })
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }

            myDeck?.let { d ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Your agent built: ${d.optString("name")}",
                            style = MaterialTheme.typography.titleSmall)
                        Text("revision ${d.optInt("revision")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        showDeck = !showDeck
                        if (showDeck) scope.launch { ensureNames() }
                    }) { Text(if (showDeck) "Hide" else "Show cards") }
                }
                if (showDeck) {
                    val cards = d.optJSONArray("cards")
                    val entries = (0 until (cards?.length() ?: 0))
                        .map { cards!!.getJSONObject(it) }
                        .sortedByDescending { it.optInt("quantity") }
                    for (e in entries) {
                        val id = e.optInt("card_id")
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${e.optInt("quantity")}x",
                                style = MaterialTheme.typography.bodySmall)
                            Text(names[id] ?: "loading…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        val s = standings
        val rows = s?.optJSONArray("standings")
        if (!loaded) {
            Text("Loading the league…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (rows == null || rows.length() == 0) {
            Text("No round has been scored yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val round = s.optJSONObject("round")
            Text("Round ${round?.optInt("id") ?: 0} — ${round?.optInt("decks") ?: 0} decks",
                style = MaterialTheme.typography.titleSmall)
            // Without this the table looks frozen: a player trains, submits,
            // and then stares at an unchanged standing with nothing saying a
            // round is coming.
            round?.optString("next_at")?.takeIf { it.isNotBlank() }?.let { nx ->
                // Relative, not "03:17 UTC". A player holding a phone should
                // not have to do timezone arithmetic to find out whether it
                // is worth waiting.
                Text(untilRound(nx),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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


/**
 * "Next round in 4 min", from the server's UTC "Y-m-d H:i:s".
 *
 * Falls back to saying nothing useful rather than guessing if the string
 * cannot be parsed: a wrong countdown is worse than no countdown.
 */
private fun untilRound(utc: String): String = try {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    val mins = ((fmt.parse(utc)!!.time - System.currentTimeMillis()) / 60000L).toInt()
    when {
        mins <= 0 -> "Next round is being scored now"
        mins == 1 -> "Next round in about a minute"
        else -> "Next round in about $mins minutes"
    }
} catch (e: Exception) {
    "A round is scored every 15 minutes"
}
