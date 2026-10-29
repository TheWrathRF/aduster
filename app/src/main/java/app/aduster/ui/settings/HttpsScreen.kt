package app.aduster.ui.settings

import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.aduster.https.HttpsConfig
import app.aduster.ui.BackBar
import app.aduster.ui.MainViewModel
import app.aduster.ui.common.AdDialog
import app.aduster.ui.common.Card
import app.aduster.ui.common.Corners
import app.aduster.ui.common.Group
import app.aduster.ui.common.Gutter
import app.aduster.ui.common.ListRow
import app.aduster.ui.common.PillButton
import app.aduster.ui.common.RowIcon
import app.aduster.ui.common.SectionHeading
import app.aduster.ui.common.TextAction
import app.aduster.ui.common.ToggleRow
import app.aduster.ui.common.ValueText
import app.aduster.ui.theme.AdIcons
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Page filtering in browsers: what it does, the one-time certificate install, and exclusions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HttpsScreen(vm: MainViewModel, onBack: () -> Unit) {
    if (!HttpsConfig.available) return Unavailable(onBack)
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    var installed by remember { mutableStateOf<Boolean?>(null) }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var keyUnreadable by remember { mutableStateOf(false) }
    var savedAs by remember { mutableStateOf<String?>(null) }
    var recreateDialog by remember { mutableStateOf(false) }
    // Null until the stored list is read: the editor never starts from placeholder settings,
    // where "Save" would erase the list (the same guard as My rules).
    var edited by rememberSaveable(stateSaver = app.aduster.ui.filters.EditorTextSaver) { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (edited == null) edited = vm.storedSettings().httpsExclusions }
    val exclusions = edited.orEmpty()
    val dirty = edited != null && exclusions.trim() != s.httpsExclusions
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()

    // Re-check every time the user comes back from system settings.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val was = installed
            installed = withContext(Dispatchers.IO) { vm.caInstalled() }
            fingerprint = withContext(Dispatchers.IO) { vm.caFingerprint() }
            keyUnreadable = withContext(Dispatchers.IO) { vm.caKeyUnreadable() }
            savedAs = withContext(Dispatchers.IO) { vm.caSavedName() }
            // Just installed: the core re-checks at once instead of trusting its cached "no".
            if (installed == true && was != true) vm.notifyCore()
        }
    }

    // Leaving the screen saves the edits, as My rules does; not on a rotation.
    val activity = ctx as? android.app.Activity
    val latestText by rememberUpdatedState(exclusions)
    val latestDirty by rememberUpdatedState(dirty)
    DisposableEffect(Unit) {
        onDispose {
            if (latestDirty && activity?.isChangingConfigurations != true) {
                vm.update { it.copy(httpsExclusions = latestText.trim()) }
                Toast.makeText(ctx, "Never-decrypt list saved", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // Leaving the app saves too, as in My rules.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (latestDirty && activity?.isChangingConfigurations != true) vm.update { it.copy(httpsExclusions = latestText.trim()) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackBar("Page filtering", onBack)
        Intro("Removes ads inside web pages in browsers, using a certificate made on this phone.")

        SectionHeading("1 · Save the certificate")
        Card {
            // The first thing to do while the certificate is not installed.
            PillButton("Save to Downloads", Modifier.fillMaxWidth(), filled = installed == false) {
                scope.launch {
                    val name = vm.exportCa()
                    fingerprint = withContext(Dispatchers.IO) { vm.caFingerprint() }
                    if (name != null) savedAs = name
                    Toast.makeText(ctx, if (name != null) "Saved as Downloads/$name" else "Could not save the certificate", Toast.LENGTH_SHORT).show()
                }
            }
            fingerprint?.let { fp ->
                // Whole, as Android's certificate details show it, so the two can be compared; a
                // long press copies it.
                Spacer(Modifier.height(16.dp))
                Text("Certificate fingerprint (SHA-256)", style = Type.small, color = p.muted)
                Spacer(Modifier.height(4.dp))
                Text(
                    fp,
                    style = Type.mono,
                    color = p.text2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Corners.inner))
                        .combinedClickable(onClick = {}, onLongClickLabel = "Copy the fingerprint", onLongClick = {
                            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("fingerprint", fp))
                            Toast.makeText(ctx, "Fingerprint copied", Toast.LENGTH_SHORT).show()
                        }),
                )
            }
            // The old certificate is never replaced silently; only this explicit action does it.
            if (keyUnreadable) {
                Spacer(Modifier.height(12.dp))
                Text("This certificate can no longer be read. Create a new one and install it.", style = Type.small, color = p.warn)
                Spacer(Modifier.height(12.dp))
                PillButton("Create a new certificate", Modifier.fillMaxWidth()) { recreateDialog = true }
            }
        }

        SectionHeading("2 · Install it")
        Card {
            // Menu paths differ by maker: Settings' own search finds the page on every phone.
            Text(installStep(savedAs), style = Type.body, color = p.text)
            Spacer(Modifier.height(16.dp))
            PillButton("Open Settings", Modifier.fillMaxWidth()) {
                ctx.startActivity(Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        SectionHeading("3 · Turn it on")
        Group {
            row {
                ListRow(
                    "Certificate",
                    leading = { RowIcon(if (installed == true) AdIcons.Check else AdIcons.Warn, tint = if (installed == false) p.warn else p.accent) },
                ) {
                    when (installed) {
                        null -> ValueText("Checking…")
                        true -> ValueText("Installed", p.accent)
                        false -> ValueText("Not installed", p.warn)
                    }
                }
            }
            // Switching off always stays possible: with the certificate removed first, the stored
            // "on" could otherwise never be cleared.
            row {
                ToggleRow(
                    "Filter pages in browsers",
                    when {
                        installed == true -> null
                        s.httpsFiltering -> "Not working without the certificate"
                        else -> "Install the certificate first"
                    },
                    checked = s.httpsFiltering,
                    enabled = installed == true || s.httpsFiltering,
                ) { vm.setHttpsFiltering(it) }
            }
        }

        SectionHeading("Never decrypted")
        Note("Banks, payments, government, health and sign-in pages are always left alone. Add your own, one site per line.")
        BasicTextField(
            value = exclusions,
            onValueChange = { if (edited != null) edited = it },
            textStyle = Type.mono.copy(color = p.text),
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier
                .padding(horizontal = Gutter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.outer))
                .background(p.surface)
                .padding(16.dp)
                // Grows with its lines and with large fonts.
                .heightIn(min = 120.dp),
            decorationBox = { inner ->
                if (exclusions.isEmpty()) Text("mybank.example\nwork.example", style = Type.mono, color = p.muted)
                inner()
            },
        )
        // Links are fine (only the host is used); lines with no host at all are pointed out.
        val invalid = remember(exclusions) { HttpsConfig.invalidExclusionLines(exclusions) }
        if (invalid.isNotEmpty()) {
            Note("Not a domain, ignored: line ${invalid.take(8).joinToString(", ")}", p.warn)
        }
        if (dirty) {
            Row(Modifier.padding(horizontal = Gutter)) {
                Spacer(Modifier.weight(1f))
                TextAction("Save") { vm.update { it.copy(httpsExclusions = exclusions.trim()) } }
            }
        }

        SectionHeading("Notes")
        Note("Android's \"Network may be monitored\" notice refers to this certificate.")
        Note("To remove it, turn this off, then search Settings for \"User credentials\".")
        Spacer(Modifier.height(32.dp))
    }

    if (recreateDialog) {
        AdDialog(
            "Create a new certificate?",
            onDismiss = { recreateDialog = false },
            text = "The old one stops working. Remove it (search Settings for \"User credentials\") and install the new one.",
            confirm = "Create",
            destructive = true,
            onConfirm = {
                recreateDialog = false
                scope.launch {
                    val name = vm.recreateCa()
                    fingerprint = withContext(Dispatchers.IO) { vm.caFingerprint() }
                    keyUnreadable = withContext(Dispatchers.IO) { vm.caKeyUnreadable() }
                    if (name != null) savedAs = name
                    Toast.makeText(ctx, if (name != null) "New certificate saved as Downloads/$name" else "Could not create it", Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}

/**
 * The install step, naming the file as it was saved ([saved]; null before the first save): next
 * to a file an earlier install left, Downloads names it "Aduster_CA (1).crt".
 */
internal fun installStep(saved: String?): String =
    "In Settings, search for \"CA certificate\", tap Install anyway and pick ${saved ?: "Aduster_CA.crt"} from Downloads."

/** A build without the page engine: said plainly, with nothing to set up. */
@Composable
private fun Unavailable(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BackBar("Page filtering", onBack)
        Intro("Not included in this build. Browsers still get DNS filtering.")
    }
}

/** What a sub-screen is for, under its title. */
@Composable
private fun Intro(text: String) {
    Text(text, style = Type.small, color = LocalPalette.current.muted, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
}

/** A line of explanation, lined up with the text of the cards around it. */
@Composable
private fun Note(text: String, color: Color = LocalPalette.current.muted) {
    Text(text, style = Type.small, color = color, modifier = Modifier.padding(start = Gutter + 16.dp, end = Gutter + 16.dp, bottom = 8.dp))
}
