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
    fun `an element that gains a TWIN while settling is tracked by its bounds, never failed`() {
        val driver = driver(Platform.IOS)
        every { driver.contentDescriptor(any()) } returnsMany listOf(
            screen(moving[0]),
            TreeNode(children = listOf(hint("[48,500][342,534]"), hint(settled))),
            TreeNode(children = listOf(hint("[48,500][342,534]"), hint(settled))),
        )

        tapHint(driver)

        verify(exactly = 1) { driver.tap(Point(195, 262)) }
        verify(exactly = 0) { driver.tap(Point(195, 517)) }
    }

    // ---- identity (artha 2026-11-24): keep tracking the node the selector chose ----

    // A native iOS alert, as in artha's family/profile run: the title, the "Sign Out" button and
    // a third node all carry {accessibilityText="Sign Out", resource-id=""}. The selector chose
    // the button (lookup bounds x=199 y=440 140x48). [dy] slides the whole alert.
    private fun signOutNode(bounds: String, clickable: Boolean = false) = TreeNode(
        attributes = mutableMapOf(
            "resource-id" to "", "text" to "Sign Out", "accessibilityText" to "Sign Out", "bounds" to bounds,
        ),
        clickable = clickable,
    )

    private fun alert(dy: Int, withSignOut: Boolean = true) = TreeNode(
        children = listOfNotNull(
            signOutNode("[51,${360 + dy}][339,${490 + dy}]").takeIf { withSignOut },    // the alert itself
            signOutNode("[60,${380 + dy}][330,${402 + dy}]").takeIf { withSignOut },    // its title
            TreeNode(attributes = mutableMapOf(
                "resource-id" to "", "text" to "Cancel", "accessibilityText" to "Cancel",
                "bounds" to "[51,${440 + dy}][191,${488 + dy}]",
            )),
            signOutNode("[199,${440 + dy}][339,${488 + dy}]", clickable = true).takeIf { withSignOut },
        )
    )

    private val signOutSelector: ElementFilter = Filters.textMatches(Regex("Sign Out"))

    private fun tapSignOut(driver: Driver, selector: ElementFilter? = signOutSelector) {
        val button = signOutNode("[199,440][339,488]", clickable = true)
        val element = UiElement(button, Bounds(199, 440, 140, 48))
        Maestro(StaticScreen(driver)).tap(
            element = element, initialHierarchy = ViewHierarchy(alert(0)), candidates = selector,
        )
    }

    @Test
    fun `three nodes sharing every attribute - the SELECTED alert button is tapped`() {
        val driver = driver(Platform.IOS)
        every { driver.contentDescriptor(any()) } returns alert(0)

        tapSignOut(driver)

        verify(exactly = 1) { driver.tap(Point(269, 464)) }   // the button's centre, not the title's
        verify(exactly = 1) { driver.tap(any()) }
    }

    @Test
    fun `three nodes sharing every attribute, with no selector - still the button, by bounds`() {
        val driver = driver(Platform.IOS)
        every { driver.contentDescriptor(any()) } returns alert(0)

        tapSignOut(driver, selector = null)

        verify(exactly = 1) { driver.tap(Point(269, 464)) }
    }

    @Test
    fun `a sliding alert whose button has identical siblings still waits for stable bounds`() {
        val driver = driver(Platform.IOS)
        every { driver.contentDescriptor(any()) } returnsMany
            listOf(alert(-8), alert(-19), alert(-26), alert(-29), alert(-29))

        tapSignOut(driver)

        verify(exactly = 1) { driver.tap(Point(269, 435)) }   // settled: y 411-459
        verify(exactly = 0) { driver.tap(Point(269, 464)) }   // where the lookup read it
        verify(exactly = 5) { driver.contentDescriptor(any()) }
    }

    @Test
    fun `an alert that truly goes away FAILS the tap by name - the selector matches nothing`() {
        val driver = driver(Platform.IOS)
        every { driver.contentDescriptor(any()) } returnsMany
            listOf(alert(-8), alert(-8, withSignOut = false))

        val error = assertThrows<MaestroException.ElementLostWhileStabilising> { tapSignOut(driver) }

        assertThat(error.message).contains("TAP-STABLE-LOST")
        assertThat(error.message).contains("text=\"Sign Out\"")
        assertThat(error.message).contains("vanished")
        assertThat(error.message).contains("(reads=2)")
        assertThat(error.message).contains("Bounds(x=199, y=432")   // the button's last read, read 1
        verify(exactly = 0) { driver.tap(any()) }
    }

    // As artha's family/access-withdrawn run: the card's accessibility text re-rendered between
    // the lookup and the first re-read; same resource-id, same place.
    private val cardId = "family-client-card-c7c28359-556e-4f30-8879-b8808acad1ba"

    private fun card(text: String, bounds: String = "[16,268][374,348]") = TreeNode(
        attributes = mutableMapOf("resource-id" to cardId, "text" to "", "accessibilityText" to text, "bounds" to bounds),
        clickable = true,
    )

    @Test
    fun `a card whose text re-renders between reads is the same card, and is tapped`() {
        val driver = driver(Platform.IOS)
        every { driver.contentDescriptor(any()) } returns TreeNode(
            children = listOf(card("Zoe Schedule-Only ub8db0500, Daughter, Schedule, Access withdrawn")),
        )
        val before = card("Zoe Schedule-Only ub8db0500, Daughter, Schedule")
        val element = UiElement(before, Bounds(16, 268, 358, 80))

        Maestro(StaticScreen(driver)).tap(
            element = element,
            initialHierarchy = ViewHierarchy(TreeNode(children = listOf(before))),
            candidates = Filters.idMatches(Regex(cardId)),
        )

        verify(exactly = 1) { driver.tap(Point(195, 308)) }
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
