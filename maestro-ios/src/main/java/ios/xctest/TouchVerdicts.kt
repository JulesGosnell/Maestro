package ios.xctest

import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * artha#5055: backboardd's own verdict on each synthesized touch of an iOS simulator.
 *
 * Every XCTest tap reaches the simulator through a NEW virtual digitizer, one per touch.
 * backboardd logs how it set that digitizer up:
 *
 *     init: <BKDirectTouchState: ...; touchScreen; builtin; senderID: 0x10E907C700009; ...>   delivered
 *     init: <BKDirectTouchState: ...; bad usage pair: (0x0 / 0x4); builtin; senderID: ...>     dropped
 *     init: <BKDirectTouchState: ...; touchScreen; accessory; authenticated: YES; senderID: ...> dropped
 *
 * and, for a dropped one, "ignoring digitizer event for display <main> from unsupported
 * service: (... id:0x10f204080000a ...)" for every report of the touch. XCTest itself reports
 * success either way. Measured: all 3 known dropped taps carried this verdict, and none of the
 * ~134 delivered taps in 8 captured sim logs did.
 *
 * A tap is re-sent only on that POSITIVE verdict. No line, a dead stream, or a lagging stream
 * is [Verdict.UNKNOWN], and UNKNOWN never re-sends: the screen is never consulted.
 */
class TouchVerdicts {

    enum class Verdict { DELIVERED, DROPPED, UNKNOWN }

    private class Session(val sender: String, var dropped: Boolean, var detached: Boolean = false)

    private val lock = Object()
    private val sessions = mutableListOf<Session>()

    /** Where the touches sent after this call begin. */
    fun mark(): Int = synchronized(lock) { sessions.size }

    /** One line of backboardd's TouchEvents log. Lines that are not a verdict are ignored. */
    fun accept(line: String) {
        synchronized(lock) {
            when {
                INIT in line -> {
                    val sender = SENDER_ID.find(line)?.groupValues?.get(1)?.uppercase() ?: return
                    sessions += Session(sender, dropped = GOOD_INIT !in line)
                }
                UNSUPPORTED in line -> {
                    val sender = SERVICE_ID.find(line)?.groupValues?.get(1)?.uppercase() ?: return
                    sessions.lastOrNull { it.sender == sender }?.dropped = true
                }
                DETACHED in line -> {
                    val sender = DETACHED_SENDER.find(line)?.groupValues?.get(1)?.uppercase() ?: return
                    sessions.lastOrNull { it.sender == sender }?.detached = true
                }
                else -> return
            }
            lock.notifyAll()
        }
    }

    /**
     * The verdict on the touches that began after [mark]: DROPPED as soon as one of them is,
     * DELIVERED once one has come and gone without a drop, UNKNOWN if neither is seen in [timeoutMs].
     */
    fun verdictSince(mark: Int, timeoutMs: Long): Verdict {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(lock) {
            while (true) {
                val touches = sessions.drop(mark)
                if (touches.any { it.dropped }) return Verdict.DROPPED
                if (touches.any { it.detached }) return Verdict.DELIVERED
                val leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (leftMs <= 0) return Verdict.UNKNOWN
                lock.wait(leftMs)
            }
        }
    }

    /**
     * Sends [tap], then sends it ONCE more only if backboardd's verdict on it is DROPPED,
     * calling [onDropped] with the ignored digitizer first. The re-send is not judged again.
     */
    fun send(tap: () -> Unit, onDropped: (sender: String?) -> Unit) {
        val mark = mark()
        tap()
        if (verdictSince(mark, VERDICT_WAIT_MS) != Verdict.DROPPED) return
        onDropped(synchronized(lock) { sessions.drop(mark).firstOrNull { it.dropped }?.sender })
        tap()
    }

    companion object {
        private const val INIT = "init: <BKDirectTouchState"
        private const val GOOD_INIT = "; touchScreen; builtin;"
        private const val UNSUPPORTED = "from unsupported service"
        private const val DETACHED = "digitizer detached"
        private val SENDER_ID = Regex("senderID: 0x([0-9A-Fa-f]+)")
        private val SERVICE_ID = Regex("IOHIDService name: id:0x([0-9A-Fa-f]+)")
        private val DETACHED_SENDER = Regex("digitizer detached \\(sender ([0-9A-Fa-f]+)\\)")

        const val PROPERTY = "maestro.tapDropResend"

        /** How long a tap waits for backboardd's verdict once XCTest has returned. */
        const val VERDICT_WAIT_MS = 1500L

        private val logger = LoggerFactory.getLogger(TouchVerdicts::class.java)

        /**
         * Follows backboardd's TouchEvents on simulator [udid]. Null when switched off with
         * -Dmaestro.tapDropResend=off or when the stream cannot be started; the caller then
         * never re-sends.
         */
        fun streamFromSimulator(udid: String): SimulatorStream? {
            if (System.getProperty(PROPERTY) == "off") return null
            return try {
                SimulatorStream(udid)
            } catch (e: Exception) {
                logger.warn("[DriverIssues] cannot follow backboardd on $udid; dropped taps will not be re-sent", e)
                null
            }
        }
    }

    /** `log stream` on the simulator, fed line by line into [verdicts]. */
    class SimulatorStream(udid: String) : AutoCloseable {
        val verdicts = TouchVerdicts()

        private val process: Process = ProcessBuilder(
            "xcrun", "simctl", "spawn", udid, "log", "stream", "--style", "compact",
            "--predicate",
            "subsystem == \"com.apple.BackBoard\" AND category == \"TouchEvents\" AND " +
                "(eventMessage CONTAINS \"$INIT\" OR eventMessage CONTAINS \"$UNSUPPORTED\" " +
                "OR eventMessage CONTAINS \"$DETACHED\")",
        ).redirectErrorStream(true).start()

        init {
            Thread({
                try {
                    process.inputStream.bufferedReader().forEachLine { verdicts.accept(it) }
                } catch (_: Exception) {
                    // closed under us
                }
                logger.info("backboardd touch stream ended (exit ${process.waitFor()})")
            }, "touch-verdicts-$udid").apply { isDaemon = true }.start()
        }

        /** A dead stream gives no verdicts; a tap then waits for none. */
        val isAlive: Boolean get() = process.isAlive

        override fun close() {
            process.destroy()
        }
    }
}
