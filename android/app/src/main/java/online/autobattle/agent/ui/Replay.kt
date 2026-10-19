package online.autobattle.agent.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import online.autobattle.agent.engine.Deck
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watch one of the games the trial just played.
 *
 * The phone's Go engine decides the result; the main game's JS engine is
 * the only one that produces a text log, so the replay RE-RUNS the game in
 * JavaScript and draws that. The two must land on the same game or the
 * player is shown something that did not happen, and three things decide
 * whether they do:
 *
 *  - THE SAME CARD DEFINITIONS. Handed over as the exact map the Go engine
 *    was given (PayloadBuilder.cardDefs), not rebuilt from the catalogue.
 *  - THE SAME SEAT. sim() alternates who goes first by seed INDEX, so the
 *    replay is told which chair you were in rather than assuming.
 *  - firstPlayer PASSED EXPLICITLY. recordBattle's own comment: omitting it
 *    takes one extra RNG draw, "permanently shifting every subsequent
 *    random decision", and the replay silently diverges.
 *
 * Game two mirrors simulateMatch's `seed ^ 0x80000000` with the seats
 * swapped, which is what makes a best-of-2 best-of-2.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReplayScreen(
    mine: Deck,
    rival: Deck,
    cardDefs: String,
    seed: Int,
    youFirst: Boolean,
    /** What the Go engine scored, from your side: won / lost / drew. */
    expected: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        setBackgroundColor(android.graphics.Color.BLACK)
                        loadDataWithBaseURL(
                            BASE,
                            page(mine, rival, cardDefs, seed, youFirst, expected),
                            "text/html", "utf-8", null,
                        )
                    }
                },
            )
            GameLink("Close", Modifier.align(Alignment.TopEnd).padding(10.dp)) { onDismiss() }
        }
    }
}

private const val BASE = "https://autobattle.online/"

private fun flat(d: Deck) = JSONArray().apply { d.cards.forEach { put(it) } }

private fun page(
    mine: Deck, rival: Deck, cardDefs: String, seed: Int, youFirst: Boolean,
    expected: String,
): String {
    // A is always the side that moves first, so the seat decides which deck
    // is A -- not which one is yours.
    val a = if (youFirst) mine else rival
    val b = if (youFirst) rival else mine
    val aName = JSONObject.quote(a.name.ifBlank { "Deck A" })
    val bName = JSONObject.quote(b.name.ifBlank { "Deck B" })
    val planA = (a.battlePlan ?: JSONObject()).toString()
    val planB = (b.battlePlan ?: JSONObject()).toString()
    val seed2 = (seed.toLong() xor 0x80000000L) and 0xFFFFFFFFL
    val youAreSlot = if (youFirst) 0 else 1
    val expectedJs = JSONObject.quote(expected)
    return """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="/css/battle-visualizer.css">
<style>
  html,body{margin:0;background:#07081a;color:#e8e4d9;
            font-family:Georgia,serif;overflow-x:hidden}
  #msg{padding:14px;font-size:14px}
  #wrap{padding:6px}
  .picker{display:flex;gap:8px;padding:8px 92px 8px 14px}
  #warn{margin:8px 14px;padding:8px;border:2px solid #ce6a5c;color:#ffd7d0;
        font-size:13px;display:none}
  #agree{margin:6px 14px 0;font-size:12px;color:#8a8aa8}
  .picker button{flex:1;padding:8px;background:#141428;color:#ffe28c;
                 border:2px solid #6e5522;font-family:inherit;font-size:14px}
</style></head><body>
<div id="msg">Loading the engine…</div>
<div id="warn"></div>
<div id="agree"></div>
<div class="picker" id="pick" style="display:none">
  <button onclick="show(0)">Game 1</button>
  <button onclick="show(1)">Game 2</button>
</div>
<div id="wrap"></div>
<script src="/js/battle-engine.js"></script>
<script src="/js/battle-visualizer.js"></script>
<script>
(function () {
  var msg = document.getElementById('msg');
  if (!window.BattleEngine || !window.BattleVisualizer) {
    msg.textContent = 'Could not load the battle engine.'; return;
  }
  var defs  = $cardDefs;
  var cardsA = ${flat(a)}, cardsB = ${flat(b)};
  var planA = $planA, planB = $planB;
  var slotA = { deck_name: $aName, owner: $aName };
  var slotB = { deck_name: $bName, owner: $bName };
  var games, viz = null;
  try {
    // firstPlayer is passed explicitly (0 then 1) and game two carries the
    // XORed seed -- together they are what keep this identical to the match
    // the Go engine scored.
    games = [
      BattleEngine.recordBattle(cardsA, cardsB, defs, $seed, planA, planB, 0,
        null, null, null, null, null, null, null),
      BattleEngine.recordBattle(cardsA, cardsB, defs, $seed2, planA, planB, 1,
        null, null, null, null, null, null, null),
    ];
  } catch (e) {
    msg.textContent = 'The replay could not be built: ' + e; return;
  }
  // DOES THE REPLAY MATCH THE GAME THAT WAS SCORED?
  //
  // The Go engine on the phone decided this result; the JS engine is
  // re-running it to draw it. If they ever disagree the player is being
  // shown a game that did not happen, so the page says so rather than
  // quietly playing the wrong one.
  try {
    var m = BattleEngine.simulateMatch(cardsA, cardsB, defs, $seed, planA, planB,
      null, null, null, null, null, null, null);
    var youAre = $youAreSlot;                 // 0 if you moved first
    var got = m.winner === -1 ? 'drew' : (m.winner === youAre ? 'won' : 'lost');
    if (got !== $expectedJs) {
      var w = document.getElementById('warn');
      w.style.display = '';
      w.textContent = 'This replay disagrees with the scored result (' +
        $expectedJs + ' vs ' + got + '), so it is not the game that counted.';
    } else {
      // Said out loud. A missing warning is indistinguishable from a check
      // that threw before it could run.
      document.getElementById('agree').textContent =
        'Replay agrees with the scored result (' + got + ').';
    }
  } catch (e) {
    // Drawing is still worth doing, but do not let a failed check read as a
    // passed one.
    document.getElementById('agree').textContent =
      'Could not verify this replay against the scored result: ' + e;
  }

  window.show = function (i) {
    var rec = games[i];
    if (viz) viz.destroy();
    var wrap = document.getElementById('wrap');
    wrap.innerHTML = '';
    viz = new BattleVisualizer(wrap, {
      turnData: rec.turnData, lines: rec.lines, result: rec.result,
      cardDefs: defs, slotA: slotA, slotB: slotB,
      maxLife: 100, maxHonor: 100,
    });
    viz.play(1);
  };
  msg.style.display = 'none';
  document.getElementById('pick').style.display = '';
  show(0);
})();
</script></body></html>
"""
}
