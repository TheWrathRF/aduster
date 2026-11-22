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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.ClipOp
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
 * The protection read-out: the launcher icon's slotted "a" (ring body cut by a slot, stem on the
 * right, the bar in the slot). On: the mark in [ink] with its bar in the accent. Paused: amber,
 * the slot empty. Off: a thin, faint outline of it.
 */
@Composable
fun ProtectionMark(state: Protection, ink: Color, accent: Color, warn: Color, faint: Color, modifier: Modifier = Modifier, size: Dp = 76.dp) {
    val body by animateColorAsState(
        when (state) {
            Protection.ON -> ink
            Protection.PAUSED -> warn
            Protection.OFF -> faint
        },
        tween(Motion.LONG, easing = Motion.Emphasized), label = "mark color",
    )
    // In the icon's 108-unit grid: the body's stroke 9, thin when off.
    val stroke by animateFloatAsState(if (state == Protection.OFF) 3f else 9f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow), label = "mark stroke")
    val bar by animateFloatAsState(if (state == Protection.ON) 1f else 0f, tween(Motion.LONG, easing = Motion.Emphasized), label = "mark bar")
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension
        // The glyph spans x 21.5-74.5, y 28-84.5 of the icon (ic_launcher_fg.xml); centred, 80 % tall.
        val k = w * 0.80f / 56.5f
        fun x(u: Float) = center.x + (u - 48f) * k
        fun y(u: Float) = center.y + (u - 56.25f) * k
        clipRect(left = 0f, top = y(54f), right = this.size.width, bottom = y(62f), clipOp = ClipOp.Difference) {
            drawCircle(body, radius = 22f * k, center = Offset(x(48f), y(58f)), style = Stroke(width = stroke * k))
        }
        drawRoundRect(
            color = body,
            topLeft = Offset(x(70f - stroke / 2), y(28f + (9f - stroke) / 2)),
            size = Size(stroke * k, (56.5f - (9f - stroke)) * k),
            cornerRadius = CornerRadius(stroke / 2 * k, stroke / 2 * k),
        )
        if (bar > 0.01f) {
            drawRoundRect(
                color = accent.copy(alpha = bar),
                topLeft = Offset(x(33f), y(55.5f)),
                size = Size(16f * k * bar, 5f * k),
                cornerRadius = CornerRadius(2.5f * k, 2.5f * k),
            )
        }
    }
}
