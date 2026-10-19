package online.autobattle.agent.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import online.autobattle.agent.data.Secrets
import online.autobattle.agent.game.Compete
import online.autobattle.agent.game.Directive
import online.autobattle.agent.game.GameClient
import online.autobattle.agent.game.GameException
import online.autobattle.agent.game.CardInfo
import online.autobattle.agent.game.Keyword
import online.autobattle.agent.game.Memory
import online.autobattle.agent.game.Training
import online.autobattle.agent.game.TrainingService
import androidx.compose.runtime.collectAsState
import online.autobattle.agent.game.Questions
import org.json.JSONArray
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
    var typed by remember { mutableStateOf(Secrets.handle(ctx) ?: "") }
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
    var showThemes by remember { mutableStateOf(false) }
    // Where the player currently is. The agent is a person you talk to; the
    // dojo and the binder are places you go to look things up. Keeping them
    // on one scroll meant the conversation was always buried under a table.
    var place by remember { mutableStateOf(Place.AGENT) }
    var history by remember { mutableStateOf<JSONArray?>(null) }
    var trialBusy by remember { mutableStateOf(false) }
    var trialStep by remember { mutableStateOf<String?>(null) }
    var trial by remember { mutableStateOf<Compete.Trial?>(null) }
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
        // A 401 means this token names an account the coordinator no longer
        // has. Every authenticated call then fails, and because each one was
        // swallowed individually the screen kept showing the stale handle and
        // said "deck not available" against every rival -- which reads as a
        // broken server rather than as "you are signed out". Noticed here and
        // handled once, rather than at each call site.
        var expired = false
        fun watch(e: Throwable) { if (e is GameException && e.status == 401) expired = true }
        runCatching {
            withContext(Dispatchers.IO) {
                val c = GameClient(token)
                val st = c.standings()
                val mine = if (token != null)
                    runCatching { c.me() }.onFailure(::watch).getOrNull() else null
                // Everyone's decks and everyone's form, in the same refresh:
                // both are small, and fetching them on demand would put a
                // spinner behind a tap that should feel instant.
                val f = if (token != null)
                    runCatching { c.field() }.onFailure(::watch).getOrNull() else null
                val lb = if (token != null)
                    runCatching { c.leaderboard() }.onFailure(::watch).getOrNull() else null
                val hi = if (token != null)
                    runCatching { c.history() }.onFailure(::watch).getOrNull() else null
                Triple(st, mine, Triple(f, lb, hi))
            }
        }.onSuccess { (st, mine, extra) ->
            standings = st
            myDeck = mine?.optJSONObject("deck")
            val (f, lb, hi) = extra
            if (hi != null) history = hi
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
        }.onFailure { watch(it); error = readable(it) }

        if (expired) {
            // Drop the dead credential so the join screen comes back. The
            // handle is kept and pre-filled: it is almost always the name the
            // player wants again, and retyping it is the only thing standing
            // between them and being back in the league.
            Secrets.clear(ctx, Secrets.SLOT_GAME_TOKEN)
            token = null
            typed = handle
            error = null
            loaded = true
            return
        }

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
            Text("“Before we proceed \u2014 by what name shall I record you?”",
                style = MaterialTheme.typography.titleMedium)
            // A stored handle with no token means this player WAS in the
            // league and their account is gone -- a deleted player, or a
            // league that was rebuilt under them. Said plainly, and derived
            // from persisted state rather than from a transient note, which
            // vanished the moment the app was relaunched.
            if (handle.isNotBlank()) {
                Text("“You have been struck from the register. \"$handle\" is " +
                    "no longer in the league, and its decks and standing " +
                    "went with it. Joining again opens a fresh record.”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
            }
            Text("“I design and test decks on this device, then enter the " +
                "strongest against the other agents. The league is scored " +
                "every fifteen minutes.”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = typed, onValueChange = { typed = it },
                singleLine = true, enabled = !busy,
                label = { Text("Handle") },
                supportingText = { Text("3-24 characters. It will appear in the standings.") },
                modifier = Modifier.fillMaxWidth())
            GameButton(enabled = !busy && typed.trim().length >= 3,
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
                }, text = "Record it")
        } else {
            SnesMenu(place) { place = it }

            if (place == Place.AGENT) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The handle gives way, not the buttons: a long one used to
                // push "Start over" off the edge, and a clipped control is
                // worse than a shortened name.
                // A nameplate, not loose text. It was the only unframed
                // thing left on the screen, which made it read as a caption
                // for the buttons beside it rather than as who you are.
                Nameplate(handle.ifBlank { "You" }, Modifier.weight(1f))
                GameLink("Any news?", enabled = !busy) { scope.launch { refresh() } }
                GameLink("Start again", enabled = !busy) { confirmReset = true }
            }

            // The agent asks; the player answers. This is the whole decision
            // surface of the game, so it sits above the button rather than
            // behind a settings icon.
            val deckIds = myDeck?.optJSONArray("cards")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).optInt("card_id") }.toSet()
            } ?: emptySet()
            val q = if (busy) null else
                Questions.next(directive, mem, handle, standings, deckIds, idea)
            val mood = moodFor(
                working = busy,
                asking = q != null,
                barrenRuns = mem.barrenRuns,
                placement = standings?.optJSONArray("standings")?.let { rows ->
                    (0 until rows.length()).map { rows.getJSONObject(it) }
                        .firstOrNull { it.optString("handle") == handle }
                        ?.optInt("placement")
                },
                lastPlacement = mem.lastPlacement.takeIf { it > 0 },
            )

            // ONE dialogue, one face. The agent had been speaking from two
            // windows at once -- a question above and a notice below, each
            // with its own portrait -- which read as two people talking over
            // each other. Everything it has to say now goes through a single
            // frame, and what it says is chosen by priority.
            val scoredAt = standings?.optJSONObject("round")?.optString("finished_at") ?: ""
            val submittedAt = myDeck?.optString("updated_at") ?: ""
            val awaitingRound = submittedAt.isNotBlank() && scoredAt.isNotBlank() &&
                scoredAt <= submittedAt

            val line = when {
                busy -> "\u201c${progress?.step ?: "Working"}\u2026\u201d"
                q != null -> q.text
                awaitingRound -> "\u201cIt has not been played yet.\u201d"
                else -> "\u201c${directive.summary()}\u201d"
            }

            SnesWindow {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AgentPortrait(mood)
                    TypedLine(line)
                }
                if (busy) {
                    val pr = progress
                    if (pr != null) SnesProgress(pr.done, pr.total) else SnesProgress(0, 1)
                }
                if (!busy && q == null && awaitingRound) {
                    Text("\u201cThe league is scored as a whole, so your result " +
                        "arrives with the next round. Were I to build another " +
                        "now, this one would be discarded unexamined.\u201d",
                        style = snesText(13))
                    standings?.optJSONObject("round")
                        ?.optString("next_at")?.takeIf { it.isNotBlank() }?.let {
                            Text(untilRound(it) + ".",
                                style = snesText(12, androidx.compose.ui.graphics.Color(0xFF8A8AA8)))
                        }
                }
                // The answer to a rehearsal stays in the window that was
                // asked for it.
                trial?.let { t ->
                    Text("\u201c${t.deckName} is favourable against ${t.beaten} of " +
                        "the ${t.opponents}. ${t.lostTo} are unfavourable" +
                        (if (t.even > 0) ", and ${t.even} could go either way" else "") +
                        ".\u201d",
                        style = snesText(14, androidx.compose.ui.graphics.Color(0xFF8CE8A0)))
                    Text("\u201cThat is ${"%.1f".format(t.wins)} wins a round across " +
                        "${t.seeds} draws it has not seen" +
                        (t.worstAgainst?.let { ". $it remains the difficulty" } ?: "") +
                        ". A rehearsal only \u2014 the league plays its own " +
                        "games at the next round.\u201d",
                        style = snesText(12, androidx.compose.ui.graphics.Color(0xFFA8A8C8)))
                }
                // The agent names a card; the player must be able to look at
                // it before answering.
                if (q != null && q.id == "idea-card") {
                    idea?.optInt("card_id")?.takeIf { it > 0 }?.let { cid ->
                        Text("\u2022 Show me ${idea?.optString("name") ?: "the card"}",
                            style = snesText(13, androidx.compose.ui.graphics.Color(0xFFA8C8E8)),
                            modifier = Modifier.clickable {
                                scope.launch {
                                    ensureNames()
                                    cardsById[cid]?.let { c -> showCard = c }
                                }
                            })
                    }
                }

                if (!busy) SnesChoiceBox {
                    if (q != null) {
                        for (c in q.choices) {
                            SnesChoice(
                                label = c.label,
                                detail = c.blurb,
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
                                    // AGREEING TO AN IDEA STARTS THE WORK.
                                    // Saying "investigate this card" and
                                    // watching nothing happen made the answer
                                    // look ignored -- the brief changed, but
                                    // the deck on screen was still the old
                                    // one and nothing said why.
                                    // Style and breadth count as a new plan
                                    // too: they change the staple budget,
                                    // the seed pool and how much of the
                                    // budget goes into refinement, so
                                    // agreeing to an angle should set the
                                    // agent working exactly as agreeing to
                                    // a card does.
                                    val newPlan = directive.seedCard != before.seedCard ||
                                        directive.theme != before.theme ||
                                        directive.style != before.style ||
                                        directive.breadth != before.breadth ||
                                        directive.original != before.original
                                    if (newPlan && !awaitingRound) {
                                        error = null; note = null; trial = null
                                        TrainingService.start(ctx)
                                    }
                                })
                        }
                    } else {
                        if (awaitingRound) {
                            SnesChoice(
                                label = if (trialBusy) (trialStep ?: "Playing it out\u2026")
                                        else "Show me what it can do",
                                enabled = !trialBusy,
                                onClick = {
                                    error = null; note = null; trial = null
                                    trialBusy = true
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.Default) {
                                                Compete.testDeck(GameClient(token), handle,
                                                    onProgress = { trialStep = it.step })
                                            }
                                        }.onSuccess { trial = it }
                                         .onFailure { error = readable(it) }
                                        trialBusy = false; trialStep = null
                                    }
                                })
                        }
                        SnesChoice(
                            // "Build another one anyway" did not say what it
                            // would do with the deck you already have, and
                            // sat as the loudest thing on the screen while
                            // the agent was asking you to wait. It says what
                            // it costs now, and carries the warning as its
                            // detail line.
                            label = if (awaitingRound) "Discard it and start a new deck"
                                    else "Get to work",
                            detail = if (awaitingRound)
                                "Throws away the deck that has not played yet." else null,
                            enabled = !trialBusy,
                            onClick = {
                                error = null; note = null; trial = null
                                TrainingService.start(ctx)
                            })
                    }
                }
                if (trialBusy) SnesProgress(0, 1)
            }

            if (!busy && q == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameLink("Build around\u2026") { showThemes = true }
                    GameLink("Let's talk") {
                        mem = mem.copy(answered = emptySet(), dismissed = emptySet())
                        Memory.save(ctx, mem)
                    }
                }
            }

            note?.let {
                Text(it, style = snesText(13, androidx.compose.ui.graphics.Color(0xFFFFE28C)))
            }
            }   // end Place.AGENT

        // The overlays belong to no place: a card tapped in the binder opens
        // the same popup as one tapped in the dialogue.
        showCard?.let { c ->
            // Drawn by the main game's own renderer, so the card looks exactly
            // like it does there. Which names inside the text are linkable is
            // its decision too -- it marks them data-keyword / data-card-id
            // and we honour whatever it marked.
            CardWeb(
                card = c,
                onDismiss = { showCard = null },
                keywords = keywords,
                cardsById = cardsById,
                onOpenCard = { showCard = it },
            )
        }

        // Asking what the agent should build, instead of waiting to be asked.
        // The agent proposes a theme on its own, but only one at a time and
        // only once per scored round, so a player who already knows they want
        // poison had no way to say so.
        if (showThemes) {
            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                onDismissRequest = { showThemes = false },
                title = { Text("“What shall I build around?”") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("“I will still select the remaining ninety-nine on " +
                            "merit. A theme governs what the deck is built " +
                            "around, not what may enter it.”",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        fun choose(tag: String?, label: String?) {
                            directive = directive.copy(
                                theme = tag, themeLabel = label,
                                seedCard = null, seedCardName = null)
                            Directive.save(ctx, directive)
                            mem = mem.copy(triedThemes = mem.triedThemes +
                                listOfNotNull(tag))
                            Memory.save(ctx, mem)
                            showThemes = false
                        }
                        // Plain rows rather than TextButtons: sixteen
                        // buttons at Material's 48dp minimum turned a list
                        // into a scroll marathon, and the chosen one was
                        // hard to spot among them.
                        @Composable
                        fun row(tag: String?, label: String) {
                            val on = directive.theme == tag
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { choose(tag, if (tag == null) null else label) }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (on) "◆" else "  ",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(8.dp))
                                Text(label.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (on) GoldBright
                                            else MaterialTheme.colorScheme.primary)
                            }
                        }
                        row(null, "Whatever looks best")
                        for ((tag, label) in Questions.THEMES) row(tag, label)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showThemes = false }) { Text("That's all") }
                })
        }

        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text("“Discard it?”") },
                text = { Text("“I would discard the present deck and take a " +
                    "fresh one. Your name and your record remain.”") },
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
                    }) { Text("Do it") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmReset = false }) { Text("Never mind") }
                })
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }

            if (place == Place.BINDER) myDeck?.let { d ->
                // The binder needs every card's name and art, so the
                // catalogue is pulled on arrival rather than behind a
                // "show cards" tap -- the page IS the content here.
                LaunchedEffect(d.toString()) { ensureNames() }
                BinderPage(d, cardsById) { showCard = it }
            }
        }

        // Signed out, the standings are the only thing worth showing, so they
        // stay on the one screen there is.
        if (token == null || place == Place.DOJO) {
        // Your own record first, before the table of everyone else. The
        // question a player comes here with is "is my deck getting better",
        // and an average over a window cannot answer it -- a steady climb
        // and a flat line with one good round average the same.
        history?.takeIf { it.length() > 0 }?.let { h ->
            SnesWindow {
                Text("Your last ${minOf(h.length(), 8)} rounds",
                    style = snesText(14, androidx.compose.ui.graphics.Color(0xFFFFE28C)))
                for (i in 0 until minOf(h.length(), 8)) {
                    val r = h.getJSONObject(i)
                    val place_ = r.optInt("placement")
                    val of = r.optInt("of")
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("#${r.optInt("round_id")}", Modifier.width(54.dp),
                            style = snesText(13, androidx.compose.ui.graphics.Color(0xFF8A8AA8)))
                        Text("${place_}/${of}", Modifier.width(54.dp),
                            style = snesText(13,
                                // Top third of the field is worth marking.
                                if (of > 0 && place_ * 3 <= of)
                                    androidx.compose.ui.graphics.Color(0xFF8CE8A0)
                                else androidx.compose.ui.graphics.Color(0xFFF2EFE4)))
                        Text("${"%.0f".format(r.optDouble("wins"))}-" +
                             "${"%.0f".format(r.optDouble("losses"))}-" +
                             "${"%.0f".format(r.optDouble("draws"))}",
                            style = snesText(13))
                    }
                    // The deck's name on its own line. Sharing the row with
                    // the numbers clipped every name worth reading -- and
                    // the name is how a player tells one run from another.
                    Text("r${r.optInt("revision")}  ${r.optString("deck_name")}",
                        Modifier.fillMaxWidth().padding(start = 54.dp, bottom = 3.dp),
                        style = snesText(12,
                            androidx.compose.ui.graphics.Color(0xFFA8A8C8)),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val s = standings
        val rows = s?.optJSONArray("standings")
        if (!loaded) {
            Text("“Let me see where matters stand…”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (rows == null || rows.length() == 0) {
            Text("No round has been scored yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val round = s.optJSONObject("round")
            SnesWindow {
            Text("Round ${round?.optInt("id") ?: 0} \u2014 ${round?.optInt("decks") ?: 0} decks",
                style = snesText(14, androidx.compose.ui.graphics.Color(0xFFFFE28C)))
            // Without this the table looks frozen: a player trains, submits,
            // and then stares at an unchanged standing with nothing saying a
            // round is coming.
            myForm?.let { f ->
                val n = f.optInt("rounds")
                if (n >= 2) {
                    Text("You: ${"%.1f".format(f.optDouble("avg_place"))} average " +
                        "place over the last $n rounds, best ${f.optInt("best_place")}.",
                        style = snesText(13, androidx.compose.ui.graphics.Color(0xFF8CE8A0)))
                }
            }
            round?.optString("next_at")?.takeIf { it.isNotBlank() }?.let { nx ->
                // Relative, not "03:17 UTC". A player holding a phone should
                // not have to do timezone arithmetic to find out whether it
                // is worth waiting.
                Text(untilRound(nx), style = snesText(12, androidx.compose.ui.graphics.Color(0xFF8A8AA8)))
            }
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val who = r.optString("handle")
                val mine = who == handle
                // Your own row is marked by the cursor and lit type, the
                // same way the dialogue marks the option you are on. Scanning
                // a table of twenty for your own name is the one thing this
                // page should never ask.
                Row(Modifier.fillMaxWidth()
                    .background(
                        if (mine) androidx.compose.ui.graphics.Color(0x33FFE28C) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable {
                        openDeck = if (openDeck == who) null else who
                        if (openDeck != null) scope.launch { ensureNames() }
                    }
                    .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (mine) "\u25b6" else " ", Modifier.width(14.dp),
                        style = snesText(12, androidx.compose.ui.graphics.Color(0xFFFFE28C)))
                    Text("${r.optInt("placement")}".padStart(2), Modifier.width(26.dp),
                        style = snesText(13,
                            if (mine) androidx.compose.ui.graphics.Color(0xFFFFE28C) else androidx.compose.ui.graphics.Color(0xFFF2EFE4)))
                    Column(Modifier.weight(1f)) {
                        Text(r.optString("handle") +
                            if (r.optInt("is_bot") == 1) "  (house)" else "",
                            style = snesText(13,
                                if (mine) androidx.compose.ui.graphics.Color(0xFFFFE28C) else androidx.compose.ui.graphics.Color(0xFFF2EFE4)),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.optString("deck_name"),
                            style = snesText(11, androidx.compose.ui.graphics.Color(0xFF8A8AA8)),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${r.optInt("wins")}-${r.optInt("losses")}-${r.optInt("draws")}",
                        style = snesText(12,
                            if (mine) androidx.compose.ui.graphics.Color(0xFFFFE28C) else androidx.compose.ui.graphics.Color(0xFFD8D8E8)))
                }
                if (openDeck == who) {
                    val cards = field[who]?.optJSONArray("cards")
                    if (cards == null) {
                        Text("    their deck is not on file",
                            style = snesText(12, androidx.compose.ui.graphics.Color(0xFF8A8AA8)))
                    } else {
                        // Their deck as pockets too. Twenty card names in a
                        // list tell you nothing at a glance; the art tells
                        // you what you are up against before you read a word.
                        Box(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 8.dp)) {
                            Column {
                                RivalPockets(cards, cardsById) { showCard = it }
                            }
                        }
                    }
                }
            }
            }   // end the league table window
        }
        }   // end Place.DOJO
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
