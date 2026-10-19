package online.autobattle.agent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
    /**
     * Asked once, on launch. The training notification is what lets the run
     * continue while the player is in another app -- without it the work
     * still happens but is invisible, which is exactly the thing a phone
     * should never do with two minutes of flat-out CPU.
     */
    private val askNotify = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* declined is survivable: the service still runs, silently. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { AgentTheme { AgentApp() } }
    }
}

private sealed interface Phase {
    data object NeedKey : Phase
    data class Ready(
        val accountName: String,
        val deckId: Int,
        val deckName: String,
        /**
         * Server-derived, never inferred from the raw flags. The site mirrors
         * require_deck_api_user() into can_write_decks precisely so the gate
         * can move without needing an app release; recomputing it here from
         * is_api_user would reintroduce the coupling it exists to avoid.
         */
        val canWrite: Boolean,
    ) : Phase
}

/**
 * The app IS the Agent league.
 *
 * It opens straight into the arena and needs no autobattle.online account --
 * a player joins with a handle they invent and never learns the main game
 * exists. That is deliberate: this is a separate game, and requiring an
 * account on another one to play it is both a pointless wall and a real
 * hazard, because the advisor's key can rewrite a deck on the player's live
 * account. A player of THIS game should never hold a credential that can
 * reach the main game at all.
 *
 * The advisor (Chat/Findings, which does need that key) is still in the
 * source but is not reachable from this build. It is a tool for the author of
 * a deck on autobattle.online, not a feature of the league, and shipping it
 * here would put that exposure in front of testers who did not ask for it.
 */
@Composable
fun AgentApp() {
    // WHICH BACKDROP, decided by the pane and drawn out here.
    //
    // It has to be drawn above this Column, not inside the pane, or it stops
    // at the pane's edges -- the title bar and the 18dp side padding stayed
    // black and the "back screen" was a framed picture rather than the room.
    // Reported upwards rather than hoisting `place` and the avatar choice out
    // of the pane: the pane owns both, and this is the only thing out here
    // that needs to know about them.
    var backdrop by remember { mutableStateOf<Avatar?>(null) }
    Surface(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize()) {
        backdrop?.let { AvatarBackdrop(it, Modifier.matchParentSize()) }
        Column(
            // safeDrawingPadding, not padding alone: targetSdk 35+ draws
            // edge-to-edge by default, so without it the heading sits under
            // the status bar clock and content under the gesture bar.
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
            ArenaPane(Modifier.fillMaxSize()) { backdrop = it }
        }
      }
    }
}

@Suppress("unused")
private fun identify(key: String): Phase.Ready {
    val api = ApiClient(key)
    val me = api.me()
    val id = me.optInt("active_deck_id")
    return Phase.Ready(
        me.optString("name"), id,
        if (id > 0) api.deck(id).optString("name") else "",
        me.optBoolean("can_write_decks", false),
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
