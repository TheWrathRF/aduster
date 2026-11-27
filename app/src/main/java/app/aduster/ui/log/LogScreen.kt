package app.aduster.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Process
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import app.aduster.ui.LogItem
import app.aduster.ui.MainViewModel
import app.aduster.ui.QueryAction
import app.aduster.ui.SiteCheck
import app.aduster.ui.appName
import app.aduster.ui.clock
import app.aduster.ui.common.AppIcon
import app.aduster.ui.common.ChoiceDialog
import app.aduster.ui.common.ClearLogDialog
import app.aduster.ui.common.Corners
import app.aduster.ui.common.FilterChip
import app.aduster.ui.common.Gutter
import app.aduster.ui.common.Haptics
import app.aduster.ui.common.InputField
import app.aduster.ui.common.Motion
import app.aduster.ui.common.PillButton
import app.aduster.ui.common.ScreenTitle
import app.aduster.ui.common.Segmented
import app.aduster.ui.common.TextAction
import app.aduster.ui.counted
import app.aduster.ui.fmt
import app.aduster.ui.micros
import app.aduster.ui.theme.AdIcons
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Type
import app.aduster.ui.theme.blockedTint
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val KINDS = listOf("All", "Blocked", "Allowed")

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
    // A browser installed while the app ran belongs in Browsers and Pages from the next look.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshBrowsers() }
    // Kept across tab switches and recreation.
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var inBrowsers by rememberSaveable { mutableStateOf(false) }
    var pages by rememberSaveable { mutableStateOf(false) }
    var app by rememberSaveable { mutableStateOf<Int?>(null) }
    var appDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<LogLine.Query?>(null) }
    val iconVersion by vm.iconVersion.collectAsStateWithLifecycle()
    val pendingFilter by vm.pendingLogFilter.collectAsStateWithLifecycle()
    LaunchedEffect(pendingFilter) {
        val req = pendingFilter ?: return@LaunchedEffect
        query = req.query
        app = req.appUid
        kind = 0
        inBrowsers = false
        pages = false
        vm.consumeLogFilter()
    }

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

    // The ledger is rebuilt at every poll over up to 10,000 entries: off the main thread. Null
    // until the first one is ready (no "Nothing logged yet" flash).
    var rows by remember { mutableStateOf<List<LogLine>?>(null) }
    val logRead by vm.logRead.collectAsStateWithLifecycle()
    LaunchedEffect(entries, query, kind, inBrowsers, pages, app, labels, browsers, logRead) {
        // Not read from the core yet: an empty log is not "Nothing logged yet".
        if (entries.isEmpty() && !logRead) return@LaunchedEffect
        rows = withContext(Dispatchers.Default) {
            ledgerOf(entries.filter { logMatches(it, kind, inBrowsers, pages, app, query, browsers) { uid -> appName(labels, uid) } })
        }
    }
    val lines = rows.orEmpty()
    val listName: (String) -> String = remember(s) { { id -> listLabel(id, s) } }

    // New rows: at the top of the list they slide in; further down the list stays put and the
    // line above it counts them instead.
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val scope = rememberCoroutineScope()
    if (clearDialog) ClearLogDialog(vm.viewModelScope, { vm.clearLog() }) { clearDialog = false }
    val undoBar = remember { androidx.compose.material3.SnackbarHostState() }
    // Saved with the list's place: coming back to the tab (or turning the phone) found it pinned
    // again and jumped to the top.
    var pinnedTop by rememberSaveable { mutableStateOf(listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 24) }
    var seenSeq by rememberSaveable { mutableLongStateOf(0L) }
    val topSeq = topSeqOf(lines)
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
    LaunchedEffect(query, kind, inBrowsers, pages, app) {
        val key = "$query|$kind|$inBrowsers|$pages|$app"
        if (lastFilters.isNotEmpty() && lastFilters != key) {
            pinnedTop = true
            seenSeq = topSeq
            listState.scrollToItem(0)
        }
        lastFilters = key
    }
    val fresh = if (pinnedTop) 0 else freshRows(lines, seenSeq)
    val shownCount = remember(lines) { lines.count { it is LogLine.Query || it is LogLine.Offline } }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Log") {
            TextAction("Clear", color = p.muted) { clearDialog = true }
        }
        InputField(query, { query = it }, "Filter by domain or app", Modifier.fillMaxWidth().padding(horizontal = Gutter), icon = AdIcons.Search)
        Spacer(Modifier.height(4.dp))
        // One of All / Blocked / Allowed with the app beside it; under them Browsers and Pages,
        // which combine with both, and the rows left or the new ones above while reading further
        // down (a line of its own: nothing covers the first row).
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Gutter),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Segmented(KINDS, kind, { kind = it })
            AppFilter(app?.let { appName(labels, it) }, onPick = { appDialog = true }, onClear = { app = null })
        }
        Row(Modifier.fillMaxWidth().padding(start = Gutter, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip("Browsers", inBrowsers) { inBrowsers = it }
            FilterChip("Pages", pages) { pages = it }
            Spacer(Modifier.weight(1f))
            if (fresh > 0) {
                Row(
                    Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(Corners.outer))
                        .clickable(role = Role.Button) {
                            scope.launch {
                                listState.animateScrollToItem(0)
                                pinnedTop = true
                                seenSeq = topSeq
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AdIcons.Up, null, tint = p.accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${fmt(fresh)} new", style = Type.bodyStrong.copy(fontSize = Type.small.fontSize), color = p.accent, maxLines = 1)
                }
            } else if (s.logEnabled && rows != null && entries.isNotEmpty()) {
                Text(counted(shownCount, "row", "rows"), style = Type.small, color = p.muted, maxLines = 1, modifier = Modifier.padding(end = 12.dp))
            }
        }

        if (!s.logEnabled) {
            Empty("The log is off", "Turn it on in Settings.")
        } else if (rows == null) {
            Box(Modifier.fillMaxSize())
        } else if (lines.none { it !is LogLine.Day }) {
            Empty(if (entries.isEmpty()) "Nothing logged yet" else "Nothing matches")
        } else {
            val today = localDay(System.currentTimeMillis())
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                for (r in lines) {
                    when (r) {
                        // The day stays at the top while its rows scroll under it.
                        is LogLine.Day -> stickyHeader(key = r.key, contentType = "day") { DayHeading(dayLabel(r.day, today)) }
                        is LogLine.Marker -> item(key = r.key, contentType = "marker") { MarkerRow(r.e, Modifier.animateItem(fadeInSpec = tween(Motion.MEDIUM), placementSpec = tween(Motion.LONG, easing = Motion.Emphasized), fadeOutSpec = tween(Motion.SHORT))) }
                        is LogLine.Offline -> item(key = r.key, contentType = "offline") { OfflineRow(r, Modifier.animateItem(fadeInSpec = tween(Motion.MEDIUM), placementSpec = tween(Motion.LONG, easing = Motion.Emphasized), fadeOutSpec = tween(Motion.SHORT))) }
                        is LogLine.Query -> item(key = r.key, contentType = "row") {
                            val mod = Modifier.animateItem(fadeInSpec = tween(Motion.MEDIUM), placementSpec = tween(Motion.LONG, easing = Motion.Emphasized), fadeOutSpec = tween(Motion.SHORT))
                            val icon = remember(iconVersion, r.e.uid) { vm.appIconForUid(r.e.uid) }
                            LogRow(
                                r, appName(labels, r.e.uid), icon, mod, listName,
                                onLongClick = {
                                    Haptics.longPress(view)
                                    copy(ctx, r.e.name)
                                },
                            ) { selected = r }
                        }
                    }
                }
            }
        }
    }
    // Under an action from the Log: what changed, with Undo.
    androidx.compose.material3.SnackbarHost(undoBar, Modifier.align(Alignment.BottomCenter).padding(horizontal = Gutter, vertical = 12.dp)) { d ->
        androidx.compose.material3.Snackbar(d, containerColor = p.surfaceHigh, contentColor = p.text, actionColor = p.accent, shape = RoundedCornerShape(Corners.outer))
    }
    }

    if (appDialog) {
        // Apps seen in the log, busiest first.
        // Counted once per log, not once per app row on every redraw (up to 10,000 entries each).
        val counts = remember(entries) { entries.filterNot { it.action.marker }.groupingBy { it.uid }.eachCount() }
        val apps = counts.entries.sortedByDescending { it.value }.map { it.key }
        ChoiceDialog(
            "Show one app",
            apps,
            app ?: Int.MIN_VALUE,
            label = { appName(labels, it) },
            detail = { uid -> counted(counts[uid] ?: 0, "entry", "entries") },
            onDismiss = { appDialog = false },
        ) {
            appDialog = false
            app = it
        }
    }

    selected?.let { r ->
        Details(
            r, appName(labels, r.e.uid), vm,
            onOnlyApp = { app = r.e.uid; selected = null },
            onChanged = { text, saved, undo ->
                // The actions act at once; the bar says what changed, once saved, and takes it back.
                scope.launch {
                    if (!saved.await()) return@launch
                    undoBar.currentSnackbarData?.dismiss()
                    val r2 = undoBar.showSnackbar(text, actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long)
                    if (r2 == androidx.compose.material3.SnackbarResult.ActionPerformed) undo()
                }
            },
        ) { selected = null }
    }
}

