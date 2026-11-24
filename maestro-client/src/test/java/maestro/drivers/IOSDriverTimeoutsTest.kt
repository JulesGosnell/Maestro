package maestro.drivers

import com.google.common.truth.Truth.assertThat
import device.IOSDevice
import io.mockk.every
import io.mockk.mockk
import maestro.OnDeviceElementQuery
import maestro.nearside.NearSideSelector
import maestro.utils.ScreenStatic
import maestro.utils.network.XCUITestServerError
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xcuitest.IOSDriverTimeouts
import xcuitest.XCTestDriverClient
import xcuitest.api.NearSideCapabilities
import xcuitest.api.NearSideElement
import xcuitest.api.NearSideFindResponse
import xcuitest.api.NearSideWaitUntilRequest
import xcuitest.api.NearSideWaitUntilResponse
import xcuitest.api.ScreenStaticResponse

/**
 * smithr (artha#5603): the iOS driver's request limits are set by MAESTRO_IOS_DRIVER_TIMEOUT_MS
 * (default 600 s), never a hard-wired number shorter than a flow's condition. loki lnfi2
 * 2026-11-24: a 300 s waitUntil came back as HTTP 500 at the runner's fixed 100 s.
 */
class IOSDriverTimeoutsTest {

    @AfterEach
    fun restore() {
        IOSDriverTimeouts.env = System::getenv
        System.clearProperty(ScreenStatic.STATIC_PROPERTY)
    }

    /**
     * The runner as it is LAUNCHED: its handler limit (and /screenStatic cap) is whatever
     * [IOSDriverTimeouts.runnerEnvironment] hands it; a handler still running at that limit is
     * cancelled by FlyingFox with an empty-bodied 500, as on zeug reid. Work runs on a virtual clock.
     */
    private class Runner(val readyAtMs: Long?) {
        val limitMs = IOSDriverTimeouts.runnerEnvironment("TEST_RUNNER_")
            .getValue("TEST_RUNNER_${IOSDriverTimeouts.ENV}").toLong()
        var nowMs = 0L

        /** Runs for up to [timeoutMs]; true if the condition came true within it. */
        fun work(timeoutMs: Long, route: String): Boolean {
            val untilReady = readyAtMs?.let { (it - nowMs).coerceAtLeast(0) }
            val met = untilReady != null && untilReady <= timeoutMs
            val runsMs = if (met) untilReady!! else timeoutMs
            if (runsMs > limitMs) {
                nowMs += limitMs
                throw XCUITestServerError.UnknownFailure("Request for $route failed, code: 500, body: ")
            }
            nowMs += runsMs
            return met
        }
    }

    private fun device(runner: Runner): IOSDevice = mockk(relaxed = true) {
        every { deviceId } returns "reid"
        every { nearSideWaitUntil(any()) } answers {
            val request = firstArg<NearSideWaitUntilRequest>()
            val ok = runner.work(request.timeoutMs, "waitUntil")
            NearSideWaitUntilResponse(
                ok = ok, elapsedMs = runner.nowMs,
                last = NearSideFindResponse(if (ok) listOf(NearSideElement(mapOf("text" to "jane@example.com"))) else emptyList()),
            )
        }
        every { nearSideCapabilities() } returns NearSideCapabilities(nearSide = 1, ops = listOf("waitUntil"), screenStatic = 1)
        every { screenStatic(any()) } answers {
            // the runner caps one /screenStatic at its own limit (ScreenDiffHandler.swift)
            val t = minOf(firstArg<Long>(), runner.limitMs)
            ScreenStaticResponse(isScreenStatic = runner.work(t, "screenStatic"), attempts = 1, frames = 1)
        }
    }

    private fun waitUntil(runner: Runner, waitMs: Long) = IOSDriver(device(runner))
        .queryOnDeviceElements(OnDeviceElementQuery.NearSide(NearSideSelector(textRegex = "jane@example.com"), waitMs))

    private fun screenStatic(runner: Runner, waitMs: Long): Boolean {
        System.setProperty(ScreenStatic.STATIC_PROPERTY, "on")
        return IOSDriver(device(runner)).waitUntilScreenIsStatic(waitMs)
    }

