package online.autobattle.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.autobattle.agent.chat.Builder
import online.autobattle.agent.chat.Counter
import online.autobattle.agent.chat.DeckRef
import online.autobattle.agent.chat.Grammar
import online.autobattle.agent.chat.Improver
import online.autobattle.agent.chat.Inspect
import online.autobattle.agent.chat.Intent
import online.autobattle.agent.api.ApiClient
import online.autobattle.agent.data.Analysis
import online.autobattle.agent.data.Analyst
import online.autobattle.agent.data.Journal
import online.autobattle.agent.data.Secrets
import online.autobattle.agent.engine.Catalogue
import org.json.JSONObject
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A change the player may apply, and everything needed to confirm it honestly.
 *
 * Only offered for results that CLEARED the acceptance gate. A change below
 * the bar "cannot be told apart from your deck as it stands" -- applying it is
 * neither an improvement nor a mistake, it is churn, and the project's own
 * measurements show that stacking such steps tunes a deck to its seed block
 * rather than to the game. The app will not put a button on that.
 */
data class Proposal(
    val deckId: Int,
    val deckName: String,
    val summary: String,
    /** What the measurement actually said, shown at the point of decision. */
    val verdict: String,
    val recommended: Boolean,
    val adds: List<Pair<Int, Int>>,
    val removes: List<Pair<Int, Int>>,
    val cards: List<Int>,
    val plan: org.json.JSONObject,
)

/** One entry in the transcript. */
sealed interface Line {
    data class Said(val fromAgent: Boolean, val text: String) : Line
    data class Applied(val deckName: String, val summary: String) : Line
    data class Analysed(val a: Analysis) : Line
    data class Built(val o: Builder.Outcome, val cat: Catalogue) : Line
    data class Improved(val r: Improver.Result, val cat: Catalogue) : Line
    data class Countered(val r: Counter.Result, val cat: Catalogue) : Line
    data class Inspected(val r: Inspect.Result, val cat: Catalogue) : Line
}

/**
 * Dispatches a parsed intent. Pure of Compose so it can be tested directly.
 *
 * Verbs the grammar understands but the app cannot yet perform answer plainly
 * instead of doing something approximate. A wrong guess here costs the player
 * thirty seconds of phone CPU before they find out they were misheard, which
 * is a worse outcome than being told no immediately.
 */
class ChatEngine(
    private val cat: Catalogue,
    private val builder: Builder,
    private val grammar: Grammar,
    private val analyse: (DeckRef) -> Analysis,
    private val improve: (DeckRef) -> Improver?,
    private val counter: () -> Counter?,
    private val inspect: () -> Inspect?,
) {
    fun respond(text: String, onProgress: (String) -> Unit): List<Line> =
        when (val i = grammar.parse(text)) {
            is Intent.Help -> listOf(Line.Said(true, HELP))

            is Intent.Card -> {
                val ins = inspect()
                if (ins == null) listOf(Line.Said(true, "I could not load your deck."))
                else {
                    val r = ins.run(i.cardId, onProgress = onProgress)
                    if (r == null) listOf(Line.Said(true,
                        "There is nothing I can try with that card in this deck."))
                    else listOf(Line.Inspected(r, cat))
                }
            }

            is Intent.Analyse -> {
                onProgress("Simulating your deck against the field…")
                listOf(Line.Analysed(analyse(i.deck)))
            }

            is Intent.Build -> {
                onProgress("Building “${i.tag}” candidates…")
                val o = builder.run(i.tag, onProgress = onProgress)
                if (o == null) listOf(Line.Said(true,
                    "I could not find enough playable ${i.tag} cards to headline a deck."))
                else listOf(Line.Built(o, cat))
            }

            is Intent.Improve -> {
                val imp = improve(i.deck)
                if (imp == null) listOf(Line.Said(true, "I could not load that deck."))
                else {
                    val r = imp.run(onProgress = onProgress)
                    if (r == null) listOf(Line.Said(true,
                        "There are no unplayed cards left to try against that deck."))
                    else listOf(Line.Improved(r, cat))
                }
            }

            is Intent.Beat -> {
                val c = counter()
                val target = c?.find(i.who)
                when {
                    c == null -> listOf(Line.Said(true, "I could not load your deck."))
                    target == null -> listOf(Line.Said(true,
                        "I cannot find a deck called “${i.who}” in the current " +
                            "field. Decks I can see include: " +
                            c.names().joinToString(", ") + "."))
                    else -> {
                        onProgress("Looking for an answer to ${target.name}…")
                        val r = c.run(target, onProgress = onProgress)
                        if (r == null) listOf(Line.Said(true,
                            "I could not build a variant to test against ${target.name}."))
                        else listOf(Line.Countered(r, cat))
                    }
                }
            }

            is Intent.Unsure -> listOf(Line.Said(true, buildString {
                append("I did not follow that. I can analyse your deck, or build one " +
                    "around a theme.")
                if (i.nearestTags.isNotEmpty())
                    append(" Did you mean ${i.nearestTags.joinToString(", ")}?")
            }))
        }

    companion object {
        const val HELP =
            "Three things work today:\n\n" +
                "•  analyse my deck — score it against the live field and show " +
                "which matchups cost you (about 3 seconds)\n" +
                "•  improve my deck — try every card the field never plays, then " +
                "validate the best one properly (about a minute)\n" +
                "•  build me a poison deck — generate candidates around a theme " +
                "and measure them against the field's best\n" +
                "•  beat Hymn — search for an answer to one named deck, then check " +
                "it has not cost you the rest of the field\n" +
                "•  what does Bomber Bee do in my deck — measure one card you name, " +
                "both adding more of it and cutting it entirely"
    }
}

