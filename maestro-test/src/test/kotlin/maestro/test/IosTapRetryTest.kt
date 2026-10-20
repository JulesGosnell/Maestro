package maestro.test

import com.google.common.truth.Truth.assertThat
import maestro.Maestro
import maestro.ViewHierarchy
import maestro.test.drivers.FakeDriver
import maestro.test.drivers.FakeLayoutElement
import maestro.test.drivers.FakeLayoutElement.Bounds
import maestro.UiElement.Companion.toUiElement
import org.junit.jupiter.api.Test

/**
 * artha#5055: on an iOS simulator a synthesized tap is occasionally ignored by backboardd
 * ("ignoring digitizer event ... from unsupported service"); Maestro reports it COMPLETED and
 * the app never saw it. retryIfNoChange is the upstream answer, but on iOS it could never fire:
 * the settle answers null once the screen is static, and null never equals the before-tap
 * hierarchy, so every tap read as "changed" — and a near-side lookup's before-tap hierarchy is
 * the element alone, which no full hierarchy equals either.
 *
 * Each test drives a toggle (Show <-> Hide) on a driver that settles as iOS does: a retry must
 * land a dropped tap, and must never tap again once a tap has taken effect.
 */
class IosTapRetryTest {

    private var clicks = 0

    private fun driver(tapsToDrop: Int): FakeDriver {
        val driver = FakeDriver()
        driver.settlesLikeIos = true
        driver.tapsToDrop = tapsToDrop
        driver.setLayout(FakeLayoutElement().apply {
            element {
                text = "Show"
                bounds = Bounds(0, 0, 100, 100)
                onClick = {
                    clicks++
                    it.text = if (it.text == "Show") "Hide" else "Show"
                }
            }
        })
        driver.open()
        return driver
    }

    /** A tap on the element as Orchestra makes it after a near-side lookup. */
    private fun tapElement(driver: FakeDriver, retryIfNoChange: Boolean) {
        Maestro(driver).use { maestro ->
            val node = maestro.viewHierarchy().aggregate().first { it.attributes["text"] == "Show" }
            maestro.tap(
                element = node.toUiElement(),
                initialHierarchy = ViewHierarchy(node),
                retryIfNoChange = retryIfNoChange,
            )
        }
    }

    @Test
    fun `a dropped tap is tapped again when retryIfNoChange is set`() {
        val driver = driver(tapsToDrop = 1)

        tapElement(driver, retryIfNoChange = true)

        driver.assertEventCount(FakeDriver.Event.Tap(maestro.Point(50, 50)), 2)
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun `a tap that took effect is never tapped again`() {
        val driver = driver(tapsToDrop = 0)

        tapElement(driver, retryIfNoChange = true)

        driver.assertEventCount(FakeDriver.Event.Tap(maestro.Point(50, 50)), 1)
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun `without retryIfNoChange a dropped tap is not retried`() {
        val driver = driver(tapsToDrop = 1)

        tapElement(driver, retryIfNoChange = false)

        driver.assertEventCount(FakeDriver.Event.Tap(maestro.Point(50, 50)), 1)
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun `a coordinate tap that was dropped is tapped again`() {
        val driver = driver(tapsToDrop = 1)

        Maestro(driver).use { it.tap(x = 50, y = 50, retryIfNoChange = true) }

        driver.assertEventCount(FakeDriver.Event.Tap(maestro.Point(50, 50)), 2)
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun `the retry is ONE more tap, however many are dropped`() {
        val driver = driver(tapsToDrop = 5)

        tapElement(driver, retryIfNoChange = true)

        driver.assertEventCount(FakeDriver.Event.Tap(maestro.Point(50, 50)), 2)
        assertThat(clicks).isEqualTo(0)
    }
}
