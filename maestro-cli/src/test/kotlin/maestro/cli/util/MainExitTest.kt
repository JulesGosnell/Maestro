package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import maestro.utils.network.XCUITestServerError
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * artha#5049: a throwable escaping `main` must END THE PROCESS, non-zero — not leave the JVM up
 * behind a live non-daemon thread until the sidecar's 420 s budget runs out.
 *
 * Each case runs [HangingMain] in a CHILD JVM: whether a process exits is only observable from
 * outside it.
 */
class MainExitTest {

    private fun runChild(mode: String): Pair<Int?, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), HangingMain::class.java.name, mode,
        ).redirectErrorStream(true).start()
        val exited = process.waitFor(30, TimeUnit.SECONDS)
        if (!exited) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        return (if (exited) process.exitValue() else null) to output
    }

    @Test
    fun `a bare Throwable from main exits the JVM non-zero despite a live non-daemon thread`() {
        val (code, output) = runChild("xcuitest-throwable")

        assertThat(code).isEqualTo(UNCAUGHT_EXIT_CODE)
        assertThat(output).contains("Exception in thread \"main\"")
        assertThat(output).contains("kAXErrorInvalidUIElement")
        assertThat(output).contains("onUncaught ran")
    }

    @Test
    fun `a RuntimeException from main exits the JVM non-zero despite a live non-daemon thread`() {
        val (code, output) = runChild("runtime-exception")

        assertThat(code).isEqualTo(UNCAUGHT_EXIT_CODE)
        assertThat(output).contains("boom")
    }

    @Test
    fun `a normal return keeps the body's own exit code`() {
        val (code, _) = runChild("exit-3")

        assertThat(code).isEqualTo(3)
    }

    @Test
    fun `onUncaught failing does not stop the exit`() {
        var exitedWith: Int? = null
        runMainExitingOnUncaught(onUncaught = { error("analytics down") }, exit = { exitedWith = it }) {
            throw XCUITestServerError.UnknownFailure("Request for viewHierarchy failed, code: 500")
        }
        assertThat(exitedWith).isEqualTo(UNCAUGHT_EXIT_CODE)
    }
}

/** A `main` shaped like the CLI's: a non-daemon thread is alive when the main thread dies. */
object HangingMain {
    @JvmStatic
    fun main(args: Array<String>) = runMainExitingOnUncaught(onUncaught = { System.err.println("onUncaught ran") }) {
        Thread { Thread.sleep(600_000) }.apply { isDaemon = false }.start()
        when (args.single()) {
            "xcuitest-throwable" -> throw XCUITestServerError.UnknownFailure(
                "Request for viewHierarchy failed, code: 500, body: Failed to resolve remote element (kAXErrorInvalidUIElement)"
            )
            "runtime-exception" -> throw RuntimeException("boom")
            "exit-3" -> kotlin.system.exitProcess(3)
        }
    }
}
