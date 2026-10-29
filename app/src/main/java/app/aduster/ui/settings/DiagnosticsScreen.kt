package app.aduster.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.aduster.https.HttpsConfig
import app.aduster.ui.BackBar
import app.aduster.ui.MainViewModel
import app.aduster.ui.clock
import app.aduster.ui.common.Group
import app.aduster.ui.common.Gutter
import app.aduster.ui.common.ListRow
import app.aduster.ui.common.SectionHeading
import app.aduster.ui.common.ValueText
import app.aduster.ui.fmt
import app.aduster.ui.hm
import app.aduster.ui.micros
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Type
import app.aduster.vpn.ProcessExits
import app.aduster.vpn.Timeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * What a person needs to tell whether protection works and to report a problem: its state and
 * the system settings that undo it, the resolver and its latency, today's counts, how the
 * processes ended lately, what browsers are not filtered right now, and Export report. The
 * engine's own counters stay in the report.
 */
@Composable
fun DiagnosticsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val stats by vm.stats.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<JsonObject?>(null) }
    var exits by remember { mutableStateOf<List<ProcessExits.Exit>>(emptyList()) }
    var timeline by remember { mutableStateOf<List<Timeline.Entry>>(emptyList()) }

    // Only while the screen is actually shown (not while the app sits in the background).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            exits = withContext(Dispatchers.IO) { vm.processExits() }
            var n = 0
            while (true) {
                d = withContext(Dispatchers.IO) { vm.diagnostics() }
                // The timeline changes only with the protection state: read every 10 s, not
                // with every 2 s poll.
                if (n++ % 5 == 0) timeline = withContext(Dispatchers.IO) { vm.timeline() }
                delay(2000)
            }
        }
    }
    val running = stats?.running ?: false

    fun n(k: String) = d?.get(k)?.jsonPrimitive?.longOrNull ?: 0L
    fun list(k: String) = (d?.get(k) as? JsonArray)?.joinToString(", ") { it.jsonPrimitive.content }.orEmpty()
    val px = d?.get("proxy") as? JsonObject
    fun pn(k: String) = px?.get(k)?.jsonPrimitive?.longOrNull ?: 0L
    val pagesRunning = px?.get("running")?.jsonPrimitive?.content == "true"

    // A lazy list: the screen polls every 2 s, and only the sections on screen are
    // composed again.
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "protection") { Column {
            BackBar("Diagnostics", onBack)

            SectionHeading("Protection")
            Group {
                row { ListRow("State") { ValueText(if (stats == null) "…" else if (running) "Running" else "Stopped") } }
                row { ListRow("Running for") { ValueText(uptime(n("uptime_s"))) } }
                d?.get("network")?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }?.let { net ->
                    val domains = list("search_domains")
                    row { ListRow("Network", if (domains.isNotEmpty()) "Names under $domains count as local" else null) { ValueText(net) } }
                }
                // Both settings take DNS away from Aduster when on, so "On" is a warning.
                row {
                    ListRow("Private DNS (strict)") {
                        val strict = d?.get("private_dns_strict")?.jsonPrimitive?.content == "true"
                        ValueText(if (strict) "On" else "Off", if (strict) p.warn else p.muted)
                    }
                }
                row {
                    ListRow("Block connections without VPN") {
                        val lockdown = d?.get("lockdown")?.jsonPrimitive?.content == "true"
                        ValueText(if (lockdown) "On" else "Off", if (lockdown) p.warn else p.muted)
                    }
                }
            }
        } }

        item(key = "resolver") { Column {
            SectionHeading("DNS server")
            Group {
                row { ListRow("In use") { ValueText(d?.get("upstream")?.jsonPrimitive?.content ?: "system") } }
                row { ListRow("Network DNS servers") { ValueText(list("system_servers").ifEmpty { "none" }) } }
                // Medians, and the slowest 1 % of the DNS server's answers.
                row { ListRow("Local answer time") { ValueText(micros(stats?.localP50 ?: 0)) } }
                row { ListRow("DNS server answer time") { ValueText(micros(stats?.upstreamP50 ?: 0)) } }
                row { ListRow("Slowest DNS server answers") { ValueText(micros(stats?.upstreamP99 ?: 0)) } }
                row { ListRow("DNS server errors") { ValueText(fmt(n("upstream_errors"))) } }
            }
        } }

        item(key = "today") { Column {
            SectionHeading("Today")
            Group {
                row { ListRow("DNS lookups") { ValueText(fmt(stats?.dnsQueries ?: 0)) } }
                row { ListRow("DNS lookups blocked") { ValueText(fmt(stats?.dnsBlocked ?: 0)) } }
                if (HttpsConfig.available) row { ListRow("Requests blocked in pages") { ValueText(fmt(pn("blocked_requests"))) } }
                row { ListRow("DNS bypass attempts blocked") { ValueText(fmt(n("rst_sent"))) } }
            }
        } }

        item(key = "pages") { Column {
            SectionHeading("Page filtering")
            Group {
                row {
                    ListRow("State") {
                        ValueText(
                            when {
                                !HttpsConfig.available -> "Not in this build"
                                pagesRunning -> "Running"
                                else -> "Off"
                            },
                        )
                    }
                }
                px?.get("error")?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() && HttpsConfig.available }?.let {
                    row { ListRow("Last error", it) }
                }
            }
        } }

        // Which sites browsers reach undecrypted right now, why and until when; a bot-check
        // bypass can be ended here ("Filter again").
        val notNow = (px?.get("not_filtered_now") as? JsonArray).orEmpty()
        if (pagesRunning) item(key = "notnow") { Column {
            SectionHeading("Not filtered right now")
            if (notNow.isEmpty()) None("None")
            Group {
                notNow.forEach { e ->
                    val o = e as? JsonObject ?: return@forEach
                    val host = o["host"]?.jsonPrimitive?.content.orEmpty()
                    val left = o["remaining_s"]?.jsonPrimitive?.longOrNull ?: 0L
                    val until = hm(System.currentTimeMillis() + left * 1000)
                    val why = when (o["reason"]?.jsonPrimitive?.content) {
                        "bot_check" -> "Bot check, until $until"
                        "media" -> "Video or audio, until $until"
                        "opened_anyway" -> "Opened anyway, until $until"
                        else -> "Until $until"
                    }
                    val bot = o["reason"]?.jsonPrimitive?.content == "bot_check"
                    row {
                        ListRow(host, why, onClick = if (bot) ({
                            scope.launch {
                                val ok = vm.unbypass(host)
                                Toast.makeText(ctx, if (ok) "$host is filtered again" else "$host was not bypassed any more", Toast.LENGTH_SHORT).show()
                                d = withContext(Dispatchers.IO) { vm.diagnostics() }
                            }
                        }) else null) { if (bot) ValueText("Filter again", p.accent) }
                    }
                }
            }
        } }

        // A page fix that went wrong on a site three times in a week is turned off there for a
        // week, by itself; listed so it is never silent.
        val retreats = (px?.get("retreats") as? JsonArray).orEmpty()
        if (retreats.isNotEmpty()) item(key = "retreats") { Column {
            SectionHeading("Turned off on these sites")
            Group {
                retreats.forEach { e ->
                    val o = e as? JsonObject ?: return@forEach
                    val left = o["remaining_s"]?.jsonPrimitive?.longOrNull ?: 0L
                    val what = when (val m = o["mechanism"]?.jsonPrimitive?.content.orEmpty()) {
                        "slots" -> "Hiding empty ad boxes"
                        "learned" -> "Learned rules"
                        "press" -> "Rejecting cookie windows"
                        "unlock" -> "Restoring scrolling"
                        "inline" -> "Forced hiding"
                        else -> m
                    }
                    row { ListRow(o["host"]?.jsonPrimitive?.content.orEmpty(), "$what, until ${hm(System.currentTimeMillis() + left * 1000)}") }
                }
            }
        } }

        item(key = "timeline") { Column {
            // When protection was on, off or paused, and why (a pause shows as one stretch).
            SectionHeading("Protection timeline")
            val lines = timelineLines(timeline).take(12)
            if (lines.isEmpty()) None("Nothing recorded yet")
            Group {
                lines.forEach { (title, detail, warn) ->
                    row { ListRow(title, detail, titleColor = if (warn) p.warn else p.text) }
                }
            }
        } }

        item(key = "exits") { Column {
            // How Aduster's processes ended lately, as Android recorded it: crashes, ANRs and kills.
            SectionHeading("How it last stopped")
            if (exits.isEmpty()) None("Nothing recorded")
            Group {
                exitLines(exits).forEach { (title, detail, notable) ->
                    row { ListRow(title, detail, titleColor = if (notable) p.warn else p.text) }
                }
            }
        } }

        item(key = "report") { Column {
            SectionHeading("Report")
            Group {
                row {
                    ListRow(
                        "Export report",
                        "Saved to Downloads",
                        onClick = {
                            scope.launch {
                                val name = withContext(Dispatchers.IO) { vm.saveReport() }
                                // Null: the core could not be reached or the file could not be written.
                                Toast.makeText(ctx, if (name != null) "Saved Downloads/$name" else "Could not save the report", Toast.LENGTH_SHORT).show()
                            }
                        },
                        titleColor = p.accent,
                    )
                }
                row {
                    ListRow(
                        "Delete saved reports",
                        onClick = {
                            scope.launch {
                                val n = vm.deleteReports()
                                Toast.makeText(ctx, if (n > 0) "Deleted $n report${if (n == 1) "" else "s"}" else "No saved reports", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        } }
    }
}

/** An empty section, said in a line where its rows would be. */
@Composable
private fun None(text: String) {
    Text(text, style = Type.small, color = LocalPalette.current.muted, modifier = Modifier.padding(horizontal = Gutter + 16.dp, vertical = 4.dp))
}

/**
 * Timeline entries as rows, newest first: (title, detail, worth a look). A pause and the resume
 * that ended it are one row ("Paused 13:02 – 13:10").
 */
internal fun timelineLines(entries: List<Timeline.Entry>): List<Triple<String, String, Boolean>> {
    val out = ArrayList<Triple<String, String, Boolean>>()
    var i = 0
    while (i < entries.size) {
        val e = entries[i]
        val next = entries.getOrNull(i + 1)
        when {
            e.event == Timeline.PAUSED && next?.event == Timeline.RESUMED -> {
                out += Triple("Paused ${clock(e.time)} – ${clock(next.time)}", "${dateTime(e.time)} · ${e.reason.orEmpty()} · resumed ${next.reason.orEmpty()}", false)
                i++
            }
            // A pause ended by turning off (older entries have no resume before the stop); any
            // other stop does not end it: it goes on after the next start.
            e.event == Timeline.PAUSED && next?.event == Timeline.STOPPED -> {
                val off = next.code == "user_off" || (next.code == null && next.reason == "turned off in the app")
                out += Triple("Paused ${clock(e.time)} – ${clock(next.time)}", "${dateTime(e.time)} · ${e.reason.orEmpty()} · ${if (off) "turned off" else "then protection stopped"}", false)
            }
            e.event == Timeline.PAUSED -> out += Triple("Paused since ${clock(e.time)}", "${dateTime(e.time)} · ${e.reason.orEmpty()}", true)
            // Turning it off, or an app update (Android ends the old process), is not a problem.
            // Decided by the entry's code; entries written before codes existed by their text.
            e.event == Timeline.STOPPED -> out += Triple(
                "Stopped", "${dateTime(e.time)} · ${e.reason.orEmpty()}",
                when (e.code) {
                    "user_off", "app_updated", "process_ended" -> false
                    null -> e.reason != "turned off in the app" && e.reason?.contains("app updated") != true
                    else -> true
                },
            )
            e.event == Timeline.STARTED -> out += Triple("Started", "${dateTime(e.time)} · ${e.reason.orEmpty()}", false)
            else -> out += Triple(e.event.replaceFirstChar { it.uppercase() }, "${dateTime(e.time)} · ${e.reason.orEmpty()}", false)
        }
        i++
    }
    return out.asReversed()
}

/**
 * Process ends as rows, (title, detail, worth a look): one row per process and cause, newest
 * first, with how often and when last ("App · Updated", "6 times, last 29 Oct 14:02"). The
 * system's own wording and the process state stay in the report.
 */
internal fun exitLines(exits: List<ProcessExits.Exit>): List<Triple<String, String, Boolean>> =
    exits.sortedByDescending { it.time }.groupBy { it.process.endsWith(":core") to it.reason }.map { (key, group) ->
        val last = group.first()
        val title = "${if (key.first) "Protection" else "App"} · ${exitWords(key.second)}"
        val detail = if (group.size == 1) dateTime(last.time) else "${group.size} times, last ${dateTime(last.time)}"
        Triple(title, detail, group.any { it.notable })
    }

/** [ProcessExits]' reason in a few plain words. */
private fun exitWords(reason: String) = when (reason) {
    "exited" -> "Ended itself"
    "signaled" -> "Ended by the system"
    "low memory" -> "Closed to free memory"
    "crash" -> "Crashed"
    "native crash" -> "Crashed in native code"
    "ANR" -> "Stopped responding"
    "initialization failure" -> "Failed to start"
    "permission change" -> "A permission changed"
    "excessive resource use" -> "Closed for using too much"
    "user request", "user stopped" -> "Stopped by you"
    "dependency died" -> "A process it needs ended"
    "freezer" -> "Closed while frozen"
    "package state change" -> "App turned off or changed"
    "app updated" -> "Updated"
    else -> "Other"
}

private val dateTimeFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm", java.util.Locale.US)

/** In the time zone of now, not of the moment the screen was first opened. */
private fun dateTime(ms: Long): String = dateTimeFormat.format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))

private fun uptime(s: Long): String = when {
    s < 60 -> "${s}s"
    s < 3600 -> "${s / 60} min"
    s < 86_400 -> "${s / 3600} h ${(s % 3600) / 60} min"
    else -> "${s / 86_400} d ${(s % 86_400) / 3600} h"
}
