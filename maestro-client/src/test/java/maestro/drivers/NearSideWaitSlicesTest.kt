package maestro.drivers

import com.google.common.truth.Truth.assertThat
import device.IOSDevice
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.verify
import io.mockk.mockk
import maestro.OnDeviceElementQuery
import maestro.nearside.NearSideSelector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import maestro.utils.ScreenStatic
import xcuitest.api.NearSideCapabilities
import xcuitest.api.NearSideElement
import xcuitest.api.NearSideFindResponse
import xcuitest.api.NearSideWaitUntilRequest
import xcuitest.api.NearSideWaitUntilResponse
import xcuitest.api.ScreenStaticResponse
import maestro.utils.network.XCUITestServerError

/**
 * smithr: a near-side waitUntil longer than the XCTest runner's 100 s HTTP handler timeout gets
 * the caller's whole budget (loki lnfi2 2026-11-24: a 300 s wait came back as HTTP 500 at 100 s).
 */
class NearSideWaitSlicesTest {

    @AfterEach
    fun restoreClock() {
        NearSideWaitSlices.clock = System::currentTimeMillis
        System.clearProperty(ScreenStatic.STATIC_PROPERTY)
    }

    /**
     * The runner, on a virtual clock: its poll loop answers ok=false when the timeoutMs it was
     * given runs out, ok=true as soon as the element is visible — and FlyingFox cancels any
     * handler still running at [handlerLimitMs] with an empty-bodied 500, as on zeug reid.
     */
    private class Runner(val visibleAtMs: Long?, val handlerLimitMs: Long = 100_000) {
        var nowMs = 0L
        val askedMs = mutableListOf<Long>()

        fun waitUntil(request: NearSideWaitUntilRequest): NearSideWaitUntilResponse {
            askedMs += request.timeoutMs
            val untilVisible = visibleAtMs?.let { (it - nowMs).coerceAtLeast(0) }
            val runsMs = if (untilVisible != null && untilVisible <= request.timeoutMs) untilVisible else request.timeoutMs
            if (runsMs > handlerLimitMs) {
                nowMs += handlerLimitMs
                throw XCUITestServerError.UnknownFailure("Request for waitUntil failed, code: 500, body: ")
            }
            nowMs += runsMs
            val visible = untilVisible != null && untilVisible <= request.timeoutMs
            return NearSideWaitUntilResponse(
                ok = visible,
                elapsedMs = runsMs,
                last = NearSideFindResponse(
                    matches = if (visible) listOf(NearSideElement(attributes = mapOf("text" to "jane@example.com"))) else emptyList(),
                ),
            )
        }
    }

    private fun deviceOver(runner: Runner): IOSDevice = mockk<IOSDevice>(relaxed = true) {
        every { deviceId } returns "reid"
        every { nearSideWaitUntil(any()) } answers { runner.waitUntil(firstArg()) }
    }

    private fun driverOver(runner: Runner, device: IOSDevice = deviceOver(runner)): IOSDriver {
        NearSideWaitSlices.clock = { runner.nowMs }
        return IOSDriver(device)
    }

    private fun waitFor(driver: IOSDriver, waitMs: Long) =
        driver.queryOnDeviceElements(OnDeviceElementQuery.NearSide(NearSideSelector(textRegex = "jane@example.com"), waitMs))

    @Test
    fun `a 300 s wait over a 100 s handler limit SUCCEEDS when the element appears at 150 s`() {
        val runner = Runner(visibleAtMs = 150_000)

        val matches = waitFor(driverOver(runner), 300_000)

        assertThat(matches.map { it.attributes["text"] }).containsExactly("jane@example.com")
        assertThat(runner.nowMs).isEqualTo(150_000)
        assertThat(runner.askedMs).isEqualTo(listOf(60_000L, 60_000L, 60_000L))
    }

    @Test
    fun `a 300 s wait that never comes true ends unmet AT 300 s - not yet, never a 500`() {
        val runner = Runner(visibleAtMs = null)

        val matches = waitFor(driverOver(runner), 300_000)

        // An empty answer is "not found": Orchestra names the selector (ElementNotFound / false condition).
        assertThat(matches).isEmpty()
        assertThat(runner.nowMs).isEqualTo(300_000)
        assertThat(runner.askedMs).isEqualTo(listOf(60_000L, 60_000L, 60_000L, 60_000L, 60_000L))
    }

