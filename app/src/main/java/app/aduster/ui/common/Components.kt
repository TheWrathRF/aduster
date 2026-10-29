package app.aduster.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aduster.ui.theme.AdIcons
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Type

val Gutter = 16.dp

/** Corner radii on the website's scale: standalone cards and dialogs 24, grouped rows 18 outside and 8 where they meet. */
object Corners {
    val card = 24.dp
    val outer = 18.dp
    val inner = 8.dp
}

/** Segment [index] of a group of [count]: round outside, tighter where segments meet; one alone is round all over. */
fun segmentShape(index: Int, count: Int): Shape {
    val top = if (index == 0) Corners.outer else Corners.inner
    val bottom = if (index == count - 1) Corners.outer else Corners.inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** A standalone card: 24 dp corners, 20 dp padding, inside the screen gutter. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.surface,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .padding(horizontal = Gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.card))
            .background(color)
            .padding(20.dp),
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

class GroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/**
 * Rows that belong together, as one card split into segments 2 dp apart (see [segmentShape]).
 * Each [GroupScope.row] is one segment; rows added under a condition count only when present.
 */
@Composable
fun Group(modifier: Modifier = Modifier, content: GroupScope.() -> Unit) {
    val p = LocalPalette.current
    val rows = GroupScope().apply(content).rows
    Column(modifier.padding(horizontal = Gutter).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, row ->
            Box(Modifier.fillMaxWidth().clip(segmentShape(i, rows.size)).background(p.surface)) { row() }
        }
    }
}

/**
 * The lazy form of [Group], for lists that can grow long (top lists, the log, sites): each item
 * is one segment, drawn only while on screen.
 */
fun <T> LazyListScope.groupItems(items: List<T>, key: (T) -> Any, content: @Composable LazyItemScope.(T) -> Unit) {
    itemsIndexed(items, key = { _, it -> key(it) }) { i, item ->
        GroupSegment(i, items.size, Modifier.animateItem()) { content(item) }
    }
}

/** One segment of a group: its corners from its place, 2 dp below it unless last. */
@Composable
fun GroupSegment(index: Int, count: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier
            .padding(start = Gutter, end = Gutter, bottom = if (index < count - 1) 2.dp else 0.dp)
            .fillMaxWidth()
            .clip(segmentShape(index, count))
            .background(p.surface),
    ) { content() }
}

/** One row as a standalone card (24 dp corners), as each list on Filters: no hairline, 8 dp below. */
@Composable
fun RowCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier
            .padding(start = Gutter, end = Gutter, bottom = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.card))
            .background(p.surface),
    ) { content() }
}

/**
 * Pill chips, one selected (filled), the others outlined: Filters' three views, the Log's
 * filters and the kind of list in Add a list. The row scrolls sideways when the chips do not
 * fit (large fonts). [inset]: the screen gutter, or none inside a dialog.
 */
@Composable
fun ChipTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, inset: Dp = Gutter, trailing: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPalette.current
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = inset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            val fill by animateColorAsState(if (on) p.surfaceHigh else Color.Transparent, tween(Motion.MEDIUM), label = "chip fill")
            val ink by animateColorAsState(if (on) p.text else p.muted, tween(Motion.MEDIUM), label = "chip ink")
            Text(
                o,
                style = Type.bodyStrong.copy(fontSize = Type.heading.fontSize),
                color = ink,
                maxLines = 1,
                modifier = Modifier
                    // A 48 dp touch target around the 36 dp chip.
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(fill)
                    .then(if (on) Modifier else Modifier.border(1.dp, p.line, CircleShape))
                    .selectable(selected = on, role = Role.Tab) { onSelect(i) }
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        trailing()
    }
}

/** A row's icon in a quiet circle, in the accent (or muted while the row is unavailable). */
@Composable
fun RowIcon(icon: ImageVector, enabled: Boolean = true, tint: Color = LocalPalette.current.accent) {
    val p = LocalPalette.current
    Box(Modifier.size(36.dp).clip(CircleShape).background(p.surfaceHigh), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (enabled) tint else p.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = Gutter + 4.dp, end = 8.dp, top = 24.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.screenTitle, color = LocalPalette.current.text, modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
    }
}

