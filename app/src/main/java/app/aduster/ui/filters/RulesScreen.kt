package app.aduster.ui.filters

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aduster.filters.UserRules
import app.aduster.ui.BackBar
import app.aduster.ui.DomainCheck
import app.aduster.ui.MainViewModel
import app.aduster.ui.SiteCheck
import androidx.compose.ui.draw.clip
import app.aduster.ui.common.Card
import app.aduster.ui.common.Corners
import app.aduster.ui.common.PillButton
import app.aduster.ui.common.Gutter
import app.aduster.ui.common.InputField
import app.aduster.ui.common.Motion
import app.aduster.ui.common.SectionHeading
import app.aduster.ui.common.TextAction
import app.aduster.ui.theme.AdIcons
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Palette
import app.aduster.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val HELP = """||ads.example.com^     block, with subdomains
@@||shop.example.com^  always allow
|exact.example.com^|   this name only
/^ad[0-9]+\./          regular expression
example.com##.ad-box   hide (web pages)
||example.com/ads/*    URL path (web pages)
! comment"""

/**
 * An editor's text across a recreation, up to 100,000 characters: a bigger saved state would
 * crash the app when it goes to the background (Android's transaction limit); a bigger text is
 * read from the settings again (saved there when the app was left).
 */
internal val EditorTextSaver = Saver<String?, String>(save = { it?.takeIf { t -> t.length <= 100_000 } }, restore = { it })

