package app.aduster.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The app's own line icons: 24-unit grid, 2-unit round strokes, drawn to sit with the "a" mark
 * (ring + stem). Tinted where used.
 */
object AdIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    private fun PathBuilder.line(x1: Float, y1: Float, x2: Float, y2: Float) {
        moveTo(x1, y1)
        lineTo(x2, y2)
    }

    /** The mark itself: ring body and stem. */
    val Home = icon("home") {
        circle(10.5f, 13f, 6f)
        line(19f, 5f, 19f, 20f)
    }

    val Log = icon("log") {
        line(4f, 7f, 4.01f, 7f)
        line(8.5f, 7f, 20f, 7f)
        line(4f, 12f, 4.01f, 12f)
        line(8.5f, 12f, 20f, 12f)
        line(4f, 17f, 4.01f, 17f)
        line(8.5f, 17f, 16f, 17f)
    }

    /** A sieve: each pass lets less through. */
    val Filters = icon("filters") {
        line(4f, 7f, 20f, 7f)
        line(7f, 12f, 17f, 12f)
        line(10f, 17f, 14f, 17f)
    }

    val Settings = icon("settings") {
        line(4f, 8f, 7f, 8f)
        line(11f, 8f, 20f, 8f)
        circle(9f, 8f, 2f)
        line(4f, 16f, 13f, 16f)
        line(17f, 16f, 20f, 16f)
        circle(15f, 16f, 2f)
    }

    val Back = icon("back") {
        moveTo(14.5f, 5f)
        lineTo(7.5f, 12f)
        lineTo(14.5f, 19f)
    }

    val Chevron = icon("chevron") {
        moveTo(9.5f, 6f)
        lineTo(15.5f, 12f)
        lineTo(9.5f, 18f)
    }

    val Search = icon("search") {
        circle(10.5f, 10.5f, 5.5f)
        line(15f, 15f, 19.5f, 19.5f)
    }

    val Check = icon("check") {
        moveTo(5f, 12.5f)
        lineTo(10f, 17f)
        lineTo(19f, 7.5f)
    }

    val Warn = icon("warn") {
        circle(12f, 12f, 8.5f)
        line(12f, 7.5f, 12f, 12.5f)
        line(12f, 16.2f, 12f, 16.21f)
    }

    /** Turn off: a ring open at the top with its stem. */
    val Power = icon("power") {
        line(12f, 3.5f, 12f, 11.5f)
        moveTo(7.2f, 6.6f)
        arcTo(7.2f, 7.2f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 16.8f, y1 = 6.6f)
    }

    val Close = icon("close") {
        line(6.5f, 6.5f, 17.5f, 17.5f)
        line(17.5f, 6.5f, 6.5f, 17.5f)
    }

    val Up = icon("up") {
        line(12f, 19f, 12f, 5.5f)
        moveTo(6.5f, 11f)
        lineTo(12f, 5.5f)
        lineTo(17.5f, 11f)
    }
}
