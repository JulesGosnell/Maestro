package ios.xctest

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * artha#5055: a JVM that ends without calling close() must not leave its touch stream running on
 * the host. Each test runs [StreamExitFixture] in its own JVM with a stand-in stream
 * (`sleep <unique n>`), ends that JVM one way, and looks for the stream afterwards.
 */
class StreamExitTest {

    /** A `sleep <n>` process itself — not a shell whose command line merely mentions it. */
    private fun isMarker(p: ProcessHandle, marker: String) =
        p.info().command().orElse("").endsWith("/sleep") &&
            p.info().arguments().orElse(emptyArray()).toList() == marker.split(" ").drop(1)

    private fun running(marker: String) = ProcessHandle.allProcesses().anyMatch { isMarker(it, marker) }

    private fun fixture(mode: String, marker: String): Process {
        val java = ProcessHandle.current().info().command().get()
        val jvm = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "ios.xctest.StreamExitFixtureKt", mode, "exec $marker",
        ).redirectErrorStream(true).start()
        val reader = jvm.inputStream.bufferedReader()
        while (true) {
            val line = reader.readLine() ?: error("fixture JVM ended before its stream was up")
            if (line == "STREAM-UP") break
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
        if (left) ProcessHandle.allProcesses()
            .filter { isMarker(it, marker) }.forEach { it.destroyForcibly() }
        assertThat(left).isFalse()
    }

    @Test
    fun `exitProcess - the stream is gone (Maestro#4's uncaught path)`() {
        val jvm = fixture("exit", "sleep 6021")
        jvm.outputStream.write("go\n".toByteArray())
        jvm.outputStream.flush()
        assertGoneAfter(jvm, "sleep 6021")
        assertThat(jvm.exitValue()).isEqualTo(1)
    }

    @Test
    fun `SIGTERM - the stream is gone (the sidecar's budget)`() {
        val jvm = fixture("hang", "sleep 6022")
        jvm.destroy()
        assertGoneAfter(jvm, "sleep 6022")
    }

    @Test
    fun `SIGKILL - the stream is gone (no hook runs, stdin EOF stops it)`() {
        val jvm = fixture("hang", "sleep 6023")
        jvm.destroyForcibly()
        assertGoneAfter(jvm, "sleep 6023")
    }
}
