package app.aduster.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.heading
import app.aduster.ui.common.AppIcon
import app.aduster.ui.hm
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.aduster.ui.LogItem
import app.aduster.ui.MainViewModel
import app.aduster.ui.QueryAction
import app.aduster.ui.appName
import app.aduster.ui.clock
import app.aduster.ui.common.ChipTabs
import app.aduster.ui.common.ChoiceDialog
import app.aduster.ui.common.GroupSegment
import app.aduster.ui.common.PillButton
import app.aduster.ui.common.Gutter
import app.aduster.ui.common.Haptics
import app.aduster.ui.common.InputField
import app.aduster.ui.common.Motion
import app.aduster.ui.common.ScreenTitle
import app.aduster.ui.common.TextAction
import app.aduster.ui.fmt
import app.aduster.ui.micros
import app.aduster.ui.theme.AdIcons
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.GoogleSansCode
import app.aduster.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val FILTERS = listOf("All", "Blocked", "Allowed", "Browsers")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogScreen(vm: MainViewModel) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val entries by vm.log.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val labels by vm.labels.collectAsStateWithLifecycle()
    val browsers by vm.browserUids.collectAsStateWithLifecycle()
    // Kept across tab switches and recreation.
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var app by rememberSaveable { mutableStateOf<Int?>(null) }
    var appDialog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<LogLine.Query?>(null) }
    val iconVersion by vm.iconVersion.collectAsStateWithLifecycle()

    // Poll only while this screen is shown (not while the app sits in the background), and not
    // at all while the log is off.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, s.logEnabled) {
        if (!s.logEnabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                vm.pollLog()
                delay(1000)
            }
        }
    }

    val shown by remember(entries, query, filter, labels, app, browsers) {
        derivedStateOf {
            val q = query.trim().lowercase()
            entries.filter { e ->
                // Pause and resume markers divide the list whatever the tab, unless searching.
                if (e.action.marker) return@filter q.isEmpty() && app == null
                val kind = when (filter) {
                    1 -> e.action.blocked
                    // Answered names only: failed lookups were never "allowed".
                    2 -> e.action.answered
                    3 -> e.uid in browsers || e.action == QueryAction.FILTERED
                    else -> true
                }
                kind && (app == null || e.uid == app) &&
                    (q.isEmpty() || e.name.contains(q) || appName(labels, e.uid).lowercase().contains(q))
            }
        }
    }
    // A ledger: minute headings, a name's record types in one row, no-network failures as one stretch.
    val rows by remember(shown) { derivedStateOf { ledgerOf(shown) } }
    val place by remember(rows) { derivedStateOf { runsOf(rows) } }

    // New entries: at the top of the list they slide in; further down the list stays put and a
    // pill counts them instead.
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val scope = rememberCoroutineScope()
    var pinnedTop by remember { mutableStateOf(true) }
    var seenSeq by remember { mutableLongStateOf(0L) }
    val topSeq = shown.firstOrNull()?.seq ?: 0L
    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress) }
            .collect { (i, o, scrolling) -> if (scrolling) pinnedTop = i == 0 && o < 24 }
    }
    LaunchedEffect(topSeq) {
        if (pinnedTop) {
            seenSeq = topSeq
            listState.scrollToItem(0)
        }
    }
    // A changed filter starts at the top; coming back to the tab keeps the place.
    var lastFilters by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query, filter, app) {
        val key = "$query|$filter|$app"
        if (lastFilters.isNotEmpty() && lastFilters != key) {
            pinnedTop = true
            seenSeq = topSeq
            listState.scrollToItem(0)
        }
        lastFilters = key
    }
    val fresh = if (pinnedTop) 0 else shown.takeWhile { it.seq > seenSeq }.size

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Log") {
            TextAction("Clear", color = p.muted) { scope.launch { vm.clearLog() } }
        }
        InputField(query, { query = it }, "Filter by domain or app", Modifier.fillMaxWidth().padding(horizontal = Gutter), icon = AdIcons.Search)
        Spacer(Modifier.height(4.dp))
        ChipTabs(FILTERS, filter, { filter = it }) {
            AppFilter(app?.let { appName(labels, it) }, onPick = { appDialog = true }, onClear = { app = null })
        }
        Spacer(Modifier.height(4.dp))

        if (!s.logEnabled) {
            Empty("The log is off", "Turn it on in Settings.")
        } else if (shown.isEmpty()) {
            Empty(if (entries.isEmpty()) "Nothing logged yet" else "Nothing matches")
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    rows.forEachIndexed { i, r ->
                        when (r) {
                            // The minute stays at the top while its rows scroll under it.
                            is LogLine.Minute -> stickyHeader(key = r.key, contentType = "minute") { MinuteHeading(r.time) }
                            is LogLine.Marker -> item(key = r.key, contentType = "marker") { MarkerRow(r.e, Modifier.animateItem(fadeInSpec = tween(Motion.MEDIUM), placementSpec = tween(Motion.LONG, easing = Motion.Emphasized), fadeOutSpec = tween(Motion.SHORT))) }
                            else -> item(key = r.key, contentType = "row") {
                                val mod = Modifier.animateItem(fadeInSpec = tween(Motion.MEDIUM), placementSpec = tween(Motion.LONG, easing = Motion.Emphasized), fadeOutSpec = tween(Motion.SHORT))
                                // The rows of one minute are one group of segments.
                                GroupSegment(place.index[i], place.count[i], mod) {
                                    when (r) {
                                        is LogLine.Query -> {
                                            val icon = remember(iconVersion, r.e.uid) { vm.appIconForUid(r.e.uid) }
                                            LogRow(
                                                r, appName(labels, r.e.uid), icon,
                                                onLongClick = {
                                                    Haptics.longPress(view)
                                                    copy(ctx, r.e.name)
                                                },
                                            ) { selected = r }
                                        }
                                        is LogLine.Offline -> OfflineRow(r)
                                        is LogLine.Marker, is LogLine.Minute -> {}
                                    }
                                }
                            }
                        }
                    }
                }
                // The plain (not ColumnScope) AnimatedVisibility: this sits in a Box.
                androidx.compose.animation.AnimatedVisibility(
                    visible = fresh > 0,
                    enter = fadeIn(tween(Motion.MEDIUM)) + slideInVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { -it },
                    exit = fadeOut(tween(Motion.SHORT)) + slideOutVertically(tween(Motion.SHORT)) { -it },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                ) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(p.accent)
                            .clickable(role = Role.Button) {
                                scope.launch {
                                    listState.animateScrollToItem(0)
                                    pinnedTop = true
                                    seenSeq = topSeq
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AdIcons.Up, null, tint = p.onAccent, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("${fmt(fresh)} new", style = Type.small, color = p.onAccent)
                    }
                }
            }
        }
    }

    if (appDialog) {
        // Apps seen in the log, busiest first.
        val apps = entries.filterNot { it.action.marker }.groupingBy { it.uid }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        ChoiceDialog(
            "Show one app",
            apps,
            app ?: Int.MIN_VALUE,
            label = { appName(labels, it) },
            detail = { uid -> "${fmt(entries.count { it.uid == uid })} entries" },
            onDismiss = { appDialog = false },
        ) {
            appDialog = false
            app = it
        }
    }

    selected?.let { r ->
        Details(r.e, r.types, appName(labels, r.e.uid), vm, onOnlyApp = { app = r.e.uid; selected = null }) { selected = null }
    }
}