/** A group's heading, in sentence case, lined up with the text of the group's rows. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Type.heading,
        color = LocalPalette.current.text2,
        modifier = modifier
            .padding(start = Gutter + 16.dp, end = Gutter + 16.dp, top = 22.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** A [SectionHeading] that folds its rows away, with how many are on; the chevron turns with it. */
@Composable
fun FoldLabel(text: String, expanded: Boolean, summary: String? = null, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val turn by animateFloatAsState(if (expanded) 90f else 0f, tween(Motion.MEDIUM, easing = Motion.Emphasized), label = "chevron")
    Row(
        Modifier
            .padding(horizontal = Gutter)
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(Corners.inner))
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Fold" else "Unfold", onClick = onToggle)
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.heading, color = p.text2, modifier = Modifier.weight(1f).semantics { heading() })
        if (summary != null) Text(summary, style = Type.small, color = p.muted)
        Icon(AdIcons.Chevron, null, tint = p.muted, modifier = Modifier.padding(start = 8.dp).size(16.dp).rotate(turn))
    }
}

/**
 * The one list row used everywhere, as a segment of a [Group] or a [RowCard] (the card's gaps
 * separate rows): optional icon, title, optional detail, optional trailing control.
 */
@Composable
fun ListRow(
    title: String,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    titleColor: Color = LocalPalette.current.text,
    leading: (@Composable () -> Unit)? = null,
    detailColor: Color = LocalPalette.current.muted,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = Gutter, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = titleColor)
            if (detail != null) {
                Spacer(Modifier.height(2.dp))
                Text(detail, style = Type.small, color = detailColor)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}

/** An app's icon (28 dp), or a quiet placeholder on the row's card while it loads. */
@Composable
fun AppIcon(icon: ImageBitmap?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    if (icon != null) {
        Image(icon, null, modifier.size(28.dp))
    } else {
        Box(modifier.size(28.dp).clip(CircleShape).background(p.surfaceHigh))
    }
}

@Composable
fun Chevron() = Icon(AdIcons.Chevron, null, tint = LocalPalette.current.faint, modifier = Modifier.size(18.dp))

@Composable
fun ToggleRow(
    title: String,
    detail: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onChange: (Boolean) -> Unit,
) {
    val view = LocalView.current
    // One switch element for TalkBack, named by its row: the row toggles, the switch only shows.
    Box(
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch) { Haptics.toggle(view, it); onChange(it) },
    ) {
        ListRow(title, detail, onClick = null, leading = leading, titleColor = if (enabled) LocalPalette.current.text else LocalPalette.current.muted) {
            AdusterSwitch(checked, enabled, onChange = null)
        }
    }
}

/**
 * [onChange] null: the switch only shows the state (its row toggles it). A switch that acts on
 * its own, beside a row that does something else, needs a [label] for TalkBack.
 */
@Composable
fun AdusterSwitch(checked: Boolean, enabled: Boolean = true, label: String? = null, onChange: ((Boolean) -> Unit)?) {
    val p = LocalPalette.current
    val view = LocalView.current
    Switch(
        checked = checked,
        onCheckedChange = onChange?.let { f -> { v: Boolean -> Haptics.toggle(view, v); f(v) } },
        modifier = if (label != null) Modifier.semantics { contentDescription = label } else Modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = p.onAccent,
            checkedTrackColor = p.accent,
            checkedBorderColor = p.accent,
            uncheckedThumbColor = p.muted,
            uncheckedTrackColor = p.bg,
            uncheckedBorderColor = p.faint,
        ),
    )
}

@Composable
fun ValueText(text: String, color: Color = LocalPalette.current.muted) {
    Text(text, style = Type.small, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
}

/**
 * A pill button, at least [height] tall. [filled] for the single primary action on a screen
 * (accent fill); otherwise a 1 dp outline. [color] fills it with another colour (amber for
 * Resume). [onLongClick] offers more choices on a long press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    color: Color? = null,
    height: Dp = 48.dp,
    onLongClick: (() -> Unit)? = null,
    /** What the long press does, for TalkBack (an unnamed long press is undiscoverable). */
    onLongClickLabel: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val view = LocalView.current
    val fill by animateColorAsState(if (filled) color ?: p.accent else Color.Transparent, tween(Motion.LONG, easing = Motion.Emphasized), label = "button fill")
    val ink by animateColorAsState(if (!enabled) p.muted else if (filled) p.onAccent else p.text, tween(Motion.LONG, easing = Motion.Emphasized), label = "button ink")
    Box(
        modifier
            .clip(CircleShape)
            .background(fill)
            .then(if (filled) Modifier else Modifier.border(1.dp, p.line, CircleShape))
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick?.let { f -> { Haptics.longPress(view); f() } },
            )
            .heightIn(min = height)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.bodyStrong, color = ink)
    }
}

