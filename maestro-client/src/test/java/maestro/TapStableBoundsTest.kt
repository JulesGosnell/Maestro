package maestro

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import maestro.device.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * smithr: an iOS element tap waits for the element's bounds to stop moving, then taps the centre
 * of the settled bounds — never the point the lookup read before the screen moved.
 */
class TapStableBoundsTest {

    @AfterEach
    fun restoreEnv() {
        TapStableBounds.env = System::getenv
    }

    private fun hint(bounds: String) =
        TreeNode(attributes = mutableMapOf("resource-id" to "skip-reason-hint", "text" to "", "bounds" to bounds))

    private fun screen(bounds: String) = TreeNode(children = listOf(hint(bounds)))

    // As the artha CI run: the lookup read the hint at y 274-308; the modal then slid up.
    private val stale = "[48,274][342,308]"
    private val moving = listOf("[48,266][342,300]", "[48,255][342,289]", "[48,248][342,282]")
    private val settled = "[48,245][342,279]"          // centre (195, 262)
    private val settledWithinTolerance = "[48,246][342,280]"

    private fun driver(platform: Platform): Driver = mockk(relaxed = true) {
        every { deviceInfo() } returns DeviceInfo(platform, 390, 844, 390, 844)
        every { capabilities() } returns emptyList()
    }

    // The screen is "static" by screenshot, so iOS settle returns null — as in the CI run.
    // (Overridden, not stubbed: mockk cannot match the ViewHierarchy value-class parameter.)
    private class StaticScreen(val mock: Driver) : Driver by mock {
        override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int?): ViewHierarchy? = null
    }

    private fun tapHint(driver: Driver) {
        val element = hint(stale).let { UiElement(it, Bounds(48, 274, 294, 34)) }
        Maestro(StaticScreen(driver)).tap(element = element, initialHierarchy = ViewHierarchy(element.treeNode))
    }

    @Test
    fun `an iOS tap lands at the centre of the bounds once they stop moving`() {
        val driver = driver(Platform.IOS)
        every { driver.contentDescriptor(any()) } returnsMany
            (moving.map(::screen) + screen(settled) + screen(settledWithinTolerance) + screen(settled))

        tapHint(driver)

        verify(exactly = 1) { driver.tap(Point(195, 263)) }
        verify(exactly = 0) { driver.tap(Point(195, 291)) }
        verify(exactly = 5) { driver.contentDescriptor(any()) }
    }

    @Test
    fun `bounds that never settle FAIL the tap by name, and nothing is tapped`() {
        TapStableBounds.env = { if (it == TapStableBounds.TIMEOUT_ENV) "0.2" else null }
        val driver = driver(Platform.IOS)
        var y = 300
        every { driver.contentDescriptor(any()) } answers {
            y = if (y == 300) 250 else 300   // jitters 50 pt for ever
            screen("[48,$y][342,${y + 34}]")
        }

        val error = assertThrows<MaestroException.ElementStillMoving> { tapHint(driver) }

        assertThat(error.message).contains("TAP-STABLE-TIMEOUT")
        assertThat(error.message).contains("id=\"skip-reason-hint\"")
        assertThat(error.message).containsMatch("Bounds\\(x=48, y=(250|300).*-> Bounds\\(x=48, y=(250|300)")
        verify(exactly = 0) { driver.tap(any()) }
    }

    @Test
    fun `an element that VANISHES while settling FAILS the tap by name, and nothing is tapped`() {
        val driver = driver(Platform.IOS)
        every { driver.contentDescriptor(any()) } returnsMany
            listOf(screen(moving[0]), TreeNode(children = listOf()))

        val error = assertThrows<MaestroException.ElementLostWhileStabilising> { tapHint(driver) }

        assertThat(error.message).contains("TAP-STABLE-LOST")
        assertThat(error.message).contains("id=\"skip-reason-hint\"")
        assertThat(error.message).contains("vanished")
        assertThat(error.message).contains("Bounds(x=48, y=266")
        verify(exactly = 0) { driver.tap(any()) }
    }

    @Test
    fun `an element that gains a TWIN while settling FAILS the tap by name, and nothing is tapped`() {
        val driver = driver(Platform.IOS)
        every { driver.contentDescriptor(any()) } returnsMany listOf(
            screen(moving[0]),
            TreeNode(children = listOf(hint(settled), hint("[48,500][342,534]"))),
        )

        val error = assertThrows<MaestroException.ElementLostWhileStabilising> { tapHint(driver) }

        assertThat(error.message).contains("2 matches")
        verify(exactly = 0) { driver.tap(any()) }
    }

    @Test
    fun `an Android tap does not re-read the hierarchy`() {
        val driver = driver(Platform.ANDROID)
        every { driver.contentDescriptor(any()) } returns screen(settled)

        tapHint(driver)

        verify(exactly = 1) { driver.tap(Point(195, 291)) }
        verify(exactly = 0) { driver.contentDescriptor(any()) }
    }

    @Test
    fun `the timeout is env-overridable and a bad value keeps the default`() {
        TapStableBounds.env = { "12" }
        assertThat(TapStableBounds.timeoutMs()).isEqualTo(12_000)
        TapStableBounds.env = { "nope" }
        assertThat(TapStableBounds.timeoutMs()).isEqualTo(60_000)
        TapStableBounds.env = { "0" }
        assertThat(TapStableBounds.timeoutMs()).isEqualTo(60_000)
        TapStableBounds.env = { null }
        assertThat(TapStableBounds.timeoutMs()).isEqualTo(60_000)
    }

    @Test
    fun `bounds agree within two points, not three`() {
        assertThat(TapStableBounds.agree(Bounds(48, 245, 294, 34), Bounds(50, 247, 292, 36))).isTrue()
        assertThat(TapStableBounds.agree(Bounds(48, 245, 294, 34), Bounds(48, 248, 294, 34))).isFalse()
    }
}