@Composable
private fun AppFilter(name: String?, onPick: () -> Unit, onClear: () -> Unit) {
    val p = LocalPalette.current
    // A chip like the filters beside it; filled while one app is picked.
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .then(if (name != null) Modifier.background(p.surfaceHigh) else Modifier.border(1.dp, p.line, RoundedCornerShape(50)))
            .clickable(role = Role.Button, onClick = onPick)
            .heightIn(min = 36.dp)
            .padding(start = 16.dp, end = if (name != null) 8.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name ?: "All apps", style = Type.bodyStrong.copy(fontSize = Type.heading.fontSize), color = if (name != null) p.text else p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
        if (name != null) {
            // A 48 dp target around the small cross, as the field's clear button.
            Icon(
                AdIcons.Close, "Show all apps", tint = p.muted,
                modifier = Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClear).padding(2.dp).size(18.dp),
            )
        }
    }
}

/** A line of the log list: a query, a stretch of lookups that failed without a network, or a pause/resume marker. */
/**
 * A line of the Log: a minute's heading, a lookup (or a name's A, AAAA and HTTPS lookups as one
 * row), a stretch of lookups that failed without a network, or a pause/resume marker. Keys stay
 * the same as newer entries arrive above, so rows do not jump.
 */
internal sealed class LogLine(val key: Any) {
    class Minute(val time: Long, key: Any) : LogLine(key)
    /** [entries]: newest first; keyed by the oldest, which a later lookup of the name never changes. */
    class Query(val entries: List<LogItem>) : LogLine(entries.last().seq) {
        val e: LogItem get() = entries.first()
        val types: String get() = entries.sortedBy { it.qtype }.map { it.typeName }.distinct().joinToString(", ")
    }
    class Offline(val newest: LogItem, val oldest: LogItem, val count: Int) : LogLine(newest.seq)
    class Marker(val e: LogItem) : LogLine(e.seq)
}

