package ios.hosttouch

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * smithr#1313: a JVM that ends without calling close() must not leave its helper running. Each
 * test runs [HelperExitFixture] in its own JVM with the stand-in helper (whose only way out is
 * stdin EOF, like the real one), ends that JVM one way, and looks for the helper afterwards.
 */
class HelperExitTest {

    @TempDir
    lateinit var dir: Path

    private fun isHelper(p: ProcessHandle, marker: String) =
        p.info().arguments().orElse(emptyArray()).contains(marker)

    private fun running(marker: String) = ProcessHandle.allProcesses().anyMatch { isHelper(it, marker) }

    private fun fixture(mode: String, marker: String): Process {
        val script = dir.resolve("fake-helper.sh")
        Files.copy(javaClass.getResourceAsStream("/hosttouch/fake-helper.sh")!!, script)
        val java = ProcessHandle.current().info().command().get()
        val jvm = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "ios.hosttouch.HelperExitFixtureKt",
            mode, script.toString(), marker,
        ).redirectErrorStream(true).start()
        val reader = jvm.inputStream.bufferedReader()
        while (true) {
            val line = reader.readLine() ?: error("fixture JVM ended before its helper was up")
            if (line == "HELPER-UP") break
        }
        assertThat(running(marker)).isTrue()
        Thread { reader.forEachLine { } }.apply { isDaemon = true }.start()
        return jvm
    }

    private fun assertGoneAfter(jvm: Process, marker: String) {
        assertThat(jvm.waitFor(20, TimeUnit.SECONDS)).isTrue()
        val deadline = System.currentTimeMillis() + 5_000
        while (running(marker) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val left = running(marker)
        if (left) ProcessHandle.allProcesses().filter { isHelper(it, marker) }.forEach { it.destroyForcibly() }
        assertThat(left).isFalse()
    }

    @Test
    fun `exitProcess - the helper is gone (Maestro#4's uncaught path)`() {
        val jvm = fixture("exit", "helper-marker-7131")
        jvm.outputStream.write("go\n".toByteArray())
        jvm.outputStream.flush()
        assertGoneAfter(jvm, "helper-marker-7131")
        assertThat(jvm.exitValue()).isEqualTo(1)
    }

    @Test
    fun `SIGTERM - the helper is gone (the sidecar's budget)`() {
        val jvm = fixture("hang", "helper-marker-7132")
        jvm.destroy()
        assertGoneAfter(jvm, "helper-marker-7132")
    }

    @Test
    fun `SIGKILL - the helper is gone (no hook runs, stdin EOF ends it)`() {
        val jvm = fixture("hang", "helper-marker-7133")
        jvm.destroyForcibly()
        assertGoneAfter(jvm, "helper-marker-7133")
    }
}
