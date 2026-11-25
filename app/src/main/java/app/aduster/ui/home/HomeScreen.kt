package app.aduster.ui.home

import android.content.Context
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.aduster.core.Upstreams
import app.aduster.https.HttpsConfig
import app.aduster.ui.DayStat
import app.aduster.ui.MainViewModel
import app.aduster.ui.Stats
import app.aduster.ui.Sub
import app.aduster.ui.ago
import app.aduster.ui.appName
import app.aduster.ui.common.AdDialog
import app.aduster.ui.common.AppIcon
import app.aduster.ui.common.Card
import app.aduster.ui.common.Chevron
import app.aduster.ui.common.Corners
import app.aduster.ui.common.Group
import app.aduster.ui.common.Gutter
import app.aduster.ui.common.Haptics
import app.aduster.ui.common.ListRow
import app.aduster.ui.common.Metric
import app.aduster.ui.common.Motion
import app.aduster.ui.common.PillButton
import app.aduster.ui.common.Protection
import app.aduster.ui.common.ProtectionMark
import app.aduster.ui.common.RollingNumber
import app.aduster.ui.common.RowIcon
import app.aduster.ui.common.SectionHeading
import app.aduster.ui.common.ValueText
import app.aduster.ui.common.groupItems
import app.aduster.ui.counted
import app.aduster.ui.filters.activeRuleCount
import app.aduster.ui.fmt
import app.aduster.ui.hm
import app.aduster.ui.percent
import app.aduster.ui.theme.AdIcons
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Type
import app.aduster.ui.theme.pastBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onStart: () -> Unit,
    open: (Sub) -> Unit,
    openSettings: () -> Unit,
    openFilters: () -> Unit,
    openLog: () -> Unit = {},
) {
    val p = LocalPalette.current
    val view = LocalView.current
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val known by vm.stats.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val meta by vm.filterMeta.collectAsStateWithLifecycle()
    val labels by vm.labels.collectAsStateWithLifecycle()
    val iconVersion by vm.iconVersion.collectAsStateWithLifecycle()
    var pauseSheet by remember { mutableStateOf(false) }
    var offDialog by remember { mutableStateOf(false) }
    // The top lists' share lines rise once, like the chart's bars, not each time Home is shown.
    // By row: each rises the first time it shows (rows further down compose only when scrolled to).

    // Until the core answers, the state comes from what the user last chose and the
    // buttons wait: no false "Off" and no tap on a "Turn on" that is not real. Before even
    // that is read (the first frames of a cold start), nothing is drawn.
    val settingsRead by vm.settingsRead.collectAsStateWithLifecycle()
    if (known == null && !settingsRead) return
    val stats = known ?: Stats.EMPTY.copy(running = s.enabled)
    val unknown = known == null
    val running = stats.running
    val paused = running && s.isPaused()
    val state = when {
        paused -> Protection.PAUSED
        running -> Protection.ON
        else -> Protection.OFF
    }

    // Re-checked when returning from system Settings or Page filtering.
    var localPrivateDnsStrict by remember { mutableStateOf(false) }
    var caInstalled by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            localPrivateDnsStrict = withContext(Dispatchers.IO) { vm.privateDnsStrict() }
            caInstalled = if (HttpsConfig.available) withContext(Dispatchers.IO) { vm.caInstalled() } else false
        }
    }

    // Timeline while off: explains an unexpected stop and tells whether protection has ever run.
    var timeline by remember { mutableStateOf<List<app.aduster.vpn.Timeline.Entry>?>(null) }
    LaunchedEffect(known) {
        timeline = if (unknown || running) null else withContext(Dispatchers.IO) { vm.timeline() }
    }
    val stopped = timeline?.let { stopNote(it) }?.takeIf { !running }
    val firstRun = !unknown && timeline != null && isFirstRun(running, stats, timeline!!)
    val privateDnsStrict = stats.privateDnsStrict || localPrivateDnsStrict
    val setupRow = if (unknown) null else homeSetupRow(
        privateDnsStrict = privateDnsStrict,
        httpsAvailable = HttpsConfig.available,
        httpsOn = s.httpsFiltering,
        caInstalled = caInstalled != false,
    )
    // Avoid showing Private DNS twice when the Setup row already surfaces it.
    val warnings = if (unknown) emptyList() else listOfNotNull(stopped?.let { Warning(it, Fix.DIAGNOSTICS) }) +
        homeWarnings(stats, s.httpsFiltering).filterNot { setupRow?.fix == SetupFix.PRIVATE_DNS && it.text.startsWith("Private DNS") }

    @Composable
    fun SetupRowCard(setupRow: SetupRow) {
        Group(Modifier.padding(top = 12.dp)) {
            row {
                ListRow(
                    title = setupRow.title,
                    detail = setupRow.detail,
                    onClick = {
                        when (setupRow.fix) {
                            SetupFix.PRIVATE_DNS -> openPrivateDnsSettings(ctx)
                            SetupFix.HTTPS -> open(Sub.HTTPS)
                        }
                    },
                    leading = { RowIcon(if (setupRow.warn) AdIcons.Warn else AdIcons.Filters, tint = if (setupRow.warn) p.warn else p.accent) },
                ) { Chevron() }
            }
        }
    }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp)) {
        item(key = "status") {
            // The status card: a large mark and state word, centred, the actions full width under
            // them, then any first-run note and warnings.
            Card(color = p.tone) {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProtectionMark(state, p.text, p.accent, p.warn, p.faint, size = 104.dp)
                    Spacer(Modifier.height(16.dp))
                    AnimatedContent(
                        targetState = state,
                        transitionSpec = { fadeIn(tween(Motion.MEDIUM, delayMillis = Motion.SHORT)) togetherWith fadeOut(tween(Motion.SHORT)) },
                        label = "state title",
                    ) { st ->
                        Text(
                            stateTitle(st),
                            // TalkBack says the new state when it changes, also when a tap elsewhere changed it.
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            style = Type.display,
                            color = when (st) {
                                Protection.ON -> p.text
                                Protection.PAUSED -> p.warn
                                Protection.OFF -> p.muted
                            },
                            textAlign = TextAlign.Center,
                        )
                    }
                    val detail = statusDetail(state, stats.httpsActive, s.pausedUntil, unknown)
                    if (detail != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(detail, style = Type.body, color = p.text2, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val wide = Modifier.weight(1f)
                        when {
                            unknown -> PillButton("…", wide, height = ActionHeight, enabled = false) {}
                            paused -> {
                                PillButton("Resume", wide, filled = true, color = p.warn, height = ActionHeight) { Haptics.confirm(view); vm.resume() }
                                OffButton { offDialog = true }
                            }
                            running -> {
                                PauseButton(wide) { pauseSheet = true }
                                OffButton { offDialog = true }
                            }
                            else -> PillButton("Turn on", wide, filled = true, height = ActionHeight) { Haptics.toggle(view, true); onStart() }
                        }
                    }
                }
                if (firstRun) {
                    Spacer(Modifier.height(16.dp))
                    FirstRunNote()
                }
                if (warnings.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        warnings.forEach { w ->
                            WarningRow(w.text) {
                                when (w.fix) {
                                    Fix.HTTPS -> open(Sub.HTTPS)
                                    Fix.DIAGNOSTICS -> open(Sub.DIAGNOSTICS)
                                    Fix.VPN_SETTINGS -> ctx.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    Fix.RELEASE -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(RELEASES))) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // A warning (strict Private DNS goes around the filter) stays in view under the status card:
        // after the numbers it was below the fold while the card said Protected.
        if (setupRow != null && setupRow.warn) item(key = "setup-row") { SetupRowCard(setupRow) }

        item(key = "numbers") {
            // Today's blocked count, its breakdown, and the 30-day chart above the fold.
            Card(Modifier.padding(top = 12.dp)) {
                val trend = remember(stats.history, stats.day, stats.blocked) {
                    weekTrend(app.aduster.ui.historySlots(stats.history, DayStat(stats.day, stats.queries, stats.blocked), 14))
                }
                Column(Modifier.clearAndSetSemantics { contentDescription = listOfNotNull("Blocked today: ${fmt(stats.blocked)}", trend).joinToString(", ") }) {
                    RollingNumber(fmt(stats.blocked), Type.display, p.text, value = stats.blocked, animate = !unknown)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Blocked today", style = Type.small, color = p.muted)
                        if (trend != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                trend,
                                style = Type.small,
                                color = p.accent,
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(p.surfaceHigh).padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                // How much of what apps asked for today was blocked: one bar and one line.
                val share by animateFloatAsState(stats.dnsRatio.toFloat().coerceIn(0f, 1f), tween(Motion.LONG * 2, easing = Motion.Emphasized), label = "share")
                Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                    val r = CornerRadius(size.height / 2, size.height / 2)
                    drawRoundRect(p.surfaceHigh, cornerRadius = r)
                    if (share > 0f) drawRoundRect(p.blocked, size = Size((size.width * share).coerceAtLeast(size.height), size.height), cornerRadius = r)
                }
                Spacer(Modifier.height(6.dp))
                Text(requestShare(stats, HttpsConfig.available), style = Type.small, color = p.muted)
                Spacer(Modifier.height(18.dp))
                WeekChart(stats)
            }
        }

        // A setup step that is not urgent (page filtering never set up) comes after the numbers.
        if (setupRow != null && !setupRow.warn) item(key = "setup-row") { SetupRowCard(setupRow) }

        if (stats.topBlocked.isNotEmpty()) {
            item(key = "top-label") { SectionHeading("Top blocked today") }
            groupItems(stats.topBlocked.take(8), key = { "b" + it.first }) { (name, n) ->
                CountRow(name, fmt(n)) {
                    vm.openLogFilter(query = name)
                    openLog()
                }
            }
        }

        val apps = stats.apps.filter { it.blocked > 0 }.sortedByDescending { it.blocked }.take(6)
        if (apps.isNotEmpty()) {
            item(key = "apps-label") { SectionHeading("Top apps") }
            groupItems(apps, key = { "a" + it.uid }) { a ->
                val icon = remember(iconVersion, a.uid) { vm.appIconForUid(a.uid) }
                CountRow(appName(labels, a.uid), "${fmt(a.blocked)} of ${fmt(a.queries)}", leading = { AppIcon(icon) }) {
                    vm.openLogFilter(appUid = a.uid)
                    openLog()
                }
            }
        }

        item(key = "setup") {
            SectionHeading("DNS and filters")
            Group {
                // The resolver is chosen in Settings; the rules come from the lists on Filters (the
                // count adds page rules while they are in use).
                row { ListRow("DNS server", onClick = openSettings) { ValueText(Upstreams.label(s.upstream, s.customUpstream)) } }
                val rules = activeRuleCount(meta, HttpsConfig.available, s.httpsFiltering, caInstalled != false)
                row { ListRow("Rules", rulesDetail(rulesAge(meta)), onClick = openFilters) { ValueText(fmt(rules)) } }
            }
        }
    }

    val refused by vm.vpnRefused.collectAsStateWithLifecycle()
    if (refused) {
        AdDialog(
            "VPN not allowed",
            onDismiss = { vm.vpnRefused.value = false },
            text = "The VPN permission was not given. If another app is the Always-on VPN, turn it off in VPN settings.",
            confirm = "VPN settings",
            dismiss = "Close",
            onConfirm = {
                vm.vpnRefused.value = false
                runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        )
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

    if (pauseSheet) {
        PauseSheet(
            onDismiss = { pauseSheet = false },
            onSelect = { minutes ->
                pauseSheet = false
                Haptics.confirm(view)
                vm.pause(minutes)
            },
        )
    }
}

private fun openPrivateDnsSettings(ctx: Context) {
    val wireless = Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(wireless) }.onFailure { runCatching { ctx.startActivity(fallback) } }
}

/** On the status card's tint: the card colour in Light, where the tint and the pill colour are one. */
@Composable
private fun onTone() = LocalPalette.current.let { if (it.dark) it.surfaceHigh else it.surface }

/** The status card's buttons: a little taller than the app's other buttons. */
private val ActionHeight = 52.dp

/** Pause, the wide button beside the power icon on the status card. */
@Composable
private fun PauseButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier
            .heightIn(min = ActionHeight)
            .clip(CircleShape)
            .background(onTone())
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Pause", style = Type.bodyStrong, color = p.text)
    }
}

/** Turn off, beside the pause or resume button: round, its name for TalkBack. */
@Composable
private fun OffButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    // Asks first: a mis-tap would leave protection off.
    Box(
        modifier
            .size(ActionHeight)
            .clip(CircleShape)
            .background(onTone())
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Turn off" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(AdIcons.Power, null, tint = p.text, modifier = Modifier.size(20.dp))
    }
}

/** In-app pause choices as plain tappable rows: one tap pauses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PauseSheet(onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface, dragHandle = null) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = 16.dp).navigationBarsPadding()) {
            Text(
                "Pause protection",
                style = Type.dialogTitle,
                color = p.text,
                modifier = Modifier.padding(horizontal = Gutter + 4.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            PAUSE_CHOICES.forEach { choice ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onSelect(choice.minutes) }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = Gutter + 4.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(choice.label, style = Type.body, color = p.text)
                }
            }
        }
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

/**
 * One row of a top list: name and count; a tap opens the Log filtered to it.
 */
@Composable
private fun CountRow(
    name: String,
    value: String,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Text(name, style = Type.body, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(value, style = Type.number, color = p.muted)
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Chevron()
            }
        }
    }
}

