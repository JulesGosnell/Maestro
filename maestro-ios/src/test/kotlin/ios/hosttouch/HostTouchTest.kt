package ios.hosttouch

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import util.IOSDeviceType
import java.nio.file.Files
import java.nio.file.Path

/**
 * smithr#1313: which path each gesture takes, against a stand-in helper (fake-helper.sh) that
 * speaks the real one's protocol for a 390x844 screen.
 */
class HostTouchTest {

    @TempDir
    lateinit var dir: Path

    private val record by lazy { dir.resolve("commands.txt") }
    private val opened = mutableListOf<HostTouch>()

    private fun helperScript(): String {
        val script = dir.resolve("fake-helper.sh")
        if (!Files.exists(script)) {
            Files.copy(javaClass.getResourceAsStream("/hosttouch/fake-helper.sh")!!, script)
        }
        return script.toString()
    }

    private fun hostTouch(mode: String, size: Pair<Int, Int> = 390 to 844, readyTimeoutMs: Long = 5_000): HostTouch =
        HostTouch(
            startHelper = {
                HostTouchHelper(listOf("/bin/sh", helperScript(), mode, record.toString()), "test", readyTimeoutMs)
            },
            interfaceSize = { size },
        ).also { opened += it }

    private fun sent(): List<String> = if (Files.exists(record)) Files.readAllLines(record) else emptyList()

    @AfterEach
    fun closeAll() = opened.forEach { it.close() }

    @Test
    fun `a tap goes to the helper as portrait fractions with XCTest's 100 ms hold, not to XCTest`() {
        var stock = 0
        hostTouch("ok").tap(195, 422, HostTouch.TAP_HOLD_MS) { stock++ }
        assertThat(stock).isEqualTo(0)
        assertThat(sent()).containsExactly("tap 0.500000 0.500000 100")
    }

    @Test
    fun `a long press holds for its own duration`() {
        hostTouch("ok").tap(39, 84, 3000) { error("not XCTest") }
        assertThat(sent()).containsExactly("tap 0.100000 0.099526 3000")
    }

    @Test
    fun `a swipe holds 100 ms, then moves for its duration`() {
        hostTouch("ok").swipe(195.0, 633.0, 195.0, 211.0, 333) { error("not XCTest") }
        assertThat(sent()).containsExactly("swipe 0.500000 0.750000 0.500000 0.250000 100 333")
    }

    @Test
    fun `a double tap is two taps, both on the helper`() {
        val touch = hostTouch("ok")
        repeat(2) { touch.tap(195, 422, HostTouch.TAP_HOLD_MS) { error("not XCTest") } }
        assertThat(sent()).hasSize(2)
    }

    @Test
    fun `a refused gesture goes to XCTest - nothing was sent`() {
        var stock = 0
        hostTouch("refuse").tap(195, 422, HostTouch.TAP_HOLD_MS) { stock++ }
        assertThat(stock).isEqualTo(1)
    }

    @Test
    fun `a helper that fails after sending is NOT followed by XCTest, and is retired`() {
        var stock = 0
        val touch = hostTouch("fail")
        touch.tap(195, 422, HostTouch.TAP_HOLD_MS) { stock++ }
        assertThat(stock).isEqualTo(0)
        touch.tap(195, 422, HostTouch.TAP_HOLD_MS) { stock++ }
        assertThat(stock).isEqualTo(1)
        assertThat(sent()).hasSize(1)
    }

    @Test
    fun `a helper that dies mid-gesture is not followed by XCTest for that gesture`() {
        var stock = 0
        val touch = hostTouch("die")
        touch.tap(195, 422, HostTouch.TAP_HOLD_MS) { stock++ }
        assertThat(stock).isEqualTo(0)
        touch.tap(195, 422, HostTouch.TAP_HOLD_MS) { stock++ }
        assertThat(stock).isEqualTo(1)
    }

    @Test
    fun `a helper that cannot start sends every gesture to XCTest`() {
        var stock = 0
        val touch = hostTouch("noready")
        repeat(3) { touch.tap(195, 422, HostTouch.TAP_HOLD_MS) { stock++ } }
        assertThat(stock).isEqualTo(3)
    }

