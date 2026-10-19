package online.autobattle.agent.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import online.autobattle.agent.engine.Deck
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watch one of the games the trial just played, in the game's own v2
 * replay viewer (js/replay2.js, `ReplayV2`).
 *
 * NOT js/battle-visualizer.js, which is the older live viewer: different
 * global, different CSS prefix, different DOM, and it draws cards as emoji.
 * v2 renders real cards when window.buildCardSVG is available, so
 * card-popup.js is loaded alongside it -- the same file the card sheet in
 * this app already uses.
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
    /** id -> art, rules text, rarity and subtype, for drawing the cards. */
    display: String,
    seed: Int,
    youFirst: Boolean,
    /** What the Go engine scored, from your side: won / lost / drew. */
    expected: String,
    /**
     * The safe area, measured in the ACTIVITY and handed in.
     *
     * A Dialog gets its own window and that window is the whole display --
     * read off the device, the activity's frame is [81,72][2196,1080] while
     * this dialog's is [0,0][2340,1080]. So it extends 144px past where the
     * activity stops, under the cutout, and WindowInsets inside it reports
     * nothing to correct that: neither safeDrawing nor
     * decorFitsSystemWindows = false made any difference. Measuring outside,
     * where the insets are real, and padding by the result is the thing that
     * actually works.
     */
    edge: PaddingValues,
    onDismiss: () -> Unit,
) {
    // The viewer is a flex column whose two halves split the height between
    // them -- top player and bottom player. It therefore needs a REAL
    // height, and CSS viewport units cannot supply one here: measured on
    // the device, the arena came back 352x0 because a WebView sized by its
    // own content has a zero-height viewport, so 72vh resolved to 0 and the
    // board spread sideways instead of stacking.
    val cfg = LocalConfiguration.current
    // Whether the PHONE is portrait, taken from Compose rather than from the
    // page. The WebView's own media features are unreliable here -- measured
    // on the device, the viewport reads 360x780 and yet matchMedia reports
    // both (orientation:landscape) and (max-height:520px) as true -- so the
    // only orientation signal worth trusting is the one Android gives the app.
    val portrait = cfg.screenHeightDp >= cfg.screenWidthDp

    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false, or the inset padding below reads
        // ZERO. A Dialog gets its own window, and by default that window
        // "fits" the system windows itself -- which consumes the insets
        // before Compose sees them, so windowInsetsPadding had nothing to
        // apply and the page ran under the display cutout regardless.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF07081A))
                // The safe area, passed in rather than queried here --
                // see `edge`. The page itself never overflowed: measured on
                // the device it is 780 CSS px wide with content fitting in
                // 772 and every element reporting scrollWidth equal to
                // clientWidth. The window was simply wider than the drawn
                // part of the screen.
                .padding(edge),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // MEASURED, not guessed. screenHeightDp includes the system
                // bars, so sizing from it put the controls off the bottom of
                // the screen; this is the space the dialog actually has.
                val avail = maxHeight.value.toInt()
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(maxHeight),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            setBackgroundColor(android.graphics.Color.BLACK)
                            loadDataWithBaseURL(
                                BASE,
                                page(mine, rival, cardDefs, display, seed, youFirst,
                                    expected, avail, portrait),
                                "text/html", "utf-8", null,
                            )
                        }
                    },
                    // ROTATION RE-LAYS-OUT, IT DOES NOT RELOAD. update runs on
                    // every recomposition, including the one a rotation causes,
                    // and swapping the orientation stylesheet leaves the battle
                    // exactly where the player paused it. Calling load again
                    // here would restart it from turn one.
                    //
                    // The resize event is for the viewer's benefit, not the
                    // browser's: ReplayV2 lays the board out from measurements
                    // it takes when it draws, and a CSS-only change does not by
                    // itself tell it to take them again.
                    update = { wv ->
                        val css = JSONObject.quote(orientCss(
                            (avail - (if (portrait) CHROME_DP else CHROME_LAND_DP))
                                .coerceAtLeast(if (portrait) 220 else 120), portrait))
                        wv.evaluateJavascript(
                            "(function(){var s=document.getElementById('orient');" +
                            "if(!s)return;s.textContent=$css;" +
                            "window.dispatchEvent(new Event('resize'));})()", null)
                    },
                )
                GameLink("Close", Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                    onDismiss()
                }
            }
        }
    }
}


/**
 * The rules that change when the phone turns, and nothing else.
 *
 * Kept in one string because a rotation swaps it wholesale into the page's
 * <style id="orient">. The alternative -- rebuilding the document -- would
 * restart the battle, and a replay that jumps back to turn one because you
 * tilted the phone is worse than one that does not rotate at all.
 */
