package app.aduster.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aduster.R
import app.aduster.settings.ThemeMode

/**
 * A deliberately small palette, the same as www.aduster.site's: one ink scale plus one fixed
 * silver-blue accent. The accent marks what is active or blocked and the actions on offer
 * (buttons, links, tabs, progress); amber marks "paused" and what needs a look (errors,
 * destructive actions). [faint] is for lines and decorations only: text uses [muted] or
 * stronger (faint text is about 2.5:1, below the contrast text needs).
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    /** Cards, grouped rows and dialogs. */
    val surface: Color,
    /** The selected nav pill, tonal buttons, icon circles. */
    val surfaceHigh: Color,
    /** The tinted header card on Home and Filters. */
    val tone: Color,
    val line: Color,
    val text: Color,
    /** Section headings. */
    val text2: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val onAccent: Color,
    val warn: Color,
)

/** Always provided by [AdusterTheme]; a read outside it fails loudly, not with a default. */
val LocalPalette = staticCompositionLocalOf<Palette> { error("no palette: wrap the content in AdusterTheme") }

/**
 * Google Sans (OFL, bundled; static Regular and Medium cut from the variable font, Latin, Greek
 * and Cyrillic) for all text. Other scripts fall back to the system font glyph by glyph.
 */
val GoogleSans = FontFamily(
    Font(R.font.google_sans_regular, FontWeight.Normal),
    Font(R.font.google_sans_medium, FontWeight.Medium),
)

/** Google Sans Code (OFL, bundled) for numbers, the query log and rules: columns line up. */
val GoogleSansCode = FontFamily(
    Font(R.font.google_sans_code_light, FontWeight.Light),
    Font(R.font.google_sans_code_regular, FontWeight.Normal),
    Font(R.font.google_sans_code_medium, FontWeight.Medium),
)

