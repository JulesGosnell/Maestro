package maestro.test

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.Driver
import maestro.Maestro
import maestro.TreeNode
import maestro.orchestra.Orchestra
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.yaml.YamlCommandReader
import maestro.test.drivers.FakeDriver
import maestro.test.drivers.FakeLayoutElement
import maestro.test.drivers.FakeLayoutElement.Bounds
import maestro.test.drivers.FakeTimer
import maestro.utils.MaestroTimer
import maestro.utils.ScreenshotUtils
import maestro.utils.SettleStats
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Paths

/** smithr#1246 Phase A: the settle/find instrument. Measure only. */
class SettleStatsTest {

    private val fakeTimer = FakeTimer()
    private val lines = mutableListOf<String>()
    private val mapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        MaestroTimer.setTimerFunc(fakeTimer.timer())
        System.clearProperty(SettleStats.PROPERTY)
        SettleStats.listener = { synchronized(lines) { lines.add(it) } }
    }

    @AfterEach
    fun tearDown() {
        SettleStats.listener = null
        System.clearProperty(SettleStats.PROPERTY)
    }

    // ---- exit reasons ----

    @Test
    fun `exit equal when two consecutive dumps match`() {
        val call = settle(listOf(tree("a"), tree("a")))
        assertThat(call["exit"].asText()).isEqualTo("equal")
        assertThat(call["dumps"].size()).isEqualTo(2)
        assertThat(call["cmp"].asInt()).isEqualTo(1)
    }

    @Test
    fun `exit cap when ten comparisons never match`() {
        val call = settle(List(20) { tree("t$it") })
        assertThat(call["exit"].asText()).isEqualTo("cap")
        assertThat(call["dumps"].size()).isEqualTo(11)
        assertThat(call["cmp"].asInt()).isEqualTo(10)
    }

    @Test
    fun `exit deadline when the timeout variant expires unmatched`() {
        val call = settle(List(20) { tree("t$it") }, timeoutMs = 0)
        assertThat(call["exit"].asText()).isEqualTo("deadline")
        assertThat(call["dumps"].size()).isEqualTo(2)
    }

    @Test
    fun `exit loading when equal dumps keep reporting is-loading`() {
        val call = settle(List(20) { tree("same", loading = true) })
        assertThat(call["exit"].asText()).isEqualTo("loading")
        assertThat(call["dumps"].size()).isEqualTo(11)
    }

    // ---- mismatch classification ----

    @Test
    fun `reordered siblings classify as order-only`() {
        val a = node("root", node("x"), node("y", node("y1"), node("y2")))
        val b = node("root", node("y", node("y2"), node("y1")), node("x"))
        assertThat(a == b).isFalse()
        assertThat(SettleStats.classifyMismatch(a, b).kind).isEqualTo("order-only")
    }

    @Test
    fun `a moved node is content, not order`() {
        // same multiset of nodes overall, different parents
        val a = node("root", node("p", node("c")), node("q"))
        val b = node("root", node("p"), node("q", node("c")))
        val m = SettleStats.classifyMismatch(a, b)
        assertThat(m.kind).isEqualTo("content")
        assertThat(m.attrs).containsExactly("(structure)")
    }

    @Test
    fun `changed attributes classify as content and name the attributes`() {
        val a = node("root", node("x", extra = mapOf("text" to "12:00", "focused" to "a")))
        val b = node("root", node("x", extra = mapOf("text" to "12:01", "focused" to "b")))
        val m = SettleStats.classifyMismatch(a, b)
        assertThat(m.kind).isEqualTo("content")
        assertThat(m.attrs).containsExactly("focused", "text").inOrder()
    }

    @Test
    fun `settle records the classification of each mismatch`() {
        val call = settle(listOf(
            node("root", node("x"), node("y")),
            node("root", node("y"), node("x")),
            node("root", node("y"), node("x", extra = mapOf("text" to "new"))),
            node("root", node("y"), node("x", extra = mapOf("text" to "new"))),
        ))
        assertThat(call["exit"].asText()).isEqualTo("equal")
        val mis = call["mis"]
        assertThat(mis.size()).isEqualTo(2)
        assertThat(mis[0]["kind"].asText()).isEqualTo("order-only")
        assertThat(mis[1]["kind"].asText()).isEqualTo("content")
        assertThat(mis[1]["attrs"].map { it.asText() }).containsExactly("text")
    }

    // ---- aggregation ----

    @Test
    fun `summary aggregates per command type and histograms`() {
        val stats = SettleStats()
        repeat(2) {
            val h = stats.beginCommand("TapOnElementCommand")
            stats.recordSettleCall(stats.newSettleCall()!!.apply {
                dumpMs += listOf(1L, 1L); comparisons = 1; exit = "equal"
                mismatches += SettleStats.Mismatch("order-only")
            })
            stats.endCommand(h)
        }
        val h = stats.beginCommand("AssertConditionCommand")
        stats.recordSettleCall(stats.newSettleCall()!!.apply {
            dumpMs += List(11) { 1L }; comparisons = 10; exit = "cap"
            mismatches += SettleStats.Mismatch("content", listOf("text", "bounds"))
        })
        stats.endCommand(h)

        val summary = captureStdout { stats.emitFlowSummary("my flow") }
        val json = summaryJson(summary)

        assertThat(json["flow"].asText()).isEqualTo("my flow")
        assertThat(json["commands"]["TapOnElementCommand"]["count"].asInt()).isEqualTo(2)
        assertThat(json["commands"]["AssertConditionCommand"]["count"].asInt()).isEqualTo(1)
        val s = json["settle"]
        assertThat(s["calls"].asInt()).isEqualTo(3)
        assertThat(s["exit"]["equal"].asInt()).isEqualTo(2)
        assertThat(s["exit"]["cap"].asInt()).isEqualTo(1)
        assertThat(s["iters"]["2"].asInt()).isEqualTo(2)
        assertThat(s["iters"]["11"].asInt()).isEqualTo(1)
        assertThat(s["mismatch"]["order-only"].asInt()).isEqualTo(2)
        assertThat(s["mismatch"]["content"].asInt()).isEqualTo(1)
        assertThat(s["attrs"]["text"].asInt()).isEqualTo(1)
        assertThat(lines.count { it.startsWith(SettleStats.LOG_PREFIX) }).isEqualTo(3)

        // emitting resets: a second flow starts from zero
        val second = summaryJson(captureStdout { stats.emitFlowSummary(null) })
        assertThat(second["settle"]["calls"].asInt()).isEqualTo(0)
        assertThat(second["commands"].size()).isEqualTo(0)
    }

    @Test
    fun `nested settle inside find is counted once, as settle`() {
        val stats = SettleStats()
        val cmd = stats.beginCommand("TapOnElementCommand")
        val find = stats.beginPhase(SettleStats.Phase.FIND)
        val settle = stats.beginPhase(SettleStats.Phase.SETTLE)
        Thread.sleep(30)
        stats.endPhase(settle)
        stats.endPhase(find)
        stats.endCommand(cmd)
        val line = mapper.readTree(lines.single().removePrefix(SettleStats.LOG_PREFIX))
        assertThat(line["settle"].asLong()).isAtLeast(30)
        assertThat(line["find"].asLong()).isLessThan(30)
        assertThat(line["self"].asLong())
            .isEqualTo(line["settle"].asLong() + line["find"].asLong() + line["other"].asLong())
    }

    @Test
    fun `a flow emits one line per command and one stdout summary`() {
        val stdout = captureStdout { runTapFlow() }

        val commandLines = lines.filter { it.startsWith(SettleStats.LOG_PREFIX) }
            .map { mapper.readTree(it.removePrefix(SettleStats.LOG_PREFIX)) }
        val tap = commandLines.single { it["cmd"].asText() == "TapOnElementCommand" }
        assertThat(tap["settles"].size()).isAtLeast(1)

        val summary = summaryJson(stdout)
        assertThat(summary["commands"]["TapOnElementCommand"]["count"].asInt()).isEqualTo(1)
        assertThat(summary["settle"]["calls"].asInt())
            .isEqualTo(commandLines.sumOf { it["settles"].size() })
        assertThat(summary["total"]["ms"].asLong()).isEqualTo(commandLines.sumOf { it["self"].asLong() })
    }

    @Test
    fun `off emits nothing`() {
        System.setProperty(SettleStats.PROPERTY, "off")

        val stdout = captureStdout { runTapFlow() }
        settle(listOf(tree("a"), tree("a")), expectRecord = false)

        assertThat(lines).isEmpty()
        assertThat(stdout).doesNotContain(SettleStats.SUMMARY_PREFIX)
    }

    // ---- helpers ----

    /** Runs ScreenshotUtils.waitForAppToSettle against scripted dumps; returns its settle record. */
    private fun settle(dumps: List<TreeNode>, timeoutMs: Int? = null, expectRecord: Boolean = true): JsonNode {
        val base = FakeDriver().apply { open() }
        val driver = ScriptedDriver(base, dumps)
        val stats = SettleStats.of(driver)
        val h = stats.beginCommand("Probe")
        ScreenshotUtils.waitForAppToSettle(null, driver, timeoutMs)
        stats.endCommand(h)
        if (!expectRecord) return mapper.createObjectNode()
        val line = lines.last { it.startsWith(SettleStats.LOG_PREFIX) }
        return mapper.readTree(line.removePrefix(SettleStats.LOG_PREFIX))["settles"].single()
    }

    private class ScriptedDriver(base: FakeDriver, private val dumps: List<TreeNode>) : Driver by base {
        private var i = 0
        override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode =
            dumps[minOf(i++, dumps.size - 1)]
    }

    private fun tree(text: String, loading: Boolean = false): TreeNode {
        val attrs = mutableMapOf("ignoreBoundsFiltering" to "true", "text" to text)
        if (loading) attrs["is-loading"] = "true"
        return TreeNode(attributes = attrs)
    }

    private fun node(id: String, vararg children: TreeNode, extra: Map<String, String> = emptyMap()): TreeNode {
        val attrs = mutableMapOf("resource-id" to id, "ignoreBoundsFiltering" to "true")
        attrs.putAll(extra)
        return TreeNode(attributes = attrs, children = children.toList())
    }

    private fun summaryJson(stdout: String): JsonNode {
        val line = stdout.lines().single { it.startsWith(SettleStats.SUMMARY_PREFIX) }
        return mapper.readTree(line.removePrefix(SettleStats.SUMMARY_PREFIX))
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    private fun runTapFlow() {
        val driver = FakeDriver()
        driver.setLayout(FakeLayoutElement().apply {
            element {
                text = "Primary button"
                bounds = Bounds(0, 0, 100, 100)
            }
        })
        driver.open()
        val resource = javaClass.classLoader.getResource("008_tap_on_element.yaml")!!
        val flowPath = Paths.get(resource.toURI())
        val commands = YamlCommandReader.readCommands(flowPath)
            .withEnv(emptyMap<String, String>().withDefaultEnvVars(flowPath.toFile()))
        Maestro(driver).use {
            runBlocking {
                Orchestra(it, lookupTimeoutMs = 0L, optionalLookupTimeoutMs = 0L).runFlow(commands)
            }
        }
    }
}