@Composable
fun Transcript(
    lines: List<Line>,
    modifier: Modifier = Modifier,
    canWrite: Boolean = false,
    onApply: (Proposal) -> Unit = {},
) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) state.animateScrollToItem(lines.size - 1)
    }
    LazyColumn(
        modifier, state = state,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(lines) { l ->
            when (l) {
                is Line.Said -> Bubble(l.fromAgent) {
                    Text(l.text, style = MaterialTheme.typography.bodyMedium)
                }
                is Line.Applied -> Bubble(true) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Applied to ${l.deckName}.", color = Good,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(l.summary, color = Subtle,
                            style = MaterialTheme.typography.bodySmall)
                        Text(
                            "It takes effect in the next cohort your deck enters. " +
                                "Ask me to analyse it again once a few have finished.",
                            color = Subtle, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is Line.Analysed -> Bubble(true) { AnalysisCard(l.a) }
                is Line.Built -> Bubble(true) { BuiltCard(l.o, l.cat) }
                is Line.Improved -> Bubble(true) {
                    ImprovedCard(l.r, l.cat)
                    Census(l.r.census, l.cat, l.r.screened)
                    ApplyRow(proposalFor(l.r, l.cat), canWrite, onApply)
                }
                is Line.Countered -> Bubble(true) {
                    CounteredCard(l.r, l.cat)
                    ApplyRow(proposalFor(l.r, l.cat), canWrite, onApply)
                }
                is Line.Inspected -> Bubble(true) { InspectCard(l.r, canWrite, onApply) }
            }
        }
    }
}

@Composable
private fun Bubble(fromAgent: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromAgent) Arrangement.Start else Arrangement.End) {
        Surface(
            color = if (fromAgent) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(if (fromAgent) 1f else 0.85f),
            // Column, not Box. A Box stacks its children on top of one
            // another, which was invisible while every bubble held a single
            // Column and silently overlapped the moment one held three --
            // the card, the census and the apply button drew over each other,
            // and only the last one painted was legible.
        ) { Column(Modifier.padding(12.dp)) { content() } }
    }
}

@Composable
private fun AnalysisCard(a: Analysis) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(a.deckName, fontWeight = FontWeight.SemiBold)
        Text("%.1f wins · %.1f losses · %.1f draws"
            .format(a.score.wins, a.score.losses, a.score.draws),
            style = MaterialTheme.typography.titleMedium)
        Text("±%.1f per cohort · %d cohorts vs %d decks · %.1fs"
            .format(a.score.winsSd, a.seeds, a.score.opponents, a.elapsedMs / 1000.0),
            color = Subtle, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(2.dp))
        Text("${a.clean} of ${a.matchups.size} matchups are clean wins.",
            style = MaterialTheme.typography.bodyMedium)
        val shown = minOf(6, a.problems.size)
        if (shown > 0) {
            Text("The ${a.problems.size} costing you something, worst $shown first:",
                color = Subtle, style = MaterialTheme.typography.bodySmall)
            a.problems.take(shown).forEach { m ->
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(m.name.take(24), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    Text("%d–%d–%d".format(m.wins, m.losses, m.draws),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = if (m.winRate < 0.35) Bad else Warn)
                }
            }
        }
    }
}

