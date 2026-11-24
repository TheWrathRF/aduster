package app.aduster.ui.filters

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.aduster.filters.Device
import app.aduster.filters.FilterCatalog
import app.aduster.filters.FilterJobs
import app.aduster.filters.FilterList
import app.aduster.filters.FilterMeta
import app.aduster.filters.FilterUpdater
import app.aduster.filters.Group
import app.aduster.filters.ListMeta
import app.aduster.filters.UserRules
import app.aduster.https.HttpsConfig
import app.aduster.settings.CustomList
import app.aduster.settings.DeviceProfile
import app.aduster.settings.Settings
import app.aduster.ui.DomainCheck
import app.aduster.ui.MainViewModel
import app.aduster.ui.SiteCheck
import app.aduster.ui.Sub
import app.aduster.ui.ago
import app.aduster.ui.common.AdDialog
import app.aduster.ui.common.AdusterSwitch
import app.aduster.ui.common.Card
import app.aduster.ui.common.ChipTabs
import app.aduster.ui.common.Chevron
import app.aduster.ui.common.ChoiceDialog
import app.aduster.ui.common.Corners
import app.aduster.ui.common.FoldLabel
import app.aduster.ui.common.Gutter
import app.aduster.ui.common.Haptics
import app.aduster.ui.common.InputField
import app.aduster.ui.common.ListRow
import app.aduster.ui.common.Motion
import app.aduster.ui.common.PillButton
import app.aduster.ui.common.ProgressStrip
import app.aduster.ui.common.RowCard
import app.aduster.ui.common.ScreenTitle
import app.aduster.ui.common.SectionHeading
import app.aduster.ui.common.ToggleRow
import app.aduster.ui.common.ValueText
import app.aduster.ui.counted
import app.aduster.ui.fmt
import app.aduster.ui.hm
import app.aduster.ui.theme.AdIcons
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Filters' three views: what filters every app, what filters pages in browsers, and the user's own. */
internal enum class FilterTab(val label: String) { DNS("All apps (DNS)"), PAGES("Browser pages"), YOURS("Custom") }

/** Actions offered for a checked site in "Fix a site". */
internal enum class SiteFixAction { FILTER_AGAIN, ALLOW, NEVER_DECRYPT }

/**
 * The list groups a view shows, in order. The Device group holds only the phone maker's own
 * list ([deviceList]); a build without the page engine has no page lists to show.
 */
internal fun tabGroups(tab: FilterTab, all: List<FilterList>, deviceList: String?, pagesAvailable: Boolean): List<Pair<Group, List<FilterList>>> = when (tab) {
    FilterTab.DNS -> listOf(Group.ADS, Group.SECURITY).map { g -> g to all.filter { it.group == g && !it.web } } +
        listOfNotNull(all.firstOrNull { it.group == Group.DEVICE && it.id == deviceList }?.let { Group.DEVICE to listOf(it) })
    FilterTab.PAGES -> if (!pagesAvailable) emptyList() else listOf(Group.WEB, Group.WEB_REGIONAL, Group.WEB_ANNOYANCES).map { g -> g to all.filter { it.group == g } }
    FilterTab.YOURS -> listOf(Group.CUSTOM to all.filter { it.group == Group.CUSTOM })
}

/** The Update button is disabled while an update or compile job runs. */
internal fun updateButtonEnabled(progress: FilterJobs.Progress?): Boolean = progress == null

/** Page rules appear in the header summary only when page filtering can actually run. */
internal fun showPageRulesSummary(available: Boolean, httpsFiltering: Boolean, caTrusted: Boolean): Boolean =
    available && httpsFiltering && caTrusted

/** Plain summary text for the Filters header, matching what [SummaryLine] displays. */
internal fun filtersSummaryText(meta: FilterMeta, inUse: List<String>, pageRules: Boolean, now: Long = System.currentTimeMillis()): String {
    val dns = counted(meta.compiledRules, "DNS rule")
    val updated = updateLine(meta, inUse, now)
    return if (pageRules && meta.webError == null) {
        "$dns, ${counted(meta.webRules, "page rule")}, $updated"
    } else {
        "$dns, $updated"
    }
}

