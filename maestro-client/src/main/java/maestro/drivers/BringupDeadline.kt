package maestro.drivers

import maestro.MaestroDriverStartupException.BringupStepTimeoutException
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * smithr#1245: bounds a blocking call made while bringing a device session up.
 *
 * dadb has no per-call timeout and its socket has no SO_TIMEOUT, so a device that accepts the
 * connection and never answers blocks the caller forever (CI run 39107869667: 1200 s, no output).
 * [withDeadline] runs the call on a daemon worker and waits at most `timeoutMs`. On expiry it
 * fires `onTimeout` (normally: close the dadb connection, so the blocked read fails and the worker
 * exits) on its own daemon thread — closing may itself block on the same lock — interrupts the
 * worker, and throws [BringupStepTimeoutException] naming the step and the elapsed time.
 *
 * A call that completes is untouched: its value is returned and its exception rethrown as-is.
 * A timeout of 0 or less disables the bound and runs the call inline.
 */
object BringupDeadline {

    private val logger = LoggerFactory.getLogger(BringupDeadline::class.java)

    private val threadId = AtomicInteger()
    private val workers = Executors.newCachedThreadPool { r ->
        Thread(r, "maestro-bringup-${threadId.incrementAndGet()}").apply { isDaemon = true }
    }

    /** Android per-step defaults, each overridable by the named system property (milliseconds). */
    const val INSTALL_PROPERTY = "maestro.android.bringup.installTimeoutMs"
    const val SHELL_PROPERTY = "maestro.android.bringup.shellTimeoutMs"
    const val INSTRUMENTATION_PROPERTY = "maestro.android.bringup.instrumentationTimeoutMs"
    const val CONNECT_PROPERTY = "maestro.android.bringup.connectTimeoutMs"
    const val DEVICE_LIST_PROPERTY = "maestro.android.bringup.deviceListTimeoutMs"

    fun installTimeoutMs() = timeoutMs(INSTALL_PROPERTY, 120_000)
    fun shellTimeoutMs() = timeoutMs(SHELL_PROPERTY, 30_000)
    fun instrumentationTimeoutMs() = timeoutMs(INSTRUMENTATION_PROPERTY, 30_000)
    fun connectTimeoutMs() = timeoutMs(CONNECT_PROPERTY, 30_000)
    fun deviceListTimeoutMs() = timeoutMs(DEVICE_LIST_PROPERTY, 60_000)

    fun timeoutMs(property: String, defaultMs: Long): Long =
        System.getProperty(property)?.trim()?.toLongOrNull() ?: defaultMs

    fun <T> withDeadline(
        phase: String,
        step: String,
        timeoutMs: Long,
        onTimeout: () -> Unit = {},
        block: () -> T,
    ): T {
        if (timeoutMs <= 0) return block()

        val start = System.nanoTime()
        val future = workers.submit<T> { block() }
        try {
            val value = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            // One line per step: the per-step margin against its bound, readable from maestro.log.
            logger.info("$phase: step '$step' took ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)} ms (bound ${timeoutMs} ms)")
            return value
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            logger.error("$phase: step '$step' exceeded ${timeoutMs} ms; closing the connection")
            Thread({
                runCatching(onTimeout).onFailure { logger.warn("$phase: cleanup after '$step' failed", it) }
            }, "maestro-bringup-abort").apply { isDaemon = true }.start()
            future.cancel(true)
            throw BringupStepTimeoutException(phase, step, timeoutMs, elapsedMs)
        } catch (e: InterruptedException) {
            future.cancel(true)
            throw e
        }
    }
}