private fun orientCss(arenaDp: Int, portrait: Boolean): String {
    /* THE CHROME IS SIZED FOR PORTRAIT and has to give ground sideways.
       Turned landscape this phone offers about 336dp of height, and the game
       picker, the transport row, the speed row and their margins want 216 of
       it -- so the arena was floored taller than what was left and pushed the
       controls off the bottom of the screen. Shrunk rather than hidden: the
       controls are the only way to pause or scrub, and a replay you cannot
       pause is worse than one with small buttons. */
    val slim = if (portrait) "" else
        ".picker{padding:3px 92px 3px 10px;gap:6px}" +
        ".picker button{padding:3px;font-size:12px}" +
        ".ctl{padding:2px 8px;gap:4px}" +
        ".ctl button{font-size:13px;padding:2px 7px;min-width:32px}" +
        ".ctl .pos{font-size:10px;min-width:74px}" +
        "#agree,#warn{display:none}"

    val stacked = if (!portrait) "" else
        "/* replay2.css puts the halves side by side on short landscape " +
        "screens, and that query matches even in portrait here, so on a " +
        "portrait phone the stacked layout is put back -- and only then, " +
        "so a real landscape phone still gets the board the viewer " +
        "intends. */\n" +
        "@media (orientation:landscape) and (max-height:520px){" +
        ".r2{flex-direction:column}" +
        ".r2-half--top{border-bottom:1px solid rgba(255,255,255,.07);border-right:0}" +
        ".r2-half--bottom .r2-stats{order:9}" +
        ".r2-bubbles{right:76px;width:auto}}"
    val chrome = if (portrait) CHROME_DP else CHROME_LAND_DP
    return "html,body{height:${arenaDp + chrome}px}\n" +
        "#arena{height:${arenaDp}px}\n" + slim + stacked
}

/**
 * Vertical room the host page's own furniture needs: the game picker, the
 * control bar, the speed row and the margins between them. Subtracted from
 * the space the dialog reports so the arena takes what is actually left.
 */
private const val CHROME_DP = 96 + 58 + 46 + 16

/** The same furniture once the landscape rules above have shrunk it. */
private const val CHROME_LAND_DP = 52 + 40 + 34 + 8

private const val BASE = "https://autobattle.online/"

private fun flat(d: Deck) = JSONArray().apply { d.cards.forEach { put(it) } }

