package online.autobattle.agent.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import online.autobattle.agent.data.Secrets
import online.autobattle.agent.engine.Catalogue
import online.autobattle.agent.game.Voice
import online.autobattle.agent.game.CardIndex
import online.autobattle.agent.engine.Repair
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
 * runCatching, except that it lets cancellation through.
 *
 * kotlin's runCatching catches Throwable, and CancellationException is a
 * Throwable -- so a coroutine cancelled while suspended has its cancellation
 * SWALLOWED and reported as a failure. On this screen that surfaced as the
 * player being shown "The coroutine scope left the composition" in red, twice,
 * on every cold start: the first refresh was still in flight when the resume
 * trigger asked for another, the first LaunchedEffect was cancelled as it
 * should be, and the cancellation was then displayed as if the server had
 * failed.
 *
 * Swallowing it is also wrong beyond the cosmetics -- it breaks structured
 * concurrency, because the cancelled block carries on to its own onFailure
 * instead of unwinding.
 */
private inline fun <T> runCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }

/**
 * A game being watched, and the run that produced it.
 *
 * The two travel together because the replay needs the DECKS, and the decks
 * differ by source: a rehearsal plays today's lists, a league replay plays the
 * lists that were scored. Holding the game alone meant the overlay guessed at
 * the decks from whichever run was last in hand.
 */
data class Watch(val game: Compete.Game, val from: Compete.Trial)

/**
 * "Watch a game that demonstrates..." -- the same menu wherever games exist.
 *
 * Shared rather than written at each site because the two differ only in
 * where the games came from, and the earlier pair had already drifted: the
 * Dojo offered a worst-matchup pick the rehearsal did not.
 *
 * Laid out two to a row rather than one long row: the labels are phrases
 * now, and a single row ran them off the edge of the phone, where they were
 * both unreachable and invisible.
 */
@Composable
private fun WatchPicker(t: Compete.Trial, onPick: (Compete.Game) -> Unit) {
    val picks = remember(t) { Compete.picks(t.games) }
    if (picks.isEmpty()) return
    Text("Watch a game:",
        style = snesText(12, androidx.compose.ui.graphics.Color(0xFF8A8AA8)))
    for (pair in picks.chunked(2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (p in pair) GameLink(p.label) { onPick(p.game) }
        }
    }
}

/**
 * The Agent game: your deck against everybody else's, scored server-side.
 *
 * Training is explicitly a button rather than something that happens in the
 * background. It is tens of thousands of simulated matches on a phone, which
 * is real battery and real heat, and a game that quietly does that while the
 * screen is off is a game that gets uninstalled.
 */