@Composable
private fun BuiltCard(o: Builder.Outcome, cat: Catalogue) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(o.best.name, fontWeight = FontWeight.SemiBold)
        Text("%.1f of %d wins".format(o.score.wins, o.opponents),
            style = MaterialTheme.typography.titleMedium)

        // The verdict must clear the same gate as any other finding: gain
        // >= 0.4 wins AND t >= 2.0. The first version reported "+0.6 wins
        // better" in green off nine seeds, where the standard error is over a
        // win -- a t of 0.9, which is the noise the gate exists to reject.
        Text(
            when {
                o.beatsField ->
                    "Beats %s, the field's strongest deck, by %.1f wins on the same %d opponents (t=%.1f)."
                        .format(o.controlName, o.gain, o.opponents, o.t)
                o.gain > 0 ->
                    ("Level with %s, the field's strongest deck: %+.1f wins on the same %d " +
                        "opponents, but t=%.1f. That is inside the noise, not an edge.")
                        .format(o.controlName, o.gain, o.opponents, o.t)
                else ->
                    "%s manages %.1f on the same %d opponents, so this is %.1f behind."
                        .format(o.controlName, o.control.wins, o.opponents, -o.gain)
            },
            color = if (o.beatsField) Good else Warn,
            style = MaterialTheme.typography.bodySmall,
        )

        DeckBody(o.best, cat)

        // Both branches are reported, because they answer different questions
        // and the honest answer is usually split. A deck built purely from the
        // theme is the thing that was asked for; a strong list with the theme
        // swapped in is the thing that wins. Measured: 12-16 of 24 from
        // scratch against 21 of 24 for the mutation.
        val other = if (o.best.origin == "theme") o.bestShell else o.bestTheme
        other?.let { (c, sc) ->
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                if (c.origin == "theme") "Built purely from the theme instead:"
                else "Strongest list with the theme swapped in instead:",
                color = Subtle, style = MaterialTheme.typography.bodySmall,
            )
            Text("${c.name} — %.1f of %d".format(sc.wins, o.opponents),
                style = MaterialTheme.typography.bodyMedium)
            DeckBody(c, cat, compact = true)
        }

        Text(
            "Built ${o.considered} candidates · ${o.seeds} seeds · " +
                "%.0fs. A screen this small orders candidates; it cannot accept "
                    .format(o.elapsedMs / 1000.0) +
                "one. Confirming a change needs the paired test over far more seeds.",
            color = Subtle, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DeckBody(c: Builder.Candidate, cat: Catalogue, compact: Boolean = false) {
    // The THEME cards, not the deck's most numerous ones. A mutation puts its
    // themed copies into a 100-card shell, so ranking by quantity shows the
    // shell back to the player: the first poison deck this built listed Men of
    // Artifice, War Profiteer and Treasure Horde, and no poison card at all.
    val counts = c.cards.groupingBy { it }.eachCount()
    val theme = c.themeCards.distinct()
    val themed = theme.sumOf { counts[it] ?: 0 }
    if (theme.isNotEmpty()) {
        Text("What carries the theme:", color = Subtle,
            style = MaterialTheme.typography.bodySmall)
        theme.take(if (compact) 3 else 6).forEach { id ->
            Text("${counts[id] ?: 0}×  ${cat.name(id)}",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (c.origin != "theme") {
        Text("$themed themed cards; the other ${c.cards.size - themed} are " +
            "${c.origin}'s, kept because that list already works.",
            color = Subtle, style = MaterialTheme.typography.bodySmall)
    }
}


private fun proposalFor(r: Improver.Result, cat: Catalogue): Proposal {
    return Proposal(
        deckId = r.deckId, deckName = r.deckName,
        verdict = if (r.accepted)
            "Measured %+.1f wins at t=%.1f over %d cohorts — clears the bar."
                .format(r.gain, r.t, r.validateSeeds)
        else
            "Measured %+.1f wins at t=%.1f over %d cohorts — below the bar, so it cannot be told apart from your deck as it stands."
                .format(r.gain, r.t, r.validateSeeds),
        recommended = r.accepted,
        summary = "${r.addQty}× ${r.addName} for " +
            r.removed.joinToString(", ") { "${it.second}× ${cat.name(it.first)}" },
        adds = listOf(r.add to r.addQty), removes = r.removed,
        cards = r.cards, plan = r.plan,
    )
}

private fun proposalFor(r: Counter.Result, cat: Catalogue): Proposal {
    return Proposal(
        deckId = r.deckId, deckName = r.deckName,
        verdict = if (r.keeps)
            "Improves %s and costs nothing elsewhere: %+.1f wins across the field, t=%.1f."
                .format(r.target.name, r.fieldGain, r.fieldT)
        else
            "Answers %s (%s → %s) but costs %+.1f wins across the whole field, t=%.1f."
                .format(r.target.name, r.before, r.after, r.fieldGain, r.fieldT),
        recommended = r.keeps,
        summary = "${r.addQty}× ${r.addName} for " +
            r.removed.joinToString(", ") { "${it.second}× ${cat.name(it.first)}" } +
            " · vs ${r.target.name}: ${r.before} → ${r.after}",
        adds = listOf(r.add to r.addQty), removes = r.removed,
        cards = r.cards, plan = r.plan,
    )
}

@Composable
private fun InspectCard(r: Inspect.Result, canWrite: Boolean, onApply: (Proposal) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(r.cardName, fontWeight = FontWeight.SemiBold)
        Text(
            if (r.copiesNow > 0) "You run ${r.copiesNow} of a possible ${r.deckLimit}."
            else "Not in your deck. You could run up to ${r.deckLimit}.",
            color = Subtle, style = MaterialTheme.typography.bodySmall,
        )
        if (r.rulesText.isNotBlank()) {
            Text(r.rulesText, style = MaterialTheme.typography.bodySmall)
        }
        Text("Your deck as it stands: %.1f wins of %d".format(r.incumbent.wins, r.opponents),
            style = MaterialTheme.typography.bodyMedium)

        // Every direction measured is shown, not the best one. The player
        // asked what this card does; "cutting it costs you nothing" is as much
        // of an answer as "one more is worth half a win", and only one of them
        // is a recommendation.
        r.variants.forEach { v ->
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(v.label, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Text("%+.1f".format(v.gain), fontFamily = FontFamily.Monospace,
                    color = when {
                        v.recommended -> Good
                        v.harmful -> Bad
                        else -> Subtle
                    })
            }
            Text(
                when {
                    v.recommended ->
                        "%.1f wins, t=%.1f — a real gain.".format(v.wins, v.t)
                    v.harmful ->
                        "%.1f wins, t=%.1f — a real loss. It is earning its slot."
                            .format(v.wins, v.t)
                    else ->
                        "%.1f wins, t=%.1f — inside the noise.".format(v.wins, v.t)
                },
                color = Subtle, style = MaterialTheme.typography.bodySmall,
            )
            ApplyRow(
                Proposal(
                    deckId = r.deckId, deckName = r.deckName,
                    summary = "${r.cardName}: ${v.label}",
                    verdict = if (v.harmful)
                        ("Measured %+.1f wins at t=%.1f over %d cohorts — this makes " +
                            "your deck measurably worse.").format(v.gain, v.t, r.seeds)
                    else
                        "Measured %+.1f wins at t=%.1f over %d cohorts against the full field."
                            .format(v.gain, v.t, r.seeds),
                    recommended = v.recommended,
                    adds = emptyList(), removes = emptyList(),
                    cards = v.cards, plan = v.plan,
                ), canWrite, onApply,
            )
        }
        Text("%d cohorts vs %d decks · %.0fs. Every option shares one seed block, so " .format(r.seeds, r.opponents, r.elapsedMs / 1000.0) +
            "they are comparable with each other and not just with your deck.",
            color = Subtle, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ApplyRow(p: Proposal, canWrite: Boolean, onApply: (Proposal) -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    if (!canWrite) {
        Spacer(Modifier.height(6.dp))
        Text(
            "To apply changes from the app, turn on API deck access under API Key " +
                "on your profile. Until then the change is yours to make on the site.",
            color = Subtle, style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    // Offered whatever the measurement said. The gate is an OPINION, not a
    // lock: it answers "should I recommend this", and the player is answering
    // "do I want it". Its verdict travels with the button rather than
    // replacing it, so the choice is informed without being made for them.
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Apply to ${p.deckName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.summary, style = MaterialTheme.typography.bodyMedium)
                    Text(p.verdict, color = if (p.recommended) Good else Warn,
                        style = MaterialTheme.typography.bodySmall)
                    Text("Your deck will have ${p.cards.size} cards afterwards. This " +
                        "replaces the list on autobattle.online and takes effect in " +
                        "the next cohort.",
                        color = Subtle, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onApply(p) }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }

    Spacer(Modifier.height(6.dp))
    if (p.recommended) {
        Button(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Apply to my deck")
        }
    } else {
        // Present but quieter. Nothing is stopping them; the app simply is not
        // urging them.
        OutlinedButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Apply anyway", color = Subtle)
        }
    }
}

/**
 * Every card the census measured, best and worst first.
 *
 * The middle is elided rather than paginated: a hundred cards clustered
 * within a win of a blank is one fact, not a hundred, and printing them all
 * would bury the two ends that actually say something.
 */
@Composable
private fun Census(census: List<Pair<Int, Double>>, cat: Catalogue, screened: Int) {
    if (census.size < 4) return
    var open by remember { mutableStateOf(false) }

    Spacer(Modifier.height(4.dp))
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
        Text(if (open) "Hide the other ${screened - 1} cards"
             else "Show what all $screened cards measured",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall)
    }
    if (!open) return

    val top = census.take(8)
    val bottom = census.takeLast(4)
    val middle = census.size - top.size - bottom.size

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        top.forEach { (id, d) -> CensusRow(cat.name(id), d) }
        if (middle > 0) {
            val lo = census[top.size].second
            val hi = census[census.size - bottom.size - 1].second
            Text("─ $middle more between %+.1f and %+.1f ─".format(hi, lo),
                color = Subtle, fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 3.dp))
        }
        bottom.forEach { (id, d) -> CensusRow(cat.name(id), d) }
        Spacer(Modifier.height(4.dp))
        Text(
            "Screen numbers, against a blank card in the same slot: 16 opponents, " +
                "5 cohorts each. They order candidates and nothing more — the " +
                "best was re-measured properly above, and came out lower. Anything " +
                "here you want tested for real, ask me to improve with it.",
            color = Subtle, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CensusRow(name: String, delta: Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name.take(28), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f))
        Text("%+.1f".format(delta), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            color = when {
                delta >= 1.0 -> Good
                delta <= -1.0 -> Bad
                else -> Subtle
            })
    }
}

@Composable
private fun ImprovedCard(r: Improver.Result, cat: Catalogue) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${r.addQty}× ${r.addName}", fontWeight = FontWeight.SemiBold)
        Text("for " + r.removed.joinToString(", ") { "${it.second}× ${cat.name(it.first)}" },
            color = Subtle, style = MaterialTheme.typography.bodySmall)

        // The validated number, never the screen's. The screen picked this
        // card out of a field of candidates, so its screen score is the
        // maximum of many noisy draws and biased upward by construction -- a
        // "+3.8W t=2.6" swap measured that way was really +1.48W.
        Text(
            if (r.gain >= 0) "%+.1f wins".format(r.gain) else "%.1f wins".format(r.gain),
            style = MaterialTheme.typography.titleMedium,
            color = if (r.accepted) Good else Warn,
        )
        Text(
            if (r.accepted)
                ("Worth making. %.1f wins over %d cohorts against the full %d-deck field, " +
                    "t=%.1f — clears the bar of %.1f wins and t=%.1f.")
                    .format(r.gain, r.validateSeeds, r.opponents, r.t,
                        online.autobattle.agent.engine.Stats.MIN_GAIN,
                        online.autobattle.agent.engine.Stats.MIN_T)
            else
                ("Not worth making. %+.1f wins at t=%.1f over %d cohorts — below the bar " +
                    "of %.1f wins and t=%.1f, so it cannot be told apart from your deck " +
                    "as it stands.")
                    .format(r.gain, r.t, r.validateSeeds,
                        online.autobattle.agent.engine.Stats.MIN_GAIN,
                        online.autobattle.agent.engine.Stats.MIN_T),
            color = if (r.accepted) Good else Warn,
            style = MaterialTheme.typography.bodySmall,
        )
        Text("%s %.1f wins · your deck %.1f".format(
            r.addName, r.candidate.wins, r.incumbent.wins),
            color = Subtle, style = MaterialTheme.typography.bodySmall)

        // Both numbers are shown on purpose. The screen figure is what made
        // this the survivor; the validated figure is what it is actually
        // worth, and the gap between them IS the winner's curse, made visible
        // rather than quietly discarded.
        Text(
            "Screened ${r.screened} unplayed cards; this one led by %.1f against a blank. "
                .format(r.screenDelta) +
                "Validated separately on a fresh seed block · %.0fs total."
                    .format(r.elapsedMs / 1000.0),
            color = Subtle, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CounteredCard(r: Counter.Result, cat: Catalogue) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${r.addQty}× ${r.addName}", fontWeight = FontWeight.SemiBold)
        Text("for " + r.removed.joinToString(", ") { "${it.second}× ${cat.name(it.first)}" } +
            (if (r.fromTheirList) "  ·  taken from their own list" else ""),
            color = Subtle, style = MaterialTheme.typography.bodySmall)

        // The matchup first, because it is what was asked about.
        Text("vs ${r.target.name}:  ${r.before}  →  ${r.after}",
            style = MaterialTheme.typography.titleMedium,
            color = if (r.matchupImproved) Good else Warn)

        // Then the verdict, which is decided on the FIELD and not on the
        // matchup. Beating one deck is worth about a win in seventy-three; a
        // card that wins it and costs two elsewhere is not a counter, however
        // well it answers the question that was asked.
        Text(
            when {
                r.keeps ->
                    ("Worth making: %+.1f wins across all %d opponents, t=%.1f — " +
                        "the matchup improved and the rest of the field did not suffer for it.")
                        .format(r.fieldGain, r.opponents, r.fieldT)
                r.matchupImproved ->
                    ("Not worth making. It does improve that matchup, but across all %d " +
                        "opponents it is %+.1f wins at t=%.1f — below the bar of %.1f and " +
                        "t=%.1f. Beating one deck is worth about a win in %d, and this " +
                        "gives that back elsewhere.")
                        .format(r.opponents, r.fieldGain, r.fieldT,
                            online.autobattle.agent.engine.Stats.MIN_GAIN,
                            online.autobattle.agent.engine.Stats.MIN_T, r.opponents)
                else ->
                    ("Nothing I tried beat them. The best of %d candidates still goes %s, " +
                        "and across the field it is %+.1f wins.")
                        .format(r.screened, r.after.toString(), r.fieldGain)
            },
            color = if (r.keeps) Good else Warn,
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Your deck %.1f wins overall, this %.1f · %d cohorts vs %d decks · %.0fs"
            .format(r.incumbent.wins, r.candidate.wins, r.validateSeeds,
                r.opponents, r.elapsedMs / 1000.0),
            color = Subtle, style = MaterialTheme.typography.bodySmall)
        Text(
            "Screened ${r.screened} cards against ${r.target.name} alone, where the " +
                "effect is undiluted, then judged on the whole field.",
            color = Subtle, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun Composer(busy: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val fire = {
        val t = text.trim()
        if (t.isNotBlank()) { text = ""; onSend(t) }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("analyse my deck", color = Subtle) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            // The field declared ImeAction.Send and then ignored it, so the
            // keyboard's own send key did nothing -- the one control a thumb
            // is already resting on.
            keyboardActions = KeyboardActions(onSend = { fire() }),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = fire, enabled = !busy && text.isNotBlank()) { Text("Send") }
    }
}


/**
 * The conversation itself: loads the catalogue once, then answers.
 *
 * The catalogue and the field are fetched on entry rather than on the first
 * question. They are ~860KB together and disk-cached, but a cold fetch in the
 * middle of answering would be indistinguishable from a slow simulation, and
 * the player would be watching a spinner with no idea which was happening.
 */
@Composable
fun ChatPane(
    accountName: String,
    deckId: Int,
    deckName: String,
    canWrite: Boolean = false,
    modifier: Modifier = Modifier,
    /** False while another tab is in front; the pane keeps its state and work. */
    visible: Boolean = true,
    onSignOut: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf(listOf<Line>()) }
    var engine by remember { mutableStateOf<ChatEngine?>(null) }
    var busy by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(deckId) {
        lines = listOf(Line.Said(true, "Loading the card catalogue and the arena field\u2026"))
        runCatching {
            withContext(Dispatchers.IO) {
                val key = Secrets.load(ctx) ?: error("No stored key")
                val analyst = Analyst(ctx, ApiClient(key))
                val cat = analyst.catalogueObj()
                val meta = analyst.metaDecks()
                val names = HashMap<String, Int>()
                for (id in cat.byId.keys) {
                    if (cat.isPlayable(id)) names[cat.name(id)] = id
                }
                val grammar = Grammar(Grammar.vocabularyOf(cat), names)
                grammar to ChatEngine(
                    cat, Builder(cat, meta), grammar,
                    analyse = { ref ->
                        analyst.analyse(if (ref is DeckRef.Id) ref.id else deckId)
                    },
                    inspect = {
                        val d = analyst.deckFor(deckId)
                        if (d == null) null else Inspect(
                            cat, meta, deckId,
                            d.optString("name").ifBlank { "your deck" },
                            Analyst.expandCards(d),
                            d.optJSONObject("battle_plan") ?: JSONObject(),
                        )
                    },
                    counter = {
                        val d = analyst.deckFor(deckId)
                        if (d == null) null else Counter(
                            cat, meta, deckId,
                            d.optString("name").ifBlank { "your deck" },
                            Analyst.expandCards(d),
                            d.optJSONObject("battle_plan") ?: JSONObject(),
                        )
                    },
                    improve = { ref ->
                        val id = if (ref is DeckRef.Id) ref.id else deckId
                        val d = analyst.deckFor(id)
                        if (d == null) null else Improver(
                            cat, meta, id,
                            d.optString("name").ifBlank { "your deck" },
                            Analyst.expandCards(d), d.optJSONObject("battle_plan") ?: JSONObject(),
                        )
                    },
                )
            }
        }.onSuccess { (g, e) ->
            engine = e
            lines = listOf(Line.Said(true, greeting(deckName, g)))
        }.onFailure {
            lines = listOf(Line.Said(true, "Could not load the field: " + readable(it)))
        }
        busy = false
    }

    var confirmSignOut by remember { mutableStateOf(false) }

    // Sign out is destructive -- it wipes the stored key and the player has to
    // fetch it from the website again -- so it is confirmed, and it does NOT
    // sit under the composer. It used to, and a single Enter reached it
    // through the focus system: AbstractClickableNode.onKeyEvent fired it
    // while the player was typing a question, wiping the credential with no
    // prompt. A thumb landing slightly low did the same thing.
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your API key will be removed from this device. You can " +
                "paste it again from your profile page.") },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier.fillMaxWidth().then(if (visible) Modifier else Modifier.size(0.dp)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!visible) return@Column
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (deckName.isNotBlank()) "$accountName \u00b7 $deckName" else accountName,
                color = Subtle, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { confirmSignOut = true }) {
                Text("Sign out", color = Subtle,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Transcript(
            lines, Modifier.weight(1f).fillMaxWidth(), canWrite = canWrite,
            onApply = { p ->
                busy = true; progress = "Saving to autobattle.online…"
                scope.launch {
                    val line = runCatching {
                        withContext(Dispatchers.IO) {
                            val key = Secrets.load(ctx) ?: error("No stored key")
                            val saved = ApiClient(key)
                                .updateDeck(p.deckId, p.cards, p.plan)
                            // Trust the server's echo, not our own intent: PUT
                            // replaces the list wholesale and marks a deck
                            // invalid outside 90-100 cards, so what came back
                            // is the only thing that says the write landed.
                            val n = saved.optInt("card_count", -1)
                            val valid = saved.optInt("is_valid", 0) == 1
                            if (n != p.cards.size || !valid)
                                error("saved as $n cards, valid=$valid")
                            Line.Applied(p.deckName, p.summary)
                        }
                    }.getOrElse {
                        Line.Said(true, "Could not save: " + readable(it))
                    }
                    if (line is Line.Applied) {
                        Journal.append(ctx, Journal.Entry(
                            at = System.currentTimeMillis(), kind = "applied",
                            deck = p.deckName, headline = "Applied: ${p.summary}",
                            detail = "written to autobattle.online",
                            wins = 0.0, opponents = 0, seeds = 0,
                            gain = null, t = null, accepted = true,
                        ))
                    }
                    lines = lines + line
                    progress = null; busy = false
                }
            },
        )

        progress?.let {
            Text(it, color = Subtle, style = MaterialTheme.typography.bodySmall)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        Composer(busy || engine == null) { text ->
            if (text.isBlank()) return@Composer
            lines = lines + Line.Said(false, text)
            busy = true; progress = null
            scope.launch {
                val said = runCatching {
                    withContext(Dispatchers.IO) {
                        engine!!.respond(text) { p -> progress = p }
                    }
                }.getOrElse { listOf(Line.Said(true, readable(it))) }
                said.forEach { record(ctx, it) }
                lines = lines + said
                progress = null
                busy = false
            }
        }
    }
}

private fun greeting(deckName: String, g: Grammar): String = buildString {
    append("Ready. ")
    if (deckName.isNotBlank()) append("Your active deck is $deckName. ")
    append("Ask me to analyse it, or to build a deck around a theme \u2014 ")
    append("try one of: ")
    append(g.someThemes(5).joinToString(", "))
    append(".")
}


/**
 * Write a measured answer into the journal.
 *
 * Only results with a number go in. A greeting or a "did not follow that" is
 * conversation, not a finding, and a notebook padded with those stops being
 * worth reading.
 */
private fun record(ctx: android.content.Context, l: Line) {
    val e = when (l) {
        is Line.Built -> Journal.Entry(
            at = System.currentTimeMillis(), kind = "build",
            deck = l.o.best.name,
            headline = l.o.best.name,
            detail = "vs ${l.o.controlName}, the field's best" +
                (if (l.o.best.origin == "theme") " · built from the theme"
                 else " · on ${l.o.best.origin}"),
            wins = l.o.score.wins, opponents = l.o.opponents, seeds = l.o.seeds,
            gain = l.o.gain, t = l.o.t, accepted = l.o.beatsField,
        )
        is Line.Improved -> Journal.Entry(
            at = System.currentTimeMillis(), kind = "improve",
            deck = l.r.deckName,
            headline = "${l.r.addQty}× ${l.r.addName}",
            detail = "for " + l.r.removed.joinToString(", ") {
                "${it.second}× ${l.cat.name(it.first)}"
            } + " · led a ${l.r.screened}-card census by %+.1f".format(l.r.screenDelta),
            wins = l.r.candidate.wins, opponents = l.r.opponents,
            seeds = l.r.validateSeeds,
            gain = l.r.gain, t = l.r.t, accepted = l.r.accepted,
        )
        is Line.Countered -> Journal.Entry(
            at = System.currentTimeMillis(), kind = "beat",
            deck = l.r.deckName,
            headline = "${l.r.addQty}× ${l.r.addName} vs ${l.r.target.name}",
            detail = "${l.r.target.name} ${l.r.before} → ${l.r.after}" +
                (if (l.r.fromTheirList) " · from their own list" else "") +
                " · screened ${l.r.screened} cards against them alone",
            wins = l.r.candidate.wins, opponents = l.r.opponents,
            seeds = l.r.validateSeeds,
            gain = l.r.fieldGain, t = l.r.fieldT, accepted = l.r.keeps,
        )
        else -> null
    } ?: return
    Journal.append(ctx, e)
}
