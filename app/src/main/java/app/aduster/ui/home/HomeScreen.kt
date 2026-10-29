package app.aduster.ui.home

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aduster.core.Upstreams
import app.aduster.https.HttpsConfig
import app.aduster.ui.MainViewModel
import app.aduster.ui.Stats
import app.aduster.ui.Sub
import app.aduster.ui.ago
import app.aduster.ui.appName
import app.aduster.ui.common.AdDialog
import app.aduster.ui.common.AppIcon
import app.aduster.ui.common.Card
import app.aduster.ui.common.Chevron
import app.aduster.ui.common.ChoiceDialog
import app.aduster.ui.common.Corners
import app.aduster.ui.common.Group
import app.aduster.ui.common.Haptics
import app.aduster.ui.common.ListRow
import app.aduster.ui.common.Metric
import app.aduster.ui.common.Motion
import app.aduster.ui.common.PillButton
import app.aduster.ui.common.Protection
import app.aduster.ui.common.ProtectionRing
import app.aduster.ui.common.RollingNumber
import app.aduster.ui.common.SectionHeading
import app.aduster.ui.common.ValueText
import app.aduster.ui.common.groupItems
import app.aduster.ui.fmt
import app.aduster.ui.hm
import app.aduster.ui.percent
import app.aduster.ui.theme.AdIcons
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun HomeScreen(vm: MainViewModel, onStart: () -> Unit, open: (Sub) -> Unit, openSettings: () -> Unit, openFilters: () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    val ctx = LocalContext.current
    val known by vm.stats.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val meta by vm.filterMeta.collectAsStateWithLifecycle()
    val labels by vm.labels.collectAsStateWithLifecycle()
    val iconVersion by vm.iconVersion.collectAsStateWithLifecycle()
    var pauseDialog by remember { mutableStateOf(false) }
    var offDialog by remember { mutableStateOf(false) }

    // Until the core answers, the state comes from what the user last chose and the
    // buttons wait: no false "Off" and no tap on a "Turn on" that is not real.
    val stats = known ?: Stats.EMPTY.copy(running = s.enabled)
    val unknown = known == null
    val running = stats.running
    val paused = running && s.isPaused()
    val state = when {
        paused -> Protection.PAUSED
        running -> Protection.ON
        else -> Protection.OFF
    }
    val warnings = if (unknown) emptyList() else homeWarnings(stats, s.httpsFiltering)

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp)) {
        item(key = "status") {
            // The status card: the state at a glance, its actions, and what needs fixing.
            Card(color = p.tone, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth()) {
                    ProtectionRing(state, p.accent, p.warn, p.faint, Modifier.align(Alignment.TopCenter).padding(top = 12.dp), size = 132.dp)
                    // Turn off is rare and asks first, so it sits in the corner and leaves the
                    // centre column and the full-width action to the state.
                    if (running && !unknown) {
                        OffButton(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp)) { offDialog = true }
                    }
                }
                Spacer(Modifier.height(14.dp))
                AnimatedContent(
                    targetState = state,
                    transitionSpec = { fadeIn(tween(Motion.MEDIUM, delayMillis = Motion.SHORT)) togetherWith fadeOut(tween(Motion.SHORT)) },
                    label = "state title",
                ) { st ->
                    Text(
                        when (st) {
                            Protection.PAUSED -> "Paused"
                            Protection.ON -> "Protected"
                            Protection.OFF -> "Off"
                        },
                        style = Type.display,
                        color = when (st) {
                            Protection.ON -> p.text
                            Protection.PAUSED -> p.warn
                            Protection.OFF -> p.muted
                        },
                    )
                }
                when {
                    paused -> Text(
                        if (s.pausedUntil == Long.MAX_VALUE) "Until you resume" else "Until ${hm(s.pausedUntil)}",
                        style = Type.body,
                        color = p.text2,
                    )
                    running && !unknown -> Row(
                        Modifier.semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RollingNumber(fmt(stats.blocked), Type.number.copy(fontSize = Type.body.fontSize), p.accent, value = stats.blocked)
                        Text(" blocked today", style = Type.body, color = p.text2)
                    }
                }
                Spacer(Modifier.height(20.dp))
                val wide = Modifier.fillMaxWidth()
                when {
                    unknown -> PillButton("…", wide, enabled = false, height = 56.dp) {}
                    paused -> PillButton("Resume", wide, filled = true, color = p.warn, height = 56.dp) { Haptics.confirm(view); vm.resume() }
                    // Asks how long, so the timed pause needs no hint.
                    running -> PillButton("Pause", wide, filled = true, height = 56.dp) { pauseDialog = true }
                    else -> PillButton("Turn on", wide, filled = true, height = 56.dp) { Haptics.toggle(view, true); onStart() }
                }
                if (warnings.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        warnings.forEach { w ->
                            WarningRow(w.text) {
                                when (w.fix) {
                                    Fix.HTTPS -> open(Sub.HTTPS)
                                    Fix.DIAGNOSTICS -> open(Sub.DIAGNOSTICS)
                                    Fix.VPN_SETTINGS -> ctx.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "numbers") {
            // Blocked is in the status card; what it is made of and the last 30 days here.
            Card(Modifier.padding(top = 12.dp)) {
                Row {
                    homeNumbers(stats, HttpsConfig.available).forEach { n ->
                        Metric(n.value, n.label, Modifier.weight(1f), raw = n.raw, animate = !unknown)
                    }
                }
                Spacer(Modifier.height(18.dp))
                History(stats)
            }
        }

        if (stats.topBlocked.isNotEmpty()) {
            item(key = "top-label") { SectionHeading("Top blocked today") }
            val max = stats.topBlocked.maxOf { it.second }.coerceAtLeast(1)
            groupItems(stats.topBlocked.take(8), key = { "b" + it.first }) { (name, n) ->
                CountRow(name, fmt(n), share = n.toFloat() / max)
            }
        }

        val apps = stats.apps.filter { it.blocked > 0 }.sortedByDescending { it.blocked }.take(6)
        if (apps.isNotEmpty()) {
            item(key = "apps-label") { SectionHeading("Top apps") }
            groupItems(apps, key = { "a" + it.uid }) { a ->
                val icon = remember(iconVersion, a.uid) { vm.appIconForUid(a.uid) }
                CountRow(
                    appName(labels, a.uid), "${fmt(a.blocked)} of ${fmt(a.queries)}",
                    share = if (a.queries > 0) a.blocked.toFloat() / a.queries else 0f,
                    leading = { AppIcon(icon) },
                )
            }
        }

        item(key = "setup") {
            SectionHeading("Setup")
            Group {
                // The resolver is chosen in Settings; the rules come from the lists on Filters (the
                // count adds page rules while they are in use).
                row { ListRow("DNS server", onClick = openSettings) { ValueText(Upstreams.label(s.upstream, s.customUpstream)) } }
                val rules = meta.compiledRules + if (s.httpsFiltering && HttpsConfig.available) meta.webRules else 0
                row { ListRow("Rules", onClick = openFilters) { ValueText("${fmt(rules)} · ${ago(meta.compiledAt)}") } }
            }
        }
    }

    if (offDialog) {
        AdDialog(
            "Turn off protection?",
            onDismiss = { offDialog = false },
            text = "Ads and trackers get through until you turn it back on.",
            confirm = "Turn off",
            destructive = true,
            onConfirm = {
                offDialog = false
                Haptics.toggle(view, false)
                vm.stop()
            },
        )
    }

    if (pauseDialog) {
        ChoiceDialog(
            title = "Pause protection",
            options = listOf(0, 5, 60),
            selected = -1,
            label = {
                when (it) {
                    0 -> "Until I resume"
                    60 -> "1 hour"
                    else -> "$it minutes"
                }
            },
            onDismiss = { pauseDialog = false },
            onSelect = {
                pauseDialog = false
                Haptics.confirm(view)
                vm.pause(it)
            },
        )
    }
}

/** On the status card's tint: the card colour in Light, where the tint and the pill colour are one. */
@Composable
private fun onTone() = LocalPalette.current.let { if (it.dark) it.surfaceHigh else it.surface }

/** Turn off, beside the main button: round, its name for TalkBack. */
@Composable
private fun OffButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    // Asks first: a mis-tap would leave protection off.
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(onTone())
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Turn off" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(AdIcons.Power, null, tint = p.text, modifier = Modifier.size(20.dp))
    }
}

