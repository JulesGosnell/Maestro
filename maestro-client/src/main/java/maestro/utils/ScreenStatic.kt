package maestro.utils

import com.github.romankh3.image.comparison.ImageComparison
import maestro.utils.network.XCUITestServerError
import java.awt.image.BufferedImage

/**
 * smithr#1246 Phase C: cheaper "is the screen static?" checks, OFF unless asked for.
 *
 *  - `-Dmaestro.fastAnim=on`: [ScreenshotUtils.waitUntilScreenIsStatic] (waitForAnimationToEnd,
 *    on every platform) compares each new screenshot with the PREVIOUS one instead of taking two
 *    fresh screenshots per attempt, and decides each comparison with [sameScreen], which gives
 *    exactly the upstream verdict but skips the decode and the ImageComparison when it can.
 *  - `-Dmaestro.fastStatic=on`: IOSDriver's settle check loops ON THE RUNNER, comparing raw
 *    pixels, when the runner advertises `screenStatic` in GET /capabilities. A runner without it
 *    (every physical iPhone, which runs with --no-reinstall-driver) keeps the upstream check.
 *
 * Off, every path is the upstream behaviour.
 */
object ScreenStatic {

    const val ANIM_PROPERTY = "maestro.fastAnim"
    const val STATIC_PROPERTY = "maestro.fastStatic"

    fun fastAnimEnabled(): Boolean = on(ANIM_PROPERTY)
    fun fastStaticEnabled(): Boolean = on(STATIC_PROPERTY)

    private fun on(property: String) = System.getProperty(property)?.trim()?.lowercase() == "on"

    /**
     * ImageComparison's per-pixel rule (pixelToleranceLevel 0.1, its default): two ARGB values
     * differ when they are not identical AND their RGB distance squared exceeds this.
     */
    private val DIFFERENCE_CONSTANT = Math.pow(0.1 * Math.sqrt(Math.pow(255.0, 2.0) * 3), 2.0)

    /** One screenshot: the bytes the driver returned, decoded at most once and only if needed. */
    class Frame(val bytes: ByteArray, private val decoder: (ByteArray) -> BufferedImage?) {
        private var decoded = false
        private var image: BufferedImage? = null

        fun image(): BufferedImage? {
            if (!decoded) {
                decoded = true
                image = try {
                    decoder(bytes)
                } catch (e: Exception) {
                    null
                }
            }
            return image
        }
    }

    /**
     * The upstream verdict — `ImageComparison(a, b).compareImages().differencePercent <= threshold`
     * on the decoded images, false when either does not decode or their sizes differ — reached
     * as cheaply as possible:
     *  1. identical PNG/JPEG bytes decode to identical images: ImageComparison finds no
     *     different pixel and reports 0, which is static;
     *  2. no pixel differs by ImageComparison's own per-pixel rule: the same "match, 0";
     *  3. otherwise ImageComparison itself decides.
     */
    fun sameScreen(a: Frame, b: Frame, threshold: Double, call: SettleStats.ScreenCall? = null): Boolean {
        if (looksLikeAnImage(a.bytes) && a.bytes.contentEquals(b.bytes)) {
            call?.let { it.byteEqual++ }
            return true
        }
        val ia = a.image() ?: return false
        val ib = b.image() ?: return false
        if (ia.width != ib.width || ia.height != ib.height) return false
        if (!anyPixelDiffers(ia, ib)) {
            call?.let { it.pixelEqual++ }
            return true
        }
        call?.let { it.fullCompares++ }
        return ImageComparison(ia, ib).compareImages().differencePercent <= threshold
    }

    /**
     * The byte shortcut skips the decode, so it is taken only for bytes that start like a PNG or
     * a JPEG (what the drivers return): an empty or garbage payload that would not decode is never
     * "static" by the shortcut — it goes the upstream way and fails the attempt.
     */
    internal fun looksLikeAnImage(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        return png || jpeg
    }

    /** ImageComparison.populateTheMatrixOfTheDifferences' test, without its per-pixel matrix and allocations. */
    fun anyPixelDiffers(a: BufferedImage, b: BufferedImage): Boolean {
        val w = a.width
        val rowA = IntArray(w)
        val rowB = IntArray(w)
        for (y in 0 until a.height) {
            a.getRGB(0, y, w, 1, rowA, 0, w)
            b.getRGB(0, y, w, 1, rowB, 0, w)
            for (x in 0 until w) {
                val p = rowA[x]
                val q = rowB[x]
                if (p != q && pixelsDiffer(p, q)) return true
            }
        }
        return false
    }