@Composable
private fun AppFilter(name: String?, onPick: () -> Unit, onClear: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(Corners.outer)
    // A chip like the others: "App ▾" opens the list of apps; filled with the app's name while one is picked.
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .then(if (name != null) Modifier.background(p.surfaceHigh) else Modifier.border(1.dp, p.line, shape))
            .clickable(role = Role.Button, onClickLabel = "Show one app", onClick = onPick)
            .heightIn(min = 36.dp)
            .padding(start = 14.dp, end = if (name != null) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name ?: "App", style = Type.bodyStrong.copy(fontSize = Type.heading.fontSize), color = if (name != null) p.text else p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        if (name != null) {
            // A 48 dp target around the small cross, as the field's clear button.
            Icon(
                AdIcons.Close, "Show all apps", tint = p.muted,
                modifier = Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClear).padding(2.dp).size(18.dp),
            )
        } else {
            Icon(AdIcons.Chevron, null, tint = p.muted, modifier = Modifier.padding(start = 4.dp).size(16.dp).rotate(90f))
        }
    }
}

/**
 * A line of the Log: a day's divider, a row of lookups (a name's A, AAAA and HTTPS records, its
 * repeats within a minute and the browser connection that went with it), a stretch of lookups
 * that failed without a network, or a pause/resume marker. Keys stay the same as newer entries
 * arrive, so rows do not jump.
 */
