package ios.hosttouch

import device.IOSDevice
import com.google.common.truth.Truth.assertThat
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import ios.LocalIOSDevice
import ios.xctest.XCTestIOSDevice
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.config.Property
import org.junit.jupiter.api.AfterEach
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

    // HostTouch's log lines, at INFO, whatever the test log configuration says
    private val logged = mutableListOf<String>()
    private val context = LoggerContext.getContext(false)
    private val capture = object : AbstractAppender("host-touch-capture", null, null, true, Property.EMPTY_ARRAY) {
        override fun append(event: LogEvent) { synchronized(logged) { logged += event.message.formattedMessage } }
    }.also { appender ->
        appender.start()
        val config = LoggerConfig(HostTouch::class.java.name, Level.INFO, false)
        config.addAppender(appender, Level.INFO, null)
        context.configuration.addLogger(HostTouch::class.java.name, config)
        context.updateLoggers()
    }

    @AfterEach
    fun removeCapture() {
        context.configuration.removeLogger(HostTouch::class.java.name)
        context.updateLoggers()
        capture.stop()
    }

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
    fun `without a HostTouch, every gesture still logs its path - xctest`() {
        val device = LocalIOSDevice("udid", xcTest, controller, hostTouch = null)
        device.tap(10, 20)
        device.longPress(10, 20, 3000)
        device.scroll(1.0, 2.0, 3.0, 4.0, 0.5)
        val paths = logged.filter { "path=xctest mode=off" in it }
        assertThat(paths).hasSize(3)
        assertThat(paths[0]).startsWith("[HostTouch] tap (10, 20) path=xctest mode=off took=")
        assertThat(paths[1]).startsWith("[HostTouch] longPress (10, 20) hold=3000ms path=xctest mode=off")
        assertThat(paths[2]).startsWith("[HostTouch] swipe (1, 2)->(3, 4) move=500ms path=xctest mode=off")
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