private val dayFormat = SimpleDateFormat("d MMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/** Weekday names under the week chart, in the phone's language ("Mon"); days are UTC day numbers. */
private val weekdayFormat = SimpleDateFormat("EEE", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }
private val weekdayLetter = SimpleDateFormat("EEEEE", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }

/**
 * The last seven days as labelled bars: past days in a softer accent, today in the blocked colour,
 * the count of the day shown above its bar (today's unless a bar is tapped), days without data an
 * empty track. The bars rise from the base when the chart first shows.
 */
@Composable
private fun WeekChart(stats: Stats) {
    val p = LocalPalette.current
    val view = LocalView.current
    val now = System.currentTimeMillis()
    val today = if (stats.day > 0) stats.day else Math.floorDiv(now + TimeZone.getDefault().getOffset(now), 86_400_000L)
    // No day recorded yet (protection never ran): seven empty tracks, "no data yet", not a Today of
    // 0; [today] only names the weekdays under them.
    val days = remember(stats.history, stats.day, stats.queries, stats.blocked) {
        app.aduster.ui.historySlots(stats.history, DayStat(stats.day, stats.queries, stats.blocked), WEEK)
    }
    var selected by remember { mutableStateOf<Int?>(null) }
    var grown by rememberSaveable { mutableStateOf(false) }
    val appear = remember { Animatable(if (grown) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!grown) {
            appear.animateTo(1f, tween(700, easing = Motion.Emphasized))
            grown = true
        }
    }
    val max = days.maxOf { it?.blocked ?: 0L }.coerceAtLeast(1L)
    val shown = selected ?: days.lastIndex
    // One letter per day where "Today" does not fit its bar: a large font, or a narrow phone
    // (360 dp at 130 % cut it to "Toda").
    val density = androidx.compose.ui.platform.LocalDensity.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val todayPx = remember(density.fontScale) { measurer.measure("Today", Type.small).size.width }
    var rowPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val columnPx = if (rowPx == 0) Int.MAX_VALUE else (rowPx - with(density) { 60.dp.roundToPx() }) / 7
    val narrow = density.fontScale > 1.3f || todayPx > columnPx
    Row(
        // One summary for TalkBack: the bars on their own read as bare day letters, too small to touch.
        Modifier
            .fillMaxWidth()
            .onSizeChanged { rowPx = it.width }
            .clearAndSetSemantics { contentDescription = weekDescription(days) },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        days.forEachIndexed { i, d ->
            val isToday = i == days.lastIndex
            Column(
                Modifier
                    .weight(1f)
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                        selected = if (selected == i || d == null) null else i
                        if (selected != null) Haptics.tick(view)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Centred over its bar. Only a count wider than its bar (a large font, a big
                // number) reaches past it: then the first bar's grows to the right and today's,
                // at the right edge, to the left, so neither leaves the card. (Moved by the font
                // size alone, a short count sat at the bar's edge.)
                val count = if (i == shown && d != null) fmt(d.blocked) else ""
                val wide = count.isNotEmpty() && measurer.measure(count, Type.number).size.width > columnPx
                val align = when {
                    wide && isToday -> Alignment.End
                    wide && i == 0 -> Alignment.Start
                    else -> Alignment.CenterHorizontally
                }
                Text(
                    count,
                    style = Type.number,
                    color = if (isToday) p.blocked else p.text,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth().wrapContentWidth(align, unbounded = true),
                )
                Spacer(Modifier.height(4.dp))
                Canvas(Modifier.fillMaxWidth().height(WeekChartHeight)) {
                    val r = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                    drawRoundRect(p.surfaceHigh.copy(alpha = 0.45f), cornerRadius = r)
                    if (d == null) return@Canvas
                    // Left to right, each bar a little after the one before.
                    val grow = ((appear.value - i / 7f * 0.4f) / 0.6f).coerceIn(0f, 1f)
                    val h = (d.blocked.toFloat() / max * size.height * grow).coerceAtLeast(6.dp.toPx())
                    val color = when {
                        isToday -> p.blocked
                        i == selected -> p.text
                        else -> p.pastBar()
                    }
                    drawRoundRect(color, Offset(0f, size.height - h), Size(size.width, h), r)
                }
                Spacer(Modifier.height(6.dp))
                val date = Date((today - (days.lastIndex - i)) * 86_400_000L)
                Text(
                    when {
                        // With a large font a day name does not fit its bar: one letter each.
                        narrow -> weekdayLetter.format(date)
                        isToday -> "Today"
                        else -> weekdayFormat.format(date)
                    },
                    style = Type.small,
                    color = if (isToday) p.text else p.muted,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

private const val WEEK = 7
private val WeekChartHeight = 112.dp

/** Screen-reader description for the week chart, counting only days with recorded stats. */
internal fun weekDescription(days: List<DayStat?>): String {
    val recorded = days.filterNotNull()
    if (recorded.isEmpty()) return "Blocked per day: no data yet"
    val total = recorded.sumOf { it.blocked }
    val today = days.last()?.blocked ?: 0L
    return "Blocked in the last ${counted(recorded.size, "day")}: ${fmt(total)}, today ${fmt(today)}"
}

/**
 * This week against the one before, for the badge beside "Blocked today" ("+18% vs last week"):
 * [days] are the last 14 days, oldest first. Null until the earlier week is a whole week (every
 * day recorded) with something blocked: a few hours of it would compare with nothing.
 */
internal fun weekTrend(days: List<DayStat?>): String? {
    if (days.size < 2 * WEEK) return null
    val before = days.take(WEEK).filterNotNull()
    val now = days.takeLast(WEEK).filterNotNull()
    val was = before.sumOf { it.blocked }
    if (before.size < WEEK || was == 0L || now.isEmpty()) return null
    val change = Math.round((now.sumOf { it.blocked } - was) * 100.0 / was)
    return when {
        change > 0 -> "+${fmt(change)}% vs last week"
        change < 0 -> "−${fmt(-change)}% vs last week"
        else -> "Same as last week"
    }
}

/** Shown on the status card until protection has run once: what Android's VPN prompt means. */
@Composable
private fun FirstRunNote() {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(onTone()).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // A phone outline in an accent disc.
        Canvas(Modifier.size(38.dp)) {
            drawCircle(p.accent)
            val w = 12.dp.toPx()
            val h = 19.dp.toPx()
            val stroke = 2.2.dp.toPx()
            val tl = Offset((size.width - w) / 2, (size.height - h) / 2)
            drawRoundRect(p.onAccent, tl, Size(w, h), CornerRadius(3.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            val y = tl.y + h - 4.dp.toPx()
            drawLine(p.onAccent, Offset(center.x - 2.dp.toPx(), y), Offset(center.x + 2.dp.toPx(), y), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        Column(Modifier.weight(1f)) {
            Text(FIRST_RUN_TITLE, style = Type.bodyStrong, color = p.text)
            Spacer(Modifier.height(2.dp))
            Text(FIRST_RUN_VPN_NOTE, style = Type.small, color = p.text2)
        }
    }
}

internal const val FIRST_RUN_TITLE = "Everything stays on this phone"

/** The first-run note's line on Android's VPN permission prompt. */
internal const val FIRST_RUN_VPN_NOTE =
    "Android will ask to allow a VPN connection. That is how Aduster filters, and nothing is sent anywhere."

/** One pause choice in the in-app pause sheet ([minutes] 0 means until you resume). */
internal data class PauseChoice(val label: String, val minutes: Int)

internal val PAUSE_CHOICES = listOf(
    PauseChoice("5 min", 5),
    PauseChoice("15 min", 15),
    PauseChoice("1 hour", 60),
    PauseChoice("Until you resume", 0),
)

/** State word on Home's status card. */
internal fun stateTitle(state: Protection): String = when (state) {
    Protection.PAUSED -> "Paused"
    Protection.ON -> "Protected"
    Protection.OFF -> "Off"
}

/** One line under the protection state: what is active, or when a pause ends. */
internal fun statusDetail(state: Protection, httpsActive: Boolean, pausedUntil: Long, unknown: Boolean = false): String? = when (state) {
    Protection.PAUSED -> if (pausedUntil == Long.MAX_VALUE) "Until you resume" else "Until ${hm(pausedUntil)}"
    Protection.ON -> if (unknown) null else if (httpsActive) "DNS and browser pages" else "DNS only"
    Protection.OFF -> "Not filtering"
}

/**
 * Protection has never run on this install: off, an empty timeline and no lookup ever counted
 * ([Stats.day] is today's date even then, so it says nothing about a first run).
 */
internal fun isFirstRun(running: Boolean, stats: Stats, timeline: List<app.aduster.vpn.Timeline.Entry>): Boolean =
    !running && timeline.isEmpty() && stats.queries == 0L && stats.history.none { it.queries > 0 }

/** Where the Setup row on Home leads. */
internal enum class SetupFix { PRIVATE_DNS, HTTPS }

internal data class SetupRow(val title: String, val detail: String, val fix: SetupFix, val warn: Boolean)

/**
 * Optional Setup row on Home: strict Private DNS (bypasses DNS filtering, takes priority) or
 * page filtering when this build includes it and it was never set up. Null when neither applies.
 */
internal fun homeSetupRow(
    privateDnsStrict: Boolean,
    httpsAvailable: Boolean,
    httpsOn: Boolean,
    caInstalled: Boolean,
): SetupRow? = when {
    privateDnsStrict -> SetupRow(
        title = "Setup",
        detail = "Private DNS is set to a strict hostname and bypasses filtering",
        fix = SetupFix.PRIVATE_DNS,
        warn = true,
    )
    httpsAvailable && !httpsOn && !caInstalled -> SetupRow(
        title = "Setup",
        detail = "Set up page filtering in browsers",
        fix = SetupFix.HTTPS,
        warn = false,
    )
    else -> null
}

/** Detail line under Home's Rules row. */
internal fun rulesDetail(ageMs: Long, now: Long = System.currentTimeMillis()): String =
    if (ageMs <= 0) "Not updated yet" else "Updated ${ago(ageMs, now)}"

/**
 * The line under today's share bar, in everyday words: the share of the apps' requests (DNS
 * lookups) blocked and, with the page engine, what was blocked inside browser pages besides. The
 * blocked requests and the page blocks add up to "Blocked today"; allowed page requests are never
 * counted, so no share is given for them.
 */
internal fun requestShare(stats: Stats, pages: Boolean): String {
    val base = "${percent(stats.dnsRatio)} of ${counted(stats.dnsQueries, "request")}"
    return if (pages && stats.blockedInBrowsers > 0) "$base, plus ${fmt(stats.blockedInBrowsers)} in browser pages" else base
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

/**
 * Why protection is off when the user did not turn it off (another VPN took over, the engine
 * failed, the process ended), from the protection timeline, newest last; null otherwise.
 */
internal fun stopNote(entries: List<app.aduster.vpn.Timeline.Entry>, now: Long = System.currentTimeMillis()): String? {
    val last = entries.lastOrNull() ?: return null
    if (last.event != app.aduster.vpn.Timeline.STOPPED) return null
    // Turned off in the app; entries from before codes existed by their text.
    if (last.code == "user_off" || (last.code == null && last.reason == "turned off in the app")) return null
    val why = last.reason?.takeIf { it.isNotBlank() } ?: return "Stopped ${ago(last.time, now)}"
    return "Stopped ${ago(last.time, now)}: $why"
}

/** When the rules were last checked for updates (as Filters says), else when they were built. */
internal fun rulesAge(meta: app.aduster.filters.FilterMeta): Long = if (meta.lastRun > 0) meta.lastRun else meta.compiledAt

/** Where a problem on Home is fixed. */
internal enum class Fix { HTTPS, DIAGNOSTICS, VPN_SETTINGS, RELEASE }

/** Where the official release is. */
internal const val RELEASES = "https://github.com/TheWrathRF/aduster/releases/latest"

internal data class Warning(val text: String, val fix: Fix)

/**
 * What stops filtering from really working, for the status card: page filtering that should run
 * but does not (with its real reason), a browser that refuses the certificate (DNS filtering
 * only, said by name), and system settings that go around the tunnel. Off, paused, page
 * filtering turned off and a build without the page engine are states, not problems.
 */
internal fun homeWarnings(stats: Stats, httpsOn: Boolean): List<Warning> = buildList {
    if (!stats.intact) add(Warning("This copy was modified: install the official release", Fix.RELEASE))
    if (httpsOn && stats.running && !stats.httpsActive && stats.httpsState != "unavailable") {
        add(Warning(httpsProblem(stats.httpsState), if (stats.httpsState == "start_failed") Fix.DIAGNOSTICS else Fix.HTTPS))
    }
    if (stats.running && stats.httpsActive) stats.untrustedBrowsers.forEach { add(Warning("$it: certificate not trusted", Fix.HTTPS)) }
    if (stats.privateDnsStrict) add(Warning("Private DNS bypasses protection", Fix.DIAGNOSTICS))
    if (stats.lockdown) add(Warning("Block connections without VPN is on", Fix.VPN_SETTINGS))
}