/** A problem in the status card, one tap from where it is fixed. */
@Composable
private fun WarningRow(text: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.outer))
            .background(onTone())
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AdIcons.Warn, null, tint = p.warn, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = Type.body, color = p.text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Chevron()
    }
}

/** One row of a top list: name, count and a thin line showing its share. */
@Composable
private fun CountRow(name: String, value: String, share: Float, leading: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val w by animateFloatAsState(if (shown) share.coerceIn(0f, 1f) else 0f, tween(Motion.LONG * 2, easing = Motion.Emphasized), label = "share")
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) {
        Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Text(name, style = Type.body, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(value, style = Type.number, color = p.muted)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
            drawRect(p.line)
            drawRect(p.faint, size = Size(size.width * w, size.height))
        }
    }
}

private val ChartGap = 4.dp

private val dayFormat = SimpleDateFormat("d MMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/**
 * Blocked per day, from the first day with data, at most 30: each day a rounded track with its
 * bar inside, past days in a softer accent and today in the full one, a day with nothing blocked
 * a dot. The days keep a 30-day width and end at the right edge, so today is always in the same
 * place. The bars rise from the base when the chart first shows; dragging a finger across it
 * reads out that day.
 */
@Composable
private fun History(stats: Stats) {
    val p = LocalPalette.current
    val view = LocalView.current
    val slots = 30
    // Calendar days: a day without data is an empty slot, not skipped.
    val days = remember(stats.history, stats.day, stats.queries, stats.blocked) {
        app.aduster.ui.historySlots(stats.history, app.aduster.ui.DayStat(stats.day, stats.queries, stats.blocked), slots)
    }
    // Slots left empty before the first day with data.
    val lead = slots - days.size
    val values = days.map { it?.blocked }
    val total = values.sumOf { it ?: 0L }
    var selected by remember { mutableStateOf<Int?>(null) }
    var grown by rememberSaveable { mutableStateOf(false) }
    val appear = remember { Animatable(if (grown) 1f else 0f) }
    val hasData = stats.day > 0
    LaunchedEffect(hasData) {
        if (hasData && !grown) {
            appear.animateTo(1f, tween(700, easing = Motion.Emphasized))
            grown = true
        }
    }
    val span = if (days.size == 1) "Today" else "${days.size} days"
    val description = "Blocked per day, ${days.size} days: ${fmt(total)} in all, today ${fmt(stats.blocked)}"
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .semantics { contentDescription = description }
                .pointerInput(days.size) {
                    val gap = ChartGap.toPx()
                    fun pick(x: Float) {
                        val w = (size.width - gap * (slots - 1)) / slots
                        val i = (x / (w + gap)).toInt().coerceIn(0, slots - 1) - lead
                        val v = i.takeIf { it in days.indices && days[it] != null }
                        if (v != selected) {
                            selected = v
                            if (v != null) Haptics.tick(view)
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pick(down.position.x)
                        var last = down.position
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            val d = c.position - last
                            last = c.position
                            // Horizontal drags belong to the chart; vertical ones scroll the page.
                            if (kotlin.math.abs(d.x) >= kotlin.math.abs(d.y)) c.consume()
                            pick(c.position.x)
                        }
                        selected = null
                    }
                },
        ) {
            val gap = ChartGap.toPx()
            val w = (size.width - gap * (slots - 1)) / slots
            val round = CornerRadius(w / 2, w / 2)
            val max = (values.maxOf { it ?: 0L }).coerceAtLeast(1L)
            val t = appear.value
            for (i in days.indices) {
                val x = (lead + i) * (w + gap)
                drawRoundRect(p.surfaceHigh, Offset(x, 0f), Size(w, size.height), round)
                val v = values[i] ?: continue
                // Left to right, each bar a little after the one before.
                val start = i.toFloat() / slots * 0.4f
                val grow = ((t - start) / 0.6f).coerceIn(0f, 1f)
                // Never shorter than its width: a day with nothing blocked is a dot.
                val h = (v.toFloat() / max * size.height * grow).coerceAtLeast(w)
                val color = when {
                    selected == i -> p.text
                    i == days.lastIndex -> p.accent
                    selected != null -> p.accent.copy(alpha = 0.25f)
                    else -> p.accent.copy(alpha = 0.5f)
                }
                drawRoundRect(color, Offset(x, size.height - h), Size(w, h), round)
            }
        }
        Spacer(Modifier.height(8.dp))
        // The first day's date under its bar, once there is room beside "Today" for it.
        if (hasData) {
            Row(Modifier.fillMaxWidth()) {
                if (days.size >= 8) {
                    if (lead > 0) Spacer(Modifier.fillMaxWidth(lead.toFloat() / slots))
                    Text(dayFormat.format(Date((stats.day - days.size + 1) * 86_400_000L)), style = Type.small, color = p.muted, modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text("Today", style = Type.small, color = p.muted)
            }
        }
        Spacer(Modifier.height(10.dp))
        val sel = selected?.let { days.getOrNull(it) }
        AnimatedContent(
            targetState = sel,
            transitionSpec = { fadeIn(tween(Motion.SHORT)) togetherWith fadeOut(tween(Motion.SHORT)) },
            label = "chart label",
        ) { d ->
            if (d == null) {
                Text("$span · ${fmt(total)} blocked", style = Type.mono, color = p.muted)
            } else {
                val date = if (d.day == stats.day) "Today" else dayFormat.format(Date(d.day * 86_400_000L))
                Text("$date · ${fmt(d.blocked)} blocked", style = Type.mono, color = p.text)
            }
        }
    }
}

internal data class HomeNumber(val value: String, val label: String, val raw: Long)

/**
 * Today's numbers under the status card, each of one kind of count: the lookups, the share of
 * them blocked, and (with the page engine) what was blocked inside browsers. The last two add up
 * to the card's "blocked today"; allowed page requests are never counted, so no share is given
 * for them.
 */
internal fun homeNumbers(stats: Stats, pages: Boolean): List<HomeNumber> = buildList {
    add(HomeNumber(fmt(stats.dnsQueries), "DNS lookups", stats.dnsQueries))
    add(HomeNumber(percent(stats.dnsRatio), "DNS lookups blocked", (stats.dnsRatio * 1000).toLong()))
    if (pages) {
        val inBrowsers = (stats.blocked - stats.dnsBlocked).coerceAtLeast(0)
        add(HomeNumber(fmt(inBrowsers), "Blocked in browsers", inBrowsers))
    }
}

/** Why browser pages are not filtered, from `Stats.httpsState`, as the status card says it. */
internal fun httpsProblem(state: String): String = when (state) {
    "unavailable" -> "No page filtering in this build"
    "no_browser" -> "No browser found"
    "ca_missing" -> "Certificate not installed"
    "ca_untrusted" -> "Certificate not trusted"
    "ca_key_unreadable" -> "Certificate unreadable"
    "ca_mismatch" -> "Certificate does not match"
    "start_failed" -> "Page filtering failed"
    else -> "Pages not filtered"
}

/** Where a problem on Home is fixed. */
internal enum class Fix { HTTPS, DIAGNOSTICS, VPN_SETTINGS }

internal data class Warning(val text: String, val fix: Fix)

/**
 * What stops filtering from really working, for the status card: page filtering that should run
 * but does not (with its real reason), a browser that refuses the certificate (DNS filtering
 * only, said by name), and system settings that go around the tunnel. Off, paused, page
 * filtering turned off and a build without the page engine are states, not problems.
 */
internal fun homeWarnings(stats: Stats, httpsOn: Boolean): List<Warning> = buildList {
    if (httpsOn && stats.running && !stats.httpsActive && stats.httpsState != "unavailable") {
        add(Warning(httpsProblem(stats.httpsState), if (stats.httpsState == "start_failed") Fix.DIAGNOSTICS else Fix.HTTPS))
    }
    if (stats.running && stats.httpsActive) stats.untrustedBrowsers.forEach { add(Warning("$it: certificate not trusted", Fix.HTTPS)) }
    if (stats.privateDnsStrict) add(Warning("Private DNS bypasses protection", Fix.DIAGNOSTICS))
    if (stats.lockdown) add(Warning("Block connections without VPN is on", Fix.VPN_SETTINGS))
}