/** Visible list names whose last download failed (companion and never-decrypt lists excluded). */
internal fun failedListNames(meta: FilterMeta, settings: Settings, web: Boolean, device: Device = Device.current()): List<String> {
    val ids = FilterUpdater.downloaded(settings, web, device).toSet()
    return FilterCatalog.all(settings)
        .filter { it.id in ids && meta.lists[it.id]?.error != null }
        .map { it.name }
}

/** Site exception rule for "Allow on this site". */
internal fun allowSiteRule(host: String): String = "@@||$host^"

/** Adds [host] to the user's never-decrypt list once. */
internal fun withNeverDecrypt(exclusions: String, host: String): String =
    if (exclusions.lines().any { it.trim().equals(host, ignoreCase = true) }) exclusions.trim()
    else (exclusions.trim() + "\n" + host).trim()

/** Removes [host] from the user's never-decrypt list (for Undo). */
internal fun withoutNeverDecrypt(exclusions: String, host: String): String =
    exclusions.lines().filterNot { it.trim().equals(host, ignoreCase = true) }.joinToString("\n").trim()

/** Which actions "Fix a site" offers for [host], in priority order. */
internal fun siteFixActions(
    host: String,
    settings: Settings,
    site: SiteCheck?,
    pagesAvailable: Boolean,
    userExcluded: Boolean,
): List<SiteFixAction> = buildList {
    if (site?.bypassed == true) add(SiteFixAction.FILTER_AGAIN)
    val alreadyAllowed = host in UserRules.sites(settings.userRules).allowed ||
        site?.reasons?.any { it.kind == "user_allowed" } == true
    if (!alreadyAllowed) add(SiteFixAction.ALLOW)
    val alreadyExcluded = userExcluded || site?.reasons?.any { it.kind == "never_decrypt" } == true
    if (pagesAvailable && settings.httpsFiltering && !alreadyExcluded) add(SiteFixAction.NEVER_DECRYPT)
}

/** The browser part of "Fix a site" as lines: (text, worth a look). */
internal fun browserLines(
    c: SiteCheck?,
    s: Settings,
    pagesAvailable: Boolean,
    userExcluded: Boolean,
    now: Long = System.currentTimeMillis(),
): List<Pair<String, Boolean>> {
    if (!pagesAvailable) return listOf("Pages: no page filtering in this build" to false)
    if (!s.httpsFiltering) return listOf("Pages: page filtering is off" to false)
    if (c == null) return listOf("Pages: page filtering is not running" to true)
    val out = ArrayList<Pair<String, Boolean>>()
    fun until(sec: Long?) = sec?.let { hm(now + it * 1000) } ?: "?"
    if (!c.filteringActive) out += "Pages: paused" to true
    for (r in c.reasons) {
        out += when (r.kind) {
            "never_decrypt" -> (if (userExcluded) "Pages: skipped (your list)" else "Pages: skipped (sensitive site)") to false
            "user_allowed" -> "Pages: allowed by your rules" to false
            "bot_check" -> "Pages: skipped until ${until(r.remainingS)} for a bot check" to true
            "paused_by_you" -> "Pages: not filtered until ${until(r.remainingS)}, as you asked" to false
            "media" -> "Pages: skipped until ${until(r.remainingS)} (video or audio)" to false
            "opened_anyway" -> "Pages: opened anyway, until ${until(r.remainingS)}" to false
            "network_block" -> "Pages: filtered (network blocks this site by name)" to false
            "passed_through" -> "Pages: last passed without filtering: ${r.detail.orEmpty()}" to true
            else -> "Pages: ${r.kind}" to false
        }
    }
    if (c.decrypted && c.reasons.none { it.kind == "user_allowed" }) out += "Pages: filtered" to false
    return out
}

