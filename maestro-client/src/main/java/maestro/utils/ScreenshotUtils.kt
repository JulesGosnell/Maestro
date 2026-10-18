package maestro.utils

import com.github.romankh3.image.comparison.ImageComparison
import maestro.Driver
import maestro.ViewHierarchy
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
         * last comparison was equal but is-loading kept it going), `error` (a dump threw).
         */
        private fun settle(
            initialHierarchy: ViewHierarchy?,
            driver: Driver,
            timeoutMs: Int?,
            call: SettleStats.SettleCall?,
        ): ViewHierarchy {
            var latestHierarchy: ViewHierarchy
            if (timeoutMs != null) {
                val endTime = System.currentTimeMillis() + timeoutMs
                latestHierarchy = initialHierarchy ?: viewHierarchy(driver, call)
                do {
                    val hierarchyAfter = viewHierarchy(driver, call)
                    if (compare(latestHierarchy, hierarchyAfter, call)) {
                        val isLoading = latestHierarchy.root.attributes.getOrDefault("is-loading", "false").toBoolean()
                        if (!isLoading) {
                            call?.exit = "equal"
                            return hierarchyAfter
                        }
                        call?.lastEqualButLoading = true
                    }
                    latestHierarchy = hierarchyAfter
                } while (System.currentTimeMillis() < endTime)
                call?.exit = if (call?.lastEqualButLoading == true) "loading" else "deadline"
            } else {
                latestHierarchy = initialHierarchy ?: viewHierarchy(driver, call)
                repeat(10) {
                    val hierarchyAfter = viewHierarchy(driver, call)
                    if (compare(latestHierarchy, hierarchyAfter, call)) {
                        val isLoading = latestHierarchy.root.attributes.getOrDefault("is-loading", "false").toBoolean()
                        if (!isLoading) {
                            call?.exit = "equal"
                            return hierarchyAfter
                        }
                        call?.lastEqualButLoading = true
                    }
                    latestHierarchy = hierarchyAfter

                    MaestroTimer.sleep(MaestroTimer.Reason.WAIT_TO_SETTLE, 200)
                }
                call?.exit = if (call?.lastEqualButLoading == true) "loading" else "cap"
            }

            return latestHierarchy
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
