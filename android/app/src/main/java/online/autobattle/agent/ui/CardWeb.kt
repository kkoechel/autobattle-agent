package online.autobattle.agent.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import online.autobattle.agent.game.CardInfo
import online.autobattle.agent.game.Keyword
import org.json.JSONObject

/**
 * A card, drawn by the main game's own renderer.
 *
 * This replaced a hand-port of the card frame. The port looked approximately
 * right and was wrong in every detail -- the real popup is not CSS at all, it
 * is a 570-line SVG built by window.buildCardSVG in js/card-popup.js, with
 * text measurement, per-rarity frames, corner filigree, supertype icons and
 * indicator rows. Reproducing that in Compose would have meant maintaining a
 * second implementation that drifts the first time the main game touches a
 * card layout.
 *
 * So the app loads that file and calls it. The page is ours, the base URL is
 * the main game's, and every relative path inside the SVG -- card art,
 * /img/icons/supertype-*.png, tag icons -- resolves against it. The main game
 * is only ever read from.
 *
 * card-popup.js is self-contained: it exposes buildCardSVG on window and the
 * only globals it reads are optional (CARD_POPUP_I18N, CARD_KEYWORDS), each
 * behind a `??` default. The PHP-looking names in its source are comments
 * about where the server-side text came from, not calls.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CardWeb(
    card: CardInfo,
    onDismiss: () -> Unit,
    keywords: Map<String, Keyword> = emptyMap(),
    cardsById: Map<Int, CardInfo> = emptyMap(),
    onOpenCard: (CardInfo) -> Unit = {},
) {
    var defining by remember { mutableStateOf<Keyword?>(null) }
    // The renderer lives on the network. When it cannot be reached the card
    // still has to appear, so the hand-built frame stays as the fallback --
    // wired to a real failure rather than left in the tree as dead code.
    var offline by remember(card.id) { mutableStateOf(false) }
    // The SVG's height is whatever its content needs, so the page reports its
    // own aspect ratio back and the dialog sizes to it. Guessing a height
    // either crops the card or leaves a band of dead space under it.
    var ratio by remember(card.id) { mutableStateOf(1.45f) }

    if (offline) {
        val tokens = remember(cardsById) {
            cardsById.values
                .filter { it.rarity.equals("token", true) || it.rarity.equals("manifest", true) }
                .associateBy { it.name.lowercase() }
        }
        CardSheet(card, onDismiss, keywords, tokens, onOpenCard)
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.width(CARD_W.dp).heightIn(max = 640.dp)) {
            AndroidView(
                modifier = Modifier.width(CARD_W.dp).height((CARD_W * ratio).dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false

                        addJavascriptInterface(object {
                            /** Reported once the SVG exists, so the dialog can size to it. */
                            @JavascriptInterface
                            fun size(w: Float, h: Float) {
                                if (w > 0 && h > 0) post { ratio = h / w }
                            }

                            /** A tap on a keyword or a card name inside the card text. */
                            @JavascriptInterface
                            fun term(kind: String, value: String) = post {
                                when (kind) {
                                    "keyword" -> keywords[value.lowercase()]?.let { defining = it }
                                    "card" -> value.toIntOrNull()
                                        ?.let(cardsById::get)?.let(onOpenCard)
                                }
                            }
                        }, "Agent")

                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView, req: WebResourceRequest, err: WebResourceError,
                            ) {
                                // Only the page itself failing means we cannot
                                // draw the card. A missing art file is the
                                // card's problem, not the renderer's.
                                if (req.isForMainFrame) post { offline = true }
                            }
                        }
                        loadDataWithBaseURL(BASE, page(card), "text/html", "utf-8", null)
                    }
                },
                // The dialog is reused across cards, so a new card has to
                // reload rather than rely on the factory running again.
                update = { it.loadDataWithBaseURL(BASE, page(card), "text/html", "utf-8", null) },
            )
            // The card itself fills the dialog, so dismissal is the scrim --
            // swallowing taps here would also swallow the keyword taps.
        }
    }

    defining?.let { KeywordSheet(it) { defining = null } }
}

private const val BASE = "https://autobattle.online/"

/** CSS pixels. buildCardSVG's own default, and what its text metrics assume. */
private const val CARD_W = 300

private fun page(card: CardInfo): String {
    // The card goes in as JSON rather than as interpolated fields: it is the
    // argument buildCardSVG actually takes, and anything the main game adds to
    // a card arrives for free.
    val cardJson = card.raw.ifBlank { JSONObject().put("id", card.id).put("name", card.name).toString() }
    return """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=$CARD_W,initial-scale=1">
<style>
  html,body{margin:0;padding:0;background:transparent;overflow:hidden}
  svg{display:block;width:100%;height:auto}
</style></head><body>
<div id="card"></div>
<script src="/js/card-popup.js"></script>
<script>
(function () {
  var host = document.getElementById('card');
  try {
    // showHint off: the renderer's default footer invites you to
    // double-click through to the card's page on the main site, which
    // does not exist here -- and it was being clipped anyway.
    host.innerHTML = window.buildCardSVG($cardJson, { width: $CARD_W, fullText: true, showHint: false });
  } catch (e) {
    document.body.textContent = 'card render failed: ' + e;
    return;
  }
  var svg = host.querySelector('svg');
  if (svg) {
    // viewBox is authoritative; the attributes may be absent or percentages.
    var vb = (svg.getAttribute('viewBox') || '').split(/[ ,]+/);
    var w = parseFloat(vb[2]), h = parseFloat(vb[3]);
    if (!(w > 0 && h > 0)) { var r = svg.getBoundingClientRect(); w = r.width; h = r.height; }
    if (window.Agent && w > 0 && h > 0) window.Agent.size(w, h);
  }
  // One delegated handler, matching how the site binds these: every
  // interactive part of the card carries data-keyword or data-card-id.
  document.addEventListener('click', function (e) {
    var t = e.target;
    if (!t || !t.closest) return;
    var kw = t.closest('[data-keyword]');
    if (kw) { window.Agent && window.Agent.term('keyword', kw.getAttribute('data-keyword')); return; }
    var cd = t.closest('[data-card-id]');
    if (cd) { window.Agent && window.Agent.term('card', cd.getAttribute('data-card-id')); }
  }, true);
})();
</script></body></html>
"""
}
