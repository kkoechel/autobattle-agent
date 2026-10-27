package online.autobattle.agent.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntRect
import online.autobattle.agent.R

/**
 * The character standing behind everything on the Agent screen.
 *
 * Derived from real card art by tools/pixelate_avatar.py -- 135x240 and
 * sixteen colours, the same palette budget the hand-drawn portrait uses.
 *
 * NOT an integer upscale, despite 135x240 having been picked as 1/8 of
 * 1080x1920. Drawn full screen it is scaled to COVER, and a 1080x2340 phone
 * is taller than 16:9, so height drives the scale at 9.75x and the pixel
 * blocks come out slightly uneven. It reads fine -- nearest-neighbour at
 * nearly ten times is chunky either way -- but the claim that the ratio is
 * exact stopped being true the moment this went behind the whole screen,
 * and device aspects vary too much for any one source size to fix it.
 */
enum class Avatar(
    val key: String,
    val label: String,
    @DrawableRes val art: Int,
    /** Where the character sits in the frame, for cropping on odd aspects. */
    val focus: Alignment,
    /**
     * True when the art is a figure on transparency rather than a whole
     * scene.
     *
     * This decides whether a locale can be drawn at all. The first three are
     * card artwork -- a complete picture, background included -- so putting a
     * scene behind one simply hides the scene. Only a cut-out leaves anything
     * for a locale to show through.
     */
    val cutout: Boolean = false,
    /**
     * The face alone, as a box in the 135x240 source: left, top, right,
     * bottom.
     *
     * A CROP, NOT A SECOND ASSET. Three more PNGs at five densities is
     * fifteen files that can drift from the figures they are cut from, and
     * the crop costs nothing -- BitmapPainter takes a source rectangle, so
     * the same bitmap already in memory for the backdrop is what gets
     * drawn.
     *
     * Placed by eye at eight times size. All three figures stand the same
     * way in their frames (head between y 0 and 40, shoulders from 36), so
     * the boxes are near-identical; they differ by a pixel or two because
     * Kard's hair spikes right, Emma's falls wide, and a box that suits one
     * clips the other.
     */
    val head: IntRect,
) {
    // ONLY PARAGONS NOW. The first three faces were card artwork -- the
    // Wizard, the Brute and the Sprite -- from before the game had Paragons
    // in it. A Paragon is a person you are playing as; a piece of card art
    // is a picture of a card you might draw, and the two cannot both be "who
    // your agent is" without the fiction going soft. Their drawables stay in
    // the tree because the keys below still map old saved preferences onto a
    // Paragon rather than crashing on them.
    //
    // The Paragons. Cut out of a chroma field by tools/pixelate_paragon.py,
    // so each stands in whatever locale the moment calls for.
    PARAGON_ONE("paragon1", "Kard Batal", R.drawable.paragon_1,
        Alignment.TopCenter, cutout = true, head = IntRect(34, 1, 88, 56)),
    PARAGON_TWO("paragon2", "Dan Mage", R.drawable.paragon_2,
        Alignment.TopCenter, cutout = true, head = IntRect(38, 1, 92, 54)),
    PARAGON_THREE("paragon3", "Emma Ji", R.drawable.paragon_3,
        Alignment.TopCenter, cutout = true, head = IntRect(38, 0, 90, 54));

    companion object {
        val DEFAULT = PARAGON_ONE
        /**
         * Reads a saved preference, including one written when the three
         * card-art faces existed -- "wizard", "brute" and "sprite" are no
         * longer offered, and anyone carrying one gets the Paragon whose
         * VOICE matches, so the agent keeps speaking the way it did.
         */
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: when (key) {
            "brute" -> PARAGON_TWO     // Dan Mage is the blunt one
            "sprite" -> PARAGON_THREE  // Emma Ji is the warm one
            else -> DEFAULT            // "wizard", null, anything unknown
        }

        private const val PREFS = "agent_look"
        private const val KEY = "avatar"

        fun load(ctx: Context): Avatar =
            of(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null))

        fun save(ctx: Context, a: Avatar) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, a.key).apply()
        }
    }
}

/**
 * The avatar, full bleed, with everything else drawn over it.
 *
 * THE FILTER QUALITY IS THE WHOLE POINT. The source is 135x240; scaled with
 * the default filtering it arrives as a soft blur that looks like a mistake
 * rather than like pixel art. FilterQuality.None keeps every pixel square.
 * Image()'s painter overload has no filterQuality parameter, so the painter
 * is built by hand -- which is also why the bitmap is loaded with
 * imageResource rather than painterResource.
 *
 * The scrim is not decoration. The dialogue windows are opaque and legible on
 * their own, but the loose text between them is not, and the art runs from
 * near-white horns to near-black robes, so there is no single text colour
 * that works over all three. Darkening the whole backdrop fixes it for every
 * avatar at once instead of per-image tuning that the next avatar breaks.
 */
