package online.autobattle.agent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import online.autobattle.agent.R

/**
 * The agent, as a face.
 *
 * The app's whole decision surface is a conversation, and until now the other
 * side of it was a paragraph of grey text. A portrait costs nothing to run and
 * turns status into someone reacting.
 *
 * PIXEL ART RULES, both of which are easy to lose:
 *  - FilterQuality.None. The default is bilinear, which turns a 32px sprite
 *    scaled to 96dp into mush. This is the whole difference between pixel art
 *    and a blurry small picture.
 *  - drawable-nodpi. In a density bucket Android pre-scales the bitmap for the
 *    device before Compose ever sees it, and that resample is bilinear too, so
 *    the damage is done before FilterQuality can help.
 *
 * Replacing the art: drop six 32x32 PNGs with transparent backgrounds over
 * res/drawable-nodpi/npc_*.png, keeping the names. Nothing else needs to
 * change. Larger sources work if they stay an exact multiple of 32, so the
 * on-screen size remains an integer scale.
 */
enum class Mood { IDLE, ASKING, WORKING, PLEASED, DISAPPOINTED, STUCK }

@Composable
fun AgentPortrait(mood: Mood, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val art = when (mood) {
        Mood.IDLE -> R.drawable.npc_idle
        Mood.ASKING -> R.drawable.npc_asking
        Mood.WORKING -> R.drawable.npc_working
        Mood.PLEASED -> R.drawable.npc_pleased
        Mood.DISAPPOINTED -> R.drawable.npc_disappointed
        Mood.STUCK -> R.drawable.npc_stuck
    }
    Box(modifier.size(size)) {
        // The ImageBitmap overload, not the Painter one: filterQuality only
        // exists here, and it is the entire point of the composable.
        Image(
            bitmap = ImageBitmap.imageResource(art),
            contentDescription = null,
            modifier = Modifier.size(size),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )
    }
}

/**
 * Which face to wear, from state the app already keeps.
 *
 * Deliberately derived rather than set by hand at each call site: a mood that
 * has to be remembered to update is a mood that goes stale, and the triggers
 * already exist -- Memory tracks the last placement and how many barren runs
 * the search has had.
 */
fun moodFor(
    working: Boolean,
    asking: Boolean,
    barrenRuns: Int,
    placement: Int?,
    lastPlacement: Int?,
): Mood = when {
    working -> Mood.WORKING
    asking -> Mood.ASKING
    barrenRuns >= 3 -> Mood.STUCK
    // Lower placement is better, so a smaller number than last time is a
    // climb. Both must be present: a missing one is "no opinion", not "even".
    placement != null && lastPlacement != null && placement < lastPlacement -> Mood.PLEASED
    placement != null && lastPlacement != null && placement > lastPlacement -> Mood.DISAPPOINTED
    else -> Mood.IDLE
}
