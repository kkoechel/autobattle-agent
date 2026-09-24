package online.autobattle.agent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import online.autobattle.agent.api.ApiClient
import online.autobattle.agent.api.ApiException
import online.autobattle.agent.data.Analysis
import online.autobattle.agent.data.Analyst
import online.autobattle.agent.data.Secrets

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AgentTheme { AgentApp() } }
    }
}

private sealed interface Phase {
    data object NeedKey : Phase
    data class Ready(val accountName: String, val deckId: Int, val deckName: String) : Phase
}

@Composable
fun AgentApp() {
    val ctx = LocalContextCompatible()
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Phase>(Phase.NeedKey) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var analysis by remember { mutableStateOf<Analysis?>(null) }

    // A stored key is verified on launch rather than trusted: a key can be
    // revoked or the account banned, and discovering that at the first analysis
    // is a worse experience than discovering it at startup.
    LaunchedEffect(Unit) {
        val key = Secrets.load(ctx) ?: return@LaunchedEffect
        busy = true
        runCatching {
            withContext(Dispatchers.IO) {
                val api = ApiClient(key)
                val me = api.me()
                val id = me.optInt("active_deck_id")
                Triple(me.optString("name"), id, if (id > 0) api.deck(id).optString("name") else "")
            }
        }.onSuccess { (n, id, dn) -> phase = Phase.Ready(n, id, dn) }
            .onFailure { status = readable(it) }
        busy = false
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            // safeDrawingPadding, not padding alone: targetSdk 35+ draws
            // edge-to-edge by default, so without it the heading sits under the
            // status bar clock and the last row under the gesture bar. It also
            // covers the IME, which is what keeps the key field visible while
            // the keyboard is up.
            Modifier.fillMaxSize()
                .safeDrawingPadding()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "AutoBattle Agent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            when (val p = phase) {
                is Phase.NeedKey -> KeyEntry(busy) { key ->
                    busy = true; status = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val api = ApiClient(key)
                                val me = api.me()
                                val id = me.optInt("active_deck_id")
                                Triple(
                                    me.optString("name"), id,
                                    if (id > 0) api.deck(id).optString("name") else ""
                                )
                            }
                        }.onSuccess { (n, id, dn) ->
                            Secrets.save(ctx, key)
                            phase = Phase.Ready(n, id, dn)
                        }.onFailure { status = readable(it) }
                        busy = false
                    }
                }

                is Phase.Ready -> {
                    Text(p.accountName, color = Subtle)
                    Text(
                        if (p.deckId > 0) "Active deck: ${p.deckName}" else "No active deck",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(
                        onClick = {
                            busy = true; status = null; analysis = null
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val key = Secrets.load(ctx)!!
                                        Analyst(ctx, ApiClient(key)).analyse(p.deckId)
                                    }
                                }.onSuccess { analysis = it }
                                    .onFailure { status = readable(it) }
                                busy = false
                            }
                        },
                        enabled = !busy && p.deckId > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "Simulating…" else "Analyse my deck") }

                    TextButton(onClick = {
                        Secrets.clear(ctx); phase = Phase.NeedKey; analysis = null
                    }) { Text("Sign out", color = Subtle) }
                }
            }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
            analysis?.let { Findings(it) }
        }
    }
}

@Composable
private fun KeyEntry(busy: Boolean, onSubmit: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Paste your API key from ${ApiClient.PROFILE_URL}. " +
                "It is stored encrypted on this device and never leaves it except " +
                "to talk to autobattle.online.",
            color = Subtle,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text("API key") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmit(key) },
            enabled = !busy && key.length > 20,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect") }
    }
}

private const val SHOWN_PROBLEMS = 10

@Composable
private fun Findings(a: Analysis) {
    Spacer(Modifier.height(4.dp))
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(a.deckName, style = MaterialTheme.typography.titleMedium)
        Text(
            "%.1f wins  ·  %.1f losses  ·  %.1f draws".format(
                a.score.wins, a.score.losses, a.score.draws
            ),
            style = MaterialTheme.typography.headlineSmall,
        )
        // The error bar is shown, not hidden. A single cohort swings by
        // several wins, so a player who reads one number as exact will think
        // the app is wrong the first time their rank moves without cause.
        Text(
            "±%.1f per cohort · %d simulated cohorts vs %d decks · %.1fs · engine %s"
                .format(a.score.winsSd, a.seeds, a.score.opponents,
                    a.elapsedMs / 1000.0, a.engineVersion),
            color = Subtle,
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "${a.clean} of ${a.matchups.size} matchups are already clean wins.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (a.problems.isNotEmpty()) {
            // Say how many are shown against how many exist. The first draft
            // said "Everything left is these" above a list capped at ten, so
            // a player with 26 problem matchups was told 16 of them did not
            // exist -- and the ones hidden were the cheapest, which is the
            // defensible cut to make but not one to make silently.
            val shown = minOf(SHOWN_PROBLEMS, a.problems.size)
            Text(
                if (shown < a.problems.size)
                    "The ${a.problems.size} that cost you something, worst $shown first:"
                else "Everything left is these:",
                color = Subtle, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            a.problems.take(SHOWN_PROBLEMS).forEach { m ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        m.name.take(26),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "%d–%d–%d".format(m.wins, m.losses, m.draws),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = when {
                            m.winRate < 0.35 -> Bad
                            m.winRate < 0.7 -> Warn
                            else -> Good
                        },
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

private fun readable(t: Throwable): String = when {
    t is ApiException && t.status == 401 -> "That key was not accepted. Check it on your profile page."
    t is ApiException && t.status == 403 ->
        "Your account does not have API access enabled — ask an admin to turn it on."
    t is ApiException -> "autobattle.online returned ${t.status}."
    else -> t.message ?: t::class.java.simpleName
}

@Composable
private fun LocalContextCompatible() = androidx.compose.ui.platform.LocalContext.current
