package maestro.test

import maestro.Maestro
import maestro.test.drivers.FakeDriver
import maestro.test.drivers.FakeLayoutElement
import maestro.test.drivers.FakeLayoutElement.Bounds
import maestro.utils.FastSettle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * smithr#1246 Phase B: a tap on a driver without FAST_HIERARCHY (iOS, FakeDriver) takes a
 * before-tap screenshot upstream; with -Dmaestro.fastSettle=on it does not when there is a
 * single attempt, and still does when retryIfNoChange makes the comparison matter.
 *
 * FakeDriver's layout does not change on a tap, so every attempt also takes the AFTER-tap
 * screenshot (upstream's "nothing changed" branch): one per attempt, untouched by the flag.
 */
class FastSettleTapTest {

    @AfterEach
    fun clear() {
        System.clearProperty(FastSettle.PROPERTY)
    }

    private fun tap(retryIfNoChange: Boolean): FakeDriver {
        val driver = FakeDriver()
        driver.setLayout(FakeLayoutElement().apply {
            element {
                text = "Primary button"
                bounds = Bounds(0, 0, 100, 100)
            }
        })
        driver.open()
        Maestro(driver).use { it.tap(x = 50, y = 50, retryIfNoChange = retryIfNoChange) }
        return driver
    }

    @Test
    fun `off - a single tap takes the before-tap and the after-tap screenshot (upstream)`() {
        tap(retryIfNoChange = false).assertEventCount(FakeDriver.Event.TakeScreenshot, 2)
    }

    @Test
    fun `on - a single tap takes only the after-tap screenshot`() {
        System.setProperty(FastSettle.PROPERTY, "on")
        tap(retryIfNoChange = false).assertEventCount(FakeDriver.Event.TakeScreenshot, 1)
    }

    @Test
    fun `on - retryIfNoChange still takes the before-tap screenshot it compares`() {
        System.setProperty(FastSettle.PROPERTY, "on")
        // one before-tap + one after-tap per attempt (2), exactly as with the flag off
        tap(retryIfNoChange = true).assertEventCount(FakeDriver.Event.TakeScreenshot, 3)
    }
}
