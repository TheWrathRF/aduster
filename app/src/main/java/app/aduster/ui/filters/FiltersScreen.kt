package app.aduster.ui.filters

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aduster.filters.Device
import app.aduster.filters.FilterCatalog
import app.aduster.filters.FilterJobs
import app.aduster.filters.FilterList
import app.aduster.filters.Group
import app.aduster.filters.ListMeta
import app.aduster.filters.UserRules
import app.aduster.https.HttpsConfig
import app.aduster.settings.CustomList
import app.aduster.settings.DeviceProfile
import app.aduster.ui.MainViewModel
import app.aduster.ui.Sub
import app.aduster.ui.ago
import app.aduster.ui.common.AdDialog
import app.aduster.ui.common.AdusterSwitch
import app.aduster.ui.common.ChipTabs
import app.aduster.ui.common.Chevron
import app.aduster.ui.common.ChoiceDialog
import app.aduster.ui.common.Corners
import app.aduster.ui.common.FoldLabel
import app.aduster.ui.common.Gutter
import app.aduster.ui.common.InputField
import app.aduster.ui.common.ListRow
import app.aduster.ui.common.Motion
import app.aduster.ui.common.PillButton
import app.aduster.ui.common.RowCard
import app.aduster.ui.common.SectionHeading
import app.aduster.ui.common.ToggleRow
import app.aduster.ui.common.ValueText
import app.aduster.ui.fmt
import app.aduster.ui.theme.GoogleSansCode
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Type
import java.util.UUID

/** Filters' three views: what filters every app, what filters pages in browsers, and the user's own. */
internal enum class FilterTab(val label: String) { DNS("All apps (DNS)"), PAGES("Browser pages"), YOURS("Custom") }

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

@Composable
fun FiltersScreen(vm: MainViewModel, open: (Sub) -> Unit) {
    val p = LocalPalette.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val meta by vm.filterMeta.collectAsStateWithLifecycle()
    val progress by vm.updateProgress.collectAsStateWithLifecycle()
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

    LaunchedEffect(Unit) { vm.refreshFilterMeta() }

    val all = FilterCatalog.all(s)
    val device = remember { Device.current() }
    val profile = FilterCatalog.deviceProfile(s, device)
    // Off turns Xiaomi's list into allow rules; it also has the Safe profile.
    val allowlist = device.brandList == FilterCatalog.XIAOMI_LIST
    // Generic hiding rules (`##.ad`) count too.
    val ruleCount = remember(s.userRules) { UserRules.ruleCount(s.userRules) }
    val sites = remember(s.userRules) { UserRules.sites(s.userRules) }
    val view = FilterTab.entries[tab]
    val groups = tabGroups(view, all, device.brandList, HttpsConfig.available)
    val onChange = { l: FilterList, on: Boolean -> vm.setListEnabled(l.id, on) }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)) {
        item(key = "header") { Header(meta, progress, s.httpsFiltering && HttpsConfig.available) { vm.updateFiltersNow() } }
        item(key = "chips") {
            ChipTabs(FilterTab.entries.map { it.label }, tab, { tab = it }, Modifier.padding(top = 8.dp, bottom = 4.dp))
        }

        when (view) {
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
                                    val what = if (allowlist && profile == DeviceProfile.OFF) "Used as an allowlist · " else ""
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
                item(key = "sites") {
                    Column(Modifier.padding(top = 12.dp)) {
                        RowCard {
                            ListRow(
                                "Sites",
                                "${fmt(sites.allowed.size)} allowed · ${fmt(sites.blocked.size)} blocked",
                                onClick = { open(Sub.SITES) },
                            ) { Chevron() }
                        }
                        RowCard {
                            ListRow("My rules", "${fmt(ruleCount)} rules", onClick = { open(Sub.RULES) }) { Chevron() }
                        }
                    }
                }
                item(key = "h-custom") { SectionHeading("Custom lists") }
                // Custom lists switch off like catalog lists; a tap on the row offers removal.
                items(groups.single().second, key = { it.id }) { l ->
                    val on = FilterCatalog.isEnabled(l, s)
                    RowCard(Modifier.animateItem()) {
                        ListRow(
                            l.name, (if (l.web) "Browser pages · " else "DNS · ") + (meta.lists[l.id]?.let(::listDetail) ?: "not downloaded yet"),
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

/**
 * The header card in the tint: the title, the rule count with when the lists last updated (or how
 * far an update is), and Update now. While an update runs, a thin accent bar fills along the
 * card's bottom edge.
 */
@Composable
private fun Header(meta: app.aduster.filters.FilterMeta, progress: FilterJobs.Progress?, pageRules: Boolean, onUpdate: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .padding(horizontal = Gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.card))
            .background(p.tone),
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 22.dp)) {
            Text("Filters", style = Type.screenTitle, color = p.text, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(6.dp))
            AnimatedContent(
                targetState = progress?.let(::progressText),
                transitionSpec = { fadeIn(tween(Motion.MEDIUM)) togetherWith fadeOut(tween(Motion.SHORT)) },
                label = "update state",
            ) { text ->
                if (text != null) {
                    Text(text, style = Type.body, color = p.accent)
                } else {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontFamily = GoogleSansCode, color = p.accent)) { append(fmt(meta.compiledRules)) }
                            append(" DNS rules · ")
                            append(if (meta.lastRun > 0) "updated ${ago(meta.lastRun)}" else "not updated yet")
                        },
                        style = Type.body,
                        color = p.text2,
                    )
                }
            }
            if (pageRules) {
                Text(
                    meta.webError?.let { "Page rules failed to build: $it" } ?: "${fmt(meta.webRules)} page rules",
                    style = Type.small,
                    color = if (meta.webError != null) p.warn else p.muted,
                )
            }
            Spacer(Modifier.height(16.dp))
            PillButton(if (progress != null) "Updating…" else "Update now", filled = true) {
                if (progress == null) onUpdate()
            }
        }
        AnimatedVisibility(
            visible = progress != null,
            enter = fadeIn(tween(Motion.MEDIUM)),
            exit = fadeOut(tween(Motion.LONG)),
            modifier = Modifier.align(Alignment.BottomStart),
        ) { ProgressLine(progress) }
    }
}

