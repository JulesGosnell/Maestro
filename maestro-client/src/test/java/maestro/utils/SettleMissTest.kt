package maestro.utils

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import maestro.DeviceInfo
import maestro.Driver
import maestro.TreeNode
import maestro.device.Platform
import maestro.utils.network.XCUITestServerError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * artha#5049: the settle loop (ScreenshotUtils.waitForAppToSettle, run after every tap) dumps the
 * hierarchy until two dumps agree. A dump that MISSES — the runner's 500 "XCTest recorded an issue
 * during /viewHierarchy: Failed to resolve remote element ... kAXErrorInvalidUIElement", which
 * the XCTest client raises as a [XCUITestServerError.TransientIssue] (Maestro#3) — is asked again.
 * In CI this miss escaped the settle at +136 s and killed the flow's main thread.
 * A settle that ENDS on a miss rethrows it; any other driver error is still fatal.
 */
class SettleMissTest {

    /** The body the runner returned in artha CI run 39349667231 (zeuglodon, carer). */
    private val miss = XCUITestServerError.TransientIssue(
        "viewHierarchy",
        """{"errorMessage":"XCTest recorded an issue during \/viewHierarchy: Failed to resolve query: Failed to resolve remote element AX element pid: 17212, elementOrHash.elementID: 1506933048378721526.9999. (Underlying Error: Error kAXErrorInvalidUIElement getting snapshot for element <AXUIElementRef 0x600000c197d0> {pid=17212} {uid=[ID:9999 hash:0x14e9b39f98e904f6]})","code":"internal"}""",
    )

    private val screenA = TreeNode(attributes = mutableMapOf("resource-id" to "a", "bounds" to "[0,0][100,100]"))
    private val screenB = TreeNode(attributes = mutableMapOf("resource-id" to "b", "bounds" to "[0,0][100,100]"))

    private fun driver(): Driver = mockk(relaxed = true) {
        every { deviceInfo() } returns DeviceInfo(Platform.IOS, 1000, 1000, 1000, 1000)
    }

    @Test
    fun `cap loop - a miss between dumps is asked again and the screen settles`() {
        val driver = driver()
        every { driver.contentDescriptor(any()) } returns screenA andThenThrows miss andThen screenA

        val settled = ScreenshotUtils.waitForAppToSettle(null, driver)

        assertThat(settled.root).isEqualTo(screenA)
        verify(exactly = 3) { driver.contentDescriptor(any()) }
    }

    @Test
    fun `cap loop - a miss on the FIRST dump is asked again`() {
        val driver = driver()
        every { driver.contentDescriptor(any()) } throws miss andThen screenB andThen screenB

        val settled = ScreenshotUtils.waitForAppToSettle(null, driver)

        assertThat(settled.root).isEqualTo(screenB)
    }

    @Test
    fun `deadline loop - a miss is asked again within the timeout`() {
        val driver = driver()
        every { driver.contentDescriptor(any()) } returns screenA andThenThrows miss andThen screenA

        val settled = ScreenshotUtils.waitForAppToSettle(null, driver, timeoutMs = 5_000)

        assertThat(settled.root).isEqualTo(screenA)
    }

    @Test
    fun `a miss is never compared - a dump either side of it must still agree`() {
        val driver = driver()
        // A, miss, B, B: the miss must not let A stand in for B
        every { driver.contentDescriptor(any()) } returns screenA andThenThrows miss andThen screenB andThen screenB

        val settled = ScreenshotUtils.waitForAppToSettle(null, driver)

        assertThat(settled.root).isEqualTo(screenB)
        verify(exactly = 4) { driver.contentDescriptor(any()) }
    }

    @Test
    fun `cap loop - a settle that ENDS on a miss rethrows it`() {
        val driver = driver()
        every { driver.contentDescriptor(any()) } throws miss

        assertThrows<XCUITestServerError.TransientIssue> {
            ScreenshotUtils.waitForAppToSettle(null, driver)
        }
    }

    @Test
    fun `deadline loop - a settle that ENDS on a miss rethrows it, even after good dumps`() {
        val driver = driver()
        every { driver.contentDescriptor(any()) } returns screenA andThen screenB andThenThrows miss

        assertThrows<XCUITestServerError.TransientIssue> {
            ScreenshotUtils.waitForAppToSettle(null, driver, timeoutMs = 300)
        }
    }

    @Test
    fun `any other driver error in a dump is still fatal at once`() {
        val driver = driver()
        every { driver.contentDescriptor(any()) } returns screenA andThenThrows
            XCUITestServerError.UnknownFailure("Request for viewHierarchy failed, code: 500")

        assertThrows<XCUITestServerError.UnknownFailure> {
            ScreenshotUtils.waitForAppToSettle(null, driver)
        }
        verify(exactly = 2) { driver.contentDescriptor(any()) }
    }

    @Test
    fun `misses are counted on the settle call`() {
        val call = SettleStats.SettleCall()
        call.misses = 2
        assertThat(call.toMap()["misses"]).isEqualTo(2)
        assertThat(SettleStats.SettleCall().toMap()).doesNotContainKey("misses")
    }
}
