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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import online.autobattle.agent.api.ApiClient
import online.autobattle.agent.api.ApiException
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
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Phase>(Phase.NeedKey) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    // A stored key is verified on launch rather than trusted: a key can be
    // revoked or the account banned, and discovering that at the first
    // analysis is a worse experience than discovering it at startup.
    LaunchedEffect(Unit) {
        val key = Secrets.load(ctx) ?: return@LaunchedEffect
        busy = true
        runCatching { withContext(Dispatchers.IO) { identify(key) } }
            .onSuccess { phase = it }
            .onFailure { status = readable(it) }
        busy = false
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            // safeDrawingPadding, not padding alone: targetSdk 35+ draws
            // edge-to-edge by default, so without it the heading sits under
            // the status bar clock and the composer under the gesture bar. It
            // also covers the IME, which keeps the input visible while typing.
            Modifier.fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 18.dp)
                .padding(top = 16.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "AutoBattle Agent",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            when (val p = phase) {
                // Only this branch scrolls. The chat pane holds a LazyColumn,
                // and a lazy list inside a scrolling parent is measured with
                // an unbounded height, which throws at layout time.
                is Phase.NeedKey -> Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    KeyEntry(busy) { key ->
                        busy = true; status = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { identify(key) } }
                                .onSuccess { Secrets.save(ctx, key); phase = it }
                                .onFailure { status = readable(it) }
                            busy = false
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    status?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is Phase.Ready -> {
                    // The chat pane is kept alive across tab switches rather
                    // than swapped out, so a 20-second improve run is not
                    // thrown away by looking at the journal while it works.
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                        Tab(selected = tab == 0, onClick = { tab = 0 },
                            text = { Text("Chat") })
                        Tab(selected = tab == 1, onClick = { tab = 1 },
                            text = { Text("Findings") })
                    }
                    Box(Modifier.weight(1f)) {
                        ChatPane(
                            accountName = p.accountName,
                            deckId = p.deckId,
                            deckName = p.deckName,
                            modifier = Modifier.fillMaxSize(),
                            visible = tab == 0,
                            onSignOut = { Secrets.clear(ctx); phase = Phase.NeedKey },
                        )
                        if (tab == 1) FindingsPane(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

private fun identify(key: String): Phase.Ready {
    val api = ApiClient(key)
    val me = api.me()
    val id = me.optInt("active_deck_id")
    return Phase.Ready(
        me.optString("name"), id,
        if (id > 0) api.deck(id).optString("name") else "",
    )
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

internal fun readable(t: Throwable): String = when {
    t is ApiException && t.status == 401 -> "That key was not accepted. Check it on your profile page."
    t is ApiException && t.status == 403 ->
        "Your account does not have API access enabled — ask an admin to turn it on."
    t is ApiException -> "autobattle.online returned ${t.status}."
    else -> t.message ?: t::class.java.simpleName
}
