package maestro.utils

import com.github.romankh3.image.comparison.ImageComparison
import maestro.Driver
import maestro.ViewHierarchy
import maestro.utils.network.XCUITestServerError
import okio.Buffer
import okio.Sink
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class ScreenshotUtils {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(ScreenshotUtils::class.java)

        fun takeScreenshot(out: Sink, compressed: Boolean, driver: Driver) {
            LOGGER.trace("Taking screenshot to output sink")

            driver.takeScreenshot(out, compressed)
        }

        fun takeScreenshot(compressed: Boolean, driver: Driver): ByteArray {
            LOGGER.trace("Taking screenshot to byte array")

            val buffer = Buffer()
            takeScreenshot(buffer, compressed, driver)

            return buffer.readByteArray()
        }

        fun tryTakingScreenshot(driver: Driver) = try {
            ImageIO.read(takeScreenshot(true, driver).inputStream())
        } catch (e: Exception) {
            LOGGER.warn("Failed to take screenshot", e)
            null
        }

        fun waitForAppToSettle(
            initialHierarchy: ViewHierarchy?,
            driver: Driver,
            timeoutMs: Int? = null
        ): ViewHierarchy {
            val stats = SettleStats.of(driver)
            val call = stats.newSettleCall()
            val startMs = System.currentTimeMillis()
            return stats.phase(SettleStats.Phase.SETTLE) {
                try {
                    settle(initialHierarchy, driver, timeoutMs, call)
                } finally {
                    if (call != null) {
                        call.ms = System.currentTimeMillis() - startMs
                        stats.recordSettleCall(call)
                    }
                }
            }
        }

        /**
         * The upstream settle loop, unchanged in behaviour; [call] only observes it (smithr#1246).
         * Exit reasons: `equal`, `cap` (10 dumps), `deadline` (timeout variant), `loading` (the
         * last comparison was equal but is-loading kept it going), `error` (a dump threw), `miss`.
         *
         * artha#5049 / Maestro#3: a dump that MISSES — an [XCUITestServerError.TransientIssue], the
         * runner's 500 "XCTest recorded an issue during /viewHierarchy" (e.g. kAXErrorInvalidUIElement,
         * "Failed to resolve remote element") — is "ask again": it uses up its turn in the loop and
         * is never compared. If the loop ENDS on a miss, the miss is rethrown (exit `miss`): the
         * settle never returns a hierarchy older than its last dump. Any other error stays fatal.
         */
        private fun settle(
            initialHierarchy: ViewHierarchy?,
            driver: Driver,
            timeoutMs: Int?,
            call: SettleStats.SettleCall?,
        ): ViewHierarchy {
            var lastMiss: XCUITestServerError.TransientIssue? = null
            fun dump(): ViewHierarchy? = try {
                viewHierarchy(driver, call).also { lastMiss = null }
            } catch (miss: XCUITestServerError.TransientIssue) {
                lastMiss = miss
                call?.let { it.misses++ }
                LOGGER.warn("[DriverIssues] settle dump missed on /${miss.endpoint}; asking again")
                null
            }

            // null only while every dump so far has missed
            var latestHierarchy: ViewHierarchy? = initialHierarchy ?: dump()
            fun settledOn(hierarchyAfter: ViewHierarchy?): Boolean {
                val before = latestHierarchy
                if (hierarchyAfter != null && before != null && compare(before, hierarchyAfter, call)) {
                    val isLoading = before.root.attributes.getOrDefault("is-loading", "false").toBoolean()
                    if (!isLoading) {
                        call?.exit = "equal"
                        return true
                    }
                    call?.lastEqualButLoading = true
                }
                latestHierarchy = hierarchyAfter ?: before
                return false
            }

            if (timeoutMs != null) {
                val endTime = System.currentTimeMillis() + timeoutMs
                do {
                    val hierarchyAfter = dump()
                    if (settledOn(hierarchyAfter)) return hierarchyAfter!!
                } while (System.currentTimeMillis() < endTime)
                call?.exit = if (call?.lastEqualButLoading == true) "loading" else "deadline"
            } else {
                repeat(10) {
                    val hierarchyAfter = dump()
                    if (settledOn(hierarchyAfter)) return hierarchyAfter!!

                    MaestroTimer.sleep(MaestroTimer.Reason.WAIT_TO_SETTLE, 200)
                }
                call?.exit = if (call?.lastEqualButLoading == true) "loading" else "cap"
            }

            // a poll that ENDS on a miss rethrows it — no answer it did not see
            lastMiss?.let {
                call?.exit = "miss"
                throw it
            }
            return latestHierarchy!!
        }

        /** `==`, exactly as upstream; on a mismatch, classifies why (in memory, no device call). */
        private fun compare(before: ViewHierarchy, after: ViewHierarchy, call: SettleStats.SettleCall?): Boolean {
            val equal = before == after
            if (call != null) {
                call.comparisons++
                if (!equal) {
                    // `loading` means the LAST comparison was equal-but-loading; a mismatch clears it
                    call.lastEqualButLoading = false
                    call.mismatches.add(SettleStats.classifyMismatch(before.root, after.root))
                }
            }
            return equal
        }

        fun waitUntilScreenIsStatic(timeoutMs: Long, threshold: Double, driver: Driver): Boolean {
            // smithr#1246 Phase C: measured as an `anim` screen call; -Dmaestro.fastAnim=on reuses
            // each screenshot as the next comparison's first and decides with ScreenStatic.sameScreen
            val stats = SettleStats.of(driver)
            val call = stats.newScreenCall("anim")
            val startMs = System.currentTimeMillis()
            val fast = ScreenStatic.fastAnimEnabled()
            call?.path = if (fast) "fast" else "upstream"
            var result = false
            try {
                result = if (fast) {
                    ScreenStatic.waitUntilStatic(
                        timeoutMs = timeoutMs,
                        fast = true,
                        clock = System::currentTimeMillis,
                        capture = { tryTakingScreenshotFrame(driver) },
                        same = { a, b -> ScreenStatic.sameScreen(a, b, threshold, call) },
                        call = call,
                    )
                } else {
                    // upstream: two fresh screenshots per attempt, ImageComparison on both
                    ScreenStatic.waitUntilStatic(
                        timeoutMs = timeoutMs,
                        fast = false,
                        clock = System::currentTimeMillis,
                        capture = { tryTakingScreenshot(driver) },
                        same = { start: BufferedImage, end: BufferedImage ->
                            start.width == end.width &&
                                start.height == end.height &&
                                ImageComparison(start, end).compareImages().differencePercent <= threshold
                        },
                        call = call,
                    )
                }
                return result
            } finally {
                if (call != null) {
                    call.ms = System.currentTimeMillis() - startMs
                    call.exit = if (result) "static" else "timeout"
                    stats.recordScreenCall(call)
                }
            }
        }

        /** fastAnim: a screenshot kept as its bytes, decoded only if a comparison needs pixels. */
        private fun tryTakingScreenshotFrame(driver: Driver): ScreenStatic.Frame? = try {
            ScreenStatic.Frame(takeScreenshot(true, driver)) { ImageIO.read(it.inputStream()) }
        } catch (e: Exception) {
            LOGGER.warn("Failed to take screenshot", e)
            null
        }

        private fun viewHierarchy(driver: Driver, call: SettleStats.SettleCall?): ViewHierarchy {
            if (call == null) return ViewHierarchy.from(driver, false)
            val startMs = System.currentTimeMillis()
            return ViewHierarchy.from(driver, false).also {
                call.dumpMs.add(System.currentTimeMillis() - startMs)
            }
        }
    }
}
