package ios.xctest

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * artha#5055. The fixtures are backboardd's TouchEvents lines from real CI sim logs (compact
 * style, VM clock UTC): the three known dropped taps (two "bad usage pair", one "accessory")
 * and a passing family login whose 54 taps were all delivered.
 */
class TouchVerdictsTest {

    private data class Line(val at: Instant, val message: String)

    private val compactTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private fun lines(name: String): List<Line> =
        javaClass.getResource("/artha-5055/$name")!!.readText().lines().filter { it.isNotBlank() }.map {
            Line(
                at = LocalDateTime.parse(it.substring(0, 23), compactTime).toInstant(ZoneOffset.UTC),
                message = it.substring(it.indexOf("] ") + 2),
            )
        }

    private fun TouchVerdicts.accept(line: Line) = accept(line.at, line.message)

    /** Every touch in a log, judged on a window that holds its attach instant alone. */
    private fun dropsIn(name: String): List<String> {
        val all = lines(name)
        val verdicts = TouchVerdicts()
        all.forEach { verdicts.accept(it) }
        return all.filter { "init: <BKDirectTouchState" in it.message }
            .filter { verdicts.droppedBetween(it.at, it.at) != null }
            .map { it.at.toString().substring(11, 23) }
    }

    @Test
    fun `prog 39312347609 - only the Sign In touch is dropped (bad usage pair)`() {
        assertThat(dropsIn("prog-39312347609-badpair.log")).containsExactly("19:49:27.684")
    }

    @Test
    fun `zeug 39367562537 - only the button-org-0 touch is dropped (accessory)`() {
        assertThat(dropsIn("zeug-39367562537-accessory.log")).containsExactly("04:14:39.948")
    }

    @Test
    fun `famlogin r3 - only the button-org-0 touch is dropped (bad usage pair)`() {
        assertThat(dropsIn("famlogin-r3-badpair.log")).containsExactly("17:15:17.179")
    }

    @Test
    fun `a passing login - none of its 54 touches is dropped`() {
        val all = lines("famlogin-r2-all-delivered.log")
        assertThat(all.count { "init: <BKDirectTouchState" in it.message }).isEqualTo(54)
        assertThat(dropsIn("famlogin-r2-all-delivered.log")).isEmpty()
    }

    // ---- send(): the re-send rule. A fake clock; each tap emits its touch's lines at its own instant ----

    private val prog = lines("prog-39312347609-badpair.log")

    /** The lines of the touch that attached at [hhmmss] in the prog log. */
    private fun touch(hhmmss: String): List<Line> {
        val init = prog.first { "init:" in it.message && it.at.toString().substring(11, 23) == hhmmss }
        val sender = Regex("senderID: 0x([0-9A-F]+)").find(init.message)!!.groupValues[1].lowercase()
        return prog.filter { it == init || ("unsupported service" in it.message && "id:0x$sender" in it.message) }
    }

    private val dropped = touch("19:49:27.684")
    private val delivered = touch("19:49:22.624")

    private class Clock(var now: Instant)

    /**
     * One tap through [send]. Each send moves the clock to the touch's attach instant, emits
     * [emitted] for that send (null = nothing), and returns 1 s later. Returns (sends, evidence).
     */
    private fun tap(
        verdicts: TouchVerdicts,
        vararg emitted: List<Line>?,
        start: Instant = Instant.parse("2026-10-19T19:49:20Z"),
    ): Pair<Int, List<String>> {
        val clock = Clock(start)
        var sends = 0
        val evidence = mutableListOf<String>()
        verdicts.send(now = { clock.now }, tap = {
            val lines = emitted.getOrNull(sends++)
            lines?.firstOrNull()?.let { clock.now = maxOf(clock.now, it.at) }
            lines?.forEach { verdicts.accept(it) }
            clock.now = clock.now.plusSeconds(1)
        }) { evidence += it }
        return sends to evidence
    }