/** Blocked, answered, or neither (failed): only lookups of one kind share a row. */
private fun kind(a: QueryAction) = if (a.blocked) 0 else if (a.answered) 1 else 2

/** One app's lookups of one name within 2 seconds and of one kind: its A, AAAA and HTTPS records. */
private fun sameLookup(a: LogItem, b: LogItem) =
    a.qtype != 0 && b.qtype != 0 && !b.offline && !b.action.marker && a.name == b.name && a.uid == b.uid &&
        kind(a.action) == kind(b.action) && kotlin.math.abs(a.time - b.time) <= 2_000

/**
 * The Log as a ledger, from [shown] (newest first): a heading for each minute (again after a
 * marker), one row per lookup with a name's record types together, and consecutive no-network
 * failures as one row.
 */
internal fun ledgerOf(shown: List<LogItem>): List<LogLine> {
    val out = ArrayList<LogLine>(shown.size + 16)
    // The rows of the minute being read (epoch minutes: zone offsets are whole minutes, so they are local minutes too).
    var section = ArrayList<LogLine>()
    var minute = Long.MIN_VALUE
    fun close() {
        if (section.isEmpty()) return
        // Keyed by the oldest row under it, which newer entries of its minute never change.
        out += LogLine.Minute(minute * 60_000L, "m${section.last().key}")
        out += section
        section = ArrayList()
    }
    var i = 0
    while (i < shown.size) {
        val e = shown[i]
        if (e.action.marker) {
            close()
            out += LogLine.Marker(e)
            minute = Long.MIN_VALUE
            i++
            continue
        }
        val m = Math.floorDiv(e.time, 60_000L)
        if (m != minute) {
            close()
            minute = m
        }
        var j = i
        if (e.offline) {
            while (j + 1 < shown.size && shown[j + 1].offline) j++
        } else {
            while (j + 1 < shown.size && sameLookup(e, shown[j + 1])) j++
        }
        section += if (e.offline) LogLine.Offline(e, shown[j], j - i + 1) else LogLine.Query(shown.subList(i, j + 1).toList())
        i = j + 1
    }
    close()
    return out
}

/** Each row's place in its run between two headings or markers: [index] within the run, and the run's [count]. */
private class Runs(val index: IntArray, val count: IntArray)

private fun runsOf(rows: List<LogLine>): Runs {
    val index = IntArray(rows.size)
    val count = IntArray(rows.size)
    var start = 0
    for (i in 0..rows.size) {
        if (i == rows.size || rows[i] is LogLine.Marker || rows[i] is LogLine.Minute) {
            for (j in start until i) {
                index[j] = j - start
                count[j] = i - start
            }
            start = i + 1
        }
    }
    return Runs(index, count)
}

@Composable
private fun OfflineRow(r: LogLine.Offline) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(start = 4.dp + MarkInset, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
        Text("No network · ${fmt(r.count)} lookup${if (r.count == 1) "" else "s"} failed", style = Type.bodyStrong, color = p.muted)
        Text(if (r.count == 1) clock(r.newest.time) else "${clock(r.oldest.time)} – ${clock(r.newest.time)}", style = Type.small, color = p.muted)
    }
}

@Composable
private fun MarkerRow(e: LogItem, modifier: Modifier) {
    val p = LocalPalette.current
    val paused = e.action == QueryAction.PAUSED
    val what = when (e.action) {
        QueryAction.PAUSED -> "Paused"
        QueryAction.RESTARTED -> "Protection restarted"
        else -> "Resumed"
    }
    Row(modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(if (paused) p.warn else p.line))
        Text(
            "$what · ${clock(e.time)}",
            style = Type.small, color = if (paused) p.warn else p.muted,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(if (paused) p.warn else p.line))
    }
}

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(Context.CLIPBOARD_SERVICE).let { it as ClipboardManager }.setPrimaryClip(ClipData.newPlainText("domain", text))
    Toast.makeText(ctx, "Copied $text", Toast.LENGTH_SHORT).show()
}

/** Where a row's text starts inside its card: the blocked mark, then the seconds column. */
private val MarkInset = 10.dp

/** A minute's heading on the page colour, lined up with the rows' seconds; a past day adds its date. */
@Composable
private fun MinuteHeading(time: Long) {
    val p = LocalPalette.current
    val today = Math.floorDiv(System.currentTimeMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()), 86_400_000L)
    val day = Math.floorDiv(time + java.util.TimeZone.getDefault().getOffset(time), 86_400_000L)
    Text(
        if (day == today) hm(time) else "${dayFormat.format(java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()))} ${hm(time)}",
        style = Type.mono,
        color = p.muted,
        modifier = Modifier
            .fillMaxWidth()
            .background(p.bg)
            .padding(start = Gutter + MarkInset + 4.dp, top = 14.dp, bottom = 6.dp)
            .semantics { heading() },
    )
}