    internal fun pixelsDiffer(p: Int, q: Int): Boolean {
        val dr = ((q shr 16) and 0xff) - ((p shr 16) and 0xff)
        val dg = ((q shr 8) and 0xff) - ((p shr 8) and 0xff)
        val db = (q and 0xff) - (p and 0xff)
        return (dr * dr + dg * dg + db * db).toDouble() > DIFFERENCE_CONSTANT
    }

    /**
     * The waitForAnimationToEnd loop, with time and the device injected.
     *
     * fast = false (upstream, MaestroTimer.retryUntilTrue): every attempt takes TWO fresh
     * screenshots and compares them; an exception fails the attempt; attempts repeat until
     * [timeoutMs] has elapsed (at least one attempt).
     *
     * fast = true: every attempt takes ONE screenshot and compares it with the previous one, so
     * each comparison is still between two consecutive captures, at half the device calls after
     * the first. At least one comparison is attempted (two captures), as upstream.
     */
    fun <F : Any> waitUntilStatic(
        timeoutMs: Long,
        fast: Boolean,
        clock: () -> Long,
        capture: () -> F?,
        same: (F, F) -> Boolean,
        call: SettleStats.ScreenCall? = null,
    ): Boolean {
        val endTime = clock() + timeoutMs
        if (!fast) {
            do {
                try {
                    call?.let { it.attempts++ }
                    val start = capture().also { call?.let { c -> c.frames++ } }
                    val end = capture().also { call?.let { c -> c.frames++ } }
                    if (start != null && end != null && same(start, end)) return true
                } catch (ignored: Exception) {
                }
            } while (clock() < endTime)
            return false
        }

        var previous: F? = null
        var captures = 0
        do {
            val current = try {
                capture()
            } catch (ignored: Exception) {
                null
            }
            captures++
            call?.let { it.frames++ }
            // a failed capture breaks the chain: only CONSECUTIVE captures are ever compared
            val before = previous
            previous = current
            if (before != null && current != null) {
                call?.let { it.attempts++ }
                val same = try {
                    same(before, current)
                } catch (ignored: Exception) {
                    false
                }
                if (same) return true
            }
        } while (clock() < endTime || captures < 2)
        return false
    }

    /** What the runner's POST /screenStatic answered. */
    data class RunnerAnswer(val static: Boolean, val attempts: Int, val frames: Int)

    /**
     * The iOS settle's screen-static check (IOSDriver.waitUntilScreenIsStatic).
     *
     * Upstream: [upstreamAttempt] (GET /isScreenStatic: two screenshots, two PNG encodes and two
     * SHA256s on the runner) repeated until true or [timeoutMs] has elapsed.
     *
     * fastStatic, when [runnerHasIt]: ONE call to [runner], which loops on the runner comparing
     * each screenshot's raw pixels with the previous one's until two are equal or [timeoutMs]
     * has elapsed. The verdict is the same — equal PNGs are equal pixels — at one HTTP round trip,
     * no PNG encode and, after the first attempt, one screenshot per attempt. If the runner call
     * fails, the upstream loop runs with the full timeout, as if fastStatic were off.
     */
    fun iosSettleStatic(
        timeoutMs: Long,
        fast: Boolean,
        runnerHasIt: () -> Boolean,
        runner: (Long) -> RunnerAnswer,
        upstreamAttempt: () -> Boolean,
        call: SettleStats.ScreenCall? = null,
    ): Boolean {
        if (fast && runnerHasIt()) {
            try {
                val answer = runner(timeoutMs)
                call?.let { it.path = "runner"; it.attempts += answer.attempts; it.frames += answer.frames }
                return answer.static
            } catch (e: Exception) {
                call?.let { it.path = "runner-failed" }
            } catch (miss: XCUITestServerError.TransientIssue) {
                // artha#5049: a miss on /screenStatic is a Throwable, not an Exception — same fallback
                call?.let { it.path = "runner-failed" }
            }
        }
        return MaestroTimer.retryUntilTrue(timeoutMs) {
            call?.let { it.attempts++; it.frames += 2 }
            upstreamAttempt()
        }
    }
}
