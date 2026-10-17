package online.autobattle.agent.ui

import androidx.compose.foundation.clickable
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
import online.autobattle.agent.game.CardInfo
import online.autobattle.agent.game.Keyword
import online.autobattle.agent.game.Memory
import online.autobattle.agent.game.Training
import online.autobattle.agent.game.TrainingService
import androidx.compose.runtime.collectAsState
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
    var uiBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    // Observed from the service, so the screen can be left and come back to
    // a run that kept going.
    val running by Training.running.collectAsState()
    val progress by Training.progress.collectAsState()
    val finished by Training.result.collectAsState()
    var standings by remember { mutableStateOf<JSONObject?>(null) }
    var myDeck by remember { mutableStateOf<JSONObject?>(null) }
    var cardsById by remember { mutableStateOf<Map<Int, CardInfo>>(emptyMap()) }
    var showCard by remember { mutableStateOf<CardInfo?>(null) }
    var keywords by remember { mutableStateOf<Map<String, Keyword>>(emptyMap()) }
    var showDeck by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var directive by remember { mutableStateOf(Directive.load(ctx)) }
    var mem by remember { mutableStateOf(Memory.load(ctx)) }
    var idea by remember { mutableStateOf<JSONObject?>(null) }
    // handle -> that player's current deck, so a rival's list can be opened
    // from the standings without another request.
    var field by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var openDeck by remember { mutableStateOf<String?>(null) }
    var myForm by remember { mutableStateOf<JSONObject?>(null) }

    // Standings and the player's own deck only. Card NAMES are a ~1MB
    // catalogue, and fetching them here made the whole screen wait on a
    // download it only needs if the deck list is expanded -- for twenty
    // seconds the app showed "No round has been scored yet", which is not
    // slow, it is wrong.
    val busy = uiBusy || running

    suspend fun refresh() {
        runCatching {
            withContext(Dispatchers.IO) {
                val c = GameClient(token)
                val st = c.standings()
                val mine = if (token != null) runCatching { c.me() }.getOrNull() else null
                // Everyone's decks and everyone's form, in the same refresh:
                // both are small, and fetching them on demand would put a
                // spinner behind a tap that should feel instant.
                val f = if (token != null) runCatching { c.field() }.getOrNull() else null
                val lb = if (token != null) runCatching { c.leaderboard() }.getOrNull() else null
                Triple(st, mine, f to lb)
            }
        }.onSuccess { (st, mine, extra) ->
            standings = st
            myDeck = mine?.optJSONObject("deck")
            val (f, lb) = extra
            if (f != null) {
                field = (0 until f.length()).associate {
                    val d = f.getJSONObject(it); d.optString("handle") to d
                }
            }
            if (lb != null) {
                myForm = (0 until lb.length()).map { lb.getJSONObject(it) }
                    .firstOrNull { it.optString("handle") == handle }
            }
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

    /** The catalogue is pulled the first time a card actually needs showing. */
    suspend fun ensureNames() {
        if (cardsById.isNotEmpty()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val c = GameClient(token)
                c.cardIndex() to runCatching { c.keywords() }.getOrDefault(emptyMap())
            }
        }.onSuccess { (idx, kw) -> cardsById = idx; keywords = kw }
    }

    LaunchedEffect(token) { refresh() }

    // A run can finish while this screen is away; picking the result up here
    // means the outcome is shown whenever the player comes back, not only if
    // they happened to be watching.
    LaunchedEffect(finished) {
        val r = finished ?: return@LaunchedEffect
        if (r.error != null) {
            error = r.error
        } else if (r.outcome != null) {
            val o = r.outcome
            note = "Submitted \"${o.name}\" (revision ${r.revision}). " +
                "Checked on fresh matchups it won ${"%.1f".format(o.wins)} of " +
                "${o.opponents} — picked from ${o.screened} designs, improved " +
                "${o.climbed} time(s). The league scores every deck itself, so " +
                "your standing may differ." +
                if (o.relaxed) " (It could not avoid the field's cards this time " +
                    "and built the best deck it could instead.)" else ""
            mem = mem.copy(barrenRuns = if (o.climbed == 0) mem.barrenRuns + 1 else 0)
            Memory.save(ctx, mem)
            refresh()
        }
        Training.clearResult()
    }

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
                    uiBusy = true; error = null
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
                        uiBusy = false
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
                        // The agent names a card; the player must be able to
                        // look at it before answering.
                        idea?.optInt("card_id")?.takeIf {
                            it > 0 && q.id == "idea-card"
                        }?.let { cid ->
                            TextButton(onClick = {
                                scope.launch {
                                    ensureNames()
                                    cardsById[cid]?.let { c -> showCard = c }
                                }
                            }) { Text("See ${idea?.optString("name") ?: "the card"}") }
                        }
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
                error = null; note = null
                // Hand the work to the service and return immediately. The
                // screen is now an observer, so leaving it is harmless.
                TrainingService.start(ctx)
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

        showCard?.let { c ->
            // Only TOKENS are linkable by name. Matching every card name would
            // turn ordinary words into links -- a card called "Shield" would
            // light up half the game's text.
            val tokens = remember(cardsById) {
                cardsById.values
                    .filter { it.rarity.equals("token", true) ||
                              it.rarity.equals("manifest", true) }
                    .associateBy { it.name.lowercase() }
            }
            CardSheet(
                card = c,
                onDismiss = { showCard = null },
                keywords = keywords,
                tokens = tokens,
                onOpenCard = { showCard = it },
            )
        }

        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text("Start over?") },
                text = { Text("Your agent throws away its current deck and " +
                    "takes a fresh one. Your handle and past results are kept.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmReset = false; uiBusy = true; error = null; note = null
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
                            uiBusy = false
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
                            Text(cardsById[id]?.name ?: "loading…",
                                Modifier.clickable {
                                    cardsById[id]?.let { c -> showCard = c }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
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
            myForm?.let { f ->
                val n = f.optInt("rounds")
                if (n >= 2) {
                    Text("You: ${"%.1f".format(f.optDouble("avg_place"))} average " +
                        "place over the last $n rounds, best ${f.optInt("best_place")}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
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
                val who = r.optString("handle")
                val mine = who == handle
                Row(Modifier.fillMaxWidth()
                    .clickable {
                        openDeck = if (openDeck == who) null else who
                        if (openDeck != null) scope.launch { ensureNames() }
                    },
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
                if (openDeck == who) {
                    val cards = field[who]?.optJSONArray("cards")
                    if (cards == null) {
                        Text("    deck not available", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        for (e in (0 until cards.length()).map { cards.getJSONObject(it) }
                            .sortedByDescending { it.optInt("quantity") }) {
                            Row(Modifier.fillMaxWidth().padding(start = 22.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("${e.optInt("quantity")}x",
                                    style = MaterialTheme.typography.bodySmall)
                                Text(cardsById[e.optInt("card_id")]?.name ?: "loading…",
                                    Modifier.clickable {
                                        cardsById[e.optInt("card_id")]?.let { c -> showCard = c }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
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