@Composable
fun FiltersScreen(vm: MainViewModel, open: (Sub) -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    val meta by vm.filterMeta.collectAsStateWithLifecycle()
    val progress by vm.updateProgress.collectAsStateWithLifecycle()
    var caTrusted by remember { mutableStateOf(false) }
    var deviceDialog by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    var removeCandidate by remember { mutableStateOf<FilterList?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Folded groups, by name. Two browser groups start folded: many lists, rarely changed.
    var folded by rememberSaveable { mutableStateOf("${Group.WEB_REGIONAL.name},${Group.WEB_ANNOYANCES.name}") }
    fun isFolded(g: Group) = g.name in folded.split(',')
    fun toggle(g: Group) {
        val set = folded.split(',').filter { it.isNotEmpty() }.toMutableSet()
        if (!set.remove(g.name)) set += g.name
        folded = set.joinToString(",")
    }

    // State for "Fix a site" on the Custom tab, kept while scrolling.
    var domain by rememberSaveable { mutableStateOf("") }
    var checkedHost by remember { mutableStateOf<String?>(null) }
    var verdict by remember { mutableStateOf<DomainCheck?>(null) }
    var site by remember { mutableStateOf<SiteCheck?>(null) }
    var recheck by remember { mutableIntStateOf(0) }
    val undoBar = remember { SnackbarHostState() }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            caTrusted = withContext(Dispatchers.IO) { vm.caInstalled() }
        }
    }
    LaunchedEffect(Unit) { vm.refreshFilterMeta() }
    LaunchedEffect(domain, recheck, s.userRules, s.httpsExclusions) {
        if (domain.isBlank()) {
            checkedHost = null
            verdict = null
            site = null
            return@LaunchedEffect
        }
        delay(250)
        val d = UserRules.siteHost(domain)
        checkedHost = d
        verdict = if (d != null) withContext(Dispatchers.IO) { vm.checkDomain(d) } else null
        site = if (d != null) withContext(Dispatchers.IO) { vm.checkSite(d) } else null
    }

    val all = FilterCatalog.all(s)
    val device = remember { Device.current() }
    val profile = FilterCatalog.deviceProfile(s, device)
    // Off turns Xiaomi's list into allow rules; it also has the Safe profile.
    val allowlist = device.brandList == FilterCatalog.XIAOMI_LIST
    // Generic hiding rules (`##.ad`) count too.
    val ruleCount = remember(s.userRules) { UserRules.ruleCount(s.userRules) }
    val sites = remember(s.userRules) { UserRules.sites(s.userRules) }
    val filterTab = FilterTab.entries[tab]
    val groups = tabGroups(filterTab, all, device.brandList, HttpsConfig.available)
    val onChange = { l: FilterList, on: Boolean -> vm.setListEnabled(l.id, on) }
    val pagesActive = showPageRulesSummary(HttpsConfig.available, s.httpsFiltering, caTrusted)
    val inUse = remember(s, pagesActive) { FilterUpdater.downloaded(s, pagesActive) }
    val failedNames = remember(meta, s, pagesActive) { failedListNames(meta, s, pagesActive) }
    val updateEnabled = updateButtonEnabled(progress)

    fun showUndo(text: String, undo: () -> Unit) {
        scope.launch {
            undoBar.currentSnackbarData?.dismiss()
            val r = undoBar.showSnackbar(text, actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) undo()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Filters") {
                PillButton(
                    text = "Update",
                    modifier = Modifier.padding(end = 8.dp).minimumInteractiveComponentSize(),
                    filled = updateEnabled,
                    enabled = updateEnabled,
                    height = 40.dp,
                ) { vm.updateFiltersNow() }
            }
            SummaryLine(meta, inUse, pagesActive)
            ProgressStrip(
                phase = progress?.phase,
                done = progress?.done ?: 0,
                total = progress?.total ?: 0,
                failed = progress?.failed ?: failedNames,
                withPages = progress?.withPages ?: pagesActive,
            )

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)) {
                item(key = "chips") {
                    ChipTabs(FilterTab.entries.map { it.label }, tab, { tab = it }, Modifier.padding(top = 8.dp, bottom = 4.dp))
                }

                when (filterTab) {
                    FilterTab.DNS -> for ((g, lists) in groups) {
                        item(key = "h-" + g.name) { SectionHeading(g.title) }
                        if (g == Group.DEVICE) {
                            // The maker's own telemetry list: a profile to pick, then the list's state.
                            val list = lists.single()
                            item(key = "device") {
                                RowCard(Modifier.animateItem()) {
                                    Column {
                                        ListRow(list.summary, profile.detail(allowlist), onClick = { deviceDialog = true }) { ValueText(profile.label) }
                                        val x = meta.lists[list.id]
                                        if (x != null && FilterCatalog.isEnabled(list, s, device)) {
                                            val what = if (allowlist && profile == DeviceProfile.OFF) "Used as an allowlist, " else ""
                                            ListRow(list.name, what + listDetail(x))
                                        }
                                    }
                                }
                            }
                        } else {
                            listCards(lists, s, meta, onChange)
                        }
                    }

                    FilterTab.PAGES -> {
                        // Only said when the lists below do nothing.
                        val note = when {
                            !HttpsConfig.available -> "Not included in this build"
                            s.httpsFiltering -> null
                            else -> "Used when page filtering is on"
                        }
                        if (note != null) item(key = "pages-note") { Note(note) }
                        for ((g, lists) in groups) {
                            if (g == Group.WEB) {
                                item(key = "h-" + g.name) { SectionHeading(g.title) }
                                listCards(lists, s, meta, onChange)
                            } else {
                                // Many lists, rarely changed: folded until opened, with how many are on.
                                item(key = "h-" + g.name) {
                                    val on = lists.count { FilterCatalog.isEnabled(it, s) }
                                    FoldLabel(g.title, expanded = !isFolded(g), summary = "$on of ${lists.size} on") { toggle(g) }
                                }
                                if (!isFolded(g)) listCards(lists, s, meta, onChange)
                            }
                        }
                    }

                    FilterTab.YOURS -> {
                        item(key = "fix-site") {
                            FixSiteSection(
                                domain = domain,
                                onDomainChange = { domain = it },
                                host = checkedHost,
                                verdict = verdict,
                                site = site,
                                settings = s,
                                userExcluded = checkedHost?.let { vm.userExcludes(it, s) } ?: false,
                                onAllow = { h ->
                                    Haptics.confirm(view)
                                    vm.addUserRule(allowSiteRule(h))
                                    recheck++
                                    showUndo("$h allowed") {
                                        vm.undoAddedRule()
                                        recheck++
                                    }
                                },
                                onNeverDecrypt = { h ->
                                    Haptics.confirm(view)
                                    vm.update { it.copy(httpsExclusions = withNeverDecrypt(it.httpsExclusions, h)) }
                                    recheck++
                                    showUndo("$h is no longer decrypted") {
                                        vm.update { cur -> cur.copy(httpsExclusions = withoutNeverDecrypt(cur.httpsExclusions, h)) }
                                        recheck++
                                    }
                                },
                                onFilterAgain = { h ->
                                    Haptics.confirm(view)
                                    scope.launch {
                                        val ok = vm.unbypass(h)
                                        Toast.makeText(ctx, if (ok) "$h is filtered again" else "$h was not bypassed any more", Toast.LENGTH_SHORT).show()
                                        recheck++
                                    }
                                },
                            )
                        }
                        item(key = "sites") {
                            Column(Modifier.padding(top = 12.dp)) {
                                RowCard {
                                    ListRow(
                                        "Sites",
                                        "${fmt(sites.allowed.size)} allowed, ${fmt(sites.blocked.size)} blocked",
                                        onClick = { open(Sub.SITES) },
                                    ) { Chevron() }
                                }
                                RowCard {
                                    ListRow("My rules", counted(ruleCount, "rule"), onClick = { open(Sub.RULES) }) { Chevron() }
                                }
                            }
                        }
                        item(key = "h-custom") { SectionHeading("Custom lists") }
                        // Custom lists switch off like catalog lists; a tap on the row offers removal.
                        items(groups.single().second, key = { it.id }) { l ->
                            val on = FilterCatalog.isEnabled(l, s)
                            RowCard(Modifier.animateItem()) {
                                ListRow(
                                    l.name, (if (l.web) "Browser pages: " else "DNS: ") + (meta.lists[l.id]?.let(::listDetail) ?: "not downloaded yet"),
                                    onClick = { removeCandidate = l },
                                ) { AdusterSwitch(on, label = "Use ${l.name}") { vm.setListEnabled(l.id, it) } }
                            }
                        }
                        item(key = "custom-add") {
                            RowCard { ListRow("Add a list by URL", onClick = { addDialog = true }, titleColor = p.accent) }
                        }
                    }
                }
            }
        }
        SnackbarHost(undoBar, Modifier.align(Alignment.BottomCenter).padding(horizontal = Gutter, vertical = 12.dp)) { d ->
            Snackbar(d, containerColor = p.surfaceHigh, contentColor = p.text, actionColor = p.accent, shape = RoundedCornerShape(Corners.outer))
        }
    }

    val deviceList = device.brandList?.let { FilterCatalog.byId(it) }
    if (deviceDialog && deviceList != null) {
        ChoiceDialog(
            deviceList.summary,
            // Safe needs a tested exceptions file, which only Xiaomi's list has.
            if (allowlist) DeviceProfile.entries else listOf(DeviceProfile.FULL, DeviceProfile.OFF),
            profile,
            label = { it.label },
            detail = { it.detail(allowlist) },
            onDismiss = { deviceDialog = false },
        ) {
            deviceDialog = false
            vm.setDeviceProfile(it)
        }
    }

    if (addDialog) AddListDialog(known = all.map { it.url.trim().lowercase() }.toSet(), onDismiss = { addDialog = false }) { name, url, web ->
        addDialog = false
        vm.addCustomList(CustomList("custom-" + UUID.randomUUID().toString().take(8), name, url, web))
    }

    removeCandidate?.let { l ->
        AdDialog(
            l.name,
            onDismiss = { removeCandidate = null },
            text = l.url,
            confirm = "Remove",
            destructive = true,
            dismiss = "Keep",
            onConfirm = {
                vm.removeCustomList(l.id)
                removeCandidate = null
            },
        )
    }
}

