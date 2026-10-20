package maestro.utils

import com.github.romankh3.image.comparison.ImageComparison
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random

/** smithr#1246 Phase C: fastAnim (waitForAnimationToEnd) and fastStatic (iOS settle) logic. */
internal class ScreenStaticTest {

    @AfterEach
    fun clear() {
        System.clearProperty(ScreenStatic.ANIM_PROPERTY)
        System.clearProperty(ScreenStatic.STATIC_PROPERTY)
        SettleStats.listener = null
    }

    // ---------------------------------------------------------------- the loop

    /** A screen whose successive captures are [frames] (the last repeats), each costing 100 ms. */
    private class Screen(vararg val frames: String?) {
        var now = 0L
        var captures = 0
        val compared = mutableListOf<String>()

        fun run(fast: Boolean, timeoutMs: Long = 1000, call: SettleStats.ScreenCall? = null): Boolean =
            ScreenStatic.waitUntilStatic(
                timeoutMs = timeoutMs,
                fast = fast,
                clock = { now },
                capture = {
                    val f = frames.getOrElse(captures) { frames.last() }
                    captures++
                    now += 100
                    if (f == "THROW") throw RuntimeException("capture failed")
                    f
                },
                same = { a, b -> compared += "$a$b"; a == b },
                call = call,
            )
    }

    @Test
    fun `off - two fresh captures per attempt, stops at the first equal pair (upstream)`() {
        val s = Screen("a", "b", "c", "d", "e", "e")
        assertThat(s.run(fast = false)).isTrue()
        assertThat(s.compared).containsExactly("ab", "cd", "ee").inOrder()
        assertThat(s.captures).isEqualTo(6)
    }

    @Test
    fun `on - each capture is compared with the previous one, half the captures after the first`() {
        val s = Screen("a", "b", "c", "d", "e", "e")
        assertThat(s.run(fast = true)).isTrue()
        assertThat(s.compared).containsExactly("ab", "bc", "cd", "de", "ee").inOrder()
        assertThat(s.captures).isEqualTo(6)
    }

    @Test
    fun `on - a static screen is decided by the same two captures as upstream`() {
        val off = Screen("a"); val on = Screen("a")
        assertThat(off.run(fast = false)).isTrue()
        assertThat(on.run(fast = true)).isTrue()
        assertThat(on.captures).isEqualTo(off.captures)
        assertThat(on.captures).isEqualTo(2)
    }

    @Test
    fun `on - a moving screen is never called static, and the loop ends at the deadline`() {
        val frames = (0 until 100).map { "f$it" }.toTypedArray()
        val s = Screen(*frames)
        assertThat(s.run(fast = true, timeoutMs = 1000)).isFalse()
        assertThat(s.now).isEqualTo(1000)
        assertThat(s.compared.none { it.substring(0, it.length / 2) == it.substring(it.length / 2) }).isTrue()
    }

    @Test
    fun `on - a zero timeout still makes one comparison, as upstream`() {
        val s = Screen("a")
        assertThat(s.run(fast = true, timeoutMs = 0)).isTrue()
        assertThat(s.compared).containsExactly("aa")
    }

    @Test
    fun `on - a failed capture breaks the chain, frames either side of it are not compared`() {
        val s = Screen("a", "THROW", "a", "b", "b")
        assertThat(s.run(fast = true)).isTrue()
        // "a" (1st) and "a" (3rd) are equal but not consecutive: never compared
        assertThat(s.compared).containsExactly("ab", "bb").inOrder()
    }

    @Test
    fun `on - a null capture breaks the chain too`() {
        val s = Screen("a", null, "a", "a")
        assertThat(s.run(fast = true)).isTrue()
        assertThat(s.compared).containsExactly("aa")
        assertThat(s.captures).isEqualTo(4)
    }