internal sealed class LogLine(val key: Any) {
    /** [day]: local epoch day. */
    class Day(val day: Long, key: Any) : LogLine(key)

    /** [entries]: newest first; keyed by the oldest, which a later entry never changes. */
    class Query(val entries: List<LogItem>) : LogLine(entries.last().seq) {
        /** The row's lookup or page request: its connections only fold into it. */
        val e: LogItem = entries.firstOrNull { !it.connection } ?: entries.first()
        val newest: LogItem get() = entries.first()
        val oldest: LogItem get() = entries.last()
        /** Record types, the connection last: "A, AAAA, Connection". */
        val types: String by lazy {
            entries.sortedWith(compareBy({ it.connection }, { it.qtype })).map { it.typeName }.distinct().joinToString(", ")
        }
        /** How many times it happened: a lookup of three record types counts once. */
        val count: Int by lazy {
            entries.filter { !it.connection }.ifEmpty { entries }.groupingBy { it.qtype }.eachCount().values.max()
        }
    }

    /** Keyed by its oldest failure, which later ones never change. */
    class Offline(val newest: LogItem, val oldest: LogItem, val count: Int) : LogLine(oldest.seq)
    class Marker(val e: LogItem) : LogLine(e.seq)
}

/** A browser's connection to a host, as the page filter records it (not a lookup, not a request blocked in a page). */
internal val LogItem.connection: Boolean get() = qtype == 0 && action != QueryAction.FILTERED

/**
 * Whether [e] is shown: [kind] 0 all, 1 blocked, 2 allowed (answered: a failed lookup was never
 * allowed); [inBrowsers] only browsers' lookups and what the page filter saw; [pages] only what
 * the page filter saw (connections and requests in pages); [app] one app; [query] part of the
 * domain or the app's name. Pause and resume markers divide the list whatever the view, but not
 * a search or one app's rows.
 */
