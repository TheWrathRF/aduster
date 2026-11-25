package app.aduster.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aduster.filters.UserRules
import app.aduster.https.HttpsConfig
import app.aduster.settings.Settings
import app.aduster.ui.MainViewModel
import app.aduster.ui.SiteCheck
import app.aduster.ui.common.Gutter
import app.aduster.ui.filters.SiteFixAction
import app.aduster.ui.filters.allowSiteRule
import app.aduster.ui.filters.browserLines
import app.aduster.ui.filters.siteFixActions
import app.aduster.ui.filters.withNeverDecrypt
import app.aduster.ui.theme.AdusterTheme
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.IDN

/** What the share sheet offers for a site, in order. */
internal enum class ShareAction(val label: String) {
    PAUSE("Don't filter for 15 minutes"),
    FILTER_AGAIN("Filter again"),
    ALLOW("Allow on this site"),
    NEVER_DECRYPT("Never decrypt"),
}

/** How long "Don't filter for 15 minutes" lasts. */
internal const val SHARE_PAUSE_MINUTES = 15

/**
 * The site of the first web address in what was shared ("Title\nhttps://…"), in its DNS form
 * (`münchen.de` gives `xn--mnchen-3ya.de`), or null. A user name before the host, an address
 * literal or a name longer than DNS allows never gives a wrong site.
 */
internal fun sharedHost(text: String?): String? {
    val url = Regex("""https?://[^\s<>"'()\[\]{}]+""", RegexOption.IGNORE_CASE).find(text ?: return null)?.value ?: return null
    val name = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').substringBefore(':').trimEnd('.', ',', ';', '!')
    val ascii = runCatching { IDN.toASCII(name) }.getOrNull() ?: return null
    val host = UserRules.siteHost(ascii) ?: return null
    if (host.length > 253 || host.split('.').all { label -> label.all(Char::isDigit) }) return null
    return host
}

/**
 * The sheet's actions: a 15-minute pause while the page is being filtered right now, then the
 * actions "Fix a site" offers (Filter again, Allow on this site, Never decrypt).
 */
internal fun shareActions(host: String, settings: Settings, site: SiteCheck?, pagesAvailable: Boolean, userExcluded: Boolean): List<ShareAction> = buildList {
    // A site the user allowed is not filtered in pages: nothing to pause.
    val filtered = site != null && site.decrypted && site.filteringActive && site.reasons.none { it.kind == "user_allowed" }
    if (pagesAvailable && settings.httpsFiltering && filtered) add(ShareAction.PAUSE)
    siteFixActions(host, settings, site, pagesAvailable, userExcluded).forEach {
        add(
            when (it) {
                SiteFixAction.FILTER_AGAIN -> ShareAction.FILTER_AGAIN
                SiteFixAction.ALLOW -> ShareAction.ALLOW
                SiteFixAction.NEVER_DECRYPT -> ShareAction.NEVER_DECRYPT
            },
        )
    }
}

/** What the sheet says when it has nothing to offer. */
internal fun nothingToChange(settings: Settings, pagesAvailable: Boolean): String =
    if (pagesAvailable && settings.httpsFiltering) "Nothing to change: this site is allowed and never decrypted" else "Nothing to change: this site is allowed"

/**
 * Share › Aduster from a browser: a sheet over the page with what can be done for its site. The
 * actions are the app's own (a timed pause the engine ends by itself, the user's rules, the
 * never-decrypt list); nothing here is a new switch.
 */
class ShareActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shared text is a CharSequence (styled from some apps); an odd extra must not crash the sheet.
        val shared = runCatching { intent?.takeIf { it.action == Intent.ACTION_SEND }?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull()
        val host = sharedHost(shared)
        if (host == null) {
            Toast.makeText(this, "No web address in what was shared", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            AdusterTheme(settings.theme) {
                ShareSheet(vm, host, settings, onDone = { text ->
                    if (text != null) Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
                    finish()
                })
            }
        }
    }

    // Like the main window: bound while visible, so the sheet can ask the protection process
    // about the site and pause it (unbound, every answer was "page filtering is not running").
    override fun onStart() {
        super.onStart()
        vm.client.bind()
    }

    override fun onStop() {
        vm.client.unbind()
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(vm: MainViewModel, host: String, settings: Settings, onDone: (String?) -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var site by remember { mutableStateOf<SiteCheck?>(null) }
    var checked by remember { mutableStateOf(false) }
    // The actions depend on the stored settings, not the placeholder shown before they are read.
    val read by vm.settingsRead.collectAsStateWithLifecycle()
    LaunchedEffect(host) {
        site = withContext(Dispatchers.IO) {
            vm.awaitCore()
            vm.checkSite(host)
        }
        checked = true
    }
    val userExcluded = vm.userExcludes(host, settings)
    // One action at a time: a second tap while the first saves would act (and say so) twice.
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { onDone(null) }, containerColor = p.surface, dragHandle = null) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = 16.dp).navigationBarsPadding()) {
            Text(host, style = Type.dialogTitle, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Gutter + 4.dp, vertical = 4.dp))
            if (!checked || !read) return@Column
            browserLines(site, settings, HttpsConfig.available, userExcluded).forEach { (line, warn) ->
                Text(line, style = Type.small, color = if (warn) p.warn else p.muted, modifier = Modifier.padding(horizontal = Gutter + 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            val actions = shareActions(host, settings, site, HttpsConfig.available, userExcluded)
            if (actions.isEmpty()) {
                Text(nothingToChange(settings, HttpsConfig.available), style = Type.body, color = p.muted, modifier = Modifier.padding(horizontal = Gutter + 4.dp, vertical = 14.dp))
            }
            actions.forEach { action ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy, role = Role.Button) {
                            busy = true
                            when (action) {
                                ShareAction.PAUSE -> scope.launch {
                                    val ok = vm.pauseSite(host, SHARE_PAUSE_MINUTES)
                                    onDone(if (ok) "$host is not filtered for $SHARE_PAUSE_MINUTES minutes" else "Page filtering is not running")
                                }
                                ShareAction.FILTER_AGAIN -> scope.launch {
                                    val ok = vm.unbypass(host)
                                    onDone(if (ok) "$host is filtered again" else "$host was not bypassed any more")
                                }
                                // Saved before the sheet closes: closing ends the view model's work.
                                // Not saved (a full disk): the app already said so, nothing more.
                                ShareAction.ALLOW -> scope.launch {
                                    val ok = vm.addUserRule(allowSiteRule(host)).await()
                                    onDone(if (ok) "$host allowed. Filters › Custom lists it" else null)
                                }
                                ShareAction.NEVER_DECRYPT -> scope.launch {
                                    val ok = vm.update { it.copy(httpsExclusions = withNeverDecrypt(it.httpsExclusions, host)) }.await()
                                    onDone(if (ok) "$host is no longer decrypted" else null)
                                }
                            }
                        }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = Gutter + 4.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(action.label, style = Type.body, color = p.text)
                }
            }
        }
    }
}
