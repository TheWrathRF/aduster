package app.aduster.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aduster.filters.UpdatePhase
import app.aduster.ui.theme.LocalPalette
import app.aduster.ui.theme.Type

/**
 * Share of the progress strip given to each phase (sums to 1). When page filtering is off there is
 * no page-build phase, so list checking and DNS compile take the whole strip.
 */
internal fun phaseWeights(withPages: Boolean): Map<UpdatePhase, Float> = if (withPages) {
    linkedMapOf(
        UpdatePhase.CHECKING to 0.45f,
        UpdatePhase.APPLYING_DNS to 0.15f,
        UpdatePhase.BUILDING_PAGES to 0.40f,
    )
} else {
    linkedMapOf(
        UpdatePhase.CHECKING to 0.75f,
        UpdatePhase.APPLYING_DNS to 0.25f,
    )
}

/** Label for an active update phase; never names an individual list since downloads run in parallel. */
internal fun progressLabel(phase: UpdatePhase, done: Int, total: Int): String = when (phase) {
    UpdatePhase.WAITING_NETWORK -> "Waiting for network"
    UpdatePhase.CHECKING -> if (total > 0) "Checking lists: ${done.coerceIn(0, total)} of $total" else "Checking lists"
    UpdatePhase.APPLYING_DNS -> "Applying DNS rules"
    UpdatePhase.BUILDING_PAGES -> "Building page rules"
}

/**
 * Overall determinate fraction in 0..1, or null when the bar should be indeterminate (waiting for
 * network, before counts are known, or while the native page compiler runs with no inner progress).
 */
internal fun progressFraction(phase: UpdatePhase, done: Int, total: Int, withPages: Boolean): Float? {
    val w = phaseWeights(withPages)
    return when (phase) {
        UpdatePhase.WAITING_NETWORK -> null
        UpdatePhase.CHECKING -> if (total <= 0) null else (done.coerceIn(0, total).toFloat() / total) * (w[UpdatePhase.CHECKING] ?: 0f)
        UpdatePhase.APPLYING_DNS -> (w[UpdatePhase.CHECKING] ?: 0f) + (w[UpdatePhase.APPLYING_DNS] ?: 0f) * 0.5f
        UpdatePhase.BUILDING_PAGES -> null
    }
}

/** Warning line naming the visible lists that failed, truncated when many fail at once. */
internal fun failedSummary(failed: List<String>, maxNames: Int = 3): String? {
    if (failed.isEmpty()) return null
    val shown = failed.take(maxNames).joinToString(", ")
    return if (failed.size <= maxNames) {
        "Failed: $shown"
    } else {
        "Failed (${failed.size}): $shown and ${failed.size - maxNames} more"
    }
}

/**
 * Phased progress strip for list updates and compiles. Takes plain values so any screen (Filters,
 * Page filtering) can pin it without coupling to job state.
 */
@Composable
fun ProgressStrip(
    phase: UpdatePhase?,
    done: Int,
    total: Int,
    failed: List<String> = emptyList(),
    withPages: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val visible = phase != null || failed.isNotEmpty()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.MEDIUM)),
        exit = fadeOut(tween(Motion.LONG)),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Gutter + 4.dp, vertical = 6.dp)) {
            if (phase != null) {
                Text(progressLabel(phase, done, total), style = Type.small, color = p.accent)
                Spacer(Modifier.height(6.dp))
                val fraction = progressFraction(phase, done, total, withPages)
                if (fraction != null) {
                    DeterminateBar(fraction, fill = p.accent, track = p.surfaceHigh)
                } else {
                    IndeterminateBar(fill = p.accent, track = p.surfaceHigh)
                }
                failedSummary(failed)?.let { text ->
                    Spacer(Modifier.height(4.dp))
                    Text(text, style = Type.small, color = p.warn)
                }
            } else {
                failedSummary(failed)?.let { text ->
                    Text(text, style = Type.small, color = p.warn)
                }
            }
        }
    }
}

@Composable
private fun DeterminateBar(fraction: Float, fill: Color, track: Color) {
    val f by animateFloatAsState(
        fraction.coerceIn(0.04f, 1f),
        tween(Motion.LONG, easing = Motion.Emphasized),
        label = "update progress",
    )
    Canvas(Modifier.fillMaxWidth().height(3.dp)) {
        val r = size.height / 2f
        val radius = CornerRadius(r, r)
        drawRoundRect(track, cornerRadius = radius)
        drawRoundRect(fill, size = Size(size.width * f, size.height), cornerRadius = radius)
    }
}

@Composable
private fun IndeterminateBar(fill: Color, track: Color) {
    val transition = rememberInfiniteTransition(label = "indeterminate")
    val pos by transition.animateFloat(
        initialValue = -0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "progress sweep",
    )
    Canvas(Modifier.fillMaxWidth().height(3.dp)) {
        val r = size.height / 2f
        val radius = CornerRadius(r, r)
        drawRoundRect(track, cornerRadius = radius)
        val start = (pos * size.width).coerceIn(0f, size.width)
        val end = ((pos + 0.35f) * size.width).coerceIn(0f, size.width)
        if (end > start) {
            drawRoundRect(fill, topLeft = Offset(start, 0f), size = Size(end - start, size.height), cornerRadius = radius)
        }
    }
}