internal fun logMatches(e: LogItem, kind: Int, inBrowsers: Boolean, pages: Boolean, app: Int?, query: String, browsers: Set<Int>, appName: (Int) -> String): Boolean {
    val q = query.trim().lowercase()
    if (e.action.marker) return q.isEmpty() && app == null
    if (kind == 1 && !e.action.blocked) return false
    if (kind == 2 && !e.action.answered) return false
    // Other apps' connections go through the proxy too (qtype 0): only browsers' rows count.
    val browser = e.uid in browsers || e.action == QueryAction.FILTERED
    if (pages && (e.qtype != 0 || !browser)) return false
    if (inBrowsers && !browser) return false
    if (app != null && e.uid != app) return false
    return q.isEmpty() || e.name.contains(q) || appName(e.uid).lowercase().contains(q)
}

/** Blocked, answered, or neither (failed): only entries of one kind share a row; an AAAA left out by IPv4-only answers keeps a row of its own. */
private fun kind(a: QueryAction) = if (a.blocked) 0 else if (a == QueryAction.NO_IPV6) 3 else if (a.answered) 1 else 2

/** One row's entries: the same name, app and outcome; for a page request, the same page too. */
private fun rowKey(e: LogItem): String = when {
    e.action == QueryAction.FILTERED -> "p|${e.name}|${e.uid}|${e.page}"
    e.connection -> "c|${e.name}|${e.uid}|${kind(e.action)}"
    else -> "d|${e.name}|${e.uid}|${kind(e.action)}"
}

/**
 * Whether connection [c] belongs with the row of [row]: an allowed connection with the name's
 * answered lookup or a page request on it, a refused one with the name's blocked lookup. A
 * connection with another outcome than its lookup (answered, then refused) is news: a row of its own.
 */
private fun fits(row: LogItem, c: LogItem): Boolean = when {
    c.action.blocked -> row.action != QueryAction.FILTERED && !row.connection && row.action.blocked
    c.action.answered -> row.action == QueryAction.FILTERED || (!row.connection && kind(row.action) == 1)
    else -> false
}

/** A row's repeats are counted from its first entry for a minute; its connections within a few seconds of it. */
private const val REPEAT_MS = 60_000L
private const val CONNECTION_MS = 5_000L

private class Group(first: LogItem, var key: String) {
    val items = arrayListOf(first)
    val start = first.time
    var main: LogItem? = first.takeIf { !it.connection }
    val last: LogItem get() = items.last()

    fun add(e: LogItem) {
        items += e
        if (!e.connection) main = e
    }
}

/**
 * The Log as a ledger, from [shown] (newest first): rows as [LogLine.Query] says, newest activity
 * first, a day's divider wherever the day changes (again after a marker). Built from the oldest
 * entry on, so a row's members and its key never change as newer entries arrive.
 */
internal fun ledgerOf(shown: List<LogItem>): List<LogLine> {
    val units = ArrayList<Any>(shown.size)
    val open = HashMap<String, Group>()
    // The latest row of a name and app, for the connections that go with it.
    val latest = HashMap<String, Group>()
    var offline: ArrayList<LogItem>? = null
    // The app's own restart marker has no number of the core's (it is negative): it sorts with
    // the newest entry older than it, just above it.
    var olderSeq = Long.MIN_VALUE
    val markerSeq = java.util.IdentityHashMap<LogItem, Long>()
    for (i in shown.indices.reversed()) {
        val e = shown[i]
        if (e.action.marker) {
            if (e.seq < 0) markerSeq[e] = olderSeq
            open.clear()
            latest.clear()
            offline = null
            units += e
            continue
        }
        olderSeq = e.seq
        if (e.offline) {
            val stretch = offline ?: ArrayList<LogItem>().also { offline = it; units.add(it) }
            stretch += e
            continue
        }
        offline = null
        val who = "${e.name}|${e.uid}"
        val prev = latest[who]?.takeIf { e.time - it.last.time <= CONNECTION_MS }
        if (e.connection) {
            val main = prev?.main
            if (prev != null && main != null && fits(main, e)) {
                prev.add(e)
                continue
            }
        }
        val key = rowKey(e)
        var g = open[key]?.takeIf { e.time - it.start <= REPEAT_MS }
        if (g == null && !e.connection && prev != null && prev.main == null && fits(e, prev.items.first())) {
            // The connection came first (a page's own, then a request in it blocked): it joins this row.
            open.remove(prev.key)
            prev.key = key
            g = prev
        }
        if (g == null) {
            g = Group(e, key)
            units += g
        } else {
            g.add(e)
        }
        open[key] = g
        latest[who] = g
    }
    val lines = units.map { u ->
        @Suppress("UNCHECKED_CAST")
        when (u) {
            is Group -> LogLine.Query(u.items.asReversed().toList())
            is LogItem -> LogLine.Marker(u)
            else -> (u as ArrayList<LogItem>).let { LogLine.Offline(it.last(), it.first(), it.size) }
        }
    }.sortedWith(
        compareByDescending<LogLine> { l -> if (l is LogLine.Marker) markerSeq[l.e] ?: l.e.seq else newestSeq(l) }
            .thenByDescending { it is LogLine.Marker },
    )

    val out = ArrayList<LogLine>(lines.size + 8)
    val keys = HashSet<Any>()
    var day = Long.MIN_VALUE
    var marker: Long? = null
    for (l in lines) {
        if (l is LogLine.Marker) {
            out += l
            day = Long.MIN_VALUE
            marker = l.e.seq
            continue
        }
        val d = localDay(newestTime(l))
        if (d != day) {
            // A clock set back can bring a day round again: its divider then takes the row's key.
            val base = if (marker == null) "d$d" else "d$d-$marker"
            out += LogLine.Day(d, if (keys.add(base)) base else "$base-${l.key}".also { keys += it })
            day = d
        }
        out += l
    }
    return out
}

