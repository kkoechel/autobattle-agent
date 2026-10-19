package online.autobattle.agent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import online.autobattle.agent.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The whole palette, in one place.
 *
 * Taken from the game rather than invented: the cards are drawn by the main
 * game's own renderer, so a neutral Material surface around them looked like
 * a web form someone had pasted trading cards into. These are the values
 * those cards actually use -- the gold of a card's frame and rule text, and
 * the near-black of its body -- so the chrome and the cards belong together.
 */
private val Ink      = Color(0xFF0B0A12)  // page, close to the site's --bg
private val Panel    = Color(0xFF16141D)  // raised surface
private val Parchment= Color(0xFFEDE3CC)  // body text, warm not white
private val Muted    = Color(0xFF9A8F7A)  // secondary text, same warmth
private val Gold     = Color(0xFFD9A441)  // the card frame's gold
private val GoldLit  = Color(0xFFF5D98B)  // its highlight, for pressed/emphasis
private val Ember    = Color(0xFFCE6A5C)

/** Edges and fills for the framed look, shared with GameUi. */
internal val GoldDim   = Color(0xFF6E5522)
internal val PanelTop  = Color(0xFF241D13)
internal val PanelFoot = Color(0xFF14110B)
internal val GoldBright = GoldLit

val Good = Color(0xFF6FA86A)
val Warn = Gold
val Bad = Ember
val Subtle = Muted

private val scheme = darkColorScheme(
    primary = Gold,
    onPrimary = Ink,
    background = Ink,
    onBackground = Parchment,
    surface = Panel,
    onSurface = Parchment,
    surfaceVariant = Panel,
    onSurfaceVariant = Muted,
    error = Ember,
)

/**
 * Serif throughout, because the cards are.
 *
 * Android has no Georgia, which is what the card renderer asks for, but
 * FontFamily.Serif resolves to Noto Serif and sits in the same place --
 * old-style, moderate contrast. Headings get letter spacing so they read as
 * engraved rather than merely bold, which is most of the difference between
 * a game and a settings screen.
 */
private val serif = FontFamily.Serif

/**
 * Pixelify Sans (OFL, licence in android/OFL-PixelifySans.txt), used by the
 * dialogue window. One weight only: it ships as a variable font with no
 * static instances, and selecting a weight from one needs API 26 while this
 * app supports 24. That costs nothing, because the era it is imitating had
 * no bold either -- emphasis was colour and a cursor.
 */
val Pixel = FontFamily(Font(R.font.pixelify_sans))

private val typography = Typography(
    titleLarge = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = 1.4.sp),
    titleMedium = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold,
        fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.9.sp),
    titleSmall = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.6.sp),
    bodyLarge = TextStyle(fontFamily = serif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = serif, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = serif, fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold,
        fontSize = 14.sp, letterSpacing = 1.2.sp),
    labelMedium = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold,
        fontSize = 12.sp, letterSpacing = 0.6.sp),
)

@Composable
fun AgentTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
