package app.aduster.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aduster.BuildConfig
import app.aduster.core.Upstreams
import app.aduster.https.HttpsConfig
import app.aduster.settings.BlockMode
import app.aduster.settings.ThemeMode
import app.aduster.ui.MainViewModel
import app.aduster.ui.Sub
import app.aduster.ui.common.AdDialog
import app.aduster.ui.common.ChoiceDialog
import app.aduster.ui.common.Chevron
import app.aduster.ui.common.Group
import app.aduster.ui.common.InputField
import app.aduster.ui.common.ListRow
import app.aduster.ui.common.ScreenTitle
import app.aduster.ui.common.SectionHeading
import app.aduster.ui.common.ToggleRow
import app.aduster.ui.common.ValueText
import app.aduster.ui.fmt
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Type
import kotlinx.coroutines.launch

private enum class Dialog { UPSTREAM, CUSTOM_UPSTREAM, BLOCK_MODE, INTERVAL, LOG_SIZE, THEME, RESET, LICENCES }

@Composable
fun SettingsScreen(vm: MainViewModel, open: (Sub) -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<Dialog?>(null) }

    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = vm.exportSettings(uri)
                Toast.makeText(ctx, if (ok) "Settings exported" else "Could not write the file", Toast.LENGTH_SHORT).show()
            }
        }
    }
    var preview by remember { mutableStateOf<app.aduster.settings.SettingsBackup.Preview?>(null) }
    val undoImport by vm.undoImport.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                // Nothing changes until the summary is confirmed.
                preview = vm.readImport(uri)
                if (preview == null) Toast.makeText(ctx, "Not an Aduster settings file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        ScreenTitle("Settings")

        // Text rows: the headings group them; an icon is kept only where it shows a state.
        SectionHeading("DNS")
        Group {
            row { ListRow("DNS server", onClick = { dialog = Dialog.UPSTREAM }) { ValueText(Upstreams.label(s.upstream, s.customUpstream)) } }
            row { ListRow("Reply to blocked lookups", onClick = { dialog = Dialog.BLOCK_MODE }) { ValueText(s.blockMode.label) } }
            row { ToggleRow("Block disguised trackers", "Trackers hidden under a site's own name", s.cnameCheck) { v -> vm.update { it.copy(cnameCheck = v) } } }
            row { ToggleRow("Optimistic cache", "Answers from cache while it refreshes", s.optimisticCache) { v -> vm.update { it.copy(optimisticCache = v) } } }
            row { ToggleRow("Block DNS bypass", "Stops apps from using their own encrypted DNS", s.blockDnsBypass) { v -> vm.update { it.copy(blockDnsBypass = v) } } }
        }

        // What is filtered: browsers' pages, and which apps are left out.
        SectionHeading("Filtering")
        Group {
            row {
                ListRow("Page filtering", onClick = { open(Sub.HTTPS) }) {
                    ValueText(if (!HttpsConfig.available) "Not in this build" else if (s.httpsFiltering) "On" else "Off")
                }
            }
            row {
                ListRow("Excluded apps", onClick = { open(Sub.APPS) }) {
                    ValueText(if (s.excludedApps.isEmpty()) "None" else fmt(s.excludedApps.size))
                }
            }
        }

        SectionHeading("List updates")
        Group {
            row { ToggleRow("Automatic updates", checked = s.autoUpdate) { v -> vm.update { it.copy(autoUpdate = v) } } }
            row {
                ListRow(
                    "How often",
                    onClick = if (s.autoUpdate) ({ dialog = Dialog.INTERVAL }) else null,
                    titleColor = if (s.autoUpdate) p.text else p.muted,
                ) { ValueText(interval(s.updateIntervalHours)) }
            }
            row { ToggleRow("Wi-Fi only", checked = s.updateWifiOnly, enabled = s.autoUpdate) { v -> vm.update { it.copy(updateWifiOnly = v) } } }
            row { ToggleRow("Only while charging", checked = s.updateWhileCharging, enabled = s.autoUpdate) { v -> vm.update { it.copy(updateWhileCharging = v) } } }
        }

        SectionHeading("Log")
        Group {
            row { ToggleRow("Keep a log", "In memory only", s.logEnabled) { v -> vm.update { it.copy(logEnabled = v) } } }
            row { ListRow("Log size", onClick = { dialog = Dialog.LOG_SIZE }) { ValueText("${fmt(s.logSize)} entries") } }
            // The engine looks up apps only while the log is kept; Home's per-app counts need it too.
            row {
                ToggleRow("Show app names", if (s.logEnabled) null else "Needs the log", s.resolveApps, enabled = s.logEnabled) { v ->
                    vm.update { it.copy(resolveApps = v) }
                }
            }
            row {
                ListRow("Clear log", onClick = {
                    scope.launch {
                        val ok = vm.clearLog()
                        Toast.makeText(ctx, if (ok) "Log cleared" else "Could not clear the log", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            row { ListRow("Reset statistics", onClick = { dialog = Dialog.RESET }, titleColor = p.warn) }
        }

        SectionHeading("App")
        Group {
            row { ListRow("Theme", onClick = { dialog = Dialog.THEME }) { ValueText(s.theme.label) } }
            row { ListRow("Export settings", onClick = { exporter.launch("aduster-settings.json") }) }
            row { ListRow("Import settings", onClick = { importer.launch(arrayOf("application/json", "text/plain")) }) }
            if (undoImport) {
                row {
                    ListRow("Undo import", onClick = {
                        scope.launch {
                            val ok = vm.undoImport()
                            Toast.makeText(ctx, if (ok) "Previous settings restored" else "Could not restore them", Toast.LENGTH_SHORT).show()
                        }
                    }, titleColor = p.accent)
                }
            }
        }

        SectionHeading("Advanced")
        Group {
            row { ListRow("Diagnostics", onClick = { open(Sub.DIAGNOSTICS) }) { Chevron() } }
        }

        SectionHeading("About")
        Group {
            row { ListRow("Version") { ValueText(BuildConfig.VERSION_NAME) } }
            row { ListRow("Website", onClick = { openLink(ctx, WEBSITE) }) { ValueText(WEBSITE.removePrefix("https://")) } }
            row { ListRow("Source code", onClick = { openLink(ctx, SOURCE) }) { ValueText("GitHub") } }
            row { ListRow("Open-source licences", onClick = { dialog = Dialog.LICENCES }) }
        }
        Spacer(Modifier.height(32.dp))
    }

    preview?.let { pv ->
        AdDialog(
            "Import these settings?",
            onDismiss = { preview = null },
            confirm = "Import",
            onConfirm = {
                preview = null
                scope.launch {
                    vm.applyImport(pv.settings)
                    Toast.makeText(ctx, "Settings imported", Toast.LENGTH_SHORT).show()
                }
            },
        ) {
            pv.summary.forEach { Text(it, style = Type.small, color = p.muted) }
            Spacer(Modifier.height(8.dp))
            Text("You can undo this afterwards.", style = Type.small, color = p.muted)
        }
    }

    when (dialog) {
        Dialog.UPSTREAM -> ChoiceDialog(
            "DNS server",
            Upstreams.presets,
            Upstreams.byId(s.upstream),
            label = { it.label },
            detail = { it.detail },
            onDismiss = { dialog = null },
        ) { preset ->
            if (preset.id == "custom") {
                dialog = Dialog.CUSTOM_UPSTREAM
            } else {
                dialog = null
                vm.update { it.copy(upstream = preset.id) }
            }
        }
        Dialog.CUSTOM_UPSTREAM -> {
            var value by remember { mutableStateOf(s.customUpstream) }
            val valid = Upstreams.validCustom(value)
            AdDialog(
                "Custom DNS server",
                onDismiss = { dialog = null },
                confirm = "Use",
                confirmEnabled = valid,
                onConfirm = {
                    if (valid) {
                        dialog = null
                        vm.update { it.copy(upstream = "custom", customUpstream = value) }
                    }
                },
            ) {
                InputField(value, { value = it.trim() }, "https://dns.example/dns-query", Modifier.fillMaxWidth(), color = p.bg)
                Text("https://… for DoH, tls://… for DoT", style = Type.small, color = p.muted, modifier = Modifier.padding(start = 18.dp, top = 8.dp))
            }
        }
        Dialog.BLOCK_MODE -> ChoiceDialog("Reply to blocked lookups", BlockMode.entries, s.blockMode, { it.label }, { it.detail }, { dialog = null }) { m ->
            dialog = null
            vm.update { it.copy(blockMode = m) }
        }
        Dialog.INTERVAL -> ChoiceDialog("Check lists for updates", listOf(12, 24, 48, 168), s.updateIntervalHours, { interval(it) }, onDismiss = { dialog = null }) { h ->
            dialog = null
            vm.update { it.copy(updateIntervalHours = h) }
        }
        Dialog.LOG_SIZE -> ChoiceDialog("Log size", listOf(500, 2000, 5000, 10000), s.logSize, { "${fmt(it)} entries" }, onDismiss = { dialog = null }) { n ->
            dialog = null
            vm.update { it.copy(logSize = n) }
        }
        Dialog.THEME -> ChoiceDialog("Theme", ThemeMode.entries, s.theme, { it.label }, onDismiss = { dialog = null }) { t ->
            dialog = null
            vm.update { it.copy(theme = t) }
        }
        Dialog.RESET -> AdDialog(
            "Reset statistics?",
            onDismiss = { dialog = null },
            text = "Today's counters and the 30-day history are cleared.",
            confirm = "Reset",
            destructive = true,
            onConfirm = {
                dialog = null
                scope.launch {
                    val ok = vm.resetStats()
                    if (!ok) Toast.makeText(ctx, "Could not reset statistics", Toast.LENGTH_SHORT).show()
                }
            },
        )
        Dialog.LICENCES -> LicencesDialog { dialog = null }
        null -> {}
    }
}

private const val WEBSITE = "https://www.aduster.site"
private const val SOURCE = "https://github.com/TheWrathRF/aduster"

/** In the phone's browser; a phone without one gets a short message instead of a crash. */
private fun openLink(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        .onFailure { Toast.makeText(ctx, "No browser to open $url", Toast.LENGTH_SHORT).show() }
}

private fun interval(h: Int) = when (h) {
    24 -> "Daily"
    48 -> "Every 2 days"
    168 -> "Weekly"
    else -> "Every $h hours"
}
