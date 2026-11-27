package app.aduster.widget

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedrawsTest {
    /**
     * The widget's updates come from an exported receiver: any app can send a burst. They are
     * drawn on one thread, with at most one waiting behind the one being drawn (it reads the
     * newest state, so more would draw the same); every update's own finish still runs.
     */
    @Test
    fun aBurstOfUpdatesDrawsOnOneThreadWithOneWaiting() {
        val redraws = Redraws()
        val drawing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val draws = AtomicInteger()
        val threads = Collections.synchronizedSet(HashSet<String>())
        val finished = AtomicInteger()
        val first = CountDownLatch(1)
        redraws.request({ finished.incrementAndGet() }) {
            threads += Thread.currentThread().name
            draws.incrementAndGet()
            drawing.countDown()
            release.await(5, TimeUnit.SECONDS)
            first.countDown()
        }
        assertTrue(drawing.await(5, TimeUnit.SECONDS))
        repeat(100) {
            redraws.request({ finished.incrementAndGet() }) {
                threads += Thread.currentThread().name
                draws.incrementAndGet()
            }
        }
        release.countDown()
        assertTrue(first.await(5, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 5_000
        while (finished.get() < 101 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        Thread.sleep(50)
        assertEquals("every update's finish ran", 101, finished.get())
        assertEquals("the one being drawn and one waiting", 2, draws.get())
        assertEquals(1, threads.size)
    }
}