    @Test
    fun `attempts and frames are counted on both paths`() {
        val off = SettleStats.ScreenCall("anim"); val on = SettleStats.ScreenCall("anim")
        Screen("a", "b", "c", "c").run(fast = false, call = off)
        Screen("a", "b", "c", "c").run(fast = true, call = on)
        assertThat(off.attempts to off.frames).isEqualTo(2 to 4)
        assertThat(on.attempts to on.frames).isEqualTo(3 to 4)
    }

    // ---------------------------------------------------------------- the comparison

    private val threshold = 0.005

    private fun upstreamVerdict(a: BufferedImage, b: BufferedImage): Boolean =
        a.width == b.width && a.height == b.height &&
            ImageComparison(a, b).compareImages().differencePercent <= threshold

    private fun image(w: Int = 60, h: Int = 120, seed: Int = 1): BufferedImage {
        val r = Random(seed)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, r.nextInt(0x1000000))
        return img
    }

    private fun copy(src: BufferedImage): BufferedImage {
        val img = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until src.height) for (x in 0 until src.width) img.setRGB(x, y, src.getRGB(x, y))
        return img
    }

    private fun png(img: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()

    private fun frame(img: BufferedImage, decodes: IntArray? = null) =
        ScreenStatic.Frame(png(img)) { decodes?.let { d -> d[0]++ }; ImageIO.read(it.inputStream()) }

    @Test
    fun `the verdict equals ImageComparison's on identical, near, few-pixel and large differences`() {
        val r = Random(42)
        val cases = mutableListOf<Pair<BufferedImage, BufferedImage>>()
        repeat(40) { i ->
            val a = image(seed = i)
            val b = copy(a)
            when (i % 5) {
                0 -> {} // identical
                1 -> repeat(200) { // within the per-pixel tolerance: no pixel "differs"
                    val x = r.nextInt(a.width); val y = r.nextInt(a.height)
                    b.setRGB(x, y, (a.getRGB(x, y) and 0xfffff0) xor 0x000008)
                }
                2 -> b.setRGB(r.nextInt(a.width), r.nextInt(a.height), 0xffffff xor a.getRGB(0, 0)) // one pixel
                3 -> repeat(1 + r.nextInt(30)) { b.setRGB(r.nextInt(a.width), r.nextInt(a.height), r.nextInt(0x1000000)) }
                4 -> for (y in 0 until a.height / 2) for (x in 0 until a.width) b.setRGB(x, y, 0)
            }
            cases += a to b
        }
        var static = 0; var moving = 0
        cases.forEach { (a, b) ->
            val expected = upstreamVerdict(a, b)
            assertThat(ScreenStatic.sameScreen(frame(a), frame(b), threshold)).isEqualTo(expected)
            if (expected) static++ else moving++
        }
        // the sample must exercise both verdicts, or it proves nothing
        assertThat(static).isAtLeast(10)
        assertThat(moving).isAtLeast(10)
    }

    @Test
    fun `a single pixel past the tolerance is still decided by ImageComparison, not called static by the fast path`() {
        val a = BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB)
        val b = copy(a)
        b.setRGB(5, 5, 0xffffff) // 1 of 1e6 pixels, max difference
        val call = SettleStats.ScreenCall("anim")
        val verdict = ScreenStatic.sameScreen(frame(a), frame(b), threshold, call)
        assertThat(verdict).isEqualTo(upstreamVerdict(a, b))
        assertThat(call.fullCompares).isEqualTo(1)
        assertThat(call.pixelEqual).isEqualTo(0)
    }

    @Test
    fun `identical PNG bytes are static without decoding either frame`() {
        val img = image()
        val decodes = intArrayOf(0)
        val call = SettleStats.ScreenCall("anim")
        assertThat(ScreenStatic.sameScreen(frame(img, decodes), frame(img, decodes), threshold, call)).isTrue()
        assertThat(decodes[0]).isEqualTo(0)
        assertThat(call.byteEqual).isEqualTo(1)
    }

    @Test
    fun `identical bytes that are not an image are NOT static (upstream fails to decode them)`() {
        val garbage = ByteArray(64) { 7 }
        val a = ScreenStatic.Frame(garbage) { ImageIO.read(it.inputStream()) }
        val b = ScreenStatic.Frame(garbage.copyOf()) { ImageIO.read(it.inputStream()) }
        assertThat(ScreenStatic.sameScreen(a, b, threshold)).isFalse()
        assertThat(ScreenStatic.sameScreen(ScreenStatic.Frame(ByteArray(0)) { null }, ScreenStatic.Frame(ByteArray(0)) { null }, threshold)).isFalse()
    }

    @Test
    fun `frames of different sizes are never static`() {
        assertThat(ScreenStatic.sameScreen(frame(image(60, 120)), frame(image(60, 121)), threshold)).isFalse()
    }

    @Test
    fun `the per-pixel rule matches ImageComparison at the tolerance boundary`() {
        // threshold distance^2 = (0.1 * sqrt(3 * 255^2))^2 = 1950.75
        assertThat(ScreenStatic.pixelsDiffer(0x000000, (25 shl 16) or (25 shl 8) or 25)).isFalse() // 1875
        assertThat(ScreenStatic.pixelsDiffer(0x000000, (26 shl 16) or (25 shl 8) or 25)).isFalse() // 1926
        assertThat(ScreenStatic.pixelsDiffer(0x000000, (26 shl 16) or (26 shl 8) or 25)).isTrue()  // 1977
        assertThat(ScreenStatic.pixelsDiffer(0x000000, 45)).isTrue() // 2025
        assertThat(ScreenStatic.pixelsDiffer(0x000000, 44)).isFalse() // 1936
        // and agrees with the library either side of it (a 10x10 block on a 50x50 image)
        for (v in listOf(44, 45)) {
            val a = BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB)
            val b = BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB)
            for (y in 20 until 30) for (x in 20 until 30) b.setRGB(x, y, v)
            val lib = ImageComparison(a, b).compareImages().imageComparisonState == com.github.romankh3.image.comparison.model.ImageComparisonState.MISMATCH
            assertThat(ScreenStatic.anyPixelDiffers(a, b)).isEqualTo(lib)
        }
    }

    // ---------------------------------------------------------------- iOS settle static

    private class Runner(
        val answer: ScreenStatic.RunnerAnswer? = null,
        val advertised: Boolean = true,
        val failure: () -> Throwable = { RuntimeException("runner failed") },
    ) {
        var runnerCalls = 0
        var upstreamCalls = 0
        var probes = 0

        fun run(fast: Boolean, upstreamResults: List<Boolean> = listOf(true), call: SettleStats.ScreenCall? = null) =
            ScreenStatic.iosSettleStatic(
                timeoutMs = 3000,
                fast = fast,
                runnerHasIt = { probes++; advertised },
                runner = { runnerCalls++; answer ?: throw failure() },
                upstreamAttempt = { upstreamResults.getOrElse(upstreamCalls++) { upstreamResults.last() } },
                call = call,
            )
    }

    @Test
    fun `static off - upstream isScreenStatic until true, the runner is never asked`() {
        val r = Runner(ScreenStatic.RunnerAnswer(true, 1, 2))
        assertThat(r.run(fast = false, upstreamResults = listOf(false, false, true))).isTrue()
        assertThat(r.upstreamCalls).isEqualTo(3)
        assertThat(r.runnerCalls).isEqualTo(0)
        assertThat(r.probes).isEqualTo(0)
    }

    @Test
    fun `static on, runner without screenStatic (a physical iPhone's old runner) - upstream`() {
        val r = Runner(ScreenStatic.RunnerAnswer(true, 1, 2), advertised = false)
        assertThat(r.run(fast = true)).isTrue()
        assertThat(r.runnerCalls).isEqualTo(0)
        assertThat(r.upstreamCalls).isEqualTo(1)
    }

    @Test
    fun `static on, runner with screenStatic - ONE runner call decides, upstream never runs`() {
        val call = SettleStats.ScreenCall("static")
        val r = Runner(ScreenStatic.RunnerAnswer(false, 7, 8))
        assertThat(r.run(fast = true, call = call)).isFalse()
        assertThat(r.runnerCalls).isEqualTo(1)
        assertThat(r.upstreamCalls).isEqualTo(0)
        assertThat(call.path).isEqualTo("runner")
        assertThat(call.attempts to call.frames).isEqualTo(7 to 8)
    }

    @Test
    fun `static on, the runner call fails - the upstream loop runs instead`() {
        val call = SettleStats.ScreenCall("static")
        val r = Runner(answer = null)
        assertThat(r.run(fast = true, upstreamResults = listOf(false, true), call = call)).isTrue()
        assertThat(r.runnerCalls).isEqualTo(1)
        assertThat(r.upstreamCalls).isEqualTo(2)
        assertThat(call.path).isEqualTo("runner-failed")
    }

    @Test
    fun `static on, the runner call MISSES (TransientIssue) - the upstream loop runs instead`() {
        // artha#5049: a TransientIssue is a Throwable, not an Exception
        val call = SettleStats.ScreenCall("static")
        val r = Runner(answer = null, failure = {
            maestro.utils.network.XCUITestServerError.TransientIssue("screenStatic", "XCTest recorded an issue during /screenStatic")
        })
        assertThat(r.run(fast = true, upstreamResults = listOf(false, true), call = call)).isTrue()
        assertThat(r.runnerCalls).isEqualTo(1)
        assertThat(r.upstreamCalls).isEqualTo(2)
        assertThat(call.path).isEqualTo("runner-failed")
    }

    @Test
    fun `the flags are off unless set to on`() {
        assertThat(ScreenStatic.fastAnimEnabled()).isFalse()
        assertThat(ScreenStatic.fastStaticEnabled()).isFalse()
        System.setProperty(ScreenStatic.ANIM_PROPERTY, "true")
        assertThat(ScreenStatic.fastAnimEnabled()).isFalse()
        System.setProperty(ScreenStatic.ANIM_PROPERTY, " ON ")
        System.setProperty(ScreenStatic.STATIC_PROPERTY, "on")
        assertThat(ScreenStatic.fastAnimEnabled()).isTrue()
        assertThat(ScreenStatic.fastStaticEnabled()).isTrue()
    }

    // ---------------------------------------------------------------- instrumentation

    @Test
    fun `a screen call lands on its command line and in the flow summary`() {
        val lines = mutableListOf<String>()
        SettleStats.listener = { lines += it }
        val stats = SettleStats()
        val cmd = stats.beginCommand("WaitForAnimationToEndCommand")
        val call = stats.newScreenCall("anim")!!.also { it.ms = 123; it.attempts = 2; it.frames = 3; it.exit = "static"; it.path = "fast" }
        stats.recordScreenCall(call)
        stats.endCommand(cmd)
        stats.emitFlowSummary("f")
        val line = lines.first { it.startsWith(SettleStats.LOG_PREFIX) }
        assertThat(line).contains("\"anim\":123")
        assertThat(line).contains("\"static\":0")
        assertThat(line).contains("\"kind\":\"anim\",\"path\":\"fast\",\"ms\":123,\"exit\":\"static\",\"attempts\":2,\"frames\":3")
        val summary = lines.first { it.startsWith(SettleStats.SUMMARY_PREFIX) }
        assertThat(summary).contains("\"screens\":{\"anim\":{\"calls\":1,\"ms\":123,\"exit\":{\"static\":1},\"attempts\":{\"2\":1},\"path\":{\"fast\":1}}}")
        assertThat(summary).contains("\"anim\":123,\"static\":0")
    }
}