    @Test
    fun `bad - a dropped tap is sent exactly once more, with backboardd's line as evidence`() {
        val (sends, evidence) = tap(TouchVerdicts(), dropped, delivered.map { it.copy(at = it.at.plusSeconds(30)) })
        assertThat(sends).isEqualTo(2)
        assertThat(evidence).hasSize(1)
        assertThat(evidence[0]).contains("bad usage pair: (0x0 / 0x4)")
    }

    @Test
    fun `bad - the re-send is ONE, even when it is dropped too`() {
        val (sends, _) = tap(TouchVerdicts(), dropped, dropped.map { it.copy(at = it.at.plusSeconds(30)) })
        assertThat(sends).isEqualTo(2)
    }

    @Test
    fun `bad - the ignored-event lines alone are evidence`() {
        val init = dropped.first()
        val good = init.copy(message = init.message.replace("bad usage pair: (0x0 / 0x4); builtin;", "touchScreen; builtin;"))
        val (sends, evidence) = tap(TouchVerdicts(), listOf(good) + dropped.drop(1))
        assertThat(sends).isEqualTo(2)
        assertThat(evidence.single()).contains("from unsupported service")
    }

    @Test
    fun `good - a delivered tap is never sent again`() {
        val (sends, evidence) = tap(TouchVerdicts(), delivered)
        assertThat(sends).isEqualTo(1)
        assertThat(evidence).isEmpty()
    }

    @Test
    fun `none - a tap backboardd said nothing about is never sent again, and nothing waits`() {
        val t0 = System.nanoTime()
        val (sends, _) = tap(TouchVerdicts(), null)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertThat(sends).isEqualTo(1)
        assertThat(ms).isLessThan(100L)
    }

    @Test
    fun `late - a drop logged only after the tap returned is not acted on`() {
        val verdicts = TouchVerdicts()
        val (sends, _) = tap(verdicts, emptyList())
        dropped.forEach { verdicts.accept(it) }
        assertThat(sends).isEqualTo(1)
    }

    @Test
    fun `stale - an earlier touch's drop does not condemn a later tap`() {
        val verdicts = TouchVerdicts()
        dropped.forEach { verdicts.accept(it) }
        val later = delivered.map { it.copy(at = it.at.plusSeconds(60)) }
        val (sends, _) = tap(verdicts, later, start = dropped.first().at.plusSeconds(10))
        assertThat(sends).isEqualTo(1)
    }

    @Test
    fun `other touch - a drop attached after this tap returned is not this tap's`() {
        val verdicts = TouchVerdicts()
        // this tap's touch is delivered; another touch, attached 5 s after it returned, was dropped
        // and its lines reach the stream before this tap's verdict is read
        val clock = Clock(Instant.parse("2026-10-19T19:49:22Z"))
        var sends = 0
        verdicts.send(now = { clock.now }, tap = {
            sends++
            delivered.forEach { verdicts.accept(it) }
            dropped.forEach { verdicts.accept(it.copy(at = Instant.parse("2026-10-19T19:49:29Z"))) }
            clock.now = Instant.parse("2026-10-19T19:49:24Z")
        }) {}
        assertThat(sends).isEqualTo(1)
    }

    // ---- the stream ----

    private val ndjsonDropped = """{"messageType":"Error","subsystem":"com.apple.BackBoard","category":"TouchEvents",""" +
        """"timestamp":"2026-10-19 19:49:27.684123+0000","eventMessage":${com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dropped.first().message)},"processID":46855}"""

    @Test
    fun `ndjson - a log stream line is read with its own timestamp`() {
        val verdicts = TouchVerdicts()
        assertThat(TouchVerdicts.acceptNdjson(verdicts, "Filtering the log data using ...")).isFalse()
        assertThat(TouchVerdicts.acceptNdjson(verdicts, ndjsonDropped)).isTrue()
        val at = Instant.parse("2026-10-19T19:49:27.684123Z")
        assertThat(verdicts.droppedBetween(at, at)).contains("bad usage pair")
        assertThat(verdicts.droppedBetween(at.plusNanos(1000), at.plusSeconds(1))).isNull()
    }