/** Pinned rule summary under the Filters title row. */
@Composable
private fun SummaryLine(meta: FilterMeta, inUse: List<String>, pageRules: Boolean) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(start = Gutter + 4.dp, end = Gutter, bottom = 4.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum", color = p.text)) { append(fmt(meta.compiledRules)) }
                append(if (meta.compiledRules == 1) " DNS rule" else " DNS rules")
                if (pageRules && meta.webError == null) {
                    append(", ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum", color = p.text)) { append(fmt(meta.webRules)) }
                    append(if (meta.webRules == 1) " page rule" else " page rules")
                }
                append(", ")
                append(updateLine(meta, inUse))
            },
            style = Type.small,
            color = p.muted,
        )
        if (pageRules && meta.webError != null) {
            Spacer(Modifier.height(2.dp))
            Text("Page rules failed to build: ${meta.webError}", style = Type.small, color = p.warn)
        }
    }
}

/** Check how one site is handled across DNS and browsers, with one-tap fixes. */
@Composable
private fun FixSiteSection(
    domain: String,
    onDomainChange: (String) -> Unit,
    host: String?,
    verdict: DomainCheck?,
    site: SiteCheck?,
    settings: Settings,
    userExcluded: Boolean,
    onAllow: (String) -> Unit,
    onNeverDecrypt: (String) -> Unit,
    onFilterAgain: (String) -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        SectionHeading("Fix a site")
        InputField(
            domain,
            onDomainChange,
            "example.com",
            Modifier.fillMaxWidth().padding(horizontal = Gutter),
            label = "Site to check",
            icon = AdIcons.Search,
        )
        AnimatedContent(
            targetState = Triple(host, verdict, site),
            transitionSpec = { fadeIn(tween(Motion.MEDIUM)) togetherWith fadeOut(tween(Motion.SHORT)) },
            label = "verdict",
        ) { (h, v, c) ->
            if (h != null && v != null) {
                val actions = siteFixActions(h, settings, c, HttpsConfig.available, userExcluded)
                Card(Modifier.padding(top = 10.dp)) {
                    Text(
                        "DNS: " + if (v.blocked) "Blocked" else "Allowed",
                        style = Type.bodyStrong,
                        color = if (v.blocked) p.blocked else p.text,
                    )
                    val why = listOfNotNull(v.rule, v.list?.let { "from $it" }).joinToString(" ")
                    Text(why.ifEmpty { "No rule matches this name" }, style = Type.mono, color = p.muted)
                    if (!v.running) Text("Protection is off; checked against the saved lists", style = Type.small, color = p.warn)
                    Spacer(Modifier.height(8.dp))
                    browserLines(c, settings, HttpsConfig.available, userExcluded).forEach { (line, warn) ->
                        Text(line, style = Type.small, color = if (warn) p.warn else p.muted)
                    }
                    if (actions.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        actions.forEachIndexed { i, action ->
                            if (i > 0) Spacer(Modifier.height(8.dp))
                            val filled = i == 0
                            when (action) {
                                SiteFixAction.FILTER_AGAIN -> PillButton("Filter again", Modifier.fillMaxWidth(), filled = filled) { onFilterAgain(h) }
                                SiteFixAction.ALLOW -> PillButton("Allow on this site", Modifier.fillMaxWidth(), filled = filled) { onAllow(h) }
                                SiteFixAction.NEVER_DECRYPT -> PillButton("Never decrypt", Modifier.fillMaxWidth(), filled = filled) { onNeverDecrypt(h) }
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** Each list as its own card: name, rules and age (or what it is), and its switch. */
private fun LazyListScope.listCards(
    lists: List<FilterList>,
    s: Settings,
    meta: FilterMeta,
    onChange: (FilterList, Boolean) -> Unit,
) {
    items(lists, key = { it.id }) { l ->
        RowCard(Modifier.animateItem()) { ListToggle(l, meta.lists[l.id], FilterCatalog.isEnabled(l, s)) { onChange(l, it) } }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = Type.small, color = LocalPalette.current.muted, modifier = Modifier.padding(start = Gutter + 16.dp, end = Gutter + 16.dp, top = 16.dp))
}

/**
 * When the lists were last updated, for the header, and how many of the lists in use ([ids])
 * failed their last download (as their rows say): when every one did, nothing was updated, only
 * checked.
 */
internal fun updateLine(m: FilterMeta, ids: List<String>, now: Long = System.currentTimeMillis()): String {
    if (m.lastRun <= 0) return "not updated yet"
    val n = FilterUpdater.failedOf(m, ids)
    if (n <= 0) return "updated ${ago(m.lastRun, now)}"
    // No-break spaces: the line wraps before "1 list failed", never inside it.
    val failed = "$n\u00A0list${if (n == 1) "" else "s"}\u00A0failed"
    return if (n >= ids.size) "checked ${ago(m.lastRun, now)}, $failed" else "updated ${ago(m.lastRun, now)}, $failed"
}

internal fun listDetail(m: ListMeta, now: Long = System.currentTimeMillis()): String {
    // Hostname rules the DNS layer already enforces are not duplicated in the page filter; the
    // count includes them, as the list itself does.
    val n = m.rules + m.dnsCovered
    val rules = if (n > 0) counted(n, "rule") else "not compiled"
    val when_ = if (m.updatedAt > 0) ago(m.updatedAt, now) else "built in"
    return if (m.error != null) "$rules, update failed: ${m.error}" else "$rules, $when_"
}

@Composable
private fun ListToggle(l: FilterList, m: ListMeta?, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val detail = if (enabled && m != null && (m.rules > 0 || m.error != null)) listDetail(m) else l.summary
    if (FilterCatalog.followsSetting(l)) {
        // On exactly when Settings › Block DNS bypass is (no switch of its own).
        ListRow(l.name, if (enabled) detail else "Follows Block DNS bypass in Settings") { ValueText(if (enabled) "On" else "Off") }
        return
    }
    ToggleRow(l.name, detail, checked = enabled, onChange = onChange)
}

@Composable
private fun AddListDialog(known: Set<String>, onDismiss: () -> Unit, onAdd: (String, String, Boolean) -> Unit) {
    val p = LocalPalette.current
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var web by remember { mutableStateOf(0) }
    // The same list twice would be downloaded and compiled twice.
    val duplicate = url.trim().lowercase() in known
    val valid = url.trim().startsWith("https://") && name.isNotBlank() && !duplicate
    AdDialog(
        "Add a list",
        onDismiss = onDismiss,
        confirm = "Add",
        confirmEnabled = valid,
        onConfirm = { if (valid) onAdd(name.trim(), url.trim(), web == 1) },
    ) {
        InputField(name, { name = it }, "Name", Modifier.fillMaxWidth(), label = "List name", color = p.bg)
        Spacer(Modifier.height(10.dp))
        InputField(url, { url = it }, "https://…", Modifier.fillMaxWidth(), label = "List address", error = duplicate, color = p.bg)
        if (duplicate) Text("This list is already added", style = Type.small, color = p.warn, modifier = Modifier.padding(start = 18.dp, top = 6.dp))
        // A list for web pages is of no use in a build without the page engine. The same names
        // as the views it will show under.
        if (HttpsConfig.available) {
            Spacer(Modifier.height(12.dp))
            ChipTabs(listOf(FilterTab.DNS.label, FilterTab.PAGES.label), web, { web = it }, inset = 0.dp)
        }
    }
}
