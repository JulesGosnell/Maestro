package ios.hosttouch

import device.IOSDevice
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import ios.LocalIOSDevice
import ios.xctest.XCTestIOSDevice
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * smithr#1313 acceptance 5 and 6: with no HostTouch — a physical iPhone always, a simulator with
 * the switch off — every gesture is exactly the XCTest call it was before, and nothing else.
 */
class LocalIOSDeviceRoutingTest {

    @TempDir
    lateinit var dir: Path

    private val xcTest = mockk<XCTestIOSDevice>(relaxed = true)
    private val controller = mockk<IOSDevice>(relaxed = true)

    @Test
    fun `without a HostTouch, gestures are XCTest's calls exactly`() {
        val device = LocalIOSDevice("udid", xcTest, controller, hostTouch = null)
        device.tap(10, 20)
        device.longPress(10, 20, 3000)
        device.scroll(1.0, 2.0, 3.0, 4.0, 0.5)
        device.setOrientation("landscapeLeft")
        device.close()
        verify(exactly = 1) { xcTest.tap(10, 20) }
        verify(exactly = 1) { xcTest.longPress(10, 20, 3000) }
        verify(exactly = 1) { xcTest.scrollV2(1.0, 2.0, 3.0, 4.0, 0.5) }
        verify(exactly = 1) { xcTest.setOrientation("landscapeLeft") }
        verify(exactly = 1) { xcTest.close() }
        confirmVerified(xcTest, controller)
    }

    @Test
    fun `with a HostTouch, a simulator's gestures do not reach XCTest`() {
        val script = dir.resolve("fake-helper.sh")
        Files.copy(javaClass.getResourceAsStream("/hosttouch/fake-helper.sh")!!, script)
        val record = dir.resolve("commands.txt")
        val host = HostTouch(
            startHelper = { HostTouchHelper(listOf("/bin/sh", script.toString(), "ok", record.toString()), "test") },
            interfaceSize = { 390 to 844 },
        )
        val device = LocalIOSDevice("udid", xcTest, controller, hostTouch = host)
        device.tap(10, 20)
        device.longPress(10, 20, 3000)
        device.scroll(1.0, 2.0, 3.0, 4.0, 0.5)
        device.close()
        verify(exactly = 0) { xcTest.tap(any(), any()) }
        verify(exactly = 0) { xcTest.longPress(any(), any(), any()) }
        verify(exactly = 0) { xcTest.scrollV2(any(), any(), any(), any(), any()) }
        assert(Files.readAllLines(record).size == 3)
    }
}