private val dayFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.US)

/**
 * One lookup as a ledger line: its second of the minute, the name, and the app with the record
 * types. A blocked line has a short bar in the accent at its start and the name in full
 * contrast; an allowed one has no bar and its name in the quieter grey (TalkBack says which).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(r: LogLine.Query, app: String, icon: ImageBitmap?, onLongClick: () -> Unit, onClick: () -> Unit) {
    val p = LocalPalette.current
    val e = r.e
    val blocked = e.action.blocked
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Copy the domain")
            .semantics { stateDescription = e.action.label }
            .padding(start = 4.dp, end = 16.dp),
    ) {
        Box(
            Modifier
                .padding(vertical = 14.dp)
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(if (blocked) p.accent else Color.Transparent),
        )
        Spacer(Modifier.width(MarkInset - 2.dp))
        Text(":%02d".format(java.util.Locale.US, (e.time / 1000) % 60), style = Type.mono, color = p.muted, modifier = Modifier.padding(top = 13.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(e.name, style = Type.monoBody, color = if (blocked) p.text else p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(icon, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                val on = e.page?.takeIf { it != e.name }?.let { " · on $it" }.orEmpty()
                Text("$app · ${r.types}$on", style = Type.small, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Empty(title: String, detail: String? = null) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(Gutter).padding(top = 40.dp)) {
        Text(title, style = Type.bodyStrong, color = p.text)
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(detail, style = Type.small, color = p.muted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Details(e: LogItem, types: String, app: String, vm: MainViewModel, onOnlyApp: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val view = LocalView.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface, dragHandle = null) {
        Column(Modifier.padding(horizontal = Gutter).padding(top = 24.dp, bottom = 16.dp).navigationBarsPadding()) {
            Text(e.action.label, style = Type.heading, color = if (e.action.blocked) p.accent else p.muted)
            Spacer(Modifier.height(8.dp))
            Text(e.name, style = Type.title.copy(fontFamily = GoogleSansCode, fontSize = Type.title.fontSize * 0.8f), color = p.text)
            Spacer(Modifier.height(16.dp))
            Field("App", app)
            Field("Time", clock(e.time))
            Field("Type", types)
            // A request inside a page: where it was made and what it asked for, apart from the rule.
            e.page?.let { Field("On page", it) }
            e.path?.let { Field("Request", it, mono = true) }
            if (e.micros > 0) Field("Answered in", micros(e.micros))
            e.rule?.let { Field("Rule", it, mono = true) }
            e.list?.let { Field("From", it) }
            Spacer(Modifier.height(20.dp))
            val page = e.page
            if (e.action == QueryAction.FILTERED && page != null) {
                // Allowing a page request must not unfilter a whole site. The first action
                // allows this host only on pages of that site (a Chrome rule); the second is
                // the plain "everywhere" rule, explained.
                PillButton("Allow ${e.name} on $page", Modifier.fillMaxWidth(), filled = true) {
                    Haptics.confirm(view)
                    vm.addUserRule("@@||${e.name}^\$domain=$page")
                    Toast.makeText(ctx, "Added to My rules: @@||${e.name}^\$domain=$page", Toast.LENGTH_LONG).show()
                    onDismiss()
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Allow everywhere", Modifier.weight(1f)) {
                        Haptics.confirm(view)
                        vm.addUserRule("@@||${e.name}^")
                        Toast.makeText(ctx, "${e.name} allowed in every app; its own pages are no longer filtered", Toast.LENGTH_LONG).show()
                        onDismiss()
                    }
                    PillButton("Copy") {
                        copy(ctx, e.name)
                        onDismiss()
                    }
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (e.action.blocked) {
                    // One user rule, @@||name^: the domain is allowed everywhere, for every app
                    // and in every page (and pages on it are no longer filtered).
                    PillButton("Allow this domain", Modifier.weight(1f), filled = true) {
                        Haptics.confirm(view)
                        vm.addUserRule("@@||${e.name}^")
                        onDismiss()
                    }
                } else {
                    PillButton("Block this domain", Modifier.weight(1f), filled = true) {
                        Haptics.confirm(view)
                        vm.addUserRule("||${e.name}^")
                        onDismiss()
                    }
                }
                PillButton("Copy") {
                    copy(ctx, e.name)
                    onDismiss()
                }
            }
            if (e.uid >= 0) {
                Spacer(Modifier.height(4.dp))
                TextAction("Show only $app", color = p.muted, onClick = onOnlyApp)
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = Type.small, color = p.muted, modifier = Modifier.width(96.dp))
        Text(value, style = if (mono) Type.mono else Type.small, color = p.text, modifier = Modifier.weight(1f))
    }
}
