package ios.hosttouch

import org.slf4j.LoggerFactory
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * smithr#1313: the long-lived `ios-host-touch` process of one Maestro session, and its line
 * protocol (the header of ios-host-touch.m is the reference).
 *
 * It ends with the JVM however the JVM ends, two independent ways:
 * - it exits on stdin EOF, and the JVM holds the only write end of its stdin, so even SIGKILL,
 *   where no hook runs, ends it;
 * - a shutdown hook runs [close] on exit, exitProcess and SIGTERM.
 */
class HostTouchHelper(command: List<String>, private val name: String, readyTimeoutMs: Long = READY_TIMEOUT_MS) : AutoCloseable {

    sealed class Reply {
        /** Sent and acknowledged; [downUpMs] from sending the down to sending the up. */
        data class Ok(val downUpMs: Double, val ackMs: Double) : Reply()

        /** Nothing was sent; another path may send this gesture. */
        data class Refused(val reason: String) : Reply()

        /** Something was sent and then failed, or no answer came: the touch may have landed. */
        data class Failed(val reason: String) : Reply()
    }

    private val process: Process = ProcessBuilder(command).start()
    private val lines = LinkedBlockingQueue<String>()
    private val closed = AtomicBoolean(false)
    private val hook = Thread({ close() }, "ios-host-touch-shutdown-$name")

    val portraitWidth: Double
    val portraitHeight: Double
    val deviceName: String

    init {
        Runtime.getRuntime().addShutdownHook(hook)
        Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { lines.put(it) }
            } catch (_: Exception) {
                // closed under us
            }
            lines.put(EOF)
        }, "ios-host-touch-$name").apply { isDaemon = true }.start()
        Thread({
            try {
                process.errorStream.bufferedReader().forEachLine { logger.warn("[HostTouch] $name: $it") }
            } catch (_: Exception) {
                // closed under us
            }
        }, "ios-host-touch-stderr-$name").apply { isDaemon = true }.start()

        val ready = lines.poll(readyTimeoutMs, TimeUnit.MILLISECONDS)
        val fields = ready?.split(" ", limit = 5)
        if (fields == null || fields.size < 4 || fields[0] != "ready") {
            close()
            throw IllegalStateException(
                if (ready == null) "no answer within ${readyTimeoutMs}ms" else "it said: $ready"
            )
        }
        portraitWidth = fields[1].toDouble()
        portraitHeight = fields[2].toDouble()
        deviceName = fields.getOrElse(4) { "" }
    }

    val isAlive: Boolean get() = !closed.get() && process.isAlive

    /** Sends one command line and waits up to [timeoutMs] for its reply. */
    @Synchronized
    fun request(line: String, timeoutMs: Long): Reply {
        if (!isAlive) return Reply.Refused("the helper is not running")
        try {
            process.outputStream.write("$line\n".toByteArray())
            process.outputStream.flush()
        } catch (e: Exception) {
            // a write that failed reached nobody
            return Reply.Refused("cannot write to the helper: ${e.message}")
        }
        val answer = lines.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: return Reply.Failed("no answer within ${timeoutMs}ms")
        return parse(answer)
    }

    /** Idempotent; also the shutdown hook. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // EOF first: the helper then exits by itself
        process.outputStream.runCatching { close() }
        if (!process.waitFor(GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(HostTouchHelper::class.java)
        private const val EOF = "\u0000eof"
        private const val GRACE_MS = 1000L
        const val READY_TIMEOUT_MS = 20_000L

        fun parse(answer: String): Reply {
            if (answer == EOF) return Reply.Failed("the helper exited")
            val (kind, rest) = answer.split(" ", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            return when (kind) {
                "ok" -> {
                    val numbers = rest.split(" ").mapNotNull { it.toDoubleOrNull() }
                    if (numbers.size == 2) Reply.Ok(numbers[0], numbers[1])
                    else Reply.Failed("unreadable answer: $answer")
                }
                "refused" -> Reply.Refused(rest)
                else -> Reply.Failed(rest.ifEmpty { answer })
            }
        }
    }
}
