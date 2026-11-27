package app.aduster.widget

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The widget's redraws, on one thread, with at most one waiting behind the one being drawn: a
 * redraw reads the newest state when it runs, so a second one waiting would draw the same. Its
 * updates come from an exported receiver, which any app can send in a burst (a thread each
 * before).
 */
internal class Redraws {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "widget").apply { isDaemon = true } }
    private val waiting = AtomicBoolean(false)

    /** Draws soon with [draw], then calls [finish]; at once [finish] alone when a redraw is already waiting. */
    fun request(finish: () -> Unit, draw: () -> Unit) {
        if (!waiting.compareAndSet(false, true)) {
            finish()
            return
        }
        worker.execute {
            waiting.set(false)
            try {
                draw()
            } finally {
                finish()
            }
        }
    }
}
