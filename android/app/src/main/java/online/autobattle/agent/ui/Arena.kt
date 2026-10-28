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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clipToBounds
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
import online.autobattle.agent.R
import online.autobattle.agent.data.Secrets
import online.autobattle.agent.engine.Catalogue
import online.autobattle.agent.game.RoundWatch
import online.autobattle.agent.game.Voice
import online.autobattle.agent.game.CardIndex
import online.autobattle.agent.engine.Repair
import online.autobattle.agent.game.Compete
import online.autobattle.agent.game.Directive
import online.autobattle.agent.game.Entities
import online.autobattle.agent.game.GameClient
import online.autobattle.agent.game.GameException
import online.autobattle.agent.game.CardInfo
import online.autobattle.agent.game.Keyword
import online.autobattle.agent.game.Act
import online.autobattle.agent.game.Workbench
import online.autobattle.agent.game.Memory
import online.autobattle.agent.game.Rules
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    /**
     * Where the scene is set, reported separately from the figure.
     *
     * The backdrop effect runs before `mood` exists -- the mood depends on
     * whether a question is pending, and that is not computed until further
     * down the pane. Rather than hoist a chain of state up past everything
     * that feeds it, the scene is reported from where it is actually known.
     */
    onMood: (Mood) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf(Secrets.load(ctx, Secrets.SLOT_GAME_TOKEN)) }
    var handle by remember { mutableStateOf(Secrets.handle(ctx) ?: "") }
    var typed by remember { mutableStateOf(Secrets.handle(ctx) ?: "") }
    var uiBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /**
     * The message the LAST FAILED REFRESH put on screen, if it is still there.
     *
     * Kept so a refresh can clear up after itself without wiping a message it
     * did not write. An action that failed -- a deck that would not save, a
     * shell with a banned card in it -- says so in the same `error` line, and
     * the player may not have read it yet when the next refresh lands.
     */
    var lastNetError by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    // Observed from the service, so the screen can be left and come back to
    // a run that kept going.
    val running by Training.running.collectAsState()
    val progress by Training.progress.collectAsState()
    val dojo by Training.dojo.collectAsState()
    val finished by Training.result.collectAsState()
    var standings by remember { mutableStateOf<JSONObject?>(null) }
    var myDeck by remember { mutableStateOf<JSONObject?>(null) }
    var cardsById by remember { mutableStateOf<Map<Int, CardInfo>>(emptyMap()) }
    var showCard by remember { mutableStateOf<CardInfo?>(null) }
    var showThemes by remember { mutableStateOf(false) }
    // The quick-build pool, fetched when the player opens the picker rather
    // than on every screen: it is a round-trip nobody waiting for standings
    // needs, and the pool only changes every few hours.
    var showShells by remember { mutableStateOf(false) }
    var shells by remember { mutableStateOf<List<Compete.Shell>?>(null) }
    var shellsError by remember { mutableStateOf<String?>(null) }
    // Where the player currently is. The agent is a person you talk to; the
    // dojo and the binder are places you go to look things up. Keeping them
    // on one scroll meant the conversation was always buried under a table.
    var place by remember { mutableStateOf(Place.BREW) }
    /**
     * The main column's scroll, held by name so an answer can be scrolled to.
     *
     * The agent appends its replies BELOW the choice list, which is fine when
     * the list is short and invisible when it is not: tapping "Is there
     * another way to play?" wrote the answer and both buttons off the bottom
     * of the screen, so the choice read as dead. Ryan reported it as the link
     * not working. It had worked every time.
     */
    val pageScroll = rememberScrollState()
    /**
     * What Talk is currently showing.
     *
     * null is the conversation itself; a RuleQA question is that answer;
     * "binder" and "bans" are the two reference panes. One piece of state
     * rather than two booleans, because they are mutually exclusive and a
     * pair of booleans eventually gets into the state where both are true.
     */
    var talkPane by remember { mutableStateOf<String?>(null) }
    /** What the Paragon last said in answer to "Any news?". */
    var talkNews by remember { mutableStateOf<String?>(null) }
    /** Whether the Paragon has been asked about the other game. */
    var talkPlay by remember { mutableStateOf(false) }
    // An answer nobody can see has not been given. Both of these append
    // below the choices, so both bring themselves into view.
    LaunchedEffect(talkPlay, talkNews) {
        if (talkPlay || talkNews != null) pageScroll.animateScrollTo(pageScroll.maxValue)
    }
    /** The find-a-card sheet, and what has been typed into it. */
    var cardSearch by remember { mutableStateOf(false) }
    var cardQuery by remember { mutableStateOf("") }
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
    // The rules conversation, and which question is open in it. Null means
    // the list of questions rather than an answer.
    var showRules by remember { mutableStateOf(false) }
    var rulesPick by remember { mutableStateOf<Int?>(null) }
    /** True only when the rules came up because there was nothing to decide. */
    var rulesIdle by remember { mutableStateOf(false) }
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
    // The round whose deck is being taken back into play.
    var reviveBusy by remember { mutableStateOf<Int?>(null) }
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
    /** A deck built while a result was pending, waiting to be entered. */
    var bench by remember { mutableStateOf<Workbench?>(Workbench.load(ctx)) }
    var directive by remember { mutableStateOf(Directive.load(ctx)) }
    var avatar by remember { mutableStateOf(Avatar.load(ctx)) }
    // The face and the register travel together: picking the Brute and
    // still being spoken to like a librarian would make the avatar a
    // costume rather than a character.
    val voice = Voice.forAvatar(avatar.key)
    var pickAvatar by remember { mutableStateOf(false) }
    var pickWait by remember { mutableStateOf(false) }
    var mem by remember { mutableStateOf(Memory.load(ctx)) }
    var idea by remember { mutableStateOf<JSONObject?>(null) }
    // handle -> that player's current deck, so a rival's list can be opened
    // from the standings without another request.
    var field by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var openDeck by remember { mutableStateOf<String?>(null) }
    var myForm by remember { mutableStateOf<JSONObject?>(null) }
    // The season ladder, and which season is being looked at: 0 means the one
    // in progress. Held as 0 rather than as its number so that the live
    // ladder stays the live ladder when the season rolls over underneath a
    // screen somebody left open.
    var season by remember { mutableStateOf<JSONObject?>(null) }
    var seasonView by remember { mutableStateOf(0) }
    var seasonNow by remember { mutableStateOf<Int?>(null) }
    var seasonTried by remember { mutableStateOf(false) }

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
            // THE SCREEN HEALS ITSELF. Nothing used to clear this, so one
            // failed poll -- the phone's resolver going stale while the app
            // sat in the background is the common one, "Unable to resolve
            // host agent.autobattle.online" -- left the message up for the
            // rest of the session. Every later refresh succeeded and left it
            // sitting there, so the only way out was restarting the app.
            // Observed by Ryan 10-26.
            //
            // Only the message this function put there: compared by value,
            // so an action's own error survives a refresh that happens to
            // land before it has been read.
            if (error != null && error == lastNetError) error = null
            lastNetError = null
        }.onFailure {
            watch(it)
            error = readable(it)
            lastNetError = error
        }

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

    /**
     * Why the agent cannot build around this card, or null if it can.
     *
     * THE SAME TEST THE GENERATOR USES, deliberately: Compete draws its seeds
     * from the cards that are neither banned nor unplayable, and a `seedCard`
     * outside that set falls through the `when` and is silently ignored. The
     * agent would take the instruction, say nothing, and build a different
     * deck. Checking here is what turns that into a sentence.
     *
     * Retired cards are the reason this is not just the ban list: 38% of the
     * catalogue is retired, the engine simulates one perfectly happily, and
     * the deck is only rejected when it reaches the server.
     */
    fun seedBlock(c: CardInfo): String? {
        val raw = rawCards ?: return null
        if (c.id in banned) return "\u201cThat one is out of the format at " +
            "the moment. Nobody may play it, so I cannot build on it.\u201d"
        if (!Catalogue(raw, banned).isPlayable(c.id))
            return "\u201cI cannot anchor a deck on that one \u2014 it is not " +
                "a card you may put in a deck.\u201d"
        return null
    }

    /**
     * Take the card as the instruction and start building.
     *
     * Clears the themes for the same reason answering "Investigate <card>"
     * does (game/Questions.kt): a named seed and a theme are two answers to
     * the same question, and leaving the old theme on would quietly reshape
     * the deck the player thought they were asking for. The shell clears
     * itself -- Directive only reports one while there is no theme and no
     * seed card.
     *
     * It STARTS the build rather than only setting the directive, because the
     * button says "build". Answering one of the agent's own questions just
     * sets the directive and waits for "Get to work", which is right there
     * and wrong here.
     */
    fun buildAround(c: CardInfo) {
        directive = directive.copy(
            seedCard = c.id, seedCardName = c.name,
            themes = emptyList(), themeLabels = emptyList())
        Directive.save(ctx, directive)
        showCard = null
        cardSearch = false
        error = null; trial = null
        note = voice.buildingAround(c.name)
        // AND TAKES YOU WHERE THE WORK IS. The progress line, the pass
        // counter and the agent's note are all drawn inside Brew. Started
        // from the binder or from a card name in the dialogue -- which is
        // most of the ways to reach a card -- the popup closed and left the
        // player on Talk looking at the same page, with a four-minute build
        // running invisibly behind it. Ryan: "pressable, but doesnt take me
        // anywhere". It was building the whole time.
        place = Place.BREW
        talkPane = null
        TrainingService.start(ctx)
    }

    // KEYED ON `place` TOO. The figures used to update only at launch, which
    // is why there was a refresh button wearing a conversational label.
    // Arriving at a tab is exactly when they need to be current.
    LaunchedEffect(token, place) { refresh() }

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
            // A benched build is not an entry: the league is unchanged and
            // there is no revision to report.
            if (r.benched) bench = Workbench.load(ctx)
            val o = r.outcome
            // A QUICK BUILD DRAFTS NOTHING, so `screened` is 0 and "picked
            // from 0 designs" is both meaningless and faintly alarming. It
            // did not pick from nothing; it started from a deck the server
            // had already measured. Say that instead.
            val provenance = if (o.screened == 0) "started from a measured deck"
                             else "picked from ${o.screened} designs"
            if (r.benched) {
                // NOT "submitted", because it was not. The league is
                // unchanged and r.revision is 0; saying otherwise would be
                // the one thing a player must be able to trust this screen
                // about.
                note = null
                justBuilt = "\u201c" + voice.benched(o.name,
                    "%.1f".format(o.wins), o.opponents) + "\u201d"
                return@LaunchedEffect
            }
            note = "Submitted \"${o.name}\" (revision ${r.revision}). " +
                "Checked on fresh matchups it won ${"%.1f".format(o.wins)} of " +
                "${o.opponents} — $provenance, improved " +
                "${o.climbed} time(s). The league scores every deck itself, so " +
                "your standing may differ." +
                if (o.relaxed) " (It could not avoid the field's cards this time " +
                    "and built the best deck it could instead.)" else ""
            justBuilt = "\u201c${voice.done()} ${o.name}, revision ${r.revision}. On fresh " +
                "matchups it won ${"%.1f".format(o.wins)} of ${o.opponents}, " +
                "$provenance and improved " +
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
    // The ladder, fetched on its own rather than inside refresh().
    //
    // Keyed on the ROUND, so it is re-read exactly when a new round lands and
    // not on every ordinary refresh that finds the same one -- and on
    // seasonView, so the arrows work. Points only change when a round is
    // scored, so any other trigger would be a request that cannot return
    // anything new.
    val shownRound = standings?.optJSONObject("round")?.optInt("id") ?: 0
    LaunchedEffect(seasonView, shownRound, token, loaded) {
        // Held until the first refresh has landed, so this runs ONCE at
        // startup. Without the guard it fires on the composition where
        // standings is still null and again when the round arrives, and the
        // first result is fetched only to be thrown away. `loaded` is set on
        // both the success and the failure path of refresh(), so a league
        // that cannot be reached at all still lets the ladder be attempted.
        if (!loaded) return@LaunchedEffect
        runCancellable {
            withContext(Dispatchers.IO) { GameClient(token).season(seasonView) }
        }.onSuccess { sn ->
            season = sn
            val m = sn.optJSONObject("season")
            if (m != null && m.optInt("current") == 1) seasonNow = m.optInt("number")
        }
        // Tried, whatever came of it. The screen cannot otherwise tell a
        // ladder that failed from one still in flight, and it showed "could
        // not be read" for the whole of a slow fetch -- which is not slow,
        // it is wrong.
        seasonTried = true
    }

    LaunchedEffect(place, token, avatar) {
        // The Paragon stands behind both halves of the conversation: Brew is
        // building a deck with them, Talk is asking them something.
        onBackdrop(if ((place == Place.BREW || place == Place.TALK) && token != null)
                   avatar else null)
    }

    Column(modifier.verticalScroll(pageScroll).padding(16.dp),
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
                "every half hour.”",
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

            if (place == Place.BREW) {
            // THE SECOND ROW IS GONE. It held a nameplate and three
            // buttons -- refresh the standings, throw the deck away, read
            // the rules -- three purposes sharing a strip of screen that the
            // dialogue and the Paragon both wanted. Each went where it
            // belongs: "Start again" into Brew's own choices, because it
            // discards a deck; "Any news?" and "How it works" into Talk; the
            // nameplate, which is also how the avatar chooser is reached,
            // into Talk beside them.

            // SETTLE THE STANDING ORDERS BY PLAYING THEM.
            //
            // Nine orderings of the SAME hundred cards go into one payload
            // and play the same opponents on the same seeds, so the whole
            // comparison is paired for the price of one run. The leader is
            // then re-played on seeds it has never seen and put through the
            // ordinary accept gate, which is what stops this adopting the
            // winner of nine coin flips.
            //
            // Adopted orders are pushed to the deck in play rather than
            // rebuilt into a new one: no plan field can make a legal deck
            // illegal, so there is nothing to re-tune.
            fun tunePlan() {
                val tk = token ?: return
                error = null; note = null; trial = null
                justBuilt = null; builtPoorly = false
                trialBusy = true
                scope.launch {
                    runCancellable {
                        val t = withContext(Dispatchers.Default) {
                            Compete.tunePlan(GameClient(tk), handle,
                                onProgress = { trialStep = it.step })
                        }
                        // Adopt inside the same block, so a failure to SAVE
                        // the winner is reported as a failure rather than
                        // leaving the player told it was adopted when it was
                        // not.
                        val best = t.best
                        if (best != null) {
                            val d = myDeck
                            if (d != null) {
                                val cs = d.optJSONArray("cards")
                                    ?: error("deck has no cards")
                                val flat = ArrayList<Int>(100)
                                for (i in 0 until cs.length()) {
                                    val e = cs.getJSONObject(i)
                                    repeat(e.getInt("quantity")) {
                                        flat.add(e.getInt("card_id"))
                                    }
                                }
                                withContext(Dispatchers.IO) {
                                    GameClient(tk).putDeck(d.optString("name"), flat,
                                        best.applyTo(JSONObject(
                                            (d.optJSONObject("battle_plan")
                                                ?: JSONObject()).toString())))
                                }
                            }
                        }
                        t
                    }.onSuccess { t ->
                        val top = t.rows.firstOrNull()
                        val best = t.best
                        note = if (best != null && top != null) {
                            // The brief has to agree with the deck, or the
                            // next build would quietly undo what was just
                            // measured.
                            directive = directive.copy(plan = best)
                            Directive.save(ctx, directive)
                            val m = mem.copy(answered = mem.answered +
                                "plan-tempo" + "plan-aim" + "plan-extras")
                            mem = m
                            Memory.save(ctx, m)
                            "\u201c" + voice.planTuned(top.label, t.bestGain) + "\u201d"
                        } else if (top != null) {
                            "\u201c" + voice.planNothingBetter(top.label) + "\u201d"
                        } else {
                            "\u201c" + voice.nothingNew() + "\u201d"
                        }
                        runCancellable { refresh() }
                    }.onFailure { error = readable(it) }
                    trialBusy = false; trialStep = null
                }
            }

            // The agent asks; the player answers. This is the whole decision
            // surface of the game, so it sits above the button rather than
            // behind a settings icon.
            val deckIds = myDeck?.optJSONArray("cards")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).optInt("card_id") }.toSet()
            } ?: emptySet()
            // How many copies in the deck offer an optional extra cost. The
            // supplement question is asked only of a deck that holds some,
            // because the orders it sets change nothing for a deck that does
            // not -- and the number is what tells the player why they are
            // being asked at all.
            val supplementCopies = myDeck?.optJSONArray("cards")?.let { arr ->
                (0 until arr.length()).sumOf { i ->
                    val e = arr.getJSONObject(i)
                    val info = cardsById[e.optInt("card_id")]
                    if (info != null && "supplement" in info.tags) e.optInt("quantity") else 0
                }
            } ?: 0
            val q = if (busy || planPending || justBuilt != null) null else
                Questions.next(directive, mem, voice, handle, standings,
                    deckIds, idea, force = askNow,
                    supplementCards = supplementCopies)
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
            LaunchedEffect(mood) { onMood(mood) }

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
                    "\u201c${voice.noted(plannedNow)}\u201d"
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
                    // SOMETHING TO WATCH BEFORE THERE IS SOMETHING TO WATCH.
                    // The first stages -- reading the catalogue, fetching the
                    // field, drafting -- have no candidates to show yet, so
                    // the bench below is empty and the screen was a progress
                    // bar and a sentence. Cards drifting past is the picture
                    // the fiction already uses: your Paragon going through
                    // the box. It gives way the moment the bench has a real
                    // pass to report.
                    if (dojo.isEmpty()) DriftingCards(cardsById)
                    // SAID ONCE, WHERE THE WAITING HAPPENS. A build is minutes
                    // of work and the screen does not need to be on for any of
                    // it; without being told, a player watches the whole thing.
                    Text("\u201c${voice.goAway()}\u201d",
                        style = snesText(12,
                            androidx.compose.ui.graphics.Color(0xFF8898B8)))
                    // THE BENCH. Everything here is work that actually
                    // happened: the cards are the real candidates, the
                    // numbers are the real measurement and the verdict is
                    // the real decision. The climb was always doing this;
                    // it just never said so.
                    DojoBench(dojo, pr)
                }
                if (!busy && q == null && awaitingRound && !planPending &&
                    justBuilt == null) {
                    Text("\u201c${voice.notPlayedYetWhy()}\u201d",
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
                                    // A CHANGE OF STANDING ORDERS IS NOT A
                                    // REBUILD.
                                    //
                                    // The plan fields are independent of the
                                    // hundred cards: nothing about aim or
                                    // energy can make a legal deck illegal,
                                    // and the card list that was tuned is
                                    // still the card list we want. Running
                                    // the full build would spend two minutes
                                    // of somebody's battery to arrive at a
                                    // different deck than the one they just
                                    // gave orders to.
                                    //
                                    // So this pushes the new orders onto the
                                    // deck already in play and leaves it
                                    // there. Without it the answer would not
                                    // reach the league until the next time
                                    // the agent happened to build something,
                                    // which is the kind of silently deferred
                                    // setting this screen exists to avoid.
                                    val planChanged = directive.plan != before.plan
                                    if (newPlan || forced) {
                                        error = null; note = null; trial = null
                                        // A new deck voids the promise: the
                                        // agent said it would report on THAT
                                        // one, and reporting on a deck the
                                        // player replaced would be worse than
                                        // saying nothing.
                                        if (mem.waitUntilRound > 0) {
                                            mem = mem.copy(waitUntilRound = 0)
                                            Memory.save(ctx, mem)
                                            RoundWatch.stop(ctx)
                                        }
                                        // The brake still holds for an ordinary
                                        // change of plan -- an unplayed deck is
                                        // not discarded because a new angle was
                                        // agreed. It does NOT hold here: waiting
                                        // out a round for a deck that may not
                                        // legally play it is not patience.
                                        // `forced` travels into the service
                                        // as well: a deck replacing an illegal
                                        // one must go straight into the league,
                                        // not onto the bench, or the player is
                                        // left entered with a deck that cannot
                                        // play.
                                        if (awaitingRound && !forced) planPending = true
                                        else TrainingService.start(ctx, forced = forced)
                                    } else if (planChanged) {
                                        // DELIBERATELY DOES NOT VOID A
                                        // PATIENCE PROMISE. A rebuild does,
                                        // because reporting on a deck the
                                        // player replaced would be worse than
                                        // silence. This is the same hundred
                                        // cards under new orders: the report
                                        // the agent promised is still about
                                        // the deck the player is watching,
                                        // and cancelling it would punish them
                                        // for answering a question.
                                        val d = myDeck
                                        val tk = token
                                        if (d != null && tk != null) {
                                            error = null
                                            scope.launch {
                                                // ON THE IO DISPATCHER, like
                                                // every other call in this
                                                // file. rememberCoroutineScope
                                                // launches on the MAIN
                                                // dispatcher, so without this
                                                // the PUT threw
                                                // NetworkOnMainThreadException
                                                // -- which carries a NULL
                                                // message, so the screen said
                                                // only "could not change the
                                                // orders" and named nothing.
                                                runCancellable {
                                                 withContext(Dispatchers.IO) {
                                                    val cs = d.optJSONArray("cards")
                                                        ?: error("deck has no cards")
                                                    val flat = ArrayList<Int>(100)
                                                    for (i in 0 until cs.length()) {
                                                        val e = cs.getJSONObject(i)
                                                        repeat(e.getInt("quantity")) {
                                                            flat.add(e.getInt("card_id"))
                                                        }
                                                    }
                                                    // Onto the deck's OWN plan,
                                                    // so card_order -- which the
                                                    // generator chose and no
                                                    // dialogue offers to change
                                                    // -- survives. Copied rather
                                                    // than edited in place: this
                                                    // object is the one the
                                                    // screen is drawing from.
                                                    val plan = directive.plan.applyTo(
                                                        JSONObject(
                                                            (d.optJSONObject("battle_plan")
                                                                ?: JSONObject()).toString()))
                                                    GameClient(tk).putDeck(
                                                        d.optString("name"), flat, plan)
                                                 }
                                                }.onSuccess {
                                                    // THE AGENT NAMES WHAT IT
                                                    // DERIVED. The player gave
                                                    // it a general idea; the
                                                    // atomic orders that follow
                                                    // are the thing they could
                                                    // not have known to ask
                                                    // for, so they get said out
                                                    // loud.
                                                    note = "\u201c" + voice.planDecided(
                                                        directive.plan.decisions()) +
                                                        " Same hundred cards \u2014 it " +
                                                        "plays them differently from the " +
                                                        "next round on.\u201d"
                                                    runCancellable { refresh() }
                                                }.onFailure { error = readable(it) }
                                            }
                                        }
                                    }
                                    if (c.act == Act.PLAN_BY_HAND) {
                                        // Open the two preference questions.
                                        // They are gated on this rather than
                                        // asked unconditionally, so a player
                                        // who had the orders measured is not
                                        // then asked to second-guess them.
                                        val m2 = mem.copy(planByHand = true)
                                        mem = m2
                                        Memory.save(ctx, m2)
                                        askNow = true
                                    }
                                    if (c.act == Act.TUNE_PLAN) tunePlan()
                                })
                        }
                    }
                    // ABOVE THE REST, AND BELOW THE QUESTION.
                    //
                    // At the end of the box it rendered under "Discard it
                    // and start a new deck" and fell below the fold. Ryan
                    // could not find it, and neither could my own test --
                    // which is why that test quietly exercised nothing and
                    // I read the silence as the feature failing. Waiting
                    // should not be harder to reach than throwing the deck
                    // away.
                    // WAITING IS A CHOICE, AND IT BELONGS WITH ALL OF THEM.
                    //
                    // It began inside the branch that runs only when the agent
                    // has nothing to ask -- and the agent nearly always has
                    // something to ask, so in practice it never appeared.
                    // Every other option here either spends the phone's
                    // battery or throws the deck away; letting a deck play is
                    // the thing the agent spends most of its breath
                    // recommending, so it has to be offered whatever else is
                    // on the screen.
                    //
                    // Not on the banned-card question: that deck is illegal
                    // and will not be scored, so promising to report on it in
                    // three rounds is a promise the league cannot keep.
                    val canWait = myDeck != null && justBuilt == null &&
                        !trialBusy && q?.id != "banned"
                    if (canWait && mem.waitRounds == 0) {
                        SnesChoice(
                            label = "I'll be patient",
                            detail = "Let it play, and tell you how it did.",
                            onClick = { pickWait = true })
                    }
                    if (canWait && mem.waitRounds > 0) {
                        SnesChoice(
                            label = "Stop waiting",
                            // The target is not known until the worker has
                            // looked, so say what is true at each stage rather
                            // than printing round 0.
                            detail = if (mem.waitUntilRound > 0)
                                "I am holding off until round " +
                                    "${mem.waitUntilRound}. Say the word and " +
                                    "we get back to work."
                            else "I am letting it play for ${mem.waitRounds} " +
                                "rounds. Say the word and we get back to work.",
                            onClick = {
                                mem = mem.copy(waitUntilRound = 0, waitRounds = 0)
                                Memory.save(ctx, mem)
                                RoundWatch.stop(ctx)
                            })
                    }
                    // REACHABLE WHILE A QUESTION IS UP. This lived inside
                    // the `q == null` block below, so the one way back to
                    // the standing orders disappeared the moment the agent
                    // asked anything -- which is most of the time. "Stop
                    // waiting" directly above is offered beside a question
                    // already, so a standing option next to one is the
                    // established shape, not a new one. Withheld only for
                    // the plan questions themselves: reopening a subject
                    // that is being asked about right now is incoherent.
                    if (q?.id?.startsWith("plan") != true) {
                        // A WAY BACK TO THE STANDING ORDERS.
                        //
                        // The plan questions are gated on `answered`, so
                        // they are asked once and never again -- and
                        // "What else could we try?" forces a question
                        // without clearing that gate. Answering "press
                        // them from turn one" in week one would otherwise
                        // be permanent, which is a strange thing to do to
                        // the half of the game a player has just been
                        // taught about. Clearing both ids puts the two
                        // questions back in the queue.
                        // "plan-how" COUNTS AS HAVING ORDERS. Checking
                        // only the two preference ids meant a player who
                        // had the orders MEASURED could never reopen
                        // them: the tuner answers plan-how and leaves the
                        // preference questions untouched, so neither id
                        // was ever set and the option never appeared for
                        // them at all.
                        if (myDeck != null &&
                            ("plan-how" in mem.answered ||
                                "plan-tempo" in mem.answered ||
                                "plan-aim" in mem.answered)) {
                            SnesChoice(
                                label = "Change our standing orders",
                                detail = "How the deck carries itself in a " +
                                    "fight, and what it goes after.",
                                enabled = !trialBusy,
                                onClick = {
                                    // CLEARS plan-how TOO, or this is a
                                    // dead button for most players. The
                                    // three preference questions are
                                    // gated on planByHand, which is false
                                    // for anyone who had the orders
                                    // MEASURED -- so clearing only those
                                    // three reopened nothing, left
                                    // plan-how answered, and the tap did
                                    // nothing at all. Reopening the
                                    // subject means asking again how they
                                    // want it settled, not assuming the
                                    // answer they gave last time.
                                    mem = mem.copy(
                                        answered = mem.answered -
                                            "plan-how" - "plan-tempo" -
                                            "plan-aim" - "plan-extras",
                                        dismissed = mem.dismissed -
                                            "plan-how" - "plan-tempo" -
                                            "plan-aim" - "plan-extras",
                                        planByHand = false)
                                    Memory.save(ctx, mem)
                                    justBuilt = null; builtPoorly = false
                                    askNow = true
                                })
                        }
                    }
                    // THE BENCH. Offered above everything else, because a
                    // deck built and not entered is the one thing on this
                    // screen the player has to decide about -- leave it
                    // further down and it reads as a note rather than a
                    // choice.
                    bench?.let { b ->
                        SnesChoice(
                            label = "Enter ${b.name} in the league",
                            detail = "Replaces the deck that is in now. It won " +
                                "${"%.1f".format(b.wins)} of ${b.opponents} on " +
                                "fresh matchups when I built it.",
                            enabled = !busy && !trialBusy && token != null,
                            onClick = {
                                scope.launch {
                                    uiBusy = true; error = null
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            GameClient(token!!).putDeck(b.name, b.cards, b.plan)
                                        }
                                    }.onSuccess { rev ->
                                        Workbench.clear(ctx); bench = null
                                        justBuilt = "\u201c${voice.entered(b.name, rev)}\u201d"
                                        refresh()
                                    }.onFailure { error = readable(it) }
                                    uiBusy = false
                                }
                            })
                        SnesChoice(
                            label = "Let it go",
                            detail = "Throws away ${b.name}. The deck in the " +
                                "league is untouched either way.",
                            enabled = !busy && !trialBusy,
                            onClick = { Workbench.clear(ctx); bench = null })
                    }
                    if (q == null) {
                        // Offered whenever there is a deck, not only while
                        // one is waiting for its round. Rehearsing changes
                        // nothing, and it is the only way to get a game you
                        // can watch -- gating it behind "your deck has not
                        // played yet" meant watching was reachable for about
                        // fifteen minutes out of every fifteen.
                        if (justBuilt == null && (myDeck != null || bench != null)) {
                            SnesChoice(
                                // NAMES WHICH DECK. With something on the
                                // bench there are two, and "show me what it
                                // can do" would be ambiguous about the one
                                // thing the player needs to be sure of.
                                label = if (trialBusy) (trialStep ?: "Playing it out\u2026")
                                        else if (bench != null)
                                            "Show me what ${bench!!.name} can do"
                                        else "Show me what it can do",
                                detail = if (bench != null)
                                    "Plays the one on the bench, not the one " +
                                    "in the league. Nothing is entered." else null,
                                enabled = !trialBusy,
                                onClick = {
                                    error = null; note = null; trial = null
                                    trialBusy = true
                                    val under = bench
                                    scope.launch {
                                        runCancellable {
                                            withContext(Dispatchers.Default) {
                                                Compete.testDeck(GameClient(token), handle,
                                                    bench = under,
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
                                detail = "Plain answers about breadth, style, " +
                                    "themes and battle plans.",
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
                            // IT NO LONGER THROWS THE DECK AWAY. Building
                            // while a result is pending puts the new deck on
                            // the bench; the entered one keeps its place in
                            // the round. The label said "discard" because
                            // that is what it used to do.
                            label = if (awaitingRound) "Work on another idea"
                                    else "Get to work",
                            detail = if (awaitingRound)
                                "Keeps the deck that is waiting for its round " +
                                "and builds the new one alongside it." else null,
                            enabled = !trialBusy,
                            onClick = {
                                error = null; note = null; trial = null
                                TrainingService.start(ctx)
                            })
                        // CAME DOWN FROM THE TOP ROW. It throws the deck
                        // away and takes a fresh starter, which is a Brew
                        // decision and a destructive one -- it had no
                        // business sitting permanently on screen next to
                        // "how does this work".
                        if (!planPending && myDeck != null) SnesChoice(
                            label = "Start again",
                            // IT NO LONGER GIVES THE DECK UP. The old deck
                            // goes to the shelf, so the honest description is
                            // a change of direction rather than a surrender.
                            detail = "Shelve this deck, take a fresh one, and " +
                                "begin again under a new Paragon.",
                            enabled = !busy && !trialBusy,
                            onClick = { confirmReset = true })
                    }
                }
                if (trialBusy) SnesProgress(0, 1)
            }

            if (!busy && q == null) {
                // A ROW THAT WRAPS. These are GameLinks, which are
                // maxLines=1 and clip rather than wrap, so a third button
                // pushed the last one off the edge -- "Let's talk" was
                // reading as "Let". Shortening labels buys room for one more
                // button and then the next one breaks it again; wrapping
                // does not have that ceiling.
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Named for what it offers. "Build around..." read as
                    // "name a card", which is the half of the game that was
                    // already over-represented.
                    GameLink("Pick a theme\u2026") { showThemes = true }
                    // NAMING A CARD IS BACK, and on purpose. The note beside
                    // "Pick a theme..." says "Build around..." was renamed
                    // because it read as "name a card", which was then the
                    // over-represented half of the game. Ryan asked for it
                    // directly on 10-26: he wants to search a card and build
                    // around it. It is a separate door rather than a renamed
                    // one, so the theme route is untouched.
                    GameLink("Find a card\u2026") {
                        cardQuery = ""; cardSearch = true
                        scope.launch { ensureNames() }
                    }
                    // THE QUICK WAY IN. A cold build is twelve refine passes
                    // and about six minutes, because measuring is the cost
                    // and it depends on a field that moves. The server has
                    // already measured a dozen archetypes against this
                    // field; starting from one of those leaves the phone
                    // four passes of polish instead of twelve of search.
                    GameLink("Measured decks\u2026") {
                        shells = null; shellsError = null; showShells = true
                        scope.launch {
                            val t = token
                            if (t == null) { shellsError = "not joined yet"; return@launch }
                            runCatching {
                                withContext(Dispatchers.IO) { Compete.readShells(GameClient(t)) }
                            }.onSuccess { shells = it }
                             .onFailure { shellsError = readable(it) }
                        }
                    }
                    GameLink("Let's talk") {
                        // IT DID NOTHING MOST OF THE TIME, and the reason was
                        // not the memory it was clearing. `q` is forced to
                        // null while justBuilt is set (see where it is
                        // computed), which is the whole period the agent is
                        // holding out a finished piece of work for
                        // acknowledgement -- in other words most of the time
                        // a player is looking at this screen. Clearing
                        // `answered` could never win against that, so the tap
                        // looked dead.
                        //
                        // Clearing the whole of `answered` was also too much:
                        // breadth and originality are not dismissible, so
                        // "let's talk" re-interrogated the player about
                        // settings they had already given. Dropping the
                        // dismissals and forcing past the per-round cap is
                        // enough to reopen anything the agent is sitting on.
                        justBuilt = null; builtPoorly = false
                        val m = mem.copy(dismissed = emptySet())
                        mem = m
                        Memory.save(ctx, m)
                        // AND IF THERE IS GENUINELY NOTHING TO DECIDE, TALK
                        // ABOUT THE GAME INSTEAD. Asked here rather than
                        // guessed: Questions.next is pure, so the same call
                        // the screen is about to make can be made now to find
                        // out whether it would come back empty-handed.
                        val deckIds = myDeck?.optJSONArray("cards")?.let { a ->
                            (0 until a.length())
                                .map { a.getJSONObject(it).optInt("card_id") }.toSet()
                        } ?: emptySet()
                        val supCopies = myDeck?.optJSONArray("cards")?.let { arr ->
                            (0 until arr.length()).sumOf { i ->
                                val e = arr.getJSONObject(i)
                                val info = cardsById[e.optInt("card_id")]
                                if (info != null && "supplement" in info.tags)
                                    e.optInt("quantity") else 0
                            }
                        } ?: 0
                        if (Questions.next(directive, m, voice, handle, standings,
                                deckIds, idea, force = true,
                                supplementCards = supCopies) == null) {
                            rulesPick = null
                            rulesIdle = true
                            showRules = true
                        } else {
                            askNow = true
                        }
                    }
                }
            }

            note?.let {
                Text(it, style = snesText(13, androidx.compose.ui.graphics.Color(0xFFFFE28C)))
            }
            }   // end Place.BREW

            // ---- TALK -------------------------------------------------
            //
            // The same dialogue box and choice list Brew uses, so the
            // Paragon is answering rather than a manual being browsed. The
            // rules entries were already written (game/Rules.kt) and were
            // already reachable -- but only through a button hoisted into
            // the old top row, because the choice list they belonged in
            // vanishes whenever the agent has a question pending, which is
            // most of the time. Here there is no question pending, ever.
            if (place == Place.TALK && talkPane == null) {
                // THE PARAGON, NOT THE HANDLE. This plate opens the face
                // chooser, so it has to say what it changes. It showed the
                // league handle, which made it read as a profile button --
                // and on the dev account that handle is "PickerProbe", so
                // the control for choosing who you play as was labelled with
                // a test name.
                //
                // The handle is still worth showing, because it is the name
                // the standings call you, so it sits under the plate where
                // it is identification rather than a button.
                // AT WAIST HEIGHT, not under the tabs. Everything in Talk
                // used to start at the top of the screen, which put the
                // plate across the Paragon's face -- the one thing the
                // screen is for looking at. The clearance that used to sit
                // below this now sits above it, so the whole column starts
                // around the figure's waist and the head is left alone.
                Spacer(Modifier.height(132.dp))
                Text("Current Paragon",
                    style = snesText(11, androidx.compose.ui.graphics.Color(0xFF8898B8)))
                Nameplate(avatar.label,
                    Modifier.fillMaxWidth().clickable(enabled = token != null) {
                        pickAvatar = true
                    })
                Spacer(Modifier.height(6.dp))
                Text("\u201c${voice.talkOpening()}\u201d", style = snesText(13))
                SnesChoiceBox {
                    SnesChoice(
                        label = "Any news?",
                        detail = "What has happened in the league since we " +
                            "last looked.",
                        enabled = !busy && !newsBusy,
                        onClick = {
                            scope.launch {
                                newsBusy = true
                                val wasRound = standings?.optJSONObject("round")?.optInt("id") ?: 0
                                refresh()
                                val nowRound = standings?.optJSONObject("round")?.optInt("id") ?: 0
                                val mine = standings?.optJSONArray("standings")?.let { rows ->
                                    (0 until rows.length()).map { rows.getJSONObject(it) }
                                        .firstOrNull { it.optString("handle") == handle }
                                }
                                talkNews = when {
                                    nowRound > wasRound && mine != null ->
                                        voice.scored(nowRound,
                                            mine.optInt("placement"),
                                            standings?.optJSONObject("round")?.optInt("decks") ?: 0,
                                            mine.optInt("wins"), mine.optInt("losses"),
                                            mine.optInt("draws"))
                                    nowRound > wasRound -> voice.scoredBare(nowRound)
                                    else -> voice.nothingNew() + " " +
                                        (standings?.optJSONObject("round")?.optString("next_at")
                                            ?.takeIf { it.isNotBlank() }?.let { untilRound(it) + "." }
                                            ?: "A round is scored every half hour.")
                                }
                                newsBusy = false
                            }
                        })
                    SnesChoice(
                        label = "How the game works",
                        detail = "Plain answers about the league, the cards " +
                            "and the rules.",
                        onClick = {
                            rulesPick = null; rulesIdle = false; showRules = true
                        })
                    SnesChoice(
                        label = "Show me my cards",
                        detail = "Everything you own, and what each one does.",
                        onClick = { talkPane = "binder" })
                    SnesChoice(
                        label = "What is out of the format?",
                        detail = "The cards nobody may play at the moment.",
                        onClick = { talkPane = "bans" })
                    // The other game. A TALK TOPIC, NOT A BANNER: the player
                    // is here to build a deck, and a permanent button
                    // pointing at a different game is an advert sitting on
                    // top of the one they opened. Asked for, it is an
                    // answer; unasked, it would be a pitch.
                    SnesChoice(
                        label = "Is there another way to play?",
                        detail = "The same cards, in a game you play " +
                            "yourself.",
                        onClick = { talkPlay = true })
                }
                talkNews?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("\u201c$it\u201d", style = snesText(13))
                }
                if (talkPlay) {
                    Spacer(Modifier.height(10.dp))
                    Text("\u201c${voice.otherGame()}\u201d", style = snesText(13))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GameLink("Open the Play Store") { openAutoBattle(ctx) }
                        GameLink("Never mind") { talkPlay = false }
                    }
                }
                if (newsBusy) SnesProgress(0, 1)
            }

            // The two reference panes get a way back, since they replace the
            // conversation rather than sitting on top of it.
            if (place == Place.TALK && talkPane != null) {
                GameLink("\u2039 Back") { talkPane = null }
                Spacer(Modifier.height(8.dp))
            }


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
                onBuildAround = { buildAround(c) },
                buildBlocked = seedBlock(c),
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
                    avatar = avatar,
                    // Measured HERE, in the activity's window, because the
                    // dialog's own window spans the whole display and reports
                    // no insets at all.
                    edge = WindowInsets.safeDrawing.asPaddingValues(),
                    // The bar on its own, for the face in the corner --
                    // safeDrawing and the navigation bar do not agree inside
                    // the dialog's window, and the face is placed against
                    // the bar. See ReplayScreen's `bars`.
                    bars = WindowInsets.navigationBars.asPaddingValues(),
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
                        Text("\u201c${voice.struckOut(b.optString("name"))}\u201d",
                            style = snesText(15, androidx.compose.ui.graphics.Color(0xFFFFE28C)))
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

        // THE RULES CONVERSATION.
        //
        // A list of questions, and one answer at a time, rather than a wall
        // of text with eight headings -- the player is talking to the agent,
        // and an agent that replies with a manual is not talking. Picking a
        // question replaces the list with its answer; the list comes back
        // underneath so there is no dead end.
        if (showRules) {
            Dialog(onDismissRequest = { showRules = false }) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SnesWindow {
                        val pick = rulesPick
                        if (pick == null) {
                            Text("\u201c${voice.rulesOpen(rulesIdle)}\u201d",
                                style = snesText(15,
                                    androidx.compose.ui.graphics.Color(0xFFFFE28C)))
                            SnesChoiceBox {
                                Rules.ALL.forEachIndexed { i, qa ->
                                    SnesChoice(qa.q) { rulesPick = i }
                                }
                                SnesChoice("Nothing just now") { showRules = false }
                            }
                        } else {
                            val qa = Rules.ALL[pick]
                            Text(qa.q, style = snesText(13,
                                androidx.compose.ui.graphics.Color(0xFF8CE8A0)))
                            Text("\u201c${qa.a}\u201d", style = snesText(12,
                                androidx.compose.ui.graphics.Color(0xFFD8D8E8)))
                            SnesChoiceBox {
                                SnesChoice("Ask something else") { rulesPick = null }
                                SnesChoice("That's all") { showRules = false }
                            }
                        }
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
        // FIND A CARD. Search by name, tap to see the card, build from the
        // card itself -- rather than a "build" button on each result row.
        // A cold build is minutes of the phone's time, so the last thing
        // before committing to one should be the card at full size, with its
        // rules text, not a line in a list.
        if (cardSearch) {
            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                onDismissRequest = { cardSearch = false },
                title = { Text("\u201cWhich card?\u201d") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = cardQuery,
                            onValueChange = { cardQuery = it },
                            singleLine = true,
                            label = { Text("Card name") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (cardsById.isEmpty()) {
                            Text("Fetching the catalogue\u2026",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // WHAT CAN BE SEARCHED, built once rather than per
                        // keystroke: 1,183 cards, and the haystack for each
                        // is its name, its rules text and its tags.
                        //
                        // RETIRED CARDS ARE NOT IN IT. 358 of the 1,183 are
                        // retired, VIP, tokens or have no deck limit, and
                        // half of what a search for "bone" returned was
                        // retired -- five dead ends in ten rows, each of
                        // which could only be opened to be told no. They are
                        // not cards you may ever put in a deck, so they are
                        // not answers to "which card shall I build around".
                        //
                        // BANNED CARDS STAY, deliberately, which is why the
                        // Catalogue here is built WITHOUT the ban list. A
                        // ban lasts hours and the card is real; hiding it
                        // would answer "no such card", which is a lie. It is
                        // labelled instead, and the popup says the rest.
                        val pool = remember(rawCards, cardsById.size) {
                            val raw = rawCards
                            if (raw == null) emptyList()
                            else {
                                val cat = Catalogue(raw)
                                cardsById.values
                                    .filter { cat.isPlayable(it.id) }
                                    .map { c ->
                                        val tags = cat.tags(c.id)
                                        Findable(c, tags,
                                            (c.name + " " + c.rules + " " +
                                                tags.joinToString(" ")).lowercase())
                                    }
                            }
                        }
                        // CAPPED, and the cap is stated. An unqualified
                        // search matches half the catalogue, and a list that
                        // long inside a dialog is not a list anybody reads.
                        val hits = remember(cardQuery, pool) {
                            val q = cardQuery.trim().lowercase()
                            if (q.length < 2) emptyList()
                            else pool.filter { q in it.hay }
                                // Name first, and a name that STARTS with it
                                // before one that merely contains it:
                                // somebody typing "bone" wants Bone Colossus
                                // before Dragonbone Relic, and both before a
                                // card that only mentions bones in its text.
                                .sortedWith(
                                    compareByDescending<Findable> {
                                        it.card.name.lowercase().startsWith(q)
                                    }.thenByDescending {
                                        q in it.card.name.lowercase()
                                    }.thenBy { it.card.name })
                        }
                        when {
                            cardQuery.trim().length < 2 ->
                                Text("Two letters or more.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            hits.isEmpty() ->
                                Text("No card by that name.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> Column(
                                Modifier.heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                val q = cardQuery.trim().lowercase()
                                for (f in hits.take(40)) {
                                    val c = f.card
                                    SnesChoice(
                                        label = c.name,
                                        detail = listOfNotNull(
                                            "${c.cost}e",
                                            c.rarity.takeIf { it.isNotBlank() },
                                            c.subtype.takeIf { it.isNotBlank() },
                                            // WHY THIS ONE IS HERE. Searching
                                            // "poison" returns Scorpioid,
                                            // whose whole rules text is the
                                            // word "Envenom" -- it matched on
                                            // a tag, and without saying so
                                            // the row looks like a mistake.
                                            if (q in c.name.lowercase()) null
                                            else f.tags.firstOrNull { q in it }
                                                ?: "in its text",
                                            "out of the format"
                                                .takeIf { c.id in banned },
                                        ).joinToString(" \u00b7 "),
                                        onClick = { showCard = c },
                                    )
                                }
                                if (hits.size > 40) {
                                    Text("\u2026and ${hits.size - 40} more. " +
                                        "Type a little more of the name.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { cardSearch = false }) { Text("Close") }
                },
            )
        }

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

        // THE QUICK-BUILD PICKER.
        //
        // Shows what each shell actually DID, not a rating: "21.4 wins of 38
        // in round 746". A percentage would hide that a shell measured
        // against 39 decks is not comparable to one measured against 12, and
        // the round number is what tells a player how fresh the figure is.
        if (showShells) {
            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                onDismissRequest = { showShells = false },
                title = { Text("\u201c${voice.whichShell()}\u201d") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("\u201c${voice.shellsExplained()}\u201d",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val list = shells
                        when {
                            shellsError != null -> Text(shellsError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                            list == null -> SnesProgress(0, 1)
                            list.isEmpty() -> Text("\u201c${voice.shellsEmpty()}\u201d",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> Column(
                                Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                for (sh in list) {
                                    val on = sh.name == directive.shell
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                directive = directive.copy(
                                                    shellName = sh.name,
                                                    // The shell IS the idea, so a
                                                    // theme or seed card chosen
                                                    // earlier would now be describing
                                                    // a build that is not happening.
                                                    themes = emptyList(),
                                                    themeLabels = emptyList(),
                                                    seedCard = null, seedCardName = null)
                                                Directive.save(ctx, directive)
                                                showShells = false
                                                error = null; note = null; trial = null
                                                TrainingService.start(ctx)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                    ) {
                                        Text((if (on) "\u25c6 " else "") + sh.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (on) GoldBright
                                                    else MaterialTheme.colorScheme.primary)
                                        Text(String.format("%.1f", sh.wins) +
                                            " wins of ${sh.opponents}" +
                                            " in round ${sh.round}" +
                                            "  \u00b7  ${sh.distinct} distinct cards",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showShells = false }) { Text("Not now") }
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
                title = { Text("\u201c${voice.askNote()}\u201d") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("\u201c${voice.noteWhy()}\u201d",
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

        // HOW LONG TO LEAVE IT. A fixed three rounds was a guess about how
        // patient a player feels, and that depends on whether they are about
        // to put the phone down for the evening or watching the next round
        // land. Rounds rather than minutes, because rounds are the unit the
        // league actually moves in; the times are given as a gloss.
        if (pickWait) {
            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                onDismissRequest = { pickWait = false },
                title = { Text("\u201c${voice.askHowLong()}\u201d") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("\u201c${voice.waitingExplained()}\u201d",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for ((n, gloss) in RoundWatch.CHOICES) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        // Only the COUNT is recorded. Which
                                        // round to report on is resolved by
                                        // the worker from live standings --
                                        // this screen's idea of the current
                                        // round is whatever it last fetched,
                                        // and a stale one made the agent
                                        // report back almost immediately.
                                        mem = mem.copy(waitRounds = n,
                                                       waitUntilRound = 0)
                                        Memory.save(ctx, mem)
                                        RoundWatch.start(ctx)
                                        justBuilt =
                                            "\u201c${voice.beingPatient(n)}\u201d"
                                        pickWait = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (n == 1) "1 round" else "$n rounds",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(gloss,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { pickWait = false }) { Text("Never mind") }
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
                // NOT IN VOICE, and that is the point. Every other dialogue
                // on this screen is the agent speaking, but the agent is one
                // of the three things being chosen here -- having Kard Batal
                // ask which of Kard Batal, Dan Mage and Emma Ji you would
                // prefer is the incoherence the old "whose face shall I
                // wear?" was hiding. This is the player's choice, so it is
                // addressed to the player.
                title = { Text("Choose your Paragon") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // The chooser is where a new player meets the word,
                        // so it says what one IS -- and says plainly that the
                        // choice is register, not strength, because a player
                        // who suspects otherwise will pick on superstition.
                        Text("The one who builds with you at the dojo. They " +
                            "all play the same game; they differ in how they " +
                            "talk about it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                // A thumbnail of the real thing: a name alone
                                // says nothing about a picture you have not
                                // seen. The tile is filled and clipped so the
                                // six rows line up whatever is in them.
                                Box(
                                    Modifier.size(44.dp, 60.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(androidx.compose.ui.graphics
                                            .Color(0xFF14142A)),
                                ) {
                                    Image(
                                        painter = androidx.compose.ui.graphics.painter.BitmapPainter(
                                            androidx.compose.ui.graphics.ImageBitmap
                                                .imageResource(a.art),
                                            filterQuality =
                                                androidx.compose.ui.graphics.FilterQuality.None),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        alignment = Alignment.TopCenter,
                                        // A CUT-OUT IS A WHOLE STANDING FIGURE,
                                        // head to boots, in a tall frame. Card
                                        // art is a scene already composed around
                                        // its subject, so it reads at any size;
                                        // a full-length figure shrunk to 60dp is
                                        // an ant. Scaled from the TOP so what
                                        // fills the tile is the face, which is
                                        // the thing being chosen.
                                        modifier = Modifier.fillMaxSize().let {
                                            if (!a.cutout) it else it.graphicsLayer {
                                                scaleX = 2.4f; scaleY = 2.4f
                                                transformOrigin =
                                                    androidx.compose.ui.graphics
                                                        .TransformOrigin(0.5f, 0f)
                                            }
                                        })
                                }
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

        // STARTING AGAIN IS A NEW CAREER, NOT A DELETION.
        //
        // It used to throw the deck away and say so. Keeping it costs
        // nothing -- the shelf is already there -- and it turns the most
        // destructive control on the screen into a change of direction,
        // which is what the player actually means by it. The new Paragon
        // follows for the same reason: a run under a different face is the
        // fiction that was already implied.
        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text("“${voice.startAgainAsk()}”") },
                text = { Text("“${voice.startAgainWhy()}”") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmReset = false; uiBusy = true; error = null; note = null
                        scope.launch {
                            // SHELVE FIRST. POST /decks/saved snapshots the
                            // deck that is LIVE and has no way to take a
                            // supplied card list, so once the reset has run
                            // the old deck is unreachable. The shelf holds
                            // twelve and answers 409 when full; that must not
                            // abort the reset the player asked for, so it is
                            // noted and carried past.
                            var kept = false
                            if (myDeck != null) {
                                kept = runCatching {
                                    withContext(Dispatchers.IO) {
                                        GameClient(token).saveDeck("Before starting again")
                                    }
                                }.isSuccess
                            }
                            runCancellable {
                                withContext(Dispatchers.IO) { GameClient(token).resetDeck() }
                            }.onSuccess {
                                val nm = it.optString("name")
                                justBuilt = "“" + (if (kept) voice.startedAgain(nm)
                                                   else voice.startedAgainUnsaved(nm)) + "”"
                                mem = Memory()
                                Memory.save(ctx, mem)
                                directive = Directive()
                                Directive.save(ctx, directive)
                                // A bench deck belonged to the run that just
                                // ended; carrying it into a new one would be
                                // the old career leaking into the new.
                                Workbench.clear(ctx); bench = null
                                refresh()
                                // The face is chosen last, so the player sees
                                // what they are choosing FOR.
                                pickAvatar = true
                            }.onFailure { error = readable(it) }
                            uiBusy = false
                        }
                    }) { Text("Begin again") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmReset = false }) { Text("Never mind") }
                })
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }

            if (place == Place.TALK && talkPane == "binder") {
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
                                                    note = "\u201c" + voice.rebuiltWithout(
                                                        r.removed.joinToString(", ") {
                                                            cardsById[it]?.name ?: "card $it"
                                                        },
                                                        r.added.take(2).joinToString(" and ") {
                                                            cardsById[it.first]?.name ?: "card"
                                                        }) + "\u201d"
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
                                                    note = "\u201c" +
                                                        voice.backInPlay(it.optString("name")) +
                                                        "\u201d"
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

        if (place == Place.TALK && talkPane == "bans") {
            LaunchedEffect(token) { ensureNames() }
            SnesWindow {
                Text("Out of the format", style = snesText(15, androidx.compose.ui.graphics.Color(0xFFFFE28C)))
                Text("These cards cannot be played. The list rotates \u2014 the " +
                    "oldest comes back when a new one goes out.",
                    style = snesText(12, androidx.compose.ui.graphics.Color(0xFF8A8AA8)))
            }
            val bl = banList
            if (bl == null) {
                SnesWindow { Text("\u201c${voice.checkingBans()}\u201d",
                    style = snesText(13)) }
            } else if (bl.length() == 0) {
                SnesWindow {
                    Text("\u201c${voice.nothingBanned()}\u201d", style = snesText(13))
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
            // THE RESULT SITS ABOVE THE LIST, not after it.
            //
            // It used to render below all eight rows -- about two screens
            // past the Watch that started it -- so the progress text
            // finished, the button came back, and the answer was somewhere
            // the player could not see. That reads as a button that does
            // nothing, and was reported as exactly that.
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
                            // TAKE AN OLD DECK BACK INTO PLAY.
                            //
                            // The snapshot that makes a round watchable is the
                            // same list that played it, so going back to a
                            // revision that was doing better costs nothing new
                            // -- the decks are already stored. Offered only on
                            // a revision that is NOT the one in play, because
                            // restoring the deck you are already running is a
                            // button that does nothing.
                            val mineRev = myDeck?.optInt("revision") ?: -1
                            if (r.optInt("revision") != mineRev) {
                                if (reviveBusy == rid) {
                                    Text("Taking it back\u2026",
                                        style = snesText(12,
                                            androidx.compose.ui.graphics.Color(0xFFFFE28C)))
                                } else GameLink("Use again") {
                                    error = null; note = null; reviveBusy = rid
                                    scope.launch {
                                        runCancellable {
                                            withContext(Dispatchers.IO) {
                                                val c = GameClient(token)
                                                val env = c.replay(rid)
                                                val arr = env.getJSONArray("decks")
                                                val mine = (0 until arr.length())
                                                    .map { arr.getJSONObject(it) }
                                                    .firstOrNull { it.optBoolean("is_me") }
                                                    ?: error("that round has no deck of ours")
                                                val cs = mine.getJSONArray("cards")
                                                val entries = (0 until cs.length()).map {
                                                    val e = cs.getJSONObject(it)
                                                    e.optInt("card_id") to e.optInt("quantity")
                                                }
                                                // An old list almost certainly
                                                // holds a card banned since, so
                                                // repair rather than refuse.
                                                val cat = Catalogue(rawCards ?: c.cards().first)
                                                val fixed = Repair.repair(entries, banned, cat)
                                                    ?: error("that deck cannot be made legal now")
                                                c.putDeck(mine.optString("deck_name"),
                                                    fixed.cards.flatMap { (id, q) -> List(q) { id } },
                                                    mine.optJSONObject("battle_plan"))
                                                fixed
                                            }
                                        }.onSuccess { fixed ->
                                            note = "\u201c" + (
                                                if (fixed.removed.isEmpty())
                                                    voice.restored(rid)
                                                else voice.restoredWithout(rid,
                                                    fixed.removed.joinToString(", ") {
                                                        cardsById[it]?.name ?: "card $it"
                                                    } + " \u2014 banned since")
                                            ) + "\u201d"
                                            refresh()
                                        }.onFailure { error = readable(it) }
                                        reviveBusy = null
                                    }
                                }
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
                        // THREE KINDS, not two. Only a GOD is titled by
                        // standing; a Paragon is a fellow duelist and reads
                        // as one, and a person is untouched. Decided by
                        // faction, never by the handle.
                        val kind = Entities.kindOf(r.optInt("is_bot"),
                                                   r.optString("faction"))
                        Text(
                            if (kind == Entities.Kind.GOD)
                                Entities.titled(r.optString("handle"),
                                                r.optInt("placement"))
                            else r.optString("handle"),
                            style = snesText(13, when {
                                mine -> androidx.compose.ui.graphics.Color(0xFFFFE28C)
                                kind == Entities.Kind.GOD ->
                                    androidx.compose.ui.graphics.Color(0xFFE89CB0)
                                kind == Entities.Kind.PARAGON ->
                                    androidx.compose.ui.graphics.Color(0xFF9CD8E8)
                                else -> androidx.compose.ui.graphics.Color(0xFFF2EFE4)
                            }),
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

        // ---- Place.LEAGUE: the season ladder ----------------------------
        //
        // THE THIRD TABLE, and the only one that accumulates. The Dojo's
        // table is the last round, which bounces for reasons that have
        // nothing to do with the list; `myForm` above it is a 48-round
        // average, which is steady but forgets -- a player whose new build
        // just took first place is still rated by the rounds its
        // predecessors played, so their best night reads as mid-table.
        //
        // A ladder has to let a good round COUNT, which means adding points
        // up, which means a season: without one the house agents have been
        // here since round one and are out of reach by arithmetic rather
        // than by merit.
        if (place == Place.LEAGUE) {
            val sn = season
            val meta = sn?.optJSONObject("season")
            val tbl = sn?.optJSONArray("table")
            if (sn == null) {
                Text(if (seasonTried) "The ladder could not be read." else
                     "\u201cLet me see how the season stands\u2026\u201d",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SnesWindow {
                    val num = meta?.optInt("number") ?: 0
                    val len = meta?.optInt("length") ?: 0
                    val scored = meta?.optInt("scored") ?: 0
                    val left = meta?.optInt("rounds_left") ?: 0
                    val current = (meta?.optInt("current") ?: 0) == 1
                    Text("SEASON $num",
                        style = snesText(14, androidx.compose.ui.graphics.Color(0xFFFFE28C)))
                    // SCORED, not "round 34 of 96". The two differ whenever
                    // the scorer missed a round, and the one place a player
                    // checks their progress is the worst place to round a
                    // number up.
                    Text(
                        if (!current && scored == 0) "Not played yet."
                        else if (!current) "Finished \u2014 $scored rounds scored."
                        // ROUNDS, NOT HOURS. This said "about 31 hours",
                        // computed from the half-hourly schedule -- and the
                        // schedule is not what the league keeps. Measured
                        // 2026-10-28, the gaps between recorded rounds ran
                        // 25, 28, 30, 34, 36, 57, 59, 61, 64 and once 182
                        // minutes, because scoring competes for a single
                        // core and a run that overruns is killed and records
                        // nothing. A countdown that is precise and wrong is
                        // worse than a count that is simply true.
                        else "$scored of $len rounds scored" +
                            (if (left <= 0) ", and the season is done."
                             else if (left == 1) ". One round to go."
                             else ". $left to go."),
                        style = snesText(12, androidx.compose.ui.graphics.Color(0xFF8A8AA8)))
                    // The rule, stated. A ladder whose numbers cannot be
                    // accounted for is a ladder a player cannot aim at.
                    Text("A point for every deck you beat, every round. " +
                        "Taking a round is worth 10 more, second 6, third 3.",
                        style = snesText(12, androidx.compose.ui.graphics.Color(0xFF8A8AA8)))

                    if (tbl == null || tbl.length() == 0) {
                        Text("No round of this season has been scored yet.",
                            style = snesText(12, androidx.compose.ui.graphics.Color(0xFF8A8AA8)))
                    }
                    for (i in 0 until (tbl?.length() ?: 0)) {
                        val r = tbl!!.getJSONObject(i)
                        val who = r.optString("handle")
                        val mine = who == handle
                        val kind = Entities.kindOf(r.optInt("is_bot"),
                                                   r.optString("faction"))
                        Row(Modifier.fillMaxWidth()
                            .background(if (mine)
                                androidx.compose.ui.graphics.Color(0x33FFE28C)
                                else androidx.compose.ui.graphics.Color.Transparent)
                            // ONLY ON THE SEASON IN PROGRESS. `field` holds
                            // everyone's deck as it is NOW, so opening a row
                            // on a finished ladder would present today's
                            // hundred cards as the list that earned those
                            // points -- which for anyone still iterating is
                            // simply a different deck. The round snapshots
                            // that would answer it honestly are pruned after
                            // 96 rounds, so there is nothing to show and the
                            // row should not pretend otherwise.
                            .clickable(enabled = current) {
                                openDeck = if (openDeck == who) null else who
                                if (openDeck != null) scope.launch { ensureNames() }
                            }
                            .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (mine) "\u25b6" else " ", Modifier.width(14.dp),
                                style = snesText(12, androidx.compose.ui.graphics.Color(0xFFFFE28C)))
                            Text("${r.optInt("rank")}".padStart(2), Modifier.width(26.dp),
                                style = snesText(13, if (mine)
                                    androidx.compose.ui.graphics.Color(0xFFFFE28C)
                                    else androidx.compose.ui.graphics.Color(0xFFF2EFE4)))
                            Column(Modifier.weight(1f)) {
                                // Titled by LADDER RANK here, not by last
                                // round's placement: on this screen the
                                // standing being described is the season's,
                                // and a god styled Sovereign for one good
                                // round while sitting ninth all season would
                                // be the screen contradicting itself.
                                Text(
                                    if (kind == Entities.Kind.GOD)
                                        Entities.titled(who, r.optInt("rank"))
                                    else who,
                                    style = snesText(13, when {
                                        mine -> androidx.compose.ui.graphics.Color(0xFFFFE28C)
                                        kind == Entities.Kind.GOD ->
                                            androidx.compose.ui.graphics.Color(0xFFE89CB0)
                                        kind == Entities.Kind.PARAGON ->
                                            androidx.compose.ui.graphics.Color(0xFF9CD8E8)
                                        else -> androidx.compose.ui.graphics.Color(0xFFF2EFE4)
                                    }),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(r.optString("deck_name"),
                                    style = snesText(11, androidx.compose.ui.graphics.Color(0xFF8A8AA8)),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${r.optInt("points")}",
                                    style = snesText(13, if (mine)
                                        androidx.compose.ui.graphics.Color(0xFFFFE28C)
                                        else androidx.compose.ui.graphics.Color(0xFFD8D8E8)))
                                // The best thing they did, in the space the
                                // round record occupies on the Dojo's table.
                                // Rounds won if they have any, because that
                                // is what a ladder is about; otherwise the
                                // best finish, so every row says something.
                                val firsts = r.optInt("firsts")
                                val best = r.optInt("best_place")
                                Text(
                                    if (firsts > 0) "won $firsts"
                                    else if (best > 0) "best ${ordinal(best)}"
                                    else "",
                                    style = snesText(11, androidx.compose.ui.graphics.Color(0xFF8A8AA8)))
                            }
                        }
                        // Said once, under the player's own row, and only
                        // when it is true: a mid-season joiner is low on the
                        // ladder for a reason that is not their deck, and
                        // the screen should say so rather than let them
                        // conclude the obvious wrong thing.
                        if (mine) {
                            // The round itself, by number, because the Dojo
                            // can play it back and a point total cannot be
                            // watched. A ladder that accumulates has to be
                            // able to point at the night it accumulated from
                            // or the number is just a number.
                            val bp = r.optInt("best_place")
                            val br = r.optInt("best_round")
                            if (bp > 0 && br > 0) {
                                Text(
                                    if (bp == 1)
                                        "    You took round $br, for " +
                                        "${r.optInt("best_pts")} points."
                                    else
                                        "    Your best was round $br \u2014 " +
                                        "${ordinal(bp)}, ${r.optInt("best_pts")} points.",
                                    style = snesText(11,
                                        androidx.compose.ui.graphics.Color(0xFFFFE28C)))
                            }
                            if (r.optInt("joined_late") == 1) {
                                val n = r.optInt("rounds")
                                Text("    You joined part-way through: $n of " +
                                    "the season's $scored scored rounds are " +
                                    "yours. Season ${num + 1} starts everyone level.",
                                    style = snesText(11,
                                        androidx.compose.ui.graphics.Color(0xFF8CE8A0)))
                            }
                        }
                        // `current` again, and not just on the tap: openDeck
                        // is shared with the Dojo's table, so a row left open
                        // there would otherwise expand itself here.
                        if (current && openDeck == who) {
                            val cards = field[who]?.optJSONArray("cards")
                            if (cards == null) {
                                Text("    their deck is not on file",
                                    style = snesText(12, androidx.compose.ui.graphics.Color(0xFF8A8AA8)))
                            } else {
                                Box(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 8.dp)) {
                                    Column {
                                        RivalPockets(cards, cardsById) { showCard = it }
                                    }
                                }
                            }
                        }
                    }
                }
                // Past seasons are readable because a season you won is the
                // only lasting thing this game hands out, and a ladder that
                // forgets it the moment the next one starts would be handing
                // out nothing.
                val num = meta?.optInt("number") ?: 0
                val current = (meta?.optInt("current") ?: 0) == 1
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (num > 1) {
                        GameLink("\u25c0 Season ${num - 1}", Modifier.weight(1f)) {
                            seasonView = num - 1
                        }
                    }
                    if (!current) {
                        GameLink("Season ${num + 1} \u25b6", Modifier.weight(1f)) {
                            // The season in PROGRESS is asked for as 0, not
                            // by number: a ladder reached by arrow and one
                            // reached by opening the screen must be the same
                            // ladder, and only 0 is guaranteed to still be
                            // the live one tomorrow.
                            seasonView = if (num + 1 >= (seasonNow ?: (num + 1))) 0 else num + 1
                        }
                    }
                }
            }
        }   // end Place.LEAGUE
    }
}


/**
 * 1st, 2nd, 3rd, 4th -- and 11th, 12th, 13th, which the obvious version of
 * this gets wrong.
 */
private fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
        1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
    }
    return "$n$suffix"
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
    "What a battle plan is" to
        "A deck here is two things: the hundred cards, and a battle plan. " +
        "Nobody plays the battles \u2014 the deck fights them on its own, and " +
        "the plan is the standing orders it fights by. The cards decide " +
        "what it is capable of; the plan decides what it actually does on a " +
        "turn where several things are possible. Two players with the same " +
        "hundred cards and different plans do not have the same deck. I " +
        "have always written one of these; until now I wrote the same one " +
        "every time and never said so.",
    "How should it carry itself" to
        "Whether the deck spends what it has each turn or waits for a " +
        "better one. Waiting is stronger than it sounds and worse than it " +
        "looks: a turn spent holding energy is a turn where nothing of ours " +
        "reaches the board, and against a fast deck that is the game. " +
        "\u201cBuild to one big turn\u201d waits once, early, and then plays " +
        "normally; \u201cnever take a weak turn\u201d keeps the rule all game. " +
        "Both of those also have me keep energy aside for the best card in " +
        "the playing order \u2014 without that, our best card can sit in hand " +
        "all game while cheaper ones spend the energy it needed. That has " +
        "happened to a real deck for twenty turns together. I reserve it " +
        "for that one card only: an earlier version of the game reserved it " +
        "for whichever good card was in hand, and decks sat on ten energy " +
        "playing nothing at all.",
    "What are we taking from them" to
        "Which of their permanents my effects reach for, when the card " +
        "itself does not name a target. Worth knowing that the health " +
        "comparison can only see things that HAVE health, so structures, " +
        "relics and enchantments are passed over by \u201cclear their " +
        "board\u201d and are fair game for the other two.",
    "Paying a card's extra cost" to
        "Some cards offer to do more for an extra cost \u2014 more energy, a " +
        "sacrifice, some honour. Paying is optional and the card gets played " +
        "either way, so one of these is never a dead card. The question is " +
        "only whether the bonus is worth what it takes out of the turn: a " +
        "card that eats five energy to do more has also stopped us doing " +
        "anything else. I only raise this if the deck actually holds cards " +
        "that offer it, and I settle it by playing it if you let me measure " +
        "the orders.",
    "Why I do not simply pick the best plan" to
        "Because I do not know which it is, and neither does anybody. I " +
        "can measure a deck against the field, but I have never measured " +
        "these orders against each other on YOUR deck \u2014 and the one " +
        "lesson this project has paid for is that searching finds what " +
        "works while reasoning about mechanisms does not. Eight changes I " +
        "argued for from first principles all made the deck worse. So the " +
        "orders are yours to give, and what I tell you about each one is " +
        "its cost, not a recommendation.",
    "Show me what it can do" to
        "Plays the current deck against the live field on this phone, on " +
        "seeds it has never seen. It changes nothing \u2014 the league plays " +
        "its own games at the next round.",
)

/**
 * Cards drifting slowly across, for the stretch of a build that has nothing
 * else to report.
 *
 * REAL FACES WHERE THERE ARE ANY. The first version drew card backs, because
 * at "Consulting the catalogue" the app genuinely does not know what any
 * card is -- the catalogue has not arrived and CardIndex starts a launch
 * with an empty art cache. That is true for about a second. By "Drafting"
 * and "Comparing N drafts", which is nearly all of the wait, the catalogue
 * IS loaded, so there is no reason to keep showing the backs.
 *
 * So it drifts faces once it has them and falls back to the back until then:
 * a slot shows art the moment its download lands, and a card with no art on
 * the server simply keeps the back. Nothing waits for everything.
 *
 * Deterministic per composition: the lanes and the chosen cards are
 * remembered, so a recomposition -- and they arrive steadily while a build
 * runs -- does not reshuffle the deck mid-flight.
 */
@Composable
private fun DriftingCards(cards: Map<Int, CardInfo>, modifier: Modifier = Modifier) {
    // (lane, scale, seconds to cross) to starting offset
    val lanes = remember {
        listOf(
            Triple(0.08f, 0.62f, 26f) to 0.00f,
            Triple(0.52f, 0.85f, 19f) to 0.37f,
            Triple(0.26f, 0.48f, 33f) to 0.61f,
            Triple(0.70f, 0.70f, 23f) to 0.14f,
            Triple(0.40f, 0.55f, 29f) to 0.82f,
        )
    }
    val picks = remember(cards.size) {
        cards.values.filter { !it.imageUrl.isNullOrBlank() }.shuffled().take(lanes.size)
    }
    var faces by remember(picks) {
        mutableStateOf<List<ImageBitmap?>>(List(lanes.size) { null })
    }
    LaunchedEffect(picks) {
        // One at a time, off the main thread, each landing on its own. A
        // failed or missing download leaves that lane on the card back
        // rather than blanking it.
        picks.forEachIndexed { i, c ->
            val art = withContext(Dispatchers.IO) { CardIndex.art(c.imageUrl!!) }
            if (art != null) {
                faces = faces.toMutableList().also { if (i < it.size) it[i] = art }
            }
        }
    }
    val clock = rememberInfiniteTransition(label = "drift")
    val phases = lanes.map { (lane, _) ->
        clock.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = (lane.third * 1000).toInt(),
                    easing = LinearEasing),
                repeatMode = RepeatMode.Restart),
            label = "lane",
        )
    }
    BoxWithConstraints(modifier.fillMaxWidth().height(92.dp).clipToBounds()) {
        val band = maxHeight
        val wide = maxWidth
        lanes.forEachIndexed { i, (lane, offset) ->
            // A card keeps the real proportions of the bundled back.
            val h = band * lane.second
            val w = h * 0.727f
            val t = (phases[i].value + offset) % 1f
            // Right to left: cards read as being dealt PAST you rather than
            // away from you.
            val x = wide - (wide + w) * t
            Box(
                Modifier
                    .offset(x = x, y = (band - h) * lane.first)
                    .size(w, h)
                    // Nearer cards are bigger AND brighter, the cheapest
                    // depth cue there is.
                    .alpha(0.30f + 0.45f * lane.second)
                    .clip(RoundedCornerShape(3.dp))
                    .border(1.dp, androidx.compose.ui.graphics.Color(0x55FFE28C),
                        RoundedCornerShape(3.dp)),
            ) {
                val face = faces.getOrNull(i)
                if (face != null) {
                    Image(face, null, Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter)
                } else {
                    Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.card_back),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * What the Paragon is doing with the cards, while they do it.
 *
 * Newest pass FIRST: the thing a player wants on glancing down is what is
 * happening now, and a log that grows downward pushes it off the screen.
 * Older passes stay below so the session reads as a record of work.
 *
 * The honesty rule for this view: it shows what the search did, never a
 * dressed-up version of it. A pass where nothing was good enough says so,
 * because that is the common case and hiding it would make the agent look
 * like it improves the deck every time, which it does not.
 */
@Composable
private fun DojoBench(passes: List<Compete.Bench>, now: Compete.Progress?) {
    if (passes.isEmpty()) {
        // The stages before the climb genuinely are one long wait.
        now?.step?.let {
            Text("\u201c$it\u2026\u201d", style = snesText(12,
                androidx.compose.ui.graphics.Color(0xFFA8C8E8)))
        }
        return
    }
    val gold = androidx.compose.ui.graphics.Color(0xFFFFE28C)
    val green = androidx.compose.ui.graphics.Color(0xFF8CE8A0)
    val dim = androidx.compose.ui.graphics.Color(0xFF8898B8)
    val plain = androidx.compose.ui.graphics.Color(0xFFD8D8E8)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in passes.asReversed()) {
            Text("Pass ${b.pass} of ${b.of}", style = snesText(12, gold))
            if (b.verdict == null) {
                // Laid out, not yet measured. Named in full, because the
                // point of watching is seeing WHICH cards were considered.
                for (a in b.attempts.take(10)) {
                    Text("  ${a.adds}  \u2190  ${a.cuts}", style = snesText(11, dim))
                }
                Text("  weighing them\u2026", style = snesText(11, dim))
            } else {
                for (a in b.attempts.sortedByDescending { it.wins ?: -1.0 }.take(10)) {
                    val w = a.wins?.let { String.format("%.1fW", it) } ?: "\u2014"
                    Text((if (a.kept) "  \u2713 " else "    ") +
                         "${a.adds}  \u2190  ${a.cuts}   $w",
                         style = snesText(11, if (a.kept) green else plain))
                }
                Text("  ${b.verdict}", style = snesText(11,
                    if (b.attempts.any { it.kept }) green else dim))
            }
        }
    }
}

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

/**
 * One searchable card: the card, its tags, and everything searchable about it
 * flattened and lowercased.
 *
 * The haystack is precomputed because the alternative is lowercasing a
 * thousand names, rules texts and tag lists on every keystroke. The tags are
 * kept apart from it so a row can say WHICH one it matched.
 */
private class Findable(
    val card: CardInfo,
    val tags: Set<String>,
    val hay: String,
)

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
    "A round is scored every half hour"
}

/**
 * Open AutoBattle's Play listing.
 *
 * The market:// form hands the request straight to the installed Play app
 * and is what lands on the listing rather than in a browser; it throws
 * ActivityNotFoundException on a device without Play, so the https form is
 * the fallback rather than the first choice. Both are the same listing.
 */
private fun openAutoBattle(ctx: android.content.Context) {
    val id = "com.autobattle.game"
    val play = "com.android.vending"
    val go = { uri: String, pkg: String? ->
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW,
                                   android.net.Uri.parse(uri))
                .setPackage(pkg)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    // NAME THE STORE. market:// on its own is not unambiguous: on a Samsung
    // the Galaxy Store claims it too, so the tap produced an "Open with"
    // chooser between two shops instead of the listing. Measured on the S22.
    // The listing is a Google Play listing, so ask Google Play for it.
    //
    // Each fallback is a real device, not defensive padding: a phone with
    // Play missing or disabled takes the plain market:// (whatever store it
    // does have), and one with neither takes the browser.
    try {
        go("market://details?id=$id", play)
    } catch (e: Exception) {
        try {
            go("market://details?id=$id", null)
        } catch (e2: Exception) {
            try {
                go("https://play.google.com/store/apps/details?id=$id", null)
            } catch (e3: Exception) {
                // No store and no browser. Nothing useful left to do, and a
                // crash here would be over a side remark in a conversation.
            }
        }
    }
}
