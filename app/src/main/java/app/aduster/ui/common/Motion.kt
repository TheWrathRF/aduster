package app.aduster.ui.common

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Motion rules of the app: movement only explains something (a state changed, a screen came, a
 * number updated). Short (≤ 350 ms), no endless animations, nothing while idle. Compose follows
 * the system animation scale, and "remove animations" turns all of it off.
 */
object Motion {
    /** Material's emphasized easing: quick start, soft landing. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val SHORT = 90
    const val MEDIUM = 210
    const val LONG = 300

    /** Tabs: the old screen fades out quickly, the new one fades in and settles from 98 %. */
    fun fadeThrough(): ContentTransform =
        (fadeIn(tween(MEDIUM, delayMillis = SHORT, easing = Emphasized)) + scaleIn(tween(MEDIUM, delayMillis = SHORT, easing = Emphasized), initialScale = 0.98f)) togetherWith
            fadeOut(tween(SHORT, easing = EmphasizedAccelerate))
}

/** Haptic confirmation for state changes the user made (pause, resume, switches). */
object Haptics {
    fun confirm(view: View) = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    fun toggle(view: View, on: Boolean) = view.performHapticFeedback(if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
    fun tick(view: View) = view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    fun longPress(view: View) = view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

/**
 * A number that rolls: each changed character slides in from below (value up) or above (value
 * down) while the old one leaves; unchanged characters stay still. Characters are matched from
 * the right, so units and tens stay in their columns (the font is monospaced).
 */
@Composable
fun RollingNumber(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, value: Long = 0, animate: Boolean = true) {
    val lastValue = androidx.compose.runtime.remember { longArrayOf(value) }
    val up = value >= lastValue[0]
    lastValue[0] = value
    // The first real value after "not known yet" appears at once: motion only explains a change,
    // and rolling every digit up from 0 on each app open would explain none.
    if (!animate) {
        Text(text, style = style, color = color, maxLines = 1, modifier = modifier)
        return
    }
    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        val width = text.length
        for (i in 0 until width) {
            val ch = text[i]
            // Keyed by position from the right, so a new leading digit does not shift the others.
            androidx.compose.runtime.key(width - i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val dir = if (up) 1 else -1
                        (slideInVertically(tween(180, easing = Motion.Emphasized)) { h -> dir * h / 2 } + fadeIn(tween(180))) togetherWith
                            (slideOutVertically(tween(140, easing = Motion.EmphasizedAccelerate)) { h -> -dir * h / 2 } + fadeOut(tween(120)))
                    },
                    label = "digit",
                ) { c -> Text(c.toString(), style = style, color = color, maxLines = 1) }
            }
        }
    }
}

enum class Protection { ON, PAUSED, OFF }

/**
 * The protection read-out, drawn from the logo: its ring. On: a closed ring in the accent with
 * the logo's bar in the middle. Paused: a gap opens in the ring (amber). Off: a thin, faint ring.
 */
@Composable
fun ProtectionRing(state: Protection, accent: Color, warn: Color, faint: Color, modifier: Modifier = Modifier, size: Dp = 76.dp) {
    val ringColor by animateColorAsState(
        when (state) {
            Protection.ON -> accent
            Protection.PAUSED -> warn
            Protection.OFF -> faint
        },
        tween(Motion.LONG, easing = Motion.Emphasized), label = "ring color",
    )
    val stroke by animateFloatAsState(if (state == Protection.OFF) 0.035f else 0.12f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow), label = "ring stroke")
    val gap by animateFloatAsState(if (state == Protection.PAUSED) 64f else 0f, tween(Motion.LONG, easing = Motion.Emphasized), label = "ring gap")
    val bar by animateFloatAsState(if (state == Protection.ON) 1f else 0f, tween(Motion.LONG, easing = Motion.Emphasized), label = "ring bar")
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension
        val sw = w * stroke
        val inset = w * 0.06f + sw / 2
        val arcSize = Size(w - inset * 2, w - inset * 2)
        // The gap opens at the top right, where the logo's stem meets the ring.
        drawArc(
            color = ringColor,
            startAngle = -60f + gap / 2,
            sweepAngle = 360f - gap,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = sw, cap = if (gap > 0.5f) androidx.compose.ui.graphics.StrokeCap.Round else androidx.compose.ui.graphics.StrokeCap.Butt),
        )
        if (bar > 0.01f) {
            val bw = w * 0.30f * bar
            val bh = w * 0.075f
            drawRoundRect(
                color = accent.copy(alpha = bar),
                topLeft = Offset(center.x - bw / 2, center.y - bh / 2),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(bh / 2, bh / 2),
            )
        }
    }
}