@Composable
fun RulesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    // Null until the stored rules are read: editing must never start from placeholder settings,
    // or saving would replace the user's rules with an empty list.
    // Kept across a recreation (rotation, theme, the UI process restored).
    var edited by rememberSaveable(stateSaver = EditorTextSaver) { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (edited == null) edited = vm.storedSettings().userRules }
    val text = edited.orEmpty()
    var analysis by remember { mutableStateOf(UserRules.Analysis(emptyMap(), emptyList())) }
    var domain by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf<DomainCheck?>(null) }
    var site by remember { mutableStateOf<SiteCheck?>(null) }
    val dirty = edited != null && text.trimEnd() != s.userRules
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val measurer = rememberTextMeasurer()

    LaunchedEffect(text) {
        delay(300)
        analysis = withContext(Dispatchers.Default) { vm.analyzeRules(text) }
    }
    LaunchedEffect(domain) {
        delay(250)
        val d = UserRules.siteHost(domain) ?: domain.trim().lowercase()
        verdict = if (d.contains('.')) withContext(Dispatchers.IO) { vm.checkDomain(d) } else null
        site = if (d.contains('.')) withContext(Dispatchers.IO) { vm.checkSite(d) } else null
    }

    // Leaving the screen (back gesture, back button) keeps the edits: they are saved, not lost.
    // A rotation or theme change recreates the screen and must not save half-typed rules.
    val activity = ctx as? android.app.Activity
    val latestText by rememberUpdatedState(text)
    val latestDirty by rememberUpdatedState(dirty)
    DisposableEffect(Unit) {
        onDispose {
            if (latestDirty && activity?.isChangingConfigurations != true) {
                vm.saveUserRules(latestText.trimEnd())
                Toast.makeText(ctx, "Rules saved", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // Leaving the app (Home, switching to Chrome) saves too: the rules apply at once, and an
    // ended UI process loses nothing.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (latestDirty && activity?.isChangingConfigurations != true) vm.saveUserRules(latestText.trimEnd())
    }

    val errorLines = analysis.errors.map { it.first }.toSet()
    val highlight = remember(p, errorLines) { RuleHighlight(p, errorLines) }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("My rules", onBack) {
            TextAction(if (dirty) "Save" else "Saved", color = if (dirty) p.accent else p.muted) {
                if (dirty) vm.saveUserRules(text.trimEnd())
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // Every layer for one site: DNS (all apps) and how browsers' pages on it are
            // handled right now (never decrypted, bot-check bypass with its end, media, …).
            SectionHeading("Check a site")
            InputField(domain, { domain = it }, "ads.example.com", Modifier.fillMaxWidth().padding(horizontal = Gutter), icon = AdIcons.Search)
            AnimatedContent(
                targetState = verdict to site,
                transitionSpec = { fadeIn(tween(Motion.MEDIUM)) togetherWith fadeOut(tween(Motion.SHORT)) },
                label = "verdict",
            ) { (v, c) ->
                if (v != null) {
                    Card(Modifier.padding(top = 10.dp)) {
                        Text("DNS · " + if (v.blocked) "Blocked" else "Allowed", style = Type.bodyStrong, color = if (v.blocked) p.accent else p.text)
                        val why = listOfNotNull(v.rule, v.list?.let { "from $it" }).joinToString(" ")
                        Text(why.ifEmpty { "No rule matches this name" }, style = Type.mono, color = p.muted)
                        if (!v.running) Text("Protection is off; checked against the saved lists", style = Type.small, color = p.warn)
                        Spacer(Modifier.height(8.dp))
                        browserLines(c, s, domain, vm).forEach { (line, warn) ->
                            Text(line, style = Type.small, color = if (warn) p.warn else p.muted)
                        }
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                }
            }

            SectionHeading("Rules")
            val dns = analysis.layers.values.count { it == UserRules.Layer.DNS }
            val chrome = analysis.layers.values.count { it == UserRules.Layer.CHROME }
            if (dns + chrome > 0) {
                Text(
                    "$dns DNS · $chrome page",
                    style = Type.small,
                    color = p.muted,
                    modifier = Modifier.padding(horizontal = Gutter + 16.dp).padding(bottom = 8.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gutter)
                    .clip(RoundedCornerShape(Corners.outer))
                    .background(p.surface)
                    .padding(vertical = 16.dp)
                    // Line numbers at the top of each rule line (a long rule may wrap), drawn in
                    // one pass (one Text per line makes long rule lists slow to edit).
                    .drawBehind {
                        val l = layout ?: return@drawBehind
                        val len = l.layoutInput.text.length
                        val right = 26.dp.toPx()
                        var start = 0
                        text.split('\n').forEachIndexed { i, line ->
                            if (start <= len) {
                                val top = l.getLineTop(l.getLineForOffset(start.coerceAtMost(len)))
                                val n = i + 1
                                val m = measurer.measure("$n", Type.mono.copy(color = if (n in errorLines) p.warn else p.muted))
                                drawText(m, topLeft = Offset(right - m.size.width, top))
                            }
                            start += line.length + 1
                        }
                    },
            ) {
                Spacer(Modifier.width(36.dp))
                BasicTextField(
                    value = text,
                    onValueChange = { if (edited != null) edited = it },
                    textStyle = Type.mono.copy(color = p.text),
                    cursorBrush = SolidColor(p.accent),
                    visualTransformation = highlight,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 14.dp)
                        .heightIn(min = 240.dp),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text(HELP, style = Type.mono, color = p.muted)
                        inner()
                    },
                )
            }
            AnimatedVisibility(visible = analysis.errors.isNotEmpty(), enter = fadeIn(tween(Motion.MEDIUM)), exit = fadeOut(tween(Motion.SHORT))) {
                Column(Modifier.padding(horizontal = Gutter + 16.dp, vertical = 10.dp)) {
                    analysis.errors.take(6).forEach { (line, why) ->
                        Row {
                            Text("Line $line  ", style = Type.small, color = p.warn)
                            Text(why, style = Type.small, color = p.muted)
                        }
                    }
                    Text("These lines are ignored.", style = Type.small, color = p.muted)
                }
            }
            SectionHeading("Syntax")
            Card { Text(HELP, style = Type.mono, color = p.muted) }
            Text(
                "Your rules override every list.",
                style = Type.small,
                color = p.muted,
                modifier = Modifier.padding(horizontal = Gutter + 16.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
        AnimatedVisibility(visible = dirty, enter = fadeIn(tween(Motion.MEDIUM)), exit = fadeOut(tween(Motion.SHORT))) {
            PillButton("Save rules", Modifier.fillMaxWidth().padding(Gutter), filled = true) {
                vm.saveUserRules(text.trimEnd())
            }
        }
    }
}

/** The browser part of "Check a site" as lines: (text, worth a look). */
private fun browserLines(c: SiteCheck?, s: app.aduster.settings.Settings, input: String, vm: MainViewModel): List<Pair<String, Boolean>> {
    if (!app.aduster.https.HttpsConfig.available) return listOf("Pages · no page filtering in this build" to false)
    if (!s.httpsFiltering) return listOf("Pages · page filtering is off" to false)
    if (c == null) return listOf("Pages · page filtering is not running" to true)
    val host = UserRules.siteHost(input) ?: input.trim().lowercase()
    val out = ArrayList<Pair<String, Boolean>>()
    fun until(sec: Long?) = sec?.let { app.aduster.ui.hm(System.currentTimeMillis() + it * 1000) } ?: "?"
    if (!c.filteringActive) out += "Pages · paused" to true
    for (r in c.reasons) {
        out += when (r.kind) {
            "never_decrypt" -> (if (vm.userExcludes(host, s)) "Pages · skipped (your list)" else "Pages · skipped (sensitive site)") to false
            "user_allowed" -> "Pages · allowed by your rules" to false
            // Ended early from Diagnostics ("Filter again").
            "bot_check" -> "Pages · skipped until ${until(r.remainingS)} for a bot check" to true
            "media" -> "Pages · skipped until ${until(r.remainingS)} (video or audio)" to false
            "opened_anyway" -> "Pages · opened anyway, until ${until(r.remainingS)}" to false
            "network_block" -> "Pages · filtered (network blocks this site by name)" to false
            "passed_through" -> "Pages · last passed without filtering: ${r.detail.orEmpty()}" to true
            else -> "Pages · ${r.kind}" to false
        }
    }
    if (c.decrypted && c.reasons.none { it.kind == "user_allowed" }) out += "Pages · filtered" to false
    return out
}

/** Compiled once, not per line per keystroke. */
private val COSMETIC_SEP = Regex("#@?[?$]?#")
private val ANCHORS = Regex("""\|\||\||\^""")

/**
 * Colours rule syntax without changing the text: exceptions (`@@`) and cosmetic separators in
 * the accent, anchors and `$options` muted, comments faint, ignored lines struck in amber.
 */
private class RuleHighlight(private val p: Palette, private val errorLines: Set<Int>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = buildAnnotatedString {
            append(raw)
            var start = 0
            raw.split('\n').forEachIndexed { i, line ->
                style(this, line, start, i + 1)
                start += line.length + 1
            }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }

    private fun style(b: AnnotatedString.Builder, line: String, at: Int, number: Int) {
        fun span(from: Int, to: Int, color: Color, deco: TextDecoration? = null) {
            if (to > from) b.addStyle(SpanStyle(color = color, textDecoration = deco), at + from, at + to)
        }
        val t = line.trimStart()
        val lead = line.length - t.length
        if (t.isEmpty()) return
        if (number in errorLines) {
            span(lead, line.length, p.warn, TextDecoration.LineThrough)
            return
        }
        if (UserRules.isComment(t)) {
            span(lead, line.length, p.muted)
            return
        }
        val cosmetic = COSMETIC_SEP.find(line)
        if (cosmetic != null) {
            span(0, cosmetic.range.first, p.muted)
            span(cosmetic.range.first, cosmetic.range.last + 1, p.accent)
            return
        }
        if (t.startsWith("/") && t.length > 1) {
            span(lead, line.length, p.muted)
            return
        }
        if (t.startsWith("@@")) span(lead, lead + 2, p.accent)
        ANCHORS.findAll(line).forEach { m -> span(m.range.first, m.range.last + 1, p.muted) }
        val dollar = line.lastIndexOf('$')
        if (dollar > 0) span(dollar, line.length, p.muted)
    }
}