private fun page(
    mine: Deck, rival: Deck, cardDefs: String, display: String, seed: Int,
    youFirst: Boolean, expected: String, availDp: Int, portrait: Boolean,
): String {
    // What the arena gets is what is left after everything else on the page:
    // the agreement line and game picker above it, and BOTH control rows
    // below -- transport and speed. Reserving for one row put the speed
    // buttons off the bottom of the screen and pushed the transport row
    // under the navigation bar.
    // Same arithmetic as the rotation path, from the same constant, so
    // the first paint and every rotation after it agree.
    val arenaDp = (availDp - (if (portrait) CHROME_DP else CHROME_LAND_DP))
        .coerceAtLeast(if (portrait) 220 else 120)
    // A is always the side that moves first, so the seat decides which deck
    // is A -- not which one is yours.
    val a = if (youFirst) mine else rival
    val b = if (youFirst) rival else mine
    val aName = JSONObject.quote(a.name.ifBlank { "Deck A" })
    val bName = JSONObject.quote(b.name.ifBlank { "Deck B" })
    val planA = (a.battlePlan ?: JSONObject()).toString()
    val planB = (b.battlePlan ?: JSONObject()).toString()
    val seed2 = (seed.toLong() xor 0x80000000L) and 0xFFFFFFFFL
    val orient = orientCss(arenaDp, portrait)
    val youAreSlot = if (youFirst) 0 else 1
    val expectedJs = JSONObject.quote(expected)
    return """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="/css/replay2.css">
<style>
  /* THE MOUNT NEEDS A DEFINITE HEIGHT.
     ReplayV2 adds .r2 to the element it is given, and .r2 is a flex column
     whose two halves are flex:1 1 50% -- the top player and the bottom
     player. With no height to divide, the halves cannot split it and the
     board spreads sideways instead of stacking. An explicit viewport height
     is used rather than a flex chain: the flex version collapsed the arena
     to a sliver, because nothing in the chain had a resolved height to give
     it. */
  /* No height here -- it is in #orient below, because it changes when the
     phone turns. */
  html,body{margin:0;background:#07081a;color:#e8e4d9;
            font-family:Georgia,serif;overflow:hidden}
  /* WHY html/body CARRY AN EXPLICIT HEIGHT.
     replay2.css flips the board to side-by-side on short landscape screens:
       @media (orientation:landscape) and (max-height:520px){.r2{flex-direction:row}}
     A WebView whose height comes from its content reports a degenerate
     viewport -- measured here as 352x0 -- so width exceeded height, the page
     read as short landscape, and the two players sat beside each other
     instead of stacking. With a real height the viewport reads 360x780 and
     the rule correctly does not apply. The rule itself is left alone: it is
     right for an actual landscape phone. */
  #msg{padding:14px;font-size:14px}
  #warn{margin:8px 14px;padding:8px;border:2px solid #ce6a5c;color:#ffd7d0;
        font-size:13px;display:none}
  #agree{margin:6px 14px 0;font-size:12px;color:#8a8aa8}
  .picker{display:flex;gap:8px;padding:8px 92px 8px 14px}
  .picker button{flex:1;padding:8px;background:#141428;color:#ffe28c;
                 border:2px solid #6e5522;font-family:inherit;font-size:14px}
  /* ReplayV2 draws the board and nothing else -- play, step, scrub and
     speed are the host page's job, which is why there were no controls at
     all. These mirror testviewer.php's. */
  .ctl{display:flex;align-items:center;gap:6px;padding:4px 8px}
  .ctl button{background:#141428;color:#ffe28c;border:2px solid #6e5522;
              font-family:inherit;font-size:15px;padding:5px 9px;min-width:38px}
  .ctl button.on{background:#2b2a4a;color:#fff}
  .ctl input[type=range]{flex:1 1 auto;min-width:0;accent-color:#ffe28c}
  .ctl .pos{font-size:11px;color:#8a8aa8;min-width:92px;text-align:right}
  #arena{margin:0 4px 2px}
  /* Lets the two halves actually shrink to their share of the width. Without
     it a flex item keeps its content's width and spills past the screen,
     which is why the second player hung off the right edge in landscape.
     Targets the children directly, since the stylesheet names only the
     --top and --bottom modifiers. */
  #arena > *{min-width:0}
</style>
<!-- EVERYTHING THAT DEPENDS ON THE PHONE'S ORIENTATION LIVES HERE, alone, so
     a rotation can replace it without touching the rest. Reloading the page
     would re-run the battle from turn one, which is not what rotating your
     phone should mean. -->
<style id="orient">$orient</style></head><body>
<div id="msg">Loading the viewer\u2026</div>
<div id="warn"></div>
<div id="agree"></div>
<div class="picker" id="pick" style="display:none">
  <button onclick="show(0)">Game 1</button>
  <button onclick="show(1)">Game 2</button>
</div>
<div id="arena"></div>
<div class="ctl" id="ctl" style="display:none">
  <button id="first" title="Start">&#9198;</button>
  <button id="back" title="Step back">&#9664;</button>
  <button id="play" title="Play or pause">&#9654;</button>
  <button id="fwd" title="Step forward">&#9654;|</button>
  <input id="seek" type="range" min="0" max="1" value="0">
  <span class="pos" id="pos"></span>
</div>
<div class="ctl" id="spd" style="display:none">
  <button data-s="0.5">&#189;&times;</button>
  <button data-s="1" class="on">1&times;</button>
  <button data-s="2">2&times;</button>
  <button data-s="4">4&times;</button>
</div>
<!-- card-popup.js first: ReplayV2 draws real cards through buildCardSVG
     when it is present, and falls back to plain boxes when it is not. -->
<script src="/js/card-popup.js"></script>
<script src="/js/battle-engine.js"></script>
<script src="/js/replay2.js"></script>
<script>
(function () {
  var msg = document.getElementById('msg');
  /* FIT THE BOARD TO THE SCREEN, BY MEASURING IT.
     Turned sideways the viewer puts the two players side by side -- which is
     its own design for a short landscape screen, and the right one -- but the
     halves do not shrink, so on a phone the second player ran off the right
     edge. The scale is read off the overflow rather than guessed: a fixed
     zoom that fits one board clips another with more cards in play, and the
     board changes as the game is played. Re-run whenever the layout changes. */
  function fit() {
    var a = document.getElementById('arena');
    if (!a) return;
    a.style.zoom = '';
    /* MEASURE THE HALVES, NOT THE ARENA. Side by side, each player is a flex
       item, and a flex item defaults to min-width:auto -- it refuses to
       shrink below its content, so it overflows its half while the ARENA's
       own scrollWidth still equals its clientWidth and reports no problem.
       The first attempt measured the arena and therefore never fired. */
    var halves = a.children, want = 0, have = a.clientWidth;
    for (var i = 0; i < halves.length; i++) {
      var h = halves[i];
      if (!h.clientWidth) continue;
      /* How much wider the half's content is than the half, expressed back in
         arena widths. Measured off the arena's own CHILDREN rather than a
         class name: replay2.css only ever names .r2-half--top and
         .r2-half--bottom, so a selector for a base .r2-half matched nothing
         and this silently did nothing at all. */
      want = Math.max(want, h.scrollWidth / h.clientWidth * have);
    }
    if (!want) want = a.scrollWidth;
    if (want > have && have > 0) {
      /* Floored at 0.55: below that the card text stops being readable, and a
         board you cannot read is no better than one you cannot see. */
      a.style.zoom = Math.max(0.55, have / want).toFixed(3);
    }
  }
  window.addEventListener('resize', fit);

  if (!window.BattleEngine || !window.ReplayV2) {
    msg.textContent = 'Could not load the replay viewer.'; return;
  }
  var defs  = $cardDefs;
  // The engine's definitions carry no art, no rules text and no rarity --
  // it does not need them, and buildCardSVG does. Merged in here rather than
  // in PayloadBuilder so what the two engines compute from stays identical.
  var display = $display;
  for (var id in display) {
    if (!defs[id]) continue;
    for (var k in display[id]) defs[id][k] = display[id][k];
  }
  var cardsA = ${flat(a)}, cardsB = ${flat(b)};
  var planA = $planA, planB = $planB;
  var games, v = null;
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
  // shown a game that did not happen, so the page says so either way --
  // an absent warning is indistinguishable from a check that threw.
  try {
    var m = BattleEngine.simulateMatch(cardsA, cardsB, defs, $seed, planA, planB,
      null, null, null, null, null, null, null);
    var youAre = $youAreSlot;
    var got = m.winner === -1 ? 'drew' : (m.winner === youAre ? 'won' : 'lost');
    if (got !== $expectedJs) {
      var w = document.getElementById('warn');
      w.style.display = '';
      w.textContent = 'This replay disagrees with the scored result (' +
        $expectedJs + ' vs ' + got + '), so it is not the game that counted.';
    } else {
      document.getElementById('agree').textContent =
        'Replay agrees with the scored result (' + got + ').';
    }
  } catch (e) {
    document.getElementById('agree').textContent =
      'Could not verify this replay against the scored result: ' + e;
  }

  window.show = function (i) {
    var rec = games[i];
    if (v) { try { v.destroy(); } catch (e) {} }
    var arena = document.getElementById('arena');
    arena.innerHTML = '';
    try {
      v = new window.ReplayV2(arena, {
        turnData: rec.turnData, lines: rec.lines, cardDefs: defs,
        maxLife: 100,
        // recordBattle's own {winner, reason}, so the end panel states the
        // result from the engine rather than inferring it from the log.
        result: rec.result || null,
      });
    } catch (e) {
      arena.innerHTML = '<div id="msg">Viewer failed to start: ' + e + '</div>';
      return;
    }
    fit();
    // Wire the controls to THIS game's viewer, then start it running --
    // opening a replay and landing on a frozen turn 1 is a step with no
    // decision in it.
    var seek = document.getElementById('seek');
    var play = document.getElementById('play');
    var pos  = document.getElementById('pos');
    seek.max = Math.max(0, v.events.length - 1);
    v.onChange(function (st) {
      seek.value = st.idx;
      play.innerHTML = st.playing ? '&#10073;&#10073;' : '&#9654;';
      pos.textContent = 'turn ' + (st.turn || '-') + ' · ' + st.idx + '/' + (st.total - 1);
    });
    document.getElementById('first').onclick = function () { v.seek(0); };
    document.getElementById('back').onclick  = function () { v.seek(Math.max(0, v.idx - 1), true); };
    document.getElementById('fwd').onclick   = function () { v.seek(Math.min(v.events.length - 1, v.idx + 1), true); };
    play.onclick = function () { v.playing ? v.pause() : v.play(); };
    seek.oninput = function () { v.seek(parseInt(seek.value, 10), true); };
    Array.prototype.forEach.call(document.querySelectorAll('#spd button'), function (b) {
      b.onclick = function () {
        v.setSpeed(parseFloat(b.dataset.s));
        Array.prototype.forEach.call(document.querySelectorAll('#spd button'),
          function (x) { x.classList.remove('on'); });
        b.classList.add('on');
      };
    });
    document.getElementById('ctl').style.display = '';
    document.getElementById('spd').style.display = '';
    v.seek(0);
    v.play();

  };
  msg.style.display = 'none';
  document.getElementById('pick').style.display = '';
  show(0);
})();
</script></body></html>
"""
}