    @Test
    fun `a helper that never says ready sends the gesture to XCTest after the ready timeout`() {
        var stock = 0
        hostTouch("hang", readyTimeoutMs = 300).tap(195, 422, HostTouch.TAP_HOLD_MS) { stock++ }
        assertThat(stock).isEqualTo(1)
    }

    @Test
    fun `a screen XCTest reports differently goes to XCTest - the orientation is not what we think`() {
        var stock = 0
        hostTouch("ok", size = 844 to 390).tap(195, 300, HostTouch.TAP_HOLD_MS) { stock++ }
        assertThat(stock).isEqualTo(1)
        assertThat(sent()).isEmpty()
    }

    @Test
    fun `after setOrientation the landscape screen is accepted and points are turned`() {
        val touch = hostTouch("ok", size = 844 to 390)
        touch.orientationChanged("landscapeRight")
        touch.tap(100, 39, HostTouch.TAP_HOLD_MS) { error("not XCTest") }
        assertThat(sent()).containsExactly("tap 0.100000 0.881517 100")
    }

    @Test
    fun `a point off the screen goes to XCTest`() {
        var stock = 0
        hostTouch("ok").tap(400, 422, HostTouch.TAP_HOLD_MS) { stock++ }
        assertThat(stock).isEqualTo(1)
        assertThat(sent()).isEmpty()
    }

    // ── the switch, and the physical iPhone (#1313 acceptance 5 and 6) ──

    private val anySize = { 390 to 844 }

    @Test
    fun `a physical iPhone never gets a HostTouch, even with the switch on`() {
        assertThat(HostTouch.forDevice("00008030-x", IOSDeviceType.REAL, anySize, switch = "on")).isNull()
    }

    @Test
    fun `a simulator gets none when the switch is off, unset, or anything but on`() {
        for (switch in listOf("off", null, "ON", "true", "")) {
            assertThat(HostTouch.forDevice("udid", IOSDeviceType.SIMULATOR, anySize, switch = switch)).isNull()
        }
    }

    private fun fake(mode: String): (String) -> List<String> = { listOf("/bin/sh", helperScript(), mode, record.toString()) }

    @Test
    fun `a simulator gets one when the switch is on, with its helper already started`() {
        val touch = HostTouch.forDevice("udid", IOSDeviceType.SIMULATOR, anySize, switch = "on", helperCommand = fake("ok"))
        assertThat(touch).isNotNull()
        opened += touch!!
        touch.tap(195, 422, HostTouch.TAP_HOLD_MS) { error("not XCTest") }
        assertThat(sent()).hasSize(1)
    }

    @Test
    fun `switch on and a helper that will not start fails the session loudly - no silent XCTest`() {
        val e = runCatching {
            HostTouch.forDevice("udid", IOSDeviceType.SIMULATOR, anySize, switch = "on", helperCommand = fake("noready"))
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalStateException::class.java)
        assertThat(e!!.message).contains("mode=on")
        assertThat(e.message).contains("fail no simulator")
        assertThat(e.message).contains("-Dmaestro.iosHostTouch=off")
    }

    @Test
    fun `switch on and no helper in the build fails the session loudly`() {
        val e = runCatching {
            HostTouch.forDevice("udid", IOSDeviceType.SIMULATOR, anySize, switch = "on",
                helperCommand = { throw IllegalStateException("this Maestro build carries no ios-host-touch") })
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalStateException::class.java)
        assertThat(e!!.message).contains("carries no ios-host-touch")
    }

    @Test
    fun `the mode names its source, and an unreadable value is off and says so`() {
        assertThat(HostTouch.mode("on")).isEqualTo(true to "-Dmaestro.iosHostTouch=on")
        assertThat(HostTouch.mode("off").first).isFalse()
        assertThat(HostTouch.mode(null)).isEqualTo(false to "-Dmaestro.iosHostTouch unset, the default")
        assertThat(HostTouch.mode("yes").first).isFalse()
        assertThat(HostTouch.mode("yes").second).contains("neither on nor off, so OFF")
    }
}