private fun newestSeq(l: LogLine): Long = when (l) {
    is LogLine.Query -> l.newest.seq
    is LogLine.Offline -> l.newest.seq
    is LogLine.Marker -> l.e.seq
    is LogLine.Day -> Long.MIN_VALUE
}

private fun newestTime(l: LogLine): Long = when (l) {
    is LogLine.Query -> l.newest.time
    is LogLine.Offline -> l.newest.time
    is LogLine.Marker -> l.e.time
    is LogLine.Day -> 0
}

/** The newest entry's sequence in [rows]. */
internal fun topSeqOf(rows: List<LogLine>): Long = rows.firstOrNull { it !is LogLine.Day }?.let { newestSeq(it) } ?: 0L

/** Rows with an entry newer than [seenSeq]: a lookup of three record types is one new row. */
internal fun freshRows(rows: List<LogLine>, seenSeq: Long): Int =
    rows.count { (it is LogLine.Query || it is LogLine.Offline) && newestSeq(it) > seenSeq }

internal fun localDay(ms: Long): Long = Math.floorDiv(ms + java.util.TimeZone.getDefault().getOffset(ms), 86_400_000L)

/** A day divider's words: "Today", "Yesterday", or the date ("27 Sep", with the year when it is another). */
internal fun dayLabel(day: Long, today: Long): String = when (day) {
    today -> "Today"
    today - 1 -> "Yesterday"
    else -> {
        val d = java.time.LocalDate.ofEpochDay(day)
        d.format(if (d.year == java.time.LocalDate.ofEpochDay(today).year) dayFormat else yearFormat)
    }
}

private val dayFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.US)
private val yearFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.US)

/** A list's name for its id as the engines record it ("hagezi-pro" → "HaGeZi Pro"); "user" is My rules. */
internal fun listLabel(id: String, s: app.aduster.settings.Settings): String = when (id) {
    "user" -> "My rules"
    else -> app.aduster.filters.FilterCatalog.all(s).firstOrNull { it.id == id }?.name ?: app.aduster.filters.FilterCatalog.byIdAny(id)?.name ?: id
}

/** What happened, in a word at the row's right; nothing for a plain answer. */
internal fun statusWord(r: LogLine.Query): String = when (r.e.action) {
    QueryAction.BLOCKED, QueryAction.BLOCKED_CNAME -> "Blocked"
    QueryAction.FILTERED -> "Page"
    QueryAction.FAILED -> "Failed"
    QueryAction.CACHE, QueryAction.STALE -> "Cached"
    QueryAction.NO_IPV6 -> "No IPv6"
    QueryAction.UNKNOWN -> "Other"
    else -> ""
}