@Composable
fun ArenaPane(
    modifier: Modifier = Modifier,
    /**
     * Which avatar should stand behind the whole app, or null for none.
     *
     * Reported rather than drawn here: inside this pane the picture stops at
     * the pane's edges, and the point of it is that it does not.
     */
    onBackdrop: (Avatar?) -> Unit = {},
) {
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
    // A plan agreed while the current deck is still waiting for its round.
    // Without this the agent took the instruction, could not act on it
    // because of the brake, and simply asked a different question -- which
    // from the outside looked like the buttons toggling between themselves.
    var planPending by remember { mutableStateOf(false) }
    // What the agent has just finished, held until the player acknowledges
    // it. Without the beat, accepting an angle and having a new question
    // appear a few seconds later reads as the options toggling between
    // themselves -- the work in between is invisible because it is fast.
    var justBuilt by remember { mutableStateOf<String?>(null) }
    // A BAD result needs somewhere to go. The beat above offered one
    // choice -- "Good" -- which is the right acknowledgement for a deck
    // that worked and a dead end for one that won nothing: the plan was
    // still set, so the agent went back to offering angles on an idea the
    // player had just watched fail. Kept as state rather than re-derived
    // because the outcome is cleared as soon as it is read.
    var builtPoorly by remember { mutableStateOf(false) }
    // The player asking the agent to talk, rather than waiting to be asked.
    var askNow by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var newsBusy by remember { mutableStateOf(false) }
    // A watched game carries the run it came from. There are two sources
    // now -- a rehearsal of the current deck, and a scored league round
    // replayed from its snapshot -- and the overlay must draw the decks
    // that played THAT game, not whichever run happens to be in hand.
    var watching by remember { mutableStateOf<Watch?>(null) }
    // One scored round, re-played on this device from its snapshot.
    var roundReplay by remember { mutableStateOf<Compete.RoundReplay?>(null) }
    var roundBusy by remember { mutableStateOf<Int?>(null) }
    var roundStep by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<JSONArray?>(null) }
    var banned by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var rawCards by remember { mutableStateOf<JSONArray?>(null) }
    var banList by remember { mutableStateOf<JSONArray?>(null) }
    // The newest ban this player has already been told about, so the
    // announcement fires once rather than on every refresh.
    var banNotice by remember { mutableStateOf<JSONObject?>(null) }
    var openSaved by remember { mutableStateOf<Int?>(null) }
    var shelfBusy by remember { mutableStateOf(false) }
    // The kept deck whose note is being written, and the text so far.
    var noteFor by remember { mutableStateOf<Int?>(null) }
    var noteText by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf<Map<String, Keyword>>(emptyMap()) }
    var showDeck by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    // Bumped to ask for a refetch. A counter rather than a flag so two
    // triggers arriving together still result in exactly one refresh.
    var wantRefresh by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    var directive by remember { mutableStateOf(Directive.load(ctx)) }
    var avatar by remember { mutableStateOf(Avatar.load(ctx)) }
    // The face and the register travel together: picking the Brute and
    // still being spoken to like a librarian would make the avatar a
    // costume rather than a character.
    val voice = Voice.forAvatar(avatar.key)
    var pickAvatar by remember { mutableStateOf(false) }
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
        runCancellable {
            withContext(Dispatchers.IO) {
                val c = GameClient(token)
                val st = c.standings()
                val mine = if (token != null)
                    runCancellable { c.me() }.onFailure(::watch).getOrNull() else null
                // Everyone's decks and everyone's form, in the same refresh:
                // both are small, and fetching them on demand would put a
                // spinner behind a tap that should feel instant.
                val f = if (token != null)
                    runCancellable { c.field() }.onFailure(::watch).getOrNull() else null
                val lb = if (token != null)
                    runCancellable { c.leaderboard() }.onFailure(::watch).getOrNull() else null
                val hi = if (token != null)
                    runCancellable { c.history() }.onFailure(::watch).getOrNull() else null
                val bn = runCancellable { c.bans() }.getOrNull()
                Triple(st, mine, listOf(f, lb, hi, bn))
            }
        }.onSuccess { (st, mine, extra) ->
            standings = st
            myDeck = mine?.optJSONObject("deck")
            val f = extra[0] as? JSONArray
            val lb = extra[1] as? JSONArray
            val hi = extra[2] as? JSONArray
            (extra[3] as? JSONArray)?.let { bl ->
                // A ban the player has not been told about yet. Compared by
                // the newest id rather than by count: a rotation can add one
                // and release one, leaving the count unchanged.
                val newest = if (bl.length() > 0) bl.getJSONObject(0) else null
                val seen = mem.lastBanSeen
                if (newest != null && newest.optInt("card_id") != seen) {
                    banNotice = newest
                }
                banList = bl
            }
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
            runCancellable { withContext(Dispatchers.IO) { GameClient(token).idea() } }
                .onSuccess { idea = it }
        }
        loaded = true
    }

    /** The catalogue is pulled the first time a card actually needs showing. */
    suspend fun ensureNames() {
        if (cardsById.isNotEmpty()) return
        runCancellable {
            withContext(Dispatchers.IO) {
                val c = GameClient(token)
                // The raw array and the ban list come back together, and both
                // are needed later: the catalogue to repair a kept deck, the
                // bans to know it needs repairing.
                val (arr, ban) = c.cards()
                Triple(arr, ban, runCancellable { c.keywords() }.getOrDefault(emptyMap()))
            }
        }.onSuccess { (arr, ban, kw) ->
            cardsById = CardIndex.parse(arr)
            rawCards = arr
            banned = ban
            keywords = kw
        }
    }

    LaunchedEffect(token) { refresh() }

    // THE SCREEN HAS TO COME BACK TO LIFE ON ITS OWN.
    //
    // Everything above was fetched once, on first composition, and nothing
    // ever asked again -- so a league scored every fifteen minutes sat frozen
    // until the app was killed and relaunched. Switching tabs did not help,
    // because the places share this one load. Measured on the device: a round
    // was scored, the Dojo still showed the previous one, and the player's own
    // result simply was not there.
    //
    // Two triggers, and deliberately not a poll. The data only changes when a
    // round is scored, and the server already says when the next one is due.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, token) {
        val obs = LifecycleEventObserver { _, e ->
            // Coming back to the app is the moment a stale screen is most
            // obvious -- the player has been away, and a round has very
            // likely been scored meanwhile.
            if (e == Lifecycle.Event.ON_RESUME) wantRefresh++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // Waits out the round rather than polling for it. A poll fine enough to
    // feel responsive would be a dozen requests between each actual change;
    // this is one, a few seconds after the round is due, and it re-arms from
    // whatever the fresh standings say comes next.
    // Keyed on wantRefresh as well as standings, so the timer re-arms after
    // EVERY attempt. Keyed on standings alone it stalled permanently the
    // first time a refresh failed or was skipped for being busy: standings
    // kept the same instance, this effect never restarted, and nothing was
    // left to ask again until the app was backgrounded.
    LaunchedEffect(standings, wantRefresh, token) {
        val next = standings?.optJSONObject("round")?.optString("next_at")
        val due = msUntil(next)
        // No next_at, or one already past, still gets a check -- a round that
        // is late is exactly when a player is watching for it.
        kotlinx.coroutines.delay((due + 5_000L).coerceIn(15_000L, 20 * 60_000L))
        wantRefresh++
    }
    LaunchedEffect(wantRefresh) {
        // Never while the engine is working. A refresh mid-build competes
        // with it for the CPU the build is already straining, and a ban
        // announcement arriving over a result the player is reading is worse
        // than a screen that is thirty seconds behind.
        if (wantRefresh > 0 && !busy && !trialBusy && roundBusy == null) refresh()
    }

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
            justBuilt = "\u201c${voice.done()} ${o.name}, revision ${r.revision}. On fresh " +
                "matchups it won ${"%.1f".format(o.wins)} of ${o.opponents}, " +
                "picked from ${o.screened} designs and improved " +
                "${o.climbed} time${if (o.climbed == 1) "" else "s"}.\u201d" +
                if (o.relaxed) " \u201c${voice.cannot("avoid the field's cards this ")}" +
                    "time, so that is the best I could do around them.\u201d" else ""
            note = null
            // "Poor" is measured against the field it was actually screened
            // on, not a fixed win count: 6 wins is good against 27 opponents
            // and bad against 8. Failing to improve at all counts too -- a
            // deck the search could not move is one the idea is not carrying.
            builtPoorly = o.opponents > 0 &&
                (o.wins / o.opponents < 0.30 || o.climbed == 0)
            mem = mem.copy(barrenRuns = if (o.climbed == 0) mem.barrenRuns + 1 else 0)
            Memory.save(ctx, mem)
            refresh()
        }
        Training.clearResult()
    }

    // THE BACKDROP DOES NOT SCROLL, and it is not drawn here. It is the room
    // the conversation happens in, not an item in the list, so it is reported
    // to the host and drawn behind everything including the title.
    //
    // Only on the Agent tab: the Dojo and the Binder are dense tables and a
    // picture behind them fights the text for every row. Only once there is a
    // token, so the join screen stays plain.
    LaunchedEffect(place, token, avatar) {
        onBackdrop(if (place == Place.AGENT && token != null) avatar else null)
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
                        runCancellable {
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
                // Tapping your own name opens the face chooser. It lived
                // with "Pick a theme..." below, which is hidden whenever the
                // agent has a question pending -- and an account whose deck
                // holds a banned card ALWAYS has one, so the chooser was
                // unreachable on exactly the screens that most wanted it.
                // Your name and your face are the same question anyway.
                Nameplate(handle.ifBlank { "You" },
                    Modifier.weight(1f).clickable(enabled = token != null) {
                        pickAvatar = true
                    })
                // It used to refresh in silence, which is indistinguishable
                // from a dead button: nothing on screen changes unless a
                // round happened to land in between. Now it answers.
                GameLink("Any news?", enabled = !busy && !newsBusy) {
                    scope.launch {
                        newsBusy = true
                        val wasRound = standings?.optJSONObject("round")?.optInt("id") ?: 0
                        refresh()
                        val nowRound = standings?.optJSONObject("round")?.optInt("id") ?: 0
                        val mine = standings?.optJSONArray("standings")?.let { rows ->
                            (0 until rows.length()).map { rows.getJSONObject(it) }
                                .firstOrNull { it.optString("handle") == handle }
                        }
                        justBuilt = when {
                            nowRound > wasRound && mine != null ->
                                "\u201c${voice.scored(nowRound,
                                    mine.optInt("placement"),
                                    standings?.optJSONObject("round")?.optInt("decks") ?: 0,
                                    mine.optInt("wins"), mine.optInt("losses"),
                                    mine.optInt("draws"))}\u201d"
                            nowRound > wasRound ->
                                "\u201c${voice.scoredBare(nowRound)}\u201d"
                            else -> "\u201c${voice.nothingNew()} " +
                                (standings?.optJSONObject("round")?.optString("next_at")
                                    ?.takeIf { it.isNotBlank() }?.let { untilRound(it) + "." }
                                    ?: "A round is scored every fifteen minutes.") + "\u201d"
                        }
                        newsBusy = false
                    }
                }
                GameLink("Start again", enabled = !busy) { confirmReset = true }
            }

            // The agent asks; the player answers. This is the whole decision
            // surface of the game, so it sits above the button rather than
            // behind a settings icon.
            val deckIds = myDeck?.optJSONArray("cards")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).optInt("card_id") }.toSet()
            } ?: emptySet()
            val q = if (busy || planPending || justBuilt != null) null else
                Questions.next(directive, mem, voice, handle, standings,
                    deckIds, idea, force = askNow)
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

            // Built before the `when` because it needs a lambda, and a lambda
            // inside a string template cannot be split across a concatenation
            // without the template swallowing the closing brace.
            val plannedNow = directive.summary().replaceFirstChar { it.lowercase() }
            LaunchedEffect(myDeck?.toString(), directive.seedCard) {
                if (myDeck != null || directive.seedCard != null) ensureNames()
            }
            val line = when {
                newsBusy -> "\u201c${voice.letMeLook()}\u201d"
                busy -> "\u201c${voice.working(progress?.step ?: "Working")}\u201d"
                q != null -> q.text
                justBuilt != null -> justBuilt!!
                planPending ->
                    "\u201cNoted \u2014 $plannedNow I will build it once this " +
                        "one has had its round.\u201d"
                awaitingRound -> "\u201c${voice.notPlayedYet()}\u201d"
                else -> "\u201c${directive.summary()}\u201d"
            }

            // Cards the agent could plausibly be naming: the one the plan is
            // built around, the one being proposed, and everything in the
            // deck. Deliberately not the whole catalogue -- there are cards
            // called Book, Brick and Anthem, and matching those would turn
            // ordinary words into links.
            val spoken = remember(cardsById, directive.seedCard, idea, myDeck) {
                val out = HashMap<String, CardInfo>()
                fun add(id: Int?) = id?.let { cardsById[it] }?.let { out[it.name] = it }
                add(directive.seedCard)
                add(idea?.optInt("card_id")?.takeIf { it > 0 })
                myDeck?.optJSONArray("cards")?.let { a ->
                    for (i in 0 until a.length()) add(a.getJSONObject(i).optInt("card_id"))
                }
                out
            }

            // ROOM FOR THE FACE. Every avatar's head sits in the upper
            // quarter of its frame, and the tabs plus the nameplate already
            // reach about a quarter down the screen -- so the dialogue
            // landed exactly on it and the character was a pair of
            // shoulders. Pushed down by a share of the SCREEN rather than a
            // fixed dp, because what has to clear the head is a fraction of
            // the picture and the picture is sized to the screen.
            //
            // Only when a backdrop is actually behind it: with no avatar
            // this would just be a hole at the top of the page.
            if (token != null) {
                Spacer(Modifier.height(
                    (LocalConfiguration.current.screenHeightDp * 0.20f).dp))
            }

            SnesWindow {
                // No portrait here any more. The avatar now stands full
                // height behind this window, so drawing the little one as
                // well put the same character on screen twice -- the exact
                // thing that looked wrong when the dialogue had two.
                TypedLine(line, spoken, onOpenCard = { showCard = it })
                if (busy) {
                    val pr = progress
                    if (pr != null) SnesProgress(pr.done, pr.total) else SnesProgress(0, 1)
                }
                if (!busy && q == null && awaitingRound && !planPending &&
                    justBuilt == null) {
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
                // WATCH ONE. The trial just played these games on this phone,
                // so the seeds that won and lost are already known -- picking
                // one out is a filter, not another simulation.
                trial?.takeIf { it.games.isNotEmpty() }?.let { t ->
                    WatchPicker(t) { g -> watching = Watch(g, t) }
                }
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
                // ANY CARD THE AGENT IS TALKING ABOUT IS TAPPABLE.
                //
                // It used to be only the card it was proposing, so once you
                // agreed to build around Turingue there was no way to look
                // at Turingue again -- the agent kept naming a card you
                // could no longer see.
                val named = when {
                    q != null && q.id == "idea-card" ->
                        idea?.optInt("card_id")?.takeIf { it > 0 } to
                            (idea?.optString("name") ?: "the card")
                    directive.seedCard != null ->
                        directive.seedCard to (directive.seedCardName ?: "the card")
                    else -> null to null
                }
                named.first?.let { cid ->
                    Text("\u2022 Show me ${named.second}",
                        style = snesText(13, androidx.compose.ui.graphics.Color(0xFFA8C8E8)),
                        modifier = Modifier.clickable {
                            scope.launch {
                                ensureNames()
                                cardsById[cid]?.let { c -> showCard = c }
                            }
                        })
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
                                            directive.themes,
                                        // The COMBINATION, so the agent does
                                        // not walk a player back round a
                                        // loop it has already taken them
                                        // through. Single tags cannot say
                                        // whether "poison + card draw" has
                                        // been tried.
                                        triedCombos = mem.triedCombos +
                                            listOfNotNull(directive.themes
                                                .takeIf { it.isNotEmpty() }
                                                ?.sorted()?.joinToString(",")),
                                        // ONLY A DECLINE QUIETENS IT.
                                        //
                                        // The per-round cap exists so the
                                        // agent does not nag, and "not just
                                        // now" needs to visibly do something
                                        // -- without the cap it re-rendered
                                        // the very same question and the tap
                                        // looked dead.
                                        //
                                        // But capping on ACCEPT was worse in
                                        // the other direction: agreeing to an
                                        // angle shut the conversation down
                                        // until the next round, which is the
                                        // opposite of being able to iterate.
                                        // Saying yes keeps it talking; saying
                                        // "not now" is what buys quiet.
                                        lastIdeaRound = if (declined &&
                                            (q.id.startsWith("idea") ||
                                                q.id.startsWith("angle")))
                                            (standings?.optJSONObject("round")
                                                ?.optInt("id") ?: 0)
                                            else mem.lastIdeaRound,
                                        barrenRuns = if (q.id == "stuck") 0
                                                     else mem.barrenRuns)
                                    Memory.save(ctx, mem)
                                    if (q.id.startsWith("idea")) idea = null
                                    askNow = false
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
                                        directive.themes != before.themes ||
                                        directive.style != before.style ||
                                        directive.breadth != before.breadth ||
                                        directive.original != before.original
                                    // A BANNED CARD MAKES THE DECK ILLEGAL, so
                                    // both of its answers have to rebuild --
                                    // whether or not the brief changed.
                                    //
                                    // This is what made them look like dead
                                    // buttons. "Rebuild around it" applies an
                                    // identity transform, and "Begin again"
                                    // clears a seed card and themes that a
                                    // player who never set a plan did not have,
                                    // so neither changed the directive and
                                    // newPlan stayed false. Nothing ran, the
                                    // deck still held the banned card, and the
                                    // same question came straight back.
                                    val forced = q.id == "banned"
                                    if (newPlan || forced) {
                                        error = null; note = null; trial = null
                                        // The brake still holds for an ordinary
                                        // change of plan -- an unplayed deck is
                                        // not discarded because a new angle was
                                        // agreed. It does NOT hold here: waiting
                                        // out a round for a deck that may not
                                        // legally play it is not patience.
                                        if (awaitingRound && !forced) planPending = true
                                        else TrainingService.start(ctx)
                                    }
                                })
                        }
                    } else {
                        // Offered whenever there is a deck, not only while
                        // one is waiting for its round. Rehearsing changes
                        // nothing, and it is the only way to get a game you
                        // can watch -- gating it behind "your deck has not
                        // played yet" meant watching was reachable for about
                        // fifteen minutes out of every fifteen.
                        if (justBuilt == null && myDeck != null) {
                            SnesChoice(
                                label = if (trialBusy) (trialStep ?: "Playing it out\u2026")
                                        else "Show me what it can do",
                                enabled = !trialBusy,
                                onClick = {
                                    error = null; note = null; trial = null
                                    trialBusy = true
                                    scope.launch {
                                        runCancellable {
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
                        if (justBuilt != null) {
                            // One beat, one tap when the deck worked. The
                            // result is the agent reporting back, so it gets
                            // acknowledged the way anything else it says does.
                            if (!builtPoorly) {
                                SnesChoice(
                                    label = "Good",
                                    detail = "Back to what we were doing.",
                                    onClick = { justBuilt = null })
                            } else {
                                // A deck that won nothing is a result, not an
                                // error, so the agent owns it and offers the
                                // three things a player could actually want:
                                // the same idea again, the same idea built
                                // differently, or a different idea entirely.
                                // All three were already reachable -- behind
                                // tapping "Good" first and then finding them.
                                SnesChoice(
                                    label = "Try that again",
                                    detail = "Same idea, fresh designs. The " +
                                        "search starts from different drafts.",
                                    enabled = !trialBusy,
                                    onClick = {
                                        justBuilt = null; builtPoorly = false
                                        error = null; note = null; trial = null
                                        TrainingService.start(ctx)
                                    })
                                if (directive.seedCardName != null ||
                                    directive.themes.isNotEmpty()) {
                                    SnesChoice(
                                        label = "Build it a different way",
                                        detail = "Keeps the idea and changes " +
                                            "how I build it.",
                                        enabled = !trialBusy,
                                        onClick = {
                                            justBuilt = null; builtPoorly = false
                                            askNow = true
                                        })
                                }
                                SnesChoice(
                                    label = "Let's try something else",
                                    detail = "Drops this idea and picks a new " +
                                        "one to build around.",
                                    enabled = !trialBusy,
                                    onClick = {
                                        justBuilt = null; builtPoorly = false
                                        showThemes = true
                                    })
                                SnesChoice(
                                    label = "Leave it for now",
                                    detail = "Keeps the deck as it is.",
                                    onClick = { justBuilt = null; builtPoorly = false })
                            }
                        }
                        if (justBuilt == null && planPending) {
                            SnesChoice(
                                label = "Build it now",
                                detail = "Throws away the deck that has not " +
                                    "played yet and builds the new plan.",
                                enabled = !trialBusy,
                                onClick = {
                                    planPending = false
                                    error = null; note = null; trial = null
                                    TrainingService.start(ctx)
                                })
                            SnesChoice(
                                label = "Wait for the round",
                                detail = "Keeps the current deck. I will build " +
                                    "the new plan after it has played.",
                                enabled = !trialBusy,
                                onClick = { planPending = false })
                        }
                        if (!planPending && justBuilt == null) {
                            SnesChoice(
                                label = "What else could we try?",
                                detail = "Talk about where to take the deck " +
                                    "next, without touching this one.",
                                enabled = !trialBusy,
                                onClick = { askNow = true })
                            SnesChoice(
                                label = "What do these choices do?",
                                detail = "Plain answers about breadth, style " +
                                    "and themes.",
                                enabled = !trialBusy,
                                onClick = { showHelp = true })
                        }
                        if (!planPending && justBuilt == null) SnesChoice(
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
                    // Named for what it offers. "Build around..." read as
                    // "name a card", which is the half of the game that was
                    // already over-represented.
                    GameLink("Pick a theme\u2026") { showThemes = true }
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

        // Watching is an overlay, like the card popup: it belongs to no place.
        watching?.let { w ->
            val g = w.game
            val t = w.from
            val rival = t.rivals.firstOrNull { it.name == g.opponent }
            if (t.mine != null && rival != null) {
                ReplayScreen(
                    mine = t.mine,
                    rival = rival,
                    cardDefs = t.cardDefs,
                    display = t.display,
                    seed = g.seed,
                    youFirst = g.youFirst,
                    expected = g.outcome,
                    // Measured HERE, in the activity's window, because the
                    // dialog's own window spans the whole display and reports
                    // no insets at all.
                    edge = WindowInsets.safeDrawing.asPaddingValues(),
                    onDismiss = { watching = null },
                )
            } else {
                watching = null
            }
        }

        // A ban changes the format under the player, so the agent says so
        // once, in its own voice, rather than leaving them to notice their
        // deck has quietly become illegal.
        banNotice?.let { b ->
            Dialog(onDismissRequest = {
                mem = mem.copy(lastBanSeen = b.optInt("card_id"))
                Memory.save(ctx, mem); banNotice = null
            }) {
                SnesWindow {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AgentPortrait(Mood.ASKING, size = 48.dp)
                        Text("\u201c${b.optString("name")} has been struck from " +
                            "the format.\u201d", style = snesText(15, androidx.compose.ui.graphics.Color(0xFFFFE28C)))
                    }
                    // NO JUSTIFICATION SHOWN. The figure behind a ban is a
                    // single sample from a wide distribution -- measured on
                    // the live field, one card's contribution came out
                    // anywhere from +1.60 to +8.93 wins depending only on
                    // which two decks were drawn -- so printing "it cost its
                    // deck 2.40 wins" states the reason far more precisely
                    // than the evidence supports. The ban itself is sound;
                    // the number is not, and a card being out of the format
                    // is the whole of what a player needs. The reason is
                    // still recorded server-side, where it is ours to read.
                    val inMine = myDeck?.optJSONArray("cards")?.let { cs ->
                        (0 until cs.length()).any {
                            cs.getJSONObject(it).optInt("card_id") == b.optInt("card_id")
                        }
                    } ?: false
                    Text(
                        "\u201c${voice.banLanded(b.optString("name"), inMine)}\u201d",
                        style = snesText(13))
                    SnesChoiceBox {
                        SnesChoice(
                            label = "Show me the card",
                            onClick = {
                                scope.launch {
                                    ensureNames()
                                    cardsById[b.optInt("card_id")]?.let { showCard = it }
                                }
                            })
                        SnesChoice(
                            label = "Understood",
                            onClick = {
                                mem = mem.copy(lastBanSeen = b.optInt("card_id"))
                                Memory.save(ctx, mem); banNotice = null
                            })
                    }
                }
            }
        }

        if (showHelp) {
            Dialog(onDismissRequest = { showHelp = false }) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SnesWindow {
                        Text("\u201cWhat the choices do\u201d",
                            style = snesText(15, androidx.compose.ui.graphics.Color(0xFFFFE28C)))
                        for ((head, body) in HELP) {
                            Text(head, style = snesText(13, androidx.compose.ui.graphics.Color(0xFF8CE8A0)))
                            Text(body, style = snesText(12, androidx.compose.ui.graphics.Color(0xFFD8D8E8)))
                        }
                        SnesChoiceBox {
                            SnesChoice("That's all") { showHelp = false }
                        }
                    }
                }
            }
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
                title = { Text("“What shall I build toward?”") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("“No particular card — I pick the whole deck " +
                            "for it. Name up to three and I will look for cards " +
                            "that do more than one.”",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // TOGGLES, not single-select. Three tags is the cap the
                        // generator uses -- past that the affinity set matches
                        // nearly every card, which is the same as having no theme
                        // at all -- and it is the cap the angle menu already
                        // enforces, so the two ways of combining themes agree.
                        fun choose(tag: String?, label: String?) {
                            if (tag == null || label == null) {
                                directive = directive.copy(themes = emptyList(),
                                    themeLabels = emptyList(),
                                    seedCard = null, seedCardName = null)
                                Directive.save(ctx, directive)
                                showThemes = false
                                return
                            }
                            val i = directive.themes.indexOf(tag)
                            val tags: List<String>
                            val labels: List<String>
                            if (i >= 0) {
                                tags = directive.themes - tag
                                labels = directive.themeLabels.filterIndexed { k, _ -> k != i }
                            } else {
                                if (directive.themes.size >= 3) return
                                tags = directive.themes + tag
                                labels = directive.themeLabels + label
                            }
                            directive = directive.copy(themes = tags, themeLabels = labels,
                                seedCard = null, seedCardName = null)
                            Directive.save(ctx, directive)
                            mem = mem.copy(triedThemes = mem.triedThemes + tags)
                            Memory.save(ctx, mem)
                        }
                        // Plain rows rather than TextButtons: sixteen
                        // buttons at Material's 48dp minimum turned a list
                        // into a scroll marathon, and the chosen one was
                        // hard to spot among them.
                        @Composable
                        fun row(tag: String?, label: String) {
                            val on = tag != null && tag in directive.themes
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
                    // Names the combination, because the dialog no longer
                    // closes on the first tap: with three slots the player
                    // needs to see what they have assembled before leaving.
                    TextButton(onClick = { showThemes = false }) {
                        Text(if (directive.themeLabels.isEmpty()) "That's all"
                             else "Build " + directive.themeLabels.joinToString(" + "))
                    }
                })
        }

        // WRITING THE NOTE IS A DIALOG, not an inline field. The shelf rows
        // are tight and a text field inside one pushed the rest off screen
        // the moment the keyboard appeared.
        noteFor?.let { id ->
            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                onDismissRequest = { noteFor = null },
                title = { Text("\u201cWhat shall I write against it?\u201d") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("\u201cYour words, not mine. I name decks after the " +
                            "card they lean on, which is no help when three of " +
                            "them lean on the same one.\u201d",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = noteText,
                            onValueChange = {
                                // Clipped here as well as on the server, so
                                // the limit is visible while typing instead
                                // of silently truncating on save.
                                if (it.length <= 120) noteText = it
                            },
                            singleLine = false, maxLines = 3,
                            enabled = !shelfBusy,
                            supportingText = { Text("${noteText.length}/120") },
                            modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(enabled = !shelfBusy, onClick = {
                        shelfBusy = true
                        val text = noteText.trim()
                        scope.launch {
                            runCancellable {
                                withContext(Dispatchers.IO) {
                                    val c = GameClient(token)
                                    c.noteDeck(id, text); c.savedDecks()
                                }
                            }.onSuccess { saved = it; noteFor = null }
                             .onFailure { error = readable(it) }
                            shelfBusy = false
                        }
                    }) { Text(if (noteText.isBlank()) "Leave it blank" else "Write it") }
                },
                dismissButton = {
                    TextButton(enabled = !shelfBusy,
                        onClick = { noteFor = null }) { Text("Never mind") }
                })
        }

        // Choosing who stands behind the conversation. Local to the phone:
        // it is how the game looks to you, not anything the league needs to
        // know, so it is a preference and not a field on the account.
        if (pickAvatar) {
            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                onDismissRequest = { pickAvatar = false },
                title = { Text("\u201cWhose face shall I wear?\u201d") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (a in Avatar.entries) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        avatar = a; Avatar.save(ctx, a)
                                        pickAvatar = false
                                    }
                                    .padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // A thumbnail of the real thing, drawn the
                                // same way: a name alone says nothing about
                                // a picture you have not seen.
                                Image(
                                    painter = androidx.compose.ui.graphics.painter.BitmapPainter(
                                        androidx.compose.ui.graphics.ImageBitmap
                                            .imageResource(a.art),
                                        filterQuality =
                                            androidx.compose.ui.graphics.FilterQuality.None),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    alignment = Alignment.TopCenter,
                                    modifier = Modifier.size(44.dp, 60.dp)
                                        .clip(RoundedCornerShape(4.dp)))
                                Text(a.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (a == avatar) GoldBright
                                            else MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { pickAvatar = false }) { Text("That one") }
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
                            runCancellable {
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

            if (place == Place.BINDER) {
                // The binder needs every card's name and art, so the
                // catalogue is pulled on arrival rather than behind a
                // "show cards" tap -- the page IS the content here. The
                // shelf comes with it.
                LaunchedEffect(myDeck?.toString(), token) {
                    ensureNames()
                    if (token != null && saved == null) {
                        runCancellable {
                            withContext(Dispatchers.IO) { GameClient(token).savedDecks() }
                        }.onSuccess { saved = it }
                    }
                }
                myDeck?.let { d ->
                    BinderPage(d, cardsById) { showCard = it }
                    SnesChoiceBox {
                        SnesChoice(
                            label = if (shelfBusy) "Keeping\u2026" else "Keep this deck",
                            detail = "Puts a copy on the shelf. The agent " +
                                "replaces the deck it is working on, so this " +
                                "is the only way back to one that was working.",
                            enabled = !shelfBusy && !busy,
                            onClick = {
                                shelfBusy = true; error = null; note = null
                                scope.launch {
                                    runCancellable {
                                        withContext(Dispatchers.IO) {
                                            val c = GameClient(token)
                                            val r = c.saveDeck()
                                            r.optInt("id") to c.savedDecks()
                                        }
                                    }.onSuccess { (newId, shelf) ->
                                        saved = shelf
                                        // Asked right after keeping it, which
                                        // is the one moment the player is
                                        // certainly thinking about why. It is
                                        // a dialog they can dismiss, so the
                                        // note stays optional.
                                        if (newId > 0) { noteText = ""; noteFor = newId }
                                    }.onFailure { error = readable(it) }
                                    shelfBusy = false
                                }
                            })
                    }
                }

                saved?.takeIf { it.length() > 0 }?.let { shelf ->
                    SnesWindow {
                        Text("Kept decks (${shelf.length()})",
                            style = snesText(14, androidx.compose.ui.graphics.Color(0xFFFFE28C)))
                        for (i in 0 until shelf.length()) {
                            val sv = shelf.getJSONObject(i)
                            val id = sv.optInt("id")
                            val place_ = sv.optInt("best_place", 0)
                            val of = sv.optInt("of_decks", 0)
                            Text(
                                sv.optString("name") + "  r${sv.optInt("revision")}" +
                                    if (place_ > 0 && of > 0) "  \u00b7  best $place_ of $of" else "",
                                Modifier.fillMaxWidth().clickable {
                                    openSaved = if (openSaved == id) null else id
                                },
                                style = snesText(13,
                                    if (openSaved == id) androidx.compose.ui.graphics.Color(0xFFFFE28C)
                                    else androidx.compose.ui.graphics.Color(0xFFF2EFE4)))
                            // The note on the row itself, not hidden behind
                            // opening the deck. Why a deck was kept is the
                            // thing a player scans the shelf for; a name like
                            // "Turingue, the Crypt Lord" is the agent's and
                            // tells them nothing they chose.
                            sv.optString("note").takeIf {
                                it.isNotBlank() && it != "null"
                            }?.let { n ->
                                Text("\u201c$n\u201d",
                                    Modifier.fillMaxWidth().padding(bottom = 2.dp),
                                    style = snesText(12,
                                        androidx.compose.ui.graphics.Color(0xFF8CE8A0)))
                            }
                            if (openSaved == id) {
                                sv.optJSONArray("cards")?.let { cards ->
                                    RivalPockets(cards, cardsById) { showCard = it }
                                }
                                // A kept deck can go illegal under it while
                                // it sits there: that is what a rotating ban
                                // pool does. The snapshot is left alone and a
                                // repaired copy is built from it, so the
                                // original is still there if the card comes
                                // back.
                                val hit = sv.optJSONArray("cards")?.let { cs ->
                                    (0 until cs.length())
                                        .map { cs.getJSONObject(it).optInt("card_id") }
                                        .filter { it in banned }
                                } ?: emptyList()
                                if (hit.isNotEmpty()) {
                                    Text("\u201c" + hit.joinToString(", ") {
                                            cardsById[it]?.name ?: "card $it"
                                        } + (if (hit.size == 1) " is" else " are") +
                                        " banned now, so this one cannot go back as " +
                                        "it stands.\u201d",
                                        style = snesText(12, androidx.compose.ui.graphics.Color(0xFFCE6A5C)))
                                }
                                SnesChoiceBox {
                                    if (hit.isNotEmpty()) SnesChoice(
                                        label = "Rebuild it without them",
                                        detail = "Keeps this copy on the shelf " +
                                            "untouched and plays a repaired " +
                                            "version, filled from what the deck " +
                                            "already leans on.",
                                        enabled = !shelfBusy && !busy && rawCards != null,
                                        onClick = {
                                            shelfBusy = true; error = null
                                            scope.launch {
                                                runCancellable {
                                                    withContext(Dispatchers.Default) {
                                                        val cat = Catalogue(rawCards!!)
                                                        val cs = sv.getJSONArray("cards")
                                                        val entries = (0 until cs.length()).map {
                                                            val e = cs.getJSONObject(it)
                                                            e.optInt("card_id") to e.optInt("quantity")
                                                        }
                                                        val r = Repair.repair(entries, banned, cat)
                                                            ?: error("that deck cannot be made legal")
                                                        val flat = r.cards.flatMap { (id, q) ->
                                                            List(q) { id }
                                                        }
                                                        GameClient(token).putDeck(
                                                            sv.optString("name"), flat,
                                                            sv.optJSONObject("battle_plan"))
                                                        r
                                                    }
                                                }.onSuccess { r ->
                                                    note = "\u201cRebuilt it without " +
                                                        r.removed.joinToString(", ") {
                                                            cardsById[it]?.name ?: "card $it"
                                                        } + ", leaning on " +
                                                        r.added.take(2).joinToString(" and ") {
                                                            cardsById[it.first]?.name ?: "card"
                                                        } + " instead. The copy on the " +
                                                        "shelf is untouched.\u201d"
                                                    openSaved = null
                                                    refresh()
                                                }.onFailure { error = readable(it) }
                                                shelfBusy = false
                                            }
                                        })
                                    if (hit.isEmpty()) SnesChoice(
                                        label = "Put this one back",
                                        detail = "Replaces the deck in play. " +
                                            "Checked for legality first \u2014 a deck " +
                                            "saved before a ban cannot come back.",
                                        enabled = !shelfBusy && !busy,
                                        onClick = {
                                            shelfBusy = true; error = null
                                            scope.launch {
                                                runCancellable {
                                                    withContext(Dispatchers.IO) {
                                                        GameClient(token).restoreDeck(id)
                                                    }
                                                }.onSuccess {
                                                    note = "\u201c${it.optString("name")} is " +
                                                        "back in play.\u201d"
                                                    openSaved = null
                                                    refresh()
                                                }.onFailure { error = readable(it) }
                                                shelfBusy = false
                                            }
                                        })
                                    SnesChoice(
                                        label = sv.optString("note").let {
                                            if (it.isNotBlank() && it != "null")
                                                "Change the note" else "Say why you kept it"
                                        },
                                        detail = "A line for you, not for the " +
                                            "agent. The shelf is named by the " +
                                            "agent and those names run together.",
                                        enabled = !shelfBusy && !busy,
                                        onClick = {
                                            noteText = sv.optString("note")
                                                .takeIf { it.isNotBlank() && it != "null" }
                                                ?: ""
                                            noteFor = id
                                        })
                                    SnesChoice(
                                        label = "Take it off the shelf",
                                        detail = "Forgets this copy.",
                                        enabled = !shelfBusy && !busy,
                                        onClick = {
                                            shelfBusy = true
                                            scope.launch {
                                                runCancellable {
                                                    withContext(Dispatchers.IO) {
                                                        val c = GameClient(token)
                                                        c.deleteSaved(id); c.savedDecks()
                                                    }
                                                }.onSuccess { saved = it; openSaved = null }
                                                 .onFailure { error = readable(it) }
                                                shelfBusy = false
                                            }
                                        })
                                }
                            }
                        }
                    }
                }
            }
        }

        if (place == Place.BANS) {
            LaunchedEffect(token) { ensureNames() }
            SnesWindow {
                Text("Out of the format", style = snesText(15, androidx.compose.ui.graphics.Color(0xFFFFE28C)))
                Text("These cards cannot be played. The list rotates \u2014 the " +
                    "oldest comes back when a new one goes out.",
                    style = snesText(12, androidx.compose.ui.graphics.Color(0xFF8A8AA8)))
            }
            val bl = banList
            if (bl == null) {
                SnesWindow { Text("\u201cLet me check what is out\u2026\u201d",
                    style = snesText(13)) }
            } else if (bl.length() == 0) {
                SnesWindow {
                    Text("\u201cNothing is banned. Everything in the catalogue " +
                        "is fair game.\u201d", style = snesText(13))
                }
            } else {
                for (i in 0 until bl.length()) {
                    val b = bl.getJSONObject(i)
                    val cid = b.optInt("card_id")
                    SnesWindow {
                        Text(b.optString("name"),
                            Modifier.fillMaxWidth().clickable {
                                cardsById[cid]?.let { showCard = it }
                            },
                            style = snesText(15, androidx.compose.ui.graphics.Color(0xFFFFE28C)))
                        Text("banned ${b.optString("banned_at")}",
                            style = snesText(11, androidx.compose.ui.graphics.Color(0xFF8A8AA8)))
                        // In your deck right now? That is the thing a player
                        // actually wants off this screen.
                        val inMine = myDeck?.optJSONArray("cards")?.let { cs ->
                            (0 until cs.length()).any {
                                cs.getJSONObject(it).optInt("card_id") == cid
                            }
                        } ?: false
                        if (inMine) {
                            Text("This is in the deck you are playing.",
                                style = snesText(12, androidx.compose.ui.graphics.Color(0xFFCE6A5C)))
                        }
                    }
                }
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
                    Row(Modifier.fillMaxWidth().padding(start = 54.dp, bottom = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("r${r.optInt("revision")}  ${r.optString("deck_name")}",
                            Modifier.weight(1f, fill = false),
                            style = snesText(12,
                                androidx.compose.ui.graphics.Color(0xFFA8A8C8)),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // WATCH offered only where the round can actually be
                        // watched. Snapshots began part-way through the
                        // league's history and are pruned at the far end, so
                        // the server says which rounds have one rather than
                        // letting the player discover it by tapping.
                        val rid = r.optInt("round_id")
                        if (r.optBoolean("replayable")) {
                            if (roundBusy == rid) {
                                Text(roundStep ?: "Playing it back\u2026",
                                    style = snesText(12,
                                        androidx.compose.ui.graphics.Color(0xFFFFE28C)))
                            } else GameLink("Watch") {
                                error = null
                                roundReplay = null; roundBusy = rid
                                scope.launch {
                                    runCancellable {
                                        withContext(Dispatchers.Default) {
                                            Compete.replayRound(
                                                GameClient(token), handle, rid,
                                                onProgress = { roundStep = it.step })
                                        }
                                    }.onSuccess { roundReplay = it }
                                     .onFailure { error = readable(it) }
                                    roundBusy = null; roundStep = null
                                }
                            }
                        }
                    }
                }
            }
            roundReplay?.let { rr ->
                SnesWindow {
                    val t = rr.trial
                    Text("Round ${rr.roundId} \u2014 ${t.deckName}",
                        style = snesText(14,
                            androidx.compose.ui.graphics.Color(0xFFFFE28C)))
                    // THE RE-DERIVATION IS CHECKED, NOT ASSUMED. The round was
                    // scored by the validator on the coordinator; this is the
                    // same engine on the phone. Agreement is expected, and a
                    // replay that quietly disagrees with the standings would be
                    // worse than no replay, so the disagreement is shown.
                    if (!rr.agrees) {
                        val (sw, sl, sd) = rr.scored!!
                        val (dw, dl, dd) = rr.derived
                        Text("\u201cPlaying it back here gives $dw-$dl-$dd, but the " +
                            "league recorded $sw-$sl-$sd. Treat what follows as " +
                            "an illustration, not the record.\u201d",
                            style = snesText(12,
                                androidx.compose.ui.graphics.Color(0xFFFF9C6E)))
                    }
                    // GAMES, said as games. These counts are over every
                    // match played -- 28 opponents by 21 seeds -- while the
                    // record on the history row above is per OPPONENT, out
                    // of 28. Printing "217 won ... across 28 opponents" put
                    // the two scales in one sentence and read as a bug.
                    val won = t.games.count { it.outcome == "won" }
                    val lost = t.games.count { it.outcome == "lost" }
                    val drew = t.games.count { it.outcome == "drew" }
                    Text("${t.games.size} games against ${t.opponents} " +
                        "opponents: $won won, $lost lost, $drew drawn.",
                        style = snesText(12,
                            androidx.compose.ui.graphics.Color(0xFF8A8AA8)))
                    if (t.games.isEmpty()) {
                        Text("No game from that round could be reconstructed.",
                            style = snesText(12,
                                androidx.compose.ui.graphics.Color(0xFFFF9C6E)))
                    } else {
                        WatchPicker(t) { g -> watching = Watch(g, t) }
                        t.worstAgainst?.let { w ->
                            t.games.filter { it.outcome == "lost" && it.opponent == w }
                                .randomOrNull()?.let { g ->
                                    // The matchup that cost the most is the
                                    // one worth watching, and a loss picked
                                    // at random rarely lands on it.
                                    Row { GameLink("Our worst matchup ($w)") {
                                        watching = Watch(g, t)
                                    } }
                                }
                        }
                    }
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
/** What each knob actually does, in the agent's voice. */
private val HELP = listOf(
    "Try lots of ideas / One, properly" to
        "Breadth. Sixty rough drafts and three passes of refinement, or " +
        "eighteen drafts and twelve passes. Refinement is where most of the " +
        "strength is found, so \u201cone, properly\u201d usually wins \u2014 but only " +
        "once we are pointed somewhere worth refining.",
    "A tight shell" to
        "Spends none of the hundred slots on cards the field plays, and " +
        "takes fewer distinct cards at their full limit. Consistent, and " +
        "weak to anything that answers the one thing it does.",
    "Keep the curve low" to
        "Seeds only from cheap cards. Wins early or not at all.",
    "Play the long game" to
        "Shields and structures, and outlasting them.",
    "Keep clear of the field" to
        "Refuses to submit a deck sharing too much with any rival. Slower, " +
        "and the only route to something nobody has seen.",
    "Pursue a <theme> angle" to
        "Weighs the other ninety-nine cards toward that tag while keeping " +
        "your chosen card at the heart of it. Up to three tags at once; " +
        "past that nearly every card matches and the deck is about nothing.",
    "Show me what it can do" to
        "Plays the current deck against the live field on this phone, on " +
        "seeds it has never seen. It changes nothing \u2014 the league plays " +
        "its own games at the next round.",
)

/** Milliseconds until a UTC "yyyy-MM-dd HH:mm:ss" instant; 0 if past or unparseable. */
private fun msUntil(utc: String?): Long {
    if (utc.isNullOrBlank()) return 0L
    return try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        (fmt.parse(utc)!!.time - System.currentTimeMillis()).coerceAtLeast(0L)
    } catch (e: Exception) {
        0L
    }
}

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
