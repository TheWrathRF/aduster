package app.aduster.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aduster.ui.common.Motion
import app.aduster.ui.filters.FiltersScreen
import app.aduster.ui.filters.RulesScreen
import app.aduster.ui.filters.SitesScreen
import app.aduster.ui.home.HomeScreen
import app.aduster.ui.log.LogScreen
import app.aduster.ui.settings.AppsScreen
import app.aduster.ui.settings.DiagnosticsScreen
import app.aduster.ui.settings.HttpsScreen
import app.aduster.ui.settings.SettingsScreen
import app.aduster.ui.theme.AdIcons
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Home", AdIcons.Home),
    LOG("Log", AdIcons.Log),
    FILTERS("Filters", AdIcons.Filters),
    SETTINGS("Settings", AdIcons.Settings),
}

enum class Sub { RULES, SITES, APPS, DIAGNOSTICS, HTTPS }

/** Where the app is: a tab, and a small stack of sub-screens on top of it. */
data class Nav(val tab: Tab, val stack: List<Sub> = emptyList())

private val NavSaver = listSaver<Nav, String>(
    save = { n -> listOf(n.tab.name) + n.stack.map { it.name } },
    restore = { l -> Nav(Tab.valueOf(l.first()), l.drop(1).map(Sub::valueOf)) },
)

/**
 * Two-level navigation without a nav library. Tabs cross-fade ("fade through"); sub-screens
 * come in from the right and leave to the right along one axis, and the back gesture drags the
 * sub-screen away with the finger (predictive back), releasing finishes or restores it.
 */
@Composable
fun AdusterApp(vm: MainViewModel, onStart: () -> Unit) {
    val p = LocalPalette.current
    var nav by rememberSaveable(stateSaver = NavSaver) { mutableStateOf(Nav(Tab.HOME)) }
    val seek = remember { SeekableTransitionState(nav) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(nav) { if (seek.targetState != nav || seek.currentState != nav) seek.animateTo(nav) }

    // Each screen keeps its saved state (Log filters, scroll place) while another is shown.
    val holder = rememberSaveableStateHolder()
    // A sub-screen that is left forgets its saved state: an editor coming back with its old text
    // would save it over rules added elsewhere in the meantime. A rotation or a process death
    // while it is open still keeps it.
    val pop: (Nav) -> Nav = { from ->
        from.stack.lastOrNull()?.let { holder.removeState(it.toString()) }
        from.copy(stack = from.stack.dropLast(1))
    }
    val open: (Sub) -> Unit = { nav = nav.copy(stack = nav.stack + it) }
    val back: () -> Unit = { if (nav.stack.isNotEmpty()) nav = pop(nav) }

    PredictiveBackHandler(enabled = nav.stack.isNotEmpty()) { events ->
        val from = nav
        val target = from.copy(stack = from.stack.dropLast(1))
        try {
            events.collect { e -> seek.seekTo(e.progress, target) }
            seek.animateTo(target)
            nav = pop(from)
        } catch (e: CancellationException) {
            // Gesture abandoned: the screen slides back into place.
            scope.launch { seek.animateTo(from) }
        }
    }
    BackHandler(enabled = nav.stack.isEmpty() && nav.tab != Tab.HOME) { nav = Nav(Tab.HOME) }

    val density = LocalDensity.current
    val shift = with(density) { 30.dp.roundToPx() }
    val transition = rememberTransition(seek, label = "nav")

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .statusBarsPadding(),
    ) {
        transition.AnimatedContent(
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val push = targetState.stack.size > initialState.stack.size
                val pop = targetState.stack.size < initialState.stack.size
                val slide = tween<androidx.compose.ui.unit.IntOffset>(Motion.LONG, easing = Motion.Emphasized)
                when {
                    push -> (slideInHorizontally(slide) { shift } + fadeIn(tween(Motion.MEDIUM, delayMillis = Motion.SHORT))) togetherWith
                        (slideOutHorizontally(slide) { -shift } + fadeOut(tween(Motion.SHORT)))
                    // Also what the back gesture scrubs through, so both halves last the whole way.
                    pop -> (slideInHorizontally(slide) { -shift } + fadeIn(tween(Motion.LONG))) togetherWith
                        (slideOutHorizontally(slide) { shift * 2 } + fadeOut(tween(Motion.LONG)))
                    else -> Motion.fadeThrough()
                }.apply { targetContentZIndex = if (pop) -1f else 0f }
            },
            contentKey = { it.stack.lastOrNull() ?: it.tab },
        ) { n ->
            Box(Modifier.fillMaxSize().background(p.bg)) {
                holder.SaveableStateProvider((n.stack.lastOrNull() ?: n.tab).toString()) {
                when (n.stack.lastOrNull()) {
                    Sub.RULES -> RulesScreen(vm, back)
                    Sub.SITES -> SitesScreen(vm, back)
                    Sub.APPS -> AppsScreen(vm, back)
                    Sub.DIAGNOSTICS -> DiagnosticsScreen(vm, back)
                    Sub.HTTPS -> HttpsScreen(vm, back)
                    null -> when (n.tab) {
                        Tab.HOME -> HomeScreen(vm, onStart, open, openSettings = { nav = Nav(Tab.SETTINGS) }, openFilters = { nav = Nav(Tab.FILTERS) })
                        Tab.LOG -> LogScreen(vm)
                        Tab.FILTERS -> FiltersScreen(vm, open)
                        Tab.SETTINGS -> SettingsScreen(vm, open)
                    }
                }
                }
            }
        }
        if (nav.stack.isEmpty()) {
            NavBar(nav.tab) { t -> if (t != nav.tab) nav = Nav(t) }
        } else {
            Spacer(Modifier.navigationBarsPadding().width(0.dp))
        }
    }
}

/** The tabs on a card-coloured bar; a pill sits behind the selected tab's icon. */
@Composable
private fun NavBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.surface)
            .navigationBarsPadding()
            // At least 72 dp, taller when large fonts need it (each tab fills the height).
            .heightIn(min = 72.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t -> NavItem(t, selected = t == selected, Modifier.weight(1f)) { onSelect(t) } }
    }
}

@Composable
private fun NavItem(t: Tab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val ink by animateColorAsState(if (selected) p.text else p.muted, tween(Motion.MEDIUM), label = "tab ink")
    val iconInk by animateColorAsState(if (selected) p.accent else p.muted, tween(Motion.MEDIUM), label = "tab icon")
    val pill by animateFloatAsState(if (selected) 1f else 0f, tween(Motion.LONG, easing = Motion.Emphasized), label = "tab pill")
    Column(
        modifier
            .fillMaxHeight()
            // Selected state for TalkBack.
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(width = 56.dp, height = 32.dp), contentAlignment = Alignment.Center) {
            // Grows out from the icon as the tab is selected.
            Box(Modifier.matchParentSize().graphicsLayer { scaleX = 0.5f + pill / 2; alpha = pill }.clip(CircleShape).background(p.surfaceHigh))
            Icon(t.icon, null, tint = iconInk, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(t.title, style = Type.nav.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal), color = ink)
    }
}

@Composable
fun BackBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AdIcons.Back, "Back", tint = p.text, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = Type.title, color = p.text, modifier = Modifier.weight(1f))
        trailing()
    }
}
