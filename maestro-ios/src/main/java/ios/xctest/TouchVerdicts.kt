package ios.xctest

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * artha#5055: backboardd's own verdict on each synthesized touch of an iOS simulator.
 *
 * Every XCTest tap reaches the simulator through a NEW virtual digitizer, one per touch.
 * backboardd logs how it set that digitizer up when it attaches:
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
 * A tap is re-sent only on that POSITIVE verdict, for a digitizer that ATTACHED between the
 * tap's send and its return. The verdict is read once the tap has returned, never waited for:
 * a dropped tap's lines are seconds old by then (XCTest stalls ~5 s after a drop), and a
 * delivered tap pays only the read. No line, a late line, or a dead stream never re-sends; the
 * screen is never consulted.
 */
class TouchVerdicts {

    private class Session(val sender: String, val attachedAt: Instant, var evidence: String?)

    private val lock = Any()
    private val sessions = ArrayDeque<Session>()

    /** One backboardd TouchEvents message, logged at [at]. Anything but a verdict is ignored. */
    fun accept(at: Instant, message: String) {
        synchronized(lock) {
            when {
                INIT in message -> {
                    val sender = SENDER_ID.find(message)?.groupValues?.get(1)?.uppercase() ?: return
                    sessions.addLast(Session(sender, at, evidence = message.takeIf { GOOD_INIT !in it }))
                    while (sessions.size > MAX_SESSIONS) sessions.removeFirst()
                }
                UNSUPPORTED in message -> {
                    val sender = SERVICE_ID.find(message)?.groupValues?.get(1)?.uppercase() ?: return
                    val session = sessions.lastOrNull { it.sender == sender } ?: return
                    if (session.evidence == null) session.evidence = message
                }
            }
        }
    }

    /**
     * backboardd's evidence that it dropped a touch whose digitizer attached within [from]..[to],
     * or null: delivered, not (yet) seen, or attached outside the window. Never waits.
     */
    fun droppedBetween(from: Instant, to: Instant): String? = synchronized(lock) {
        sessions.firstOrNull { it.evidence != null && !it.attachedAt.isBefore(from) && !it.attachedAt.isAfter(to) }
            ?.evidence
    }

    /**
     * Sends [tap]; sends it ONCE more only when backboardd dropped a touch attached while [tap]
     * ran, calling [onDropped] with backboardd's line first. The re-send is not judged again.
     */
    fun send(now: () -> Instant = Instant::now, tap: () -> Unit, onDropped: (evidence: String) -> Unit) {
        val from = now()
        tap()
        val evidence = droppedBetween(from, now()) ?: return
        onDropped(evidence)
        tap()
    }

    companion object {
        private const val INIT = "init: <BKDirectTouchState"
        private const val GOOD_INIT = "; touchScreen; builtin;"
        private const val UNSUPPORTED = "from unsupported service"
        private val SENDER_ID = Regex("senderID: 0x([0-9A-Fa-f]+)")
        private val SERVICE_ID = Regex("IOHIDService name: id:0x([0-9A-Fa-f]+)")
        private const val MAX_SESSIONS = 256
        private const val GRACE_MS = 1000L

        const val PROPERTY = "maestro.tapDropResend"

        // `log stream --style ndjson`: "2026-10-20 05:10:59.287388+0000"
        private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSxx")
        private val json = ObjectMapper()

        private val logger = LoggerFactory.getLogger(TouchVerdicts::class.java)

        /** One `log stream --style ndjson` line into [verdicts]; false if it is not a log event. */
        fun acceptNdjson(verdicts: TouchVerdicts, line: String): Boolean {
            if (!line.startsWith("{")) return false
            val event = try { json.readTree(line) } catch (_: Exception) { return false }
            val message = event.get("eventMessage")?.asText() ?: return false
            val at = event.get("timestamp")?.asText()
                ?.let { runCatching { OffsetDateTime.parse(it, TIMESTAMP).toInstant() }.getOrNull() }
                ?: return false
            verdicts.accept(at, message)
            return true
        }

        fun predicate() = "subsystem == \"com.apple.BackBoard\" AND category == \"TouchEvents\" AND " +
            "(eventMessage CONTAINS \"$INIT\" OR eventMessage CONTAINS \"$UNSUPPORTED\")"

        /**
         * Follows backboardd's TouchEvents on simulator [udid] for one driver session. Null when
         * switched off with -Dmaestro.tapDropResend=off or when it cannot be started; the
         * driver then never re-sends.
         */
        fun streamFromSimulator(udid: String): SimulatorStream? {
            if (System.getProperty(PROPERTY) == "off") return null
            return try {
                SimulatorStream(
                    listOf("xcrun", "simctl", "spawn", udid, "log", "stream", "--style", "ndjson", "--predicate", predicate()),
                    name = udid,
                )
            } catch (e: Exception) {
                logger.warn("[DriverIssues] cannot follow backboardd on $udid; dropped taps will not be re-sent", e)
                null
            }
        }
    }

    /**
     * [command] (a `log stream`), fed line by line into [verdicts]. Two independent ways it ends
     * with the JVM, so no exit leaves it behind on the host:
     * - a shutdown hook runs [close] on exit, exitProcess (Maestro#4's uncaught path) and SIGTERM
     *   (the sidecar's budget);
     * - it runs under a shell that kills it when its stdin closes, which also covers SIGKILL,
     *   where no hook runs.
     */
    class SimulatorStream(command: List<String>, name: String) : AutoCloseable {
        val verdicts = TouchVerdicts()

        private val process: Process = ProcessBuilder(
            // the stream ends when stdin closes (the JVM is gone), and the shell ends with the
            // stream, so a dead stream reaches EOF here
            listOf("/bin/sh", "-c", "exec 3<&0; \"$@\" </dev/null & p=\$!; { read _ <&3; kill \$p 2>/dev/null; } & r=\$!; wait \$p; kill \$r 2>/dev/null", "touch-verdicts") + command,
        ).redirectErrorStream(true).start()

        private val ended = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        private val hook = Thread({ close() }, "touch-verdicts-shutdown-$name")

        init {
            Runtime.getRuntime().addShutdownHook(hook)
            Thread({
                try {
                    process.inputStream.bufferedReader().forEachLine { acceptNdjson(verdicts, it) }
                } catch (_: Exception) {
                    // closed under us
                }
                if (ended.compareAndSet(false, true)) {
                    logger.warn("[DriverIssues] backboardd touch stream on $name ended; dropped taps will not be re-sent")
                }
            }, "touch-verdicts-$name").apply { isDaemon = true }.start()
        }

        /** A dead stream gives no verdicts; the driver then taps without them. */
        val isAlive: Boolean get() = !ended.get() && process.isAlive

        /** [TouchVerdicts.send] while the stream is alive; otherwise [tap] alone, never re-sent. */
        fun send(now: () -> Instant = Instant::now, tap: () -> Unit, onDropped: (evidence: String) -> Unit) {
            if (!isAlive) return tap()
            verdicts.send(now, tap, onDropped)
        }

        /** Idempotent; also the shutdown hook. */
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            ended.set(true)
            val tree = process.descendants().toList()
            // EOF first: the shell then stops the stream itself
            process.outputStream.runCatching { close() }
            if (!process.waitFor(GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            // whatever outlived the grace; SIGTERM, which simctl passes on to the in-sim `log`
            tree.filter { it.isAlive }.forEach { it.destroy() }
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        }
    }
}
