package ios.hosttouch

import util.IOSDeviceType
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * smithr#1313: tap, long press and swipe on an iOS SIMULATOR, sent host-side through the
 * simulator's persistent built-in digitizer ([HostTouchHelper]) instead of XCTest, whose
 * per-gesture digitizer backboardd drops under CPU load while XCTest reports success.
 *
 * Only gestures route here. Element lookup, hierarchy, text input, launch and screenshots stay
 * on XCTest, and a physical iPhone never gets a HostTouch at all ([forDevice]).
 *
 * A gesture goes to the stock XCTest path instead when nothing has been sent yet and the host
 * path cannot take it: the helper is missing or will not start, the screen XCTest reports does
 * not match the simulator's, the point is off the screen, or the helper refuses it. Once the
 * helper has sent something and then failed, the touch may have landed, so the gesture is NOT
 * sent again; the helper is retired and later gestures go to XCTest.
 *
 * Every gesture logs one line: which path ran, and on the host path the down→up time this
 * process measured.
 */
class HostTouch(
    private val startHelper: () -> HostTouchHelper,
    /** The interface size in points XCTest reports now (DeviceInfo widthPoints, heightPoints). */
    private val interfaceSize: () -> Pair<Int, Int>,
) : AutoCloseable {

    private var helper: HostTouchHelper? = null
    private var retired: String? = null
    private var unusable = ""
    private var orientation = HostTouchGeometry.PORTRAIT
    private var screenChecked = false

    /**
     * Starts the helper now, or throws: with the switch on, a helper that will not start is a
     * failed session, never a silent fall back to XCTest.
     */
    @Synchronized
    fun start(): HostTouchHelper {
        helper?.let { return it }
        return try {
            startHelper().also { helper = it }
        } catch (e: Exception) {
            throw IllegalStateException(
                "[HostTouch] mode=on (-D$PROPERTY=on) but ios-host-touch did not start: ${e.message}. " +
                    "Fix the helper, or run with -D$PROPERTY=off to use XCTest.", e
            )
        }
    }

    /** Called with the orientation Maestro asked XCTest for; the screen is checked again. */
    @Synchronized
    fun orientationChanged(orientation: String) {
        this.orientation = orientation
        screenChecked = false
    }

    fun tap(x: Int, y: Int, holdMs: Long, stock: () -> Unit) {
        val what = "${if (holdMs == TAP_HOLD_MS) "tap" else "longPress"} ($x, $y) hold=${holdMs}ms"
        route(what, x.toDouble(), y.toDouble(), x.toDouble(), y.toDouble(), stock) { a, _ ->
            "tap ${f(a.first)} ${f(a.second)} $holdMs" to holdMs
        }
    }

    /**
     * A swipe the way XCTest's EventRecord.addSwipeEvent lands it, measured from backboardd: the
     * finger starts moving at the down, with NO hold, and moves for [TAP_HOLD_MS] + [durationMs]
     * (the helper's move follows XCTest's speed profile too). A swipe that holds first and then
     * moves for [durationMs] covers the same distance faster, releases faster, and a scroll view
     * flings further: 505 pt against XCTest's 406 pt for the same swipe (smithr#1313).
     */
    fun swipe(xStart: Double, yStart: Double, xEnd: Double, yEnd: Double, durationMs: Long, stock: () -> Unit) {
        val what = "swipe (${xStart.toInt()}, ${yStart.toInt()})->(${xEnd.toInt()}, ${yEnd.toInt()}) move=${durationMs}ms"
        val moveMs = TAP_HOLD_MS + durationMs
        route(what, xStart, yStart, xEnd, yEnd, stock) { a, b ->
            "swipe ${f(a.first)} ${f(a.second)} ${f(b.first)} ${f(b.second)} 0 $moveMs" to moveMs
        }
    }

    @Synchronized
    private fun route(
        what: String,
        x1: Double, y1: Double, x2: Double, y2: Double,
        stock: () -> Unit,
        command: (Pair<Double, Double>, Pair<Double, Double>) -> Pair<String, Long>,
    ) {
        val started = System.nanoTime()
        fun took() = (System.nanoTime() - started) / 1_000_000

        val refusal: String = run {
            val h = usableHelper() ?: return@run unusable
            val a = HostTouchGeometry.toPortraitFraction(x1, y1, orientation, h.portraitWidth, h.portraitHeight)
            val b = HostTouchGeometry.toPortraitFraction(x2, y2, orientation, h.portraitWidth, h.portraitHeight)
            if (a == null || b == null) return@run "off the $orientation screen"
            val (line, gestureMs) = command(a, b)
            when (val reply = h.request(line, gestureMs + REPLY_SLACK_MS)) {
                is HostTouchHelper.Reply.Ok -> {
                    logger.info("[HostTouch] $what path=host down->up=${reply.downUpMs}ms ack=${reply.ackMs}ms took=${took()}ms")
                    return
                }
                is HostTouchHelper.Reply.Refused -> return@run "helper refused: ${reply.reason}"
                is HostTouchHelper.Reply.Failed -> {
                    retire("helper failed after sending: ${reply.reason}")
                    logger.warn(
                        "[DriverIssues] [HostTouch] $what path=host FAILED after sending (${reply.reason}); " +
                            "not re-sent, since it may have landed. Later gestures use XCTest. took=${took()}ms"
                    )
                    return
                }
            }
        }
        stock()
        logger.info("[HostTouch] $what path=xctest reason=$refusal took=${took()}ms")
    }

    /** The running helper, started on first use, once its screen matches XCTest's; else null and [unusable] says why. */
    private fun usableHelper(): HostTouchHelper? {
        retired?.let { unusable = it; return null }
        val h = helper ?: try {
            startHelper().also { helper = it }
        } catch (e: Exception) {
            retire("helper did not start: ${e.message}")
            logger.warn("[DriverIssues] [HostTouch] ${retired}; every gesture of this session uses XCTest")
            unusable = retired!!
            return null
        }
        if (!h.isAlive) {
            retire("helper exited")
            logger.warn("[DriverIssues] [HostTouch] helper exited; every later gesture of this session uses XCTest")
            unusable = retired!!
            return null
        }
        if (!screenChecked) {
            if (orientation !in HostTouchGeometry.ORIENTATIONS) {
                unusable = "unknown orientation $orientation"
                return null
            }
            val (ew, eh) = HostTouchGeometry.expectedInterfaceSize(orientation, h.portraitWidth, h.portraitHeight)
            val (w, h2) = interfaceSize()
            if (Math.abs(w - ew) > 1 || Math.abs(h2 - eh) > 1) {
                unusable = "XCTest reports a ${w}x${h2} screen, the simulator is ${ew.toInt()}x${eh.toInt()} in $orientation"
                return null
            }
            screenChecked = true
        }
        return h
    }

    private fun retire(why: String) {
        retired = why
        helper?.close()
    }

    @Synchronized
    override fun close() {
        helper?.close()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(HostTouch::class.java)

        const val PROPERTY = "maestro.iosHostTouch"

        /** Where a build that carries the helper carries it, inside maestro-ios.jar. */
        const val RESOURCE = "/ios-host-touch/ios-host-touch"

        /** A different helper binary, for development; the switch must still be on. */
        const val HELPER_PROPERTY = "maestro.iosHostTouch.helper"

        /**
         * XCTest's own tap hold (EventRecord.defaultTapDuration). In a swipe XCTest adds it to the
         * move, not as a hold before it: see [swipe].
         */
        const val TAP_HOLD_MS = 100L

        /** Beyond the gesture's own length, how long an answer may take before it counts as lost. */
        private const val REPLY_SLACK_MS = 10_000L

        private fun f(v: Double) = "%.6f".format(java.util.Locale.ROOT, v)

        /**
         * A gesture of a session with no HostTouch (mode=off, or a physical iPhone): [gesture], the
         * XCTest call exactly as before, then the gesture's one log line, so a CI log shows the
         * path of every gesture whatever the mode.
         */
        fun xctest(what: String, gesture: () -> Unit) {
            val started = System.nanoTime()
            gesture()
            logger.info("[HostTouch] $what path=xctest mode=off took=${(System.nanoTime() - started) / 1_000_000}ms")
        }

        /**
         * -Dmaestro.iosHostTouch=on|off, default off: whether it is on, and where that came from,
         * for the session's one mode line. Anything but exactly "on" or "off" is off, and says so.
         */
        fun mode(value: String? = System.getProperty(PROPERTY)): Pair<Boolean, String> = when (value) {
            "on" -> true to "-D$PROPERTY=on"
            "off" -> false to "-D$PROPERTY=off"
            null -> false to "-D$PROPERTY unset, the default"
            else -> false to "-D$PROPERTY=$value is neither on nor off, so OFF"
        }

        fun isOn(value: String? = System.getProperty(PROPERTY)): Boolean = mode(value).first

        /**
         * The HostTouch for a device, or null — and null means XCTest exactly as before: always
         * for a physical iPhone, and for a simulator unless the switch is on. Logs the session's
         * mode once. With the switch on, the helper is started HERE and a helper that does not
         * start fails the session: a run must never claim host touch while using XCTest.
         */
        fun forDevice(
            udid: String,
            deviceType: IOSDeviceType,
            interfaceSize: () -> Pair<Int, Int>,
            switch: String? = System.getProperty(PROPERTY),
            helperCommand: (udid: String) -> List<String> = { listOf(helperBinary().toString(), it) },
        ): HostTouch? {
            val (on, source) = mode(switch)
            if (deviceType != IOSDeviceType.SIMULATOR) {
                logger.info("[HostTouch] mode=off (physical iPhone $udid: always XCTest; $source applies to simulators only)")
                return null
            }
            if (!on) {
                val log: (String) -> Unit = if (switch == null || switch == "off") logger::info else logger::warn
                log("[HostTouch] mode=off (source: $source); gestures use XCTest")
                return null
            }
            val touch = HostTouch(
                startHelper = { HostTouchHelper(helperCommand(udid), name = udid) },
                interfaceSize = interfaceSize,
            )
            val helper = touch.start()
            logger.info(
                "[HostTouch] mode=on (source: $source); simulator $udid (${helper.deviceName}): tap, long press " +
                    "and swipe go through its built-in digitizer; CoreSimulator ${helper.coreSimulatorVersion}"
            )
            return touch
        }

        /**
         * The helper binary: -Dmaestro.iosHostTouch.helper, else the copy this build carries,
         * written once per content to the temp directory so concurrent sessions share it.
         */
        fun helperBinary(): Path {
            System.getProperty(HELPER_PROPERTY)?.let { return Paths.get(it) }
            val bytes = HostTouch::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
                ?: throw IllegalStateException("this Maestro build carries no ios-host-touch (it was not built on macOS)")
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val dir = Paths.get(System.getProperty("java.io.tmpdir"), "maestro-ios-host-touch-${digest.take(16)}")
            val target = dir.resolve("ios-host-touch")
            if (Files.isExecutable(target) && Files.size(target) == bytes.size.toLong()) return target
            Files.createDirectories(dir)
            val partial = Files.createTempFile(dir, "ios-host-touch", ".partial")
            Files.write(partial, bytes)
            partial.toFile().setExecutable(true, false)
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return target
        }
    }
}