/** A row's second line: the app, with the page a request was blocked on ("Chrome on news.test") or the list that blocked the name ("Chrome, HaGeZi Multi PRO"). */
internal fun secondLine(r: LogLine.Query, app: String, listName: (String) -> String = { it }): String {
    val e = r.e
    return when {
        e.action == QueryAction.FILTERED -> e.page?.takeIf { it != e.name }?.let { "$app on $it" } ?: app
        // "?" is what a refused connection carries when the rule's list is not known.
        e.action.blocked -> e.list?.takeIf { it.isNotBlank() && it != "?" }?.let { "$app, ${listName(it)}" } ?: app
        else -> app
    }
}

@Composable
private fun OfflineRow(r: LogLine.Offline, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().padding(start = Gutter + IconColumn, end = Gutter, top = 10.dp, bottom = 10.dp)) {
        Text("No network: ${counted(r.count, "lookup", "lookups")} failed", style = Type.bodyStrong, color = p.muted)
        Text(if (r.count == 1) clock(r.newest.time) else "${clock(r.oldest.time)} – ${clock(r.newest.time)}", style = Type.time, color = p.muted)
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
            "$what at ${clock(e.time)}",
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

/** The app icon's column: 24 dp and the space after it; texts and hairlines start after it. */
private val IconColumn: Dp = 36.dp

/** A day's divider on the page colour, lined up with the rows' text. */
@Composable
private fun DayHeading(label: String) {
    val p = LocalPalette.current
    Text(
        label,
        style = Type.heading,
        color = p.text2,
        modifier = Modifier
            .fillMaxWidth()
            .background(p.bg)
            .padding(start = Gutter, top = 14.dp, bottom = 6.dp)
            .semantics { heading() },
    )
}

/**
 * One row of the ledger, flat on the page: the app's icon, the domain (and how many times), the
 * app with the page or list; the time and a word for what happened at the right. A blocked row
 * has a bar in the blocked colour at its start and a faint wash of it; a hairline under each row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(r: LogLine.Query, app: String, icon: ImageBitmap?, modifier: Modifier, listName: (String) -> String, onLongClick: () -> Unit, onClick: () -> Unit) {
    val p = LocalPalette.current
    val e = r.e
    val blocked = e.action.blocked
    val word = statusWord(r)
    Row(
        modifier
            .fillMaxWidth()
            .background(if (blocked) p.blockedTint() else Color.Transparent)
            .drawBehind {
                if (blocked) drawRect(p.blocked, size = Size(3.dp.toPx(), size.height))
                val x = (Gutter + IconColumn).toPx()
                drawRect(p.line, topLeft = Offset(x, size.height - 1f), size = Size(size.width - x, 1f))
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Copy the domain")
            .semantics { stateDescription = if (r.count > 1) "${e.action.label}, ${r.count} times" else e.action.label }
            .heightIn(min = 52.dp)
            .padding(start = Gutter, end = Gutter, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, Modifier.size(24.dp))
        Spacer(Modifier.width(IconColumn - 24.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, style = Type.domain, color = if (blocked) p.text else p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (r.count > 1) Text("×${fmt(r.count)}", style = Type.time, color = p.muted, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
            }
            Text(secondLine(r, app, listName), style = Type.small, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(clock(r.newest.time), style = Type.time, color = p.muted, maxLines = 1)
            if (word.isNotEmpty()) Text(word, style = Type.small, color = if (blocked) p.blocked else p.muted, maxLines = 1)
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

/** The site host the page filter handled for [r], or null for plain DNS rows and other apps' connections. */
internal fun proxySiteOf(r: LogLine.Query, browsers: Set<Int>): String? =
    if (r.entries.any { it.qtype == 0 && (it.action == QueryAction.FILTERED || it.uid in browsers) }) r.e.page ?: r.e.name else null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Details(r: LogLine.Query, app: String, vm: MainViewModel, onOnlyApp: () -> Unit, onChanged: (String, Deferred<Boolean>, () -> Unit) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val e = r.e
    fun rule(text: String, said: String) {
        Haptics.confirm(view)
        val saved = vm.addUserRule(text)
        onChanged(said, saved) { vm.undoAddedRule() }
        onDismiss()
    }
    val s by vm.settings.collectAsStateWithLifecycle()
    val browsers by vm.browserUids.collectAsStateWithLifecycle()
    val proxySite = proxySiteOf(r, browsers)
    var siteCheck by remember(proxySite) { mutableStateOf<SiteCheck?>(null) }
    LaunchedEffect(proxySite) {
        if (proxySite != null) siteCheck = withContext(Dispatchers.IO) { vm.checkSite(proxySite) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface, dragHandle = null) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Gutter).padding(top = 24.dp, bottom = 16.dp).navigationBarsPadding()) {
            Text(e.action.label, style = Type.heading, color = if (e.action.blocked) p.blocked else p.muted)
            Spacer(Modifier.height(8.dp))
            Text(e.name, style = Type.title.copy(fontSize = Type.title.fontSize * 0.8f), color = p.text)
            Spacer(Modifier.height(16.dp))
            Field("App", app)
            Field("Time", if (r.count > 1) "${clock(r.newest.time)}, ${r.count} times since ${clock(r.oldest.time)}" else clock(r.newest.time))
            Field("Type", r.types)
            // A request inside a page: where it was made and what it asked for, apart from the rule.
            e.page?.let { Field("On page", it) }
            e.path?.let { Field("Request", it, mono = true) }
            if (e.micros > 0) Field("Answered in", micros(e.micros))
            e.rule?.let { Field("Rule", it, mono = true) }
            e.list?.takeIf { it != "?" }?.let { Field("From", listLabel(it, s)) }
            Spacer(Modifier.height(20.dp))
            val page = e.page
            if (e.action == QueryAction.FILTERED && page != null) {
                // Allowing a page request must not unfilter a whole site. The first action
                // allows this host only on pages of that site (a page rule); the second is
                // the plain "everywhere" rule, explained.
                PillButton("Allow on $page", Modifier.fillMaxWidth(), filled = true) {
                    rule("@@||${e.name}^\$domain=$page", "${e.name} allowed on $page")
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Allow everywhere", Modifier.weight(1f)) {
                        rule("@@||${e.name}^", "${e.name} allowed everywhere; its pages are no longer filtered")
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
                    PillButton("Allow everywhere", Modifier.weight(1f), filled = true) { rule("@@||${e.name}^", "${e.name} allowed") }
                } else {
                    PillButton("Block this domain", Modifier.weight(1f), filled = true) { rule("||${e.name}^", "${e.name} blocked") }
                }
                PillButton("Copy") {
                    copy(ctx, e.name)
                    onDismiss()
                }
            }
            Spacer(Modifier.height(4.dp))
            if (e.uid >= 0) TextAction("Show only $app", color = p.muted, onClick = onOnlyApp)
            // Leave the app out of protection altogether (Settings › Excluded apps).
            // A binder call: asked off the main thread, never while drawing.
            val packages by produceState(emptySet<String>(), e.uid) {
                value = if (e.uid >= Process.FIRST_APPLICATION_UID) withContext(Dispatchers.IO) { vm.packagesOf(e.uid) } else emptySet()
            }
            val added = packages - s.excludedApps
            if (added.isNotEmpty()) {
                TextAction("Exclude $app", color = p.muted) {
                    Haptics.confirm(view)
                    val saved = vm.update { it.copy(excludedApps = it.excludedApps + added) }
                    onChanged("$app excluded from protection", saved) { vm.update { it.copy(excludedApps = it.excludedApps - added) } }
                    onDismiss()
                }
            }
            // What the page filter saw: its site can be left undecrypted (Page filtering › Never decrypted).
            // The row's site as the page filter saw it, also when its lookup and connection are one row.
            val site = proxySite
            if (site != null && !vm.userExcludes(site, s)) {
                TextAction("Never decrypt $site", color = p.muted) {
                    Haptics.confirm(view)
                    val saved = vm.update { it.copy(httpsExclusions = (it.httpsExclusions.trim() + "\n" + site).trim()) }
                    onChanged("$site is no longer decrypted", saved) {
                        vm.update { cur -> cur.copy(httpsExclusions = cur.httpsExclusions.lines().filterNot { it.trim() == site }.joinToString("\n").trim()) }
                    }
                    onDismiss()
                }
            }
            // A site the page engine automatically passed through can be filtered again right here.
            if (proxySite != null && siteCheck?.bypassed == true) {
                TextAction("Filter again", color = p.accent) {
                    Haptics.confirm(view)
                    onDismiss()
                    vm.viewModelScope.launch {
                        val ok = vm.unbypass(proxySite)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, if (ok) "$proxySite is filtered again" else "$proxySite was not bypassed any more", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
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