    @Test
    fun `defaults - 600 s on the runner, the client above it, both at least twice a 300 s wait`() {
        IOSDriverTimeouts.env = { null }
        assertThat(IOSDriverTimeouts.serverMs()).isEqualTo(600_000)
        assertThat(IOSDriverTimeouts.serverMs()).isAtLeast(2 * 300_000L)
        assertThat(IOSDriverTimeouts.client().inWholeMilliseconds).isGreaterThan(IOSDriverTimeouts.serverMs())
        assertThat(IOSDriverTimeouts.runnerEnvironment("SIMCTL_CHILD_"))
            .containsExactly("SIMCTL_CHILD_MAESTRO_IOS_DRIVER_TIMEOUT_MS", "600000")
    }

    @Test
    fun `a bad value keeps the default`() {
        for (bad in listOf("nope", "0", "-5", "", "1.5")) {
            IOSDriverTimeouts.env = { bad }
            assertThat(IOSDriverTimeouts.serverMs()).isEqualTo(600_000)
        }
    }

    @Test
    fun `a 300 s waitUntil under the defaults finds the element that appears at 150 s`() {
        IOSDriverTimeouts.env = { null }
        val runner = Runner(readyAtMs = 150_000)
        assertThat(waitUntil(runner, 300_000).map { it.attributes["text"] }).containsExactly("jane@example.com")
        assertThat(runner.nowMs).isEqualTo(150_000)
    }

    @Test
    fun `a 300 s waitUntil under the defaults that never comes true ends unmet AT 300 s, never a 500`() {
        IOSDriverTimeouts.env = { null }
        val runner = Runner(readyAtMs = null)
        // empty = not found: Orchestra names the selector (ElementNotFound / a false condition)
        assertThat(waitUntil(runner, 300_000)).isEmpty()
        assertThat(runner.nowMs).isEqualTo(300_000)
    }

    @Test
    fun `a 300 s screenStatic under the defaults finds the screen static at 150 s`() {
        IOSDriverTimeouts.env = { null }
        val runner = Runner(readyAtMs = 150_000)
        assertThat(screenStatic(runner, 300_000)).isTrue()
        assertThat(runner.nowMs).isEqualTo(150_000)
    }

    @Test
    fun `a 300 s screenStatic under the defaults that never settles is not-static AT 300 s, never a 500`() {
        IOSDriverTimeouts.env = { null }
        val runner = Runner(readyAtMs = null)
        assertThat(screenStatic(runner, 300_000)).isFalse()
        assertThat(runner.nowMs).isEqualTo(300_000)
    }

    @Test
    fun `the env override reaches the runner's launch env and the client's timeouts`() {
        IOSDriverTimeouts.env = { if (it == IOSDriverTimeouts.ENV) "900000" else null }

        assertThat(IOSDriverTimeouts.runnerEnvironment("TEST_RUNNER_"))
            .containsExactly("TEST_RUNNER_MAESTRO_IOS_DRIVER_TIMEOUT_MS", "900000")
        val http = XCTestDriverClient::class.java.getDeclaredField("okHttpClient")
            .apply { isAccessible = true }.get(XCTestDriverClient(installer = mockk(relaxed = true))) as OkHttpClient
        assertThat(http.callTimeoutMillis).isEqualTo(960_000)
        assertThat(http.readTimeoutMillis).isEqualTo(960_000)
        // and the runner launched with it lets a 700 s wait run its course
        val runner = Runner(readyAtMs = 650_000)
        assertThat(waitUntil(runner, 700_000)).hasSize(1)
    }

    @Test
    fun `a wait longer than an overridden SMALLER limit still fails as before - the limit is real`() {
        IOSDriverTimeouts.env = { if (it == IOSDriverTimeouts.ENV) "100000" else null }
        val runner = Runner(readyAtMs = null)
        org.junit.jupiter.api.assertThrows<XCUITestServerError.UnknownFailure> { waitUntil(runner, 300_000) }
    }
}