    @Test
    fun `no slice ever asks the runner for more than the slice bound, and the last asks only what is left`() {
        val runner = Runner(visibleAtMs = null)

        waitFor(driverOver(runner), 130_000)

        assertThat(runner.askedMs).isEqualTo(listOf(60_000L, 60_000L, 10_000L))
        assertThat(runner.askedMs.max()).isAtMost(NearSideWaitSlices.SLICE_MS)
        assertThat(runner.nowMs).isEqualTo(130_000)
    }

    @Test
    fun `a wait within one slice is still one round trip`() {
        val runner = Runner(visibleAtMs = 2_000)

        val matches = waitFor(driverOver(runner), 7_000)

        assertThat(matches).hasSize(1)
        assertThat(runner.askedMs).isEqualTo(listOf(7_000L))
    }

    @Test
    fun `a zero budget is one check`() {
        val runner = Runner(visibleAtMs = null)

        assertThat(waitFor(driverOver(runner), 0)).isEmpty()
        assertThat(runner.askedMs).isEqualTo(listOf(0L))
    }

    @Test
    fun `a real runner error in a slice is not retried - it propagates`() {
        val runner = Runner(visibleAtMs = null, handlerLimitMs = 30_000)   // a slice itself overruns

        assertThrows<XCUITestServerError.UnknownFailure> { waitFor(driverOver(runner), 300_000) }
        assertThat(runner.askedMs).hasSize(1)
    }

    // ---- loki on artha#5603: slicing re-asks ONLY the condition; no action is ever replayed ----

    @Test
    fun `a sliced waitUntil sends the device NOTHING but the same read-only condition check`() {
        val runner = Runner(visibleAtMs = null)
        val device = deviceOver(runner)
        excludeRecords { device.deviceId }
        val requests = mutableListOf<NearSideWaitUntilRequest>()
        every { device.nearSideWaitUntil(capture(requests)) } answers { runner.waitUntil(firstArg()) }

        waitFor(driverOver(runner, device), 300_000)

        verify(exactly = 5) { device.nearSideWaitUntil(any()) }
        confirmVerified(device)   // no tap, input, swipe, launch, erase … — nothing else at all
        assertThat(requests.map { it.copy(timeoutMs = 0) }.distinct()).hasSize(1)   // the same question each slice
        assertThat(requests.map { it.condition }.distinct()).containsExactly("visible")
    }

    @Test
    fun `a sliced screen-static wait sends the device NOTHING but the read-only screenStatic check`() {
        System.setProperty(ScreenStatic.STATIC_PROPERTY, "on")
        var nowMs = 0L
        NearSideWaitSlices.clock = { nowMs }
        val asked = mutableListOf<Long>()
        val device = mockk<IOSDevice>(relaxed = true) {
            every { deviceId } returns "reid"
            every { nearSideCapabilities() } returns NearSideCapabilities(nearSide = 1, ops = listOf("waitUntil"), screenStatic = 1)
            every { screenStatic(any()) } answers {
                val t = firstArg<Long>()
                asked += t
                if (t > 100_000) throw XCUITestServerError.UnknownFailure("Request for screenStatic failed, code: 500, body: ")
                nowMs += minOf(t, 30_000L)   // the runner's own 30 s cap; never static
                ScreenStaticResponse(isScreenStatic = false, attempts = 1, frames = 1)
            }
        }
        excludeRecords { device.deviceId }

        assertThat(IOSDriver(device).waitUntilScreenIsStatic(300_000)).isFalse()

        assertThat(nowMs).isEqualTo(300_000)
        assertThat(asked.max()).isAtMost(NearSideWaitSlices.SLICE_MS)
        verify(exactly = 1) { device.nearSideCapabilities() }
        verify(exactly = asked.size) { device.screenStatic(any()) }
        confirmVerified(device)   // no isScreenStatic fallback, no action — only the check, re-asked
    }
}
