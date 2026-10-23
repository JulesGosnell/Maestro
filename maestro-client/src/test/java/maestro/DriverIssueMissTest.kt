package maestro

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import maestro.device.Platform
import maestro.nearside.NearSideSelector
import maestro.utils.network.XCUITestServerError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Maestro#3: what the lookup poll does with a [XCUITestServerError.TransientIssue] — the miss the
 * XCTest client raises for a 500 "XCTest recorded an issue" on /viewHierarchy or /waitUntil.
 * A miss is "ask again" within the lookup's own timeout, and never "not found".
 */
class DriverIssueMissTest {

    private val miss = XCUITestServerError.TransientIssue("viewHierarchy", "XCTest recorded an issue during /viewHierarchy")

    private val button = TreeNode(attributes = mutableMapOf("resource-id" to "button-continue", "bounds" to "[0,0][100,100]"))
    private val screenWithButton = TreeNode(children = listOf(button))
    private val screenWithout = TreeNode(children = listOf(TreeNode(attributes = mutableMapOf("resource-id" to "other", "bounds" to "[0,0][100,100]"))))

    private fun driver(): Driver = mockk(relaxed = true) {
        every { deviceInfo() } returns DeviceInfo(Platform.IOS, 1000, 1000, 1000, 1000)
    }

    private val byId = Filters.idMatches("button-continue".toRegex())

    @Test
    fun `a hierarchy miss is polled past and the element is found`() {
        val driver = driver()
        every { driver.contentDescriptor(any()) } throws miss andThen screenWithButton

        val result = Maestro(driver).findElementWithTimeout(timeoutMs = 2_000, filter = byId)

        assertThat(result).isNotNull()
        verify(exactly = 2) { driver.contentDescriptor(any()) }
    }

    @Test
    fun `a lookup whose poll ENDS on a miss rethrows it - never not-found, which would pass an assertNotVisible`() {
        val driver = driver()
        every { driver.contentDescriptor(any()) } throws miss

        assertThrows<XCUITestServerError.TransientIssue> {
            Maestro(driver).findElementWithTimeout(timeoutMs = 0, filter = byId)
        }
    }

    @Test
    fun `a real not-found after a miss is not-found`() {
        val driver = driver()
        every { driver.contentDescriptor(any()) } throws miss andThen screenWithout

        val result = Maestro(driver).findElementWithTimeout(timeoutMs = 300, filter = byId)

        assertThat(result).isNull()
    }

    @Test
    fun `a near-side waitUntil miss falls back to the hierarchy poll`() {
        val driver = driver()
        every { driver.queryOnDeviceElements(ofType<OnDeviceElementQuery.NearSide>()) } throws
            XCUITestServerError.TransientIssue("waitUntil", "XCTest recorded an issue during /waitUntil")
        every { driver.contentDescriptor(any()) } returns screenWithButton

        val result = Maestro(driver).findElementWithTimeout(
            timeoutMs = 2_000,
            filter = byId,
            nearSide = NearSideSelector(idRegex = "button-continue"),
        )

        assertThat(result).isNotNull()
        verify(exactly = 1) { driver.queryOnDeviceElements(ofType<OnDeviceElementQuery.NearSide>()) }
    }

    @Test
    fun `smithr#1334 - a near-side miss followed by misses until the timeout fails at the timeout, not later`() {
        // the app really gone, or a guest starved for the whole budget: bounded by the lookup's own timeout
        val driver = driver()
        every { driver.queryOnDeviceElements(ofType<OnDeviceElementQuery.NearSide>()) } throws
            XCUITestServerError.TransientIssue("waitUntil", """{"ok":false,"reason":"driverError"}""")
        every { driver.contentDescriptor(any()) } throws miss

        val start = System.currentTimeMillis()
        assertThrows<XCUITestServerError.TransientIssue> {
            Maestro(driver).findElementWithTimeout(
                timeoutMs = 500,
                filter = byId,
                nearSide = NearSideSelector(idRegex = "button-continue"),
            )
        }
        assertThat(System.currentTimeMillis() - start).isLessThan(3_000L)
    }
}
