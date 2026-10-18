package maestro.drivers

import com.google.common.truth.Truth.assertThat
import dadb.Dadb
import maestro.MaestroDriverStartupException.BringupStepTimeoutException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * smithr#1245: a device that accepts the adb connection and never answers must fail Android
 * bring-up within the per-step bound, naming the step — not block forever (CI run 39107869667).
 */
class AndroidBringupDeadlineTest {

    private lateinit var silentAdb: ServerSocket
    private val held = CopyOnWriteArrayList<Socket>()
    private val properties = listOf(
        BringupDeadline.SHELL_PROPERTY,
        BringupDeadline.INSTALL_PROPERTY,
        BringupDeadline.INSTRUMENTATION_PROPERTY,
        BringupDeadline.CONNECT_PROPERTY,
    )

    @BeforeEach
    fun setUp() {
        // A fake adb endpoint: accepts every connection, reads nothing, writes nothing.
        silentAdb = ServerSocket(0)
        thread(isDaemon = true, name = "silent-adb") {
            while (!silentAdb.isClosed) {
                runCatching { held += silentAdb.accept() }
            }
        }
        properties.forEach { System.setProperty(it, "1500") }
    }

    @AfterEach
    fun tearDown() {
        properties.forEach { System.clearProperty(it) }
        held.forEach { runCatching { it.close() } }
        silentAdb.close()
    }

    @Test
    // Without the fix open() blocks in an uninterruptible socket read; SEPARATE_THREAD lets the
    // timeout abandon it, so the unfixed code is a red at 30 s rather than a hung build.
    @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `open fails within the bound and names the step when adb never answers`() {
        val dadb = Dadb.create("localhost", silentAdb.localPort, keyPair = null)
        val driver = AndroidDriver(dadb, hostPort = ServerSocket(0).use { it.localPort })

        val start = System.nanoTime()
        val error = assertThrows<BringupStepTimeoutException> { driver.open() }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        assertThat(error.message).startsWith("Android driver bring-up: step '")
        assertThat(error.step).isEqualTo("check dev.mobile.maestro installed")
        assertThat(error.message).contains("exceeded 1500ms")
        assertThat(elapsedMs).isLessThan(10_000)
        assertThat(held).isNotEmpty() // the fake endpoint was really reached
    }

    @Test
    fun `a call that completes returns its value untouched`() {
        assertThat(BringupDeadline.withDeadline("p", "s", 5_000) { 42 }).isEqualTo(42)
    }

    @Test
    fun `a call that fails rethrows its own exception`() {
        val e = assertThrows<IOException> {
            BringupDeadline.withDeadline("p", "s", 5_000) { throw IOException("boom") }
        }
        assertThat(e.message).isEqualTo("boom")
    }

    @Test
    fun `expiry runs the cleanup that unblocks the call`() {
        val closed = CountDownLatch(1)
        val error = assertThrows<BringupStepTimeoutException> {
            BringupDeadline.withDeadline("Android driver bring-up", "install maestro-server", 200, onTimeout = { closed.countDown() }) {
                Thread.sleep(60_000)
            }
        }
        assertThat(error.message).startsWith("Android driver bring-up: step 'install maestro-server' exceeded 200ms")
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `defaults are generous and overridable`() {
        assertThat(BringupDeadline.installTimeoutMs()).isEqualTo(1500) // overridden in setUp
        System.clearProperty(BringupDeadline.INSTALL_PROPERTY)
        assertThat(BringupDeadline.installTimeoutMs()).isEqualTo(120_000)
        assertThat(BringupDeadline.deviceListTimeoutMs()).isEqualTo(60_000)
    }
}