@Composable
fun AvatarBackdrop(
    avatar: Avatar,
    modifier: Modifier = Modifier,
    /**
     * Where the scene is set, or null to keep the figure's own artwork as the
     * whole backdrop.
     *
     * TWO LAYERS, TWO PALETTES. A Paragon is cut out and quantised alone, and
     * the locale is quantised alone behind it, because sixteen colours shared
     * between a figure and its background is what turned the first test piece
     * into sixteen shades of brown. Separated, a teal-and-crimson figure
     * survives against a golden forest and a grey graveyard alike.
     *
     * The locale is CARD ART. The catalogue is already drawn flat-colour with
     * hard edges, so over a thousand usable scenes exist and none had to be
     * drawn.
     */
    locale: Int? = null,
) {
    val bmp = androidx.compose.ui.graphics.ImageBitmap.imageResource(avatar.art)
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (locale != null) {
            Image(
                painter = BitmapPainter(
                    androidx.compose.ui.graphics.ImageBitmap.imageResource(locale),
                    filterQuality = FilterQuality.None),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Image(
            painter = BitmapPainter(bmp, filterQuality = FilterQuality.None),
            contentDescription = null,
            // FIT, NOT CROP, FOR A CUT-OUT.
            //
            // Crop covers the box, and the box here is proportionally WIDER
            // than the art (135x240 against roughly 1058x1802 on an S22), so
            // it scaled to the width and shaved the surplus off the bottom --
            // which on a standing figure is the feet. Emma Ji was walking
            // around with her boots cut off at the ankle.
            //
            // A cut-out has nothing to lose by fitting: it is a figure on
            // transparency, so the spare width is invisible rather than
            // letterboxed. Card art still crops, because there the spare
            // space is picture and leaving it in would shrink the subject.
            contentScale = if (avatar.cutout) ContentScale.Fit else ContentScale.Crop,
            alignment = avatar.focus,
            // THE HEAD HAS TO CLEAR THE CHROME. A cut-out figure fills its
            // frame from the very top, so drawn flush it puts the face
            // directly behind the tab row -- which is exactly what happened
            // the moment the title was removed and the UI moved up. The old
            // card-art avatars never showed this because their subjects sit
            // lower inside their own artwork.
            //
            // A FRACTION, NOT A DP. The chrome is a share of the screen, not
            // a fixed number of pixels, and a hardcoded offset would be wrong
            // on every phone but this one. The figure is allowed to overflow
            // the bottom; the dialogue box covers it.
            modifier = Modifier.fillMaxSize().let {
                if (locale != null)
                    it.padding(top = maxHeight * 0.23f,
                               start = maxWidth * 0.01f, end = maxWidth * 0.01f)
                       // UP, AND ONLY UP. Shrinking the top padding would
                       // have raised the figure by growing its box, changing
                       // the framing and the scale at once. The offset moves
                       // the same figure at the same size; the backdrop is
                       // full-bleed behind it, so nothing is uncovered.
                       //
                       // Placed by hand, not calculated. Ryan set these three
                       // on a scale model of the screen that draws the figure
                       // the same way this does -- padded box, cropped from
                       // the top, then translated -- so what he dialled in is
                       // what these numbers are.
                       .offset(y = -(maxHeight * 0.11f))
                else it
            },
        )
        // The scrim is its own layer. On the figure it only darkened the
        // figure's own box, so with the offset above it would have left the
        // top quarter of the screen unshaded and the tabs unreadable.
        Box(Modifier.fillMaxSize().drawWithContent {
            drawContent()
            // Heavier at the bottom, where the dialogue and the choices sit,
            // and light at the top so the character still reads. A flat scrim
            // strong enough for the text turned the whole picture to mud.
            drawRect(Brush.verticalGradient(
                0.0f to Color(0x66101020),
                0.45f to Color(0x99101020),
                1.0f to Color(0xD9101020),
            ))
        })
    }
}

/**
 * The Paragon's face alone, for a corner.
 *
 * Drawn from the SAME bitmap the backdrop uses, through BitmapPainter's
 * source rectangle -- so switching Paragon costs nothing beyond the box,
 * and there is no second set of files to keep in step with the first.
 *
 * FilterQuality.None for the same reason the backdrop has it: the source
 * is pixel art about fifty pixels across, and default filtering turns it
 * into a smear at any size worth looking at.
 *
 * No scrim and no frame. In the replay the figure is a watcher at the edge
 * of the board rather than a piece of furniture, and a plate around it
 * would make it the latter.
 */
@Composable
fun ParagonHead(avatar: Avatar, modifier: Modifier = Modifier) {
    val bmp = androidx.compose.ui.graphics.ImageBitmap.imageResource(avatar.art)
    Image(
        painter = BitmapPainter(bmp, avatar.head.topLeft, avatar.head.size,
                                filterQuality = FilterQuality.None),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

/**
 * Which scene the Paragon is standing in, for a given mood.
 *
 * Keyed on the Mood the screen already computes from real state -- working,
 * asking, a barren streak, a climb or a slip -- rather than on a second,
 * parallel notion of context that could disagree with the portrait. The
 * backdrop and the expression are then always telling the same story.
 *
 * Every locale is a card from the live catalogue, pixelated by
 * tools/pixelate_paragon.py:
 *
 *   IDLE          Mountain Temple    the dojo, between things
 *   ASKING        Field Shrine       a question needs answering
 *   WORKING       Dwarven Forge      something is being made
 *   PLEASED       Hallowed Field     we climbed
 *   DISAPPOINTED  Sad Storm          we slipped
 *   STUCK         Living Graveyard   three runs, nothing gained
 */
fun localeFor(mood: Mood): Int = when (mood) {
    Mood.IDLE -> R.drawable.locale_idle
    Mood.ASKING -> R.drawable.locale_asking
    Mood.WORKING -> R.drawable.locale_working
    Mood.PLEASED -> R.drawable.locale_pleased
    Mood.DISAPPOINTED -> R.drawable.locale_disappointed
    Mood.STUCK -> R.drawable.locale_stuck
}
