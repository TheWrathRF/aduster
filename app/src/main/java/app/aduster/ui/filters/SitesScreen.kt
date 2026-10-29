package app.aduster.ui.filters

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aduster.filters.UserRules
import app.aduster.ui.BackBar
import app.aduster.ui.MainViewModel
import app.aduster.ui.common.PillButton
import app.aduster.ui.common.Gutter
import app.aduster.ui.common.Haptics
import app.aduster.ui.common.InputField
import app.aduster.ui.common.SectionHeading
import app.aduster.ui.common.groupItems
import app.aduster.ui.theme.AdIcons
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Type

/**
 * Whole sites the user allows or blocks everywhere, managed without writing rules: an allowed
 * site becomes `@@||site^` (never blocked in any app, its pages not filtered in browsers), a blocked
 * one `||site^` (blocked for every app). Both live among "My rules".
 */
@Composable
fun SitesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val sites = remember(s.userRules) { UserRules.sites(s.userRules) }
    var input by remember { mutableStateOf("") }
    val host = UserRules.siteHost(input)

    fun set(allow: Boolean) {
        val h = host ?: return
        Haptics.confirm(view)
        vm.setSite(h, allow)
        input = ""
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Sites", onBack)
        Text(
            "Allowed sites are never blocked or filtered. Blocked sites are blocked in every app.",
            style = Type.small,
            color = p.muted,
            modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
        )
        InputField(input, { input = it }, "example.com", Modifier.fillMaxWidth().padding(horizontal = Gutter), error = input.isNotBlank() && host == null)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Both wait for a valid site, so bad input never fails silently.
            PillButton("Allow", Modifier.weight(1f), filled = host != null, enabled = host != null) { set(true) }
            PillButton("Block", Modifier.weight(1f), enabled = host != null) { set(false) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "allowed") { SectionHeading("Allowed · ${sites.allowed.size}") }
            if (sites.allowed.isEmpty()) item(key = "allowed-none") { None("No allowed sites") }
            groupItems(sites.allowed, key = { "a-$it" }) { h -> SiteRow(h) { vm.setSite(h, null) } }
            item(key = "blocked") { SectionHeading("Blocked · ${sites.blocked.size}") }
            if (sites.blocked.isEmpty()) item(key = "blocked-none") { None("No blocked sites") }
            groupItems(sites.blocked, key = { "b-$it" }) { h -> SiteRow(h) { vm.setSite(h, null) } }
        }
    }
}

@Composable
private fun SiteRow(host: String, onRemove: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(host, style = Type.monoBody, color = p.text, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AdIcons.Close, "Remove $host", tint = p.muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun None(text: String) {
    Text(text, style = Type.small, color = LocalPalette.current.muted, modifier = Modifier.padding(horizontal = Gutter + 16.dp))
}
