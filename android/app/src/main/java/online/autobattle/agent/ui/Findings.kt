package online.autobattle.agent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import online.autobattle.agent.api.ApiClient
import online.autobattle.agent.data.Journal
import online.autobattle.agent.data.Secrets
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One finished cohort, as /results reports it. */
data class Placement(
    val cohortId: Long,
    val deckName: String,
    val placement: Int,
    val slots: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val finalisedAt: String,
) {
    /**
     * Where this finished as a fraction of the field, 1.0 being first.
     *
     * Cohorts are not all the same size, so raw placement is not comparable
     * across them: 6th of 73 and 6th of 20 are very different results and
     * plotting the bare number would draw them at the same height.
     */
    val topFraction: Double
        get() = if (slots <= 1) 1.0 else 1.0 - (placement - 1).toDouble() / (slots - 1)
}

private fun parseResults(a: JSONArray): List<Placement> =
    (0 until a.length()).map { a.getJSONObject(it) }.map {
        Placement(
            cohortId = it.optLong("cohort_id"),
            deckName = it.optString("deck_name"),
            placement = it.optInt("placement"),
            slots = it.optInt("slot_count"),
            wins = it.optInt("wins"),
            losses = it.optInt("losses"),
            draws = it.optInt("draws"),
            finalisedAt = it.optString("finalized_at"),
        )
    }.sortedBy { it.finalisedAt }

@Composable
fun FindingsPane(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var placements by remember { mutableStateOf<List<Placement>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val journal = remember { Journal.all(ctx) }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                val key = Secrets.load(ctx) ?: error("no key")
                parseResults(ApiClient(key).results(limit = 25))
            }
        }.onSuccess { placements = it }.onFailure { error = readable(it) }
        loading = false
    }

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Where you finished", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall)
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall) } }

        if (placements.isNotEmpty()) {
            item { RankChart(placements) }
            items(placements.reversed().take(8)) { p ->
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(p.deckName.take(28),
                            style = MaterialTheme.typography.bodyMedium)
                        Text(p.finalisedAt.take(16), color = Subtle,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("#${p.placement} of ${p.slots}   ${p.wins}–${p.losses}–${p.draws}",
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = if (p.topFraction > 0.9) Good else Subtle)
                }
            }
        } else if (!loading && error == null) {
            item { Text("No finished cohorts yet.", color = Subtle,
                style = MaterialTheme.typography.bodySmall) }
        }

        item {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            Text("What you tried", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall)
            Text(
                "Rejections are kept. A deck near the top of the field rejects " +
                    "almost everything, and the rejections are where the game is.",
                color = Subtle, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (journal.isEmpty()) {
            item { Text("Nothing yet — ask me to improve or build a deck.",
                color = Subtle, style = MaterialTheme.typography.bodySmall) }
        }
        items(journal) { e -> JournalRow(e) }
    }
}

@Composable
private fun JournalRow(e: Journal.Entry) {
    val when_ = remember(e.at) {
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(e.at))
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(e.headline, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Text(
                    when (e.accepted) {
                        true -> "kept"
                        false -> "rejected"
                        null -> "screened"
                    },
                    fontSize = 11.sp,
                    color = when (e.accepted) {
                        true -> Good
                        false -> Warn
                        null -> Subtle
                    },
                )
            }
            if (e.detail.isNotBlank()) {
                Text(e.detail, color = Subtle, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                buildString {
                    append("%.1f of %d".format(e.wins, e.opponents))
                    if (e.gain != null && e.t != null) {
                        // What the gain is measured AGAINST differs by kind, and
                        // saying "vs your deck" for a build would be plainly
                        // wrong: a built deck is scored against the field's
                        // strongest, not against the player's own list.
                        val versus = if (e.kind == "improve") "vs your deck"
                        else "vs the field's best"
                        append("  ·  %+.1f %s, t=%.1f".format(e.gain, versus, e.t))
                    }
                    append("  ·  ${e.seeds} cohorts  ·  $when_")
                },
                color = Subtle, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
        }
    }
}

/**
 * Placement over time, as a fraction of each cohort's field.
 *
 * Cohorts are not all the same size, so raw placement is not comparable across
 * them and the line plots the fraction of the field finished above.
 *
 * The axis runs from first place down to the WORST result actually seen, not
 * down to last place. Anchored at the bottom of the field a competitive deck
 * draws as a flat line crushed against the ceiling, which hides the only
 * movement there is. The floor is labelled with the placement it represents,
 * because a rescaled axis with no label is how a two-place wobble gets read as
 * a collapse.
 */
@Composable
private fun RankChart(ps: List<Placement>) {
    val gold = MaterialTheme.colorScheme.primary
    val faint = Subtle.copy(alpha = 0.25f)
    val worst = ps.minByOrNull { it.topFraction }!!
    val floor = maxOf(0.0, worst.topFraction - 0.02)
    val span = (1.0 - floor).coerceAtLeast(0.02)
    Column {
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            if (ps.size < 2) return@Canvas
            val w = size.width
            val h = size.height
            drawLine(faint, Offset(0f, 2f), Offset(w, 2f), strokeWidth = 1f)
            drawLine(faint, Offset(0f, h - 2f), Offset(w, h - 2f), strokeWidth = 1f)
            val step = w / (ps.size - 1)
            var prev: Offset? = null
            ps.forEachIndexed { i, p ->
                val frac = ((p.topFraction - floor) / span).coerceIn(0.0, 1.0)
                val y = (h - 4f) * (1f - frac.toFloat()) + 2f
                val pt = Offset(i * step, y)
                prev?.let { drawLine(gold, it, pt, strokeWidth = 3f) }
                drawCircle(gold, radius = 4f, center = pt)
                prev = pt
            }
        }
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("worst: #${worst.placement} of ${worst.slots}",
                color = Subtle, fontSize = 10.sp)
            Text("${ps.size} cohorts, oldest to latest", color = Subtle, fontSize = 10.sp)
            Text("top ↑", color = Subtle, fontSize = 10.sp)
        }

        // A small cohort produces coarse placements -- 4th of 12 plots lower
        // than 9th of 77 -- so the deepest dip on this chart is often just a
        // short field rather than a bad run. Saying so is the difference
        // between a chart that informs and one that alarms.
        val typical = ps.map { it.slots }.sorted()[ps.size / 2]
        if (worst.slots * 2 < typical) {
            Text(
                "The low point is a ${worst.slots}-deck cohort against a usual " +
                    "${typical}. Short fields give coarse placings, so that dip is " +
                    "mostly cohort size.",
                color = Subtle, fontSize = 10.sp,
            )
        }
    }
}