/** A read-out: rolling number above its label. */
@Composable
fun Metric(value: String, label: String, modifier: Modifier = Modifier, accent: Boolean = false, raw: Long = 0, animate: Boolean = true) {
    val p = LocalPalette.current
    val color by animateColorAsState(if (accent) p.accent else p.text, tween(Motion.LONG), label = "metric color")
    // Read as one value ("Blocked: 1,234"), not digit by digit.
    Column(modifier.clearAndSetSemantics { contentDescription = "$label: $value" }) {
        RollingNumber(value, Type.metric, color, value = raw, animate = animate)
        Text(label, style = Type.small, color = p.muted)
    }
}

/**
 * Every dialog: a title, an optional line of [text] and/or [content], and pill buttons: [dismiss]
 * outlined, [confirm] filled (amber when [destructive]). A confirm that is not possible yet is
 * outlined and does nothing.
 */
@Composable
fun AdDialog(
    title: String,
    onDismiss: () -> Unit,
    text: String? = null,
    confirm: String? = null,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    dismiss: String = "Cancel",
    onConfirm: () -> Unit = {},
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text(title, style = Type.dialogTitle, color = p.text) },
        text = if (text == null && content == null) null else ({
            Column {
                if (text != null) Text(text, style = Type.small, color = p.muted)
                if (text != null && content != null) Spacer(Modifier.height(16.dp))
                content?.invoke(this)
            }
        }),
        confirmButton = {
            if (confirm != null) {
                PillButton(confirm, filled = confirmEnabled, color = if (destructive) p.warn else null, enabled = confirmEnabled, onClick = onConfirm)
            }
        },
        dismissButton = { PillButton(dismiss, onClick = onDismiss) },
    )
}

/** Single choice: a row per option, the selected one on a pill with its radio filled. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    detail: (T) -> String? = { null },
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    val p = LocalPalette.current
    AdDialog(title, onDismiss, dismiss = "Close") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(options) { o ->
                val on = o == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Corners.outer))
                        .background(if (on) p.surfaceHigh else Color.Transparent)
                        .selectable(selected = on, role = Role.RadioButton) { onSelect(o) }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(20.dp).border(2.dp, if (on) p.accent else p.faint, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(p.accent))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(label(o), style = Type.body, color = p.text)
                        detail(o)?.let { Text(it, style = Type.small, color = p.muted) }
                    }
                }
            }
        }
    }
}

/** A one-line pill field: on the card colour on screens, on the page colour ([color]) inside a dialog's card. */
@Composable
fun InputField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.body,
    icon: ImageVector? = null,
    error: Boolean = false,
    color: Color = LocalPalette.current.surface,
) {
    val p = LocalPalette.current
    val edge by animateColorAsState(if (error) p.warn else Color.Transparent, tween(Motion.MEDIUM), label = "field edge")
    Row(
        modifier
            .clip(CircleShape)
            .background(color)
            .border(1.dp, edge, CircleShape)
            .heightIn(min = 48.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = p.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = style, color = p.muted)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = style.copy(color = p.text),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (icon != null && value.isNotEmpty()) {
            Icon(
                AdIcons.Close, "Clear", tint = p.muted,
                modifier = Modifier.padding(start = 8.dp).minimumInteractiveComponentSize().clip(CircleShape).clickable(role = Role.Button) { onChange("") }.padding(2.dp).size(18.dp),
            )
        }
    }
}

@Composable
fun TextAction(text: String, color: Color = LocalPalette.current.accent, onClick: () -> Unit) {
    Text(
        text,
        style = Type.bodyStrong,
        color = color,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
