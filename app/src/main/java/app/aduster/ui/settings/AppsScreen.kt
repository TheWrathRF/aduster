package app.aduster.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aduster.ui.BackBar
import app.aduster.ui.InstalledApp
import app.aduster.ui.MainViewModel
import app.aduster.ui.common.AppIcon
import app.aduster.ui.common.Group
import app.aduster.ui.common.Gutter
import app.aduster.ui.common.groupItems
import app.aduster.ui.common.InputField
import app.aduster.ui.common.ToggleRow
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Type

@Composable
fun AppsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val iconVersion by vm.iconVersion.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { apps = vm.installedApps() }

    // Toggles are collected and saved together a second after the last one (and on leaving):
    // each saved change rebuilds the VPN for every app.
    // Null until the stored set is read: after the main process was killed this screen is
    // restored before DataStore's first read, and starting from the placeholder settings would
    // save an empty set over the user's exclusions (the same guard as My rules).
    var excluded by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(Unit) { if (excluded == null) excluded = vm.storedSettings().excludedApps }
    val current = excluded ?: emptySet()
    LaunchedEffect(excluded) {
        val want = excluded ?: return@LaunchedEffect
        delay(1000)
        if (want != vm.storedSettings().excludedApps) vm.update { it.copy(excludedApps = want) }
    }
    val latest by rememberUpdatedState(excluded)
    DisposableEffect(Unit) {
        onDispose { latest?.let { want -> vm.update { if (it.excludedApps == want) it else it.copy(excludedApps = want) } } }
    }

    val q = query.trim().lowercase()
    // Filtered and sorted only when its inputs change, not for every icon that loads.
    val shown = remember(apps, q, showSystem, current) {
        apps.orEmpty()
            .filter { showSystem || !it.system || it.pkg in current }
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
            // Excluded apps first, so the current state is visible at a glance.
            .sortedByDescending { it.pkg in current }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("Excluded apps", onBack)
        Text(
            "Excluded apps skip Aduster completely.",
            style = Type.small,
            color = p.muted,
            modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
        )
        InputField(query, { query = it }, "Search apps", Modifier.fillMaxWidth().padding(horizontal = Gutter), icon = app.aduster.ui.theme.AdIcons.Search)
        Spacer(Modifier.height(12.dp))
        Group { row { ToggleRow("Show system apps", checked = showSystem) { showSystem = it } } }
        Spacer(Modifier.height(12.dp))
        if (apps == null) {
            Text("Loading apps…", style = Type.small, color = p.muted, modifier = Modifier.padding(horizontal = Gutter + 16.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            groupItems(shown, key = { it.pkg }) { a ->
                val isExcluded = a.pkg in current
                val toggle = { v: Boolean ->
                    val e = excluded
                    if (e != null) excluded = if (v) e + a.pkg else e - a.pkg
                }
                val icon = remember(iconVersion, a.pkg) { vm.appIcon(a.pkg) }
                ToggleRow(a.label, a.pkg, checked = isExcluded, leading = { AppIcon(icon) }, onChange = toggle)
            }
        }
    }
}
