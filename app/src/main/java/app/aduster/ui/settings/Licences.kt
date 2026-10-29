package app.aduster.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.aduster.ui.common.AdDialog
import app.aduster.ui.common.Corners
import app.aduster.ui.common.Gutter
import app.aduster.ui.common.Motion
import app.aduster.ui.theme.AdIcons
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The licence texts the app ships, in a dialog from Settings: one block per file in
 * `assets/licenses/`, which opens to its text. A file named `<Component>-<Licence>.txt` is
 * headed "<Component> · <Licence>".
 */
@Composable
fun LicencesDialog(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    // Each text in paragraphs: some files are hundreds of KB, too much to lay out as one text.
    val texts by produceState(emptyList<Pair<String, List<String>>>()) {
        value = withContext(Dispatchers.IO) {
            val assets = ctx.assets
            assets.list(DIR).orEmpty().sorted().map { f ->
                licenceTitle(f) to assets.open("$DIR/$f").bufferedReader().use { it.readText() }.trimEnd().split("\n\n")
            }
        }
    }
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    AdDialog("Open-source licences", onDismiss, dismiss = "Close") {
        LazyColumn {
            // One card per licence, on the page colour as fields in a dialog are: its header,
            // and when open its paragraphs as items of their own.
            texts.forEachIndexed { n, (title, paragraphs) ->
                val expanded = open == title
                item(key = title) {
                    val shape = if (expanded) RoundedCornerShape(topStart = Corners.outer, topEnd = Corners.outer) else RoundedCornerShape(Corners.outer)
                    Box(Modifier.padding(top = if (n == 0) 0.dp else 8.dp).fillMaxWidth().clip(shape).background(p.bg)) {
                        Header(title, expanded) { open = if (expanded) null else title }
                    }
                }
                if (expanded) {
                    itemsIndexed(paragraphs, key = { i, _ -> "$title/$i" }) { i, text ->
                        val last = i == paragraphs.lastIndex
                        val shape = if (last) RoundedCornerShape(bottomStart = Corners.outer, bottomEnd = Corners.outer) else RectangleShape
                        Text(
                            if (last) text else text + "\n",
                            style = Type.mono,
                            color = p.muted,
                            modifier = Modifier.fillMaxWidth().clip(shape).background(p.bg).padding(start = Gutter, end = Gutter, bottom = if (last) Gutter else 0.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A licence's name; the chevron turns while its text is shown. */
@Composable
private fun Header(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val turn by animateFloatAsState(if (expanded) 90f else 0f, tween(Motion.MEDIUM, easing = Motion.Emphasized), label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Fold" else "Unfold", onClick = onToggle)
            .heightIn(min = 56.dp)
            .padding(horizontal = Gutter, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.body, color = p.text, modifier = Modifier.weight(1f))
        Icon(AdIcons.Chevron, null, tint = p.muted, modifier = Modifier.size(16.dp).rotate(turn))
    }
}

private const val DIR = "licenses"

/**
 * "GoogleSansCode-OFL.txt" → "Google Sans Code · OFL", "Rust libraries-Apache-2.0.txt" →
 * "Rust libraries · Apache-2.0": the component ends at the first dash (licence ids have their
 * own), and only a name without spaces is split, before each capital that starts a word
 * ("ABCLib" keeps its acronym).
 */
internal fun licenceTitle(file: String): String {
    val name = file.removeSuffix(".txt")
    val dash = name.indexOf('-')
    val raw = if (dash > 0) name.substring(0, dash) else name
    val component = if (' ' in raw) raw else raw.replace(Regex("(?<=[a-z])(?=[A-Z][a-z])"), " ")
    return if (dash > 0) component + " · " + name.substring(dash + 1) else component
}