object Type {
    private val tnum = "tnum"
    val display = TextStyle(fontSize = 40.sp, fontFamily = GoogleSans, fontWeight = FontWeight.Normal, letterSpacing = (-1.0).sp, lineHeight = 46.sp)
    /** The tabs' titles (Filters, Settings, Log). */
    val screenTitle = TextStyle(fontSize = 32.sp, fontFamily = GoogleSans, fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp, lineHeight = 40.sp)
    val title = TextStyle(fontSize = 26.sp, fontFamily = GoogleSans, fontWeight = FontWeight.Normal, letterSpacing = (-0.4).sp, lineHeight = 32.sp)
    val dialogTitle = TextStyle(fontSize = 22.sp, fontFamily = GoogleSans, fontWeight = FontWeight.Normal, letterSpacing = (-0.2).sp, lineHeight = 28.sp)
    val metric = TextStyle(fontSize = 26.sp, fontFamily = GoogleSansCode, fontWeight = FontWeight.Light, letterSpacing = (-0.8).sp, lineHeight = 32.sp)
    val body = TextStyle(fontSize = 15.sp, fontFamily = GoogleSans, fontWeight = FontWeight.Normal, lineHeight = 21.sp)
    val bodyStrong = TextStyle(fontSize = 15.sp, fontFamily = GoogleSans, fontWeight = FontWeight.Medium, lineHeight = 21.sp)
    val small = TextStyle(fontSize = 13.sp, fontFamily = GoogleSans, fontWeight = FontWeight.Normal, lineHeight = 18.sp, fontFeatureSettings = tnum)
    /** Section headings, sentence case. */
    val heading = TextStyle(fontSize = 14.sp, fontFamily = GoogleSans, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
    val nav = TextStyle(fontSize = 12.sp, fontFamily = GoogleSans, fontWeight = FontWeight.Normal, lineHeight = 16.sp)
    val mono = TextStyle(fontSize = 13.sp, fontFamily = GoogleSansCode, lineHeight = 19.sp)
    val monoBody = TextStyle(fontSize = 14.sp, fontFamily = GoogleSansCode, lineHeight = 20.sp)
    val number = TextStyle(fontSize = 14.sp, fontFamily = GoogleSansCode, lineHeight = 20.sp)
    val numberLabel = TextStyle(fontSize = 11.sp, fontFamily = GoogleSansCode, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
}

/** Material's own text (dialog buttons, toasts aside) in Google Sans too. */
private val typography = Typography().let { t ->
    fun TextStyle.sans() = copy(fontFamily = GoogleSans)
    Typography(
        displayLarge = t.displayLarge.sans(), displayMedium = t.displayMedium.sans(), displaySmall = t.displaySmall.sans(),
        headlineLarge = t.headlineLarge.sans(), headlineMedium = t.headlineMedium.sans(), headlineSmall = t.headlineSmall.sans(),
        titleLarge = t.titleLarge.sans(), titleMedium = t.titleMedium.sans(), titleSmall = t.titleSmall.sans(),
        bodyLarge = t.bodyLarge.sans(), bodyMedium = t.bodyMedium.sans(), bodySmall = t.bodySmall.sans(),
        labelLarge = t.labelLarge.sans(), labelMedium = t.labelMedium.sans(), labelSmall = t.labelSmall.sans(),
    )
}

@Composable
fun AdusterTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.BLACK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val palette = if (dark) BlackPalette else LightPalette
    MaterialTheme(colorScheme = colorScheme(palette), typography = typography, shapes = shapes) {
        CompositionLocalProvider(LocalPalette provides palette, content = content)
    }
}

/** Dialogs (Material's extra-large shape) take the cards' 24 dp corners. */
private val shapes = Shapes(extraLarge = RoundedCornerShape(24.dp))

/** Material's own parts (dialogs, menus, ripples, text fields) in the palette's colours, nothing from the wallpaper. */
internal fun colorScheme(p: Palette): ColorScheme = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
    primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.surfaceHigh, onPrimaryContainer = p.text, inversePrimary = p.accent,
    secondary = p.accent, onSecondary = p.onAccent, secondaryContainer = p.surfaceHigh, onSecondaryContainer = p.text,
    tertiary = p.accent, onTertiary = p.onAccent, tertiaryContainer = p.surfaceHigh, onTertiaryContainer = p.text,
    background = p.bg, onBackground = p.text, surface = p.bg, onSurface = p.text, surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.muted,
    surfaceTint = p.surface, inverseSurface = p.text, inverseOnSurface = p.bg,
    error = p.warn, onError = p.bg, errorContainer = p.surfaceHigh, onErrorContainer = p.warn,
    outline = p.line, outlineVariant = p.line, scrim = Color.Black,
    surfaceBright = p.surfaceHigh, surfaceDim = p.bg, surfaceContainerLowest = p.bg, surfaceContainerLow = p.surface,
    surfaceContainer = p.surface, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surfaceHigh,
)

/** Every text colour keeps at least 4.5:1 on [Palette.bg], [Palette.surface], [Palette.surfaceHigh] and [Palette.tone] (a test checks). */
internal val BlackPalette = Palette(
    dark = true,
    bg = Color.Black,
    surface = Color(0xFF121214),
    surfaceHigh = Color(0xFF24262D),
    tone = Color(0xFF1A1D27),
    line = Color(0xFF222226),
    text = Color(0xFFEDEDEF),
    text2 = Color(0xFFC4C4CA),
    // The site's #8B8B93 is 4.47:1 on surfaceHigh: one step lighter.
    muted = Color(0xFF8E8E96),
    faint = Color(0xFF4E4E55),
    accent = Color(0xFFC2C6D6),
    onAccent = Color(0xFF15171D),
    warn = Color(0xFFFFB547),
)

internal val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFF2F2F0),
    surface = Color.White,
    surfaceHigh = Color(0xFFE6E8EF),
    tone = Color(0xFFE6E8EF),
    line = Color(0xFFE2E2DD),
    text = Color(0xFF121214),
    text2 = Color(0xFF3C3C42),
    muted = Color(0xFF66666E),
    faint = Color(0xFFA5A5AC),
    // The site's --sig-deep: its --sig is too light on a light page.
    accent = Color(0xFF3D4766),
    onAccent = Color.White,
    warn = Color(0xFF9A5900),
)