/** Each list as its own card: name, rules and age (or what it is), and its switch. */
private fun LazyListScope.listCards(
    lists: List<FilterList>,
    s: app.aduster.settings.Settings,
    meta: app.aduster.filters.FilterMeta,
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

private fun progressText(p: FilterJobs.Progress): String =
    if (p.total > 0 && p.done < p.total) "Updating ${p.done + 1} of ${p.total} · ${p.text}" else "Updating · ${p.text}"

/** How far an update is, along the header card's bottom edge. */
@Composable
private fun ProgressLine(progress: FilterJobs.Progress?) {
    val p = LocalPalette.current
    val target = when {
        progress == null -> 1f
        progress.total > 0 -> (progress.done.toFloat() / progress.total).coerceIn(0.04f, 1f)
        else -> 0.04f
    }
    val f by animateFloatAsState(target, tween(Motion.LONG, easing = Motion.Emphasized), label = "update progress")
    Canvas(Modifier.fillMaxWidth().height(3.dp)) {
        drawRect(p.accent, size = Size(size.width * f, size.height))
    }
}

private fun listDetail(m: ListMeta): String {
    // Hostname rules the DNS layer already enforces are not duplicated in the page filter; the
    // count includes them, as the list itself does.
    val n = m.rules + m.dnsCovered
    val rules = if (n > 0) "${fmt(n)} rules" else "not compiled"
    val when_ = if (m.updatedAt > 0) ago(m.updatedAt) else "built in"
    return if (m.error != null) "$rules · update failed: ${m.error}" else "$rules · $when_"
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
        InputField(name, { name = it }, "Name", Modifier.fillMaxWidth(), color = p.bg)
        Spacer(Modifier.height(10.dp))
        InputField(url, { url = it }, "https://…", Modifier.fillMaxWidth(), error = duplicate, color = p.bg)
        if (duplicate) Text("This list is already added", style = Type.small, color = p.warn, modifier = Modifier.padding(start = 18.dp, top = 6.dp))
        // A list for web pages is of no use in a build without the page engine. The same names
        // as the views it will show under.
        if (HttpsConfig.available) {
            Spacer(Modifier.height(12.dp))
            ChipTabs(listOf(FilterTab.DNS.label, FilterTab.PAGES.label), web, { web = it }, inset = 0.dp)
        }
    }
}