    @Test
    fun `ndjson - a real line from a live zeuglodon stream is read, and judged delivered`() {
        val line = javaClass.getResource("/artha-5055/zeug-live-ndjson-init.json")!!.readText().trim()
        val at = Instant.parse("2026-10-20T05:17:32.019640Z")

        val verdicts = TouchVerdicts()
        assertThat(TouchVerdicts.acceptNdjson(verdicts, line)).isTrue()
        assertThat(verdicts.droppedBetween(at, at)).isNull()

        val bad = TouchVerdicts()
        assertThat(TouchVerdicts.acceptNdjson(bad, line.replace("touchScreen; builtin;", "bad usage pair: (0x0 / 0x4); builtin;"))).isTrue()
        assertThat(bad.droppedBetween(at, at)).contains("bad usage pair")
    }

    /** A `sleep <n>` process itself — not a shell whose command line merely mentions it. */
    private fun isSleep(p: ProcessHandle, n: String) =
        p.info().command().orElse("").endsWith("/sleep") && p.info().arguments().orElse(emptyArray()).toList() == listOf(n)

    private fun stream(script: String) = TouchVerdicts.SimulatorStream(listOf("/bin/sh", "-c", script), name = "test")

    @Test
    fun `dead stream - its verdicts are not used, so a dropped tap is not re-sent`() {
        // the stream reports the drop, then dies
        val s = stream("printf '%s\\n' '$ndjsonDropped'")
        val deadline = System.currentTimeMillis() + 10_000
        while (s.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertThat(s.isAlive).isFalse()
        val at = Instant.parse("2026-10-19T19:49:27.684123Z")
        assertThat(s.verdicts.droppedBetween(at, at)).isNotNull() // the evidence did arrive

        var sends = 0
        s.send(now = { at }, tap = { sends++ }) {}
        assertThat(sends).isEqualTo(1)
        s.close()
    }

    @Test
    fun `live stream - its verdicts are used`() {
        val s = stream("printf '%s\\n' '$ndjsonDropped'; exec sleep 30")
        val at = Instant.parse("2026-10-19T19:49:27.684123Z")
        val deadline = System.currentTimeMillis() + 10_000
        while (s.verdicts.droppedBetween(at, at) == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertThat(s.isAlive).isTrue()

        var sends = 0
        s.send(now = { at }, tap = { sends++ }) {}
        assertThat(sends).isEqualTo(2)
        s.close()
    }

    @Test
    fun `lifecycle - close leaves no process behind`() {
        val s = stream("exec sleep 6011")
        val deadline = System.currentTimeMillis() + 10_000
        while (ProcessHandle.current().descendants().noneMatch { isSleep(it, "6011") } &&
            System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertThat(ProcessHandle.current().descendants().anyMatch { isSleep(it, "6011") }).isTrue()

        s.close()

        val gone = System.currentTimeMillis() + 10_000
        while (ProcessHandle.allProcesses().anyMatch { isSleep(it, "6011") } &&
            System.currentTimeMillis() < gone) Thread.sleep(20)
        assertThat(ProcessHandle.allProcesses().noneMatch { isSleep(it, "6011") }).isTrue()
    }

    @Test
    fun `overhead - reading the verdict after a tap costs microseconds, never a wait`() {
        val verdicts = TouchVerdicts()
        val base = Instant.parse("2026-10-19T19:00:00Z")
        // a full history: 256 touches already seen, one of them dropped
        repeat(256) { i -> delivered.forEach { verdicts.accept(it.copy(at = base.plusSeconds(i.toLong()))) } }
        val samples = LongArray(20_000)
        for (i in samples.indices) {
            val t0 = System.nanoTime()
            verdicts.droppedBetween(base.plusSeconds(300), base.plusSeconds(301))
            samples[i] = System.nanoTime() - t0
        }
        samples.sort()
        val p50us = samples[samples.size / 2] / 1000.0
        val maxUs = samples.last() / 1000.0
        println("TAP-VERDICT-OVERHEAD p50=${p50us}us max=${maxUs}us n=${samples.size}")
        assertThat(p50us).isLessThan(1000.0)
    }
}
