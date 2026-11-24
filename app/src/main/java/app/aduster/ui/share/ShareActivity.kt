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

/** What the share sheet offers for a site, in order. */
internal enum class ShareAction(val label: String) {
    PAUSE("Don't filter for 15 minutes"),
    FILTER_AGAIN("Filter again"),
    ALLOW("Allow on this site"),
    NEVER_DECRYPT("Never decrypt"),
}

/** How long "Don't filter for 15 minutes" lasts. */
internal const val SHARE_PAUSE_MINUTES = 15

/** The site of the first web address in what was shared ("Title\nhttps://…"), or null. */
internal fun sharedHost(text: String?): String? {
    val url = Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(text ?: return null)?.value ?: return null
    return UserRules.siteHost(url)
}

/**
 * The sheet's actions: a 15-minute pause while the page is being filtered right now, then the
 * actions "Fix a site" offers (Filter again, Allow on this site, Never decrypt).
 */
internal fun shareActions(host: String, settings: Settings, site: SiteCheck?, pagesAvailable: Boolean, userExcluded: Boolean): List<ShareAction> = buildList {
    if (pagesAvailable && settings.httpsFiltering && site != null && site.decrypted && site.filteringActive) add(ShareAction.PAUSE)
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

/**
 * Share › Aduster from a browser: a sheet over the page with what can be done for its site. The
 * actions are the app's own (a timed pause the engine ends by itself, the user's rules, the
 * never-decrypt list); nothing here is a new switch.
 */
class ShareActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = sharedHost(intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT))
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
        site = withContext(Dispatchers.IO) { vm.checkSite(host) }
        checked = true
    }
    val userExcluded = vm.userExcludes(host, settings)
    ModalBottomSheet(onDismissRequest = { onDone(null) }, containerColor = p.surface, dragHandle = null) {
        Column(Modifier.padding(top = 20.dp, bottom = 16.dp).navigationBarsPadding()) {
            Text(host, style = Type.dialogTitle, color = p.text, modifier = Modifier.padding(horizontal = Gutter + 4.dp, vertical = 4.dp))
            if (!checked || !read) return@Column
            browserLines(site, settings, HttpsConfig.available, userExcluded).forEach { (line, warn) ->
                Text(line, style = Type.small, color = if (warn) p.warn else p.muted, modifier = Modifier.padding(horizontal = Gutter + 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            val actions = shareActions(host, settings, site, HttpsConfig.available, userExcluded)
            if (actions.isEmpty()) {
                Text("Nothing to change: this site is allowed and never decrypted", style = Type.body, color = p.muted, modifier = Modifier.padding(horizontal = Gutter + 4.dp, vertical = 14.dp))
            }
            actions.forEach { action ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
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
                                ShareAction.ALLOW -> scope.launch {
                                    vm.addUserRule(allowSiteRule(host)).join()
                                    onDone("$host allowed. Filters › Custom lists it")
                                }
                                ShareAction.NEVER_DECRYPT -> scope.launch {
                                    vm.update { it.copy(httpsExclusions = withNeverDecrypt(it.httpsExclusions, host)) }.join()
                                    onDone("$host is no longer decrypted")
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
