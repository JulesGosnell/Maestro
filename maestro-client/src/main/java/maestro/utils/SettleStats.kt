package maestro.utils

import com.fasterxml.jackson.databind.ObjectMapper
import maestro.Driver
import maestro.TreeNode
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.WeakHashMap

/**
 * smithr#1246 Phase A: measure how much of each command's time goes to waitForAppToSettle.
 * MEASURE ONLY — nothing here changes what Maestro does.
 *
 * One recorder per [Driver] (so shards in one JVM do not mix). Orchestra opens a command frame
 * per command; settle and find open phase frames. Phase time is EXCLUSIVE (a settle inside a find
 * is counted once, as settle) and lands on the innermost command; a command's `self` time
 * excludes its child commands, so summing `self` over a flow never double counts.
 *
 * Outputs (both disabled by `-Dmaestro.settleStats=off`):
 *  - per command, a maestro.log line `SETTLE-STATS {json}`;
 *  - per flow, a stdout line `MAESTRO-SETTLE-SUMMARY {json}`.
 */
class SettleStats {

    enum class Phase { SETTLE, FIND }

    /** One waitForAppToSettle call. */
    class SettleCall {
        val dumpMs = mutableListOf<Long>()
        var comparisons = 0
        var exit = "error"
        var ms = 0L
        val mismatches = mutableListOf<Mismatch>()
        var lastEqualButLoading = false

        fun toMap(): Map<String, Any> {
            val m = linkedMapOf<String, Any>(
                "dumps" to dumpMs.toList(),
                "cmp" to comparisons,
                "ms" to ms,
                "exit" to exit,
            )
            if (mismatches.isNotEmpty()) m["mis"] = mismatches.map { it.toMap() }
            return m
        }
    }

    data class Mismatch(val kind: String, val attrs: List<String> = emptyList()) {
        fun toMap(): Map<String, Any> =
            if (attrs.isEmpty()) mapOf("kind" to kind) else mapOf("kind" to kind, "attrs" to attrs)

        companion object {
            const val ORDER_ONLY = "order-only"
            const val CONTENT = "content"
        }
    }

    private sealed class Frame(val startMs: Long) {
        var childMs = 0L
    }

    private class CommandFrame(val type: String, startMs: Long, val depth: Int) : Frame(startMs) {
        var settleMs = 0L
        var findMs = 0L
        val settles = mutableListOf<SettleCall>()
    }

    private class PhaseFrame(val phase: Phase, startMs: Long) : Frame(startMs)

    class TypeAggregate {
        var count = 0
        var ms = 0L
        var settleMs = 0L
        var findMs = 0L
    }

    // Guarded by `this`.
    private val stack = ArrayList<Frame>()
    private val byType = linkedMapOf<String, TypeAggregate>()
    private val exitHistogram = sortedMapOf<String, Int>()
    private val iterationHistogram = sortedMapOf<Int, Int>()
    private val comparisonHistogram = sortedMapOf<Int, Int>()
    private val mismatchCounts = sortedMapOf<String, Int>()
    private val attrCounts = sortedMapOf<String, Int>()
    private var settleCalls = 0
    private var settleCallMs = 0L

    // ---- command frames (Orchestra) ----

    /** Opaque handle; null when disabled. */
    fun beginCommand(type: String): Any? {
        if (!enabled()) return null
        synchronized(this) {
            val depth = stack.count { it is CommandFrame }
            val frame = CommandFrame(type, now(), depth)
            stack.add(frame)
            return frame
        }
    }

    fun endCommand(handle: Any?) {
        val frame = handle as? CommandFrame ?: return
        val line: String
        synchronized(this) {
            val elapsed = now() - frame.startMs
            stack.remove(frame)
            (stack.lastOrNull { it is CommandFrame } as? CommandFrame)?.let { it.childMs += elapsed }
            val self = elapsed - frame.childMs
            val agg = byType.getOrPut(frame.type) { TypeAggregate() }
            agg.count++
            agg.ms += self
            agg.settleMs += frame.settleMs
            agg.findMs += frame.findMs
            val m = linkedMapOf<String, Any>(
                "cmd" to frame.type,
                "depth" to frame.depth,
                "ms" to elapsed,
                "self" to self,
                "settle" to frame.settleMs,
                "find" to frame.findMs,
                "other" to (self - frame.settleMs - frame.findMs),
                "settles" to frame.settles.map { it.toMap() },
            )
            line = try {
                LOG_PREFIX + MAPPER.writeValueAsString(m)
            } catch (e: Exception) {
                LOGGER.warn("SettleStats: could not serialise a command record", e)
                return
            }
        }
        LOGGER.info(line)
        listener?.invoke(line)
    }

    // ---- phases (settle / find) ----

    inline fun <T> phase(phase: Phase, block: () -> T): T {
        val handle = beginPhase(phase)
        try {
            return block()
        } finally {
            endPhase(handle)
        }
    }

    fun beginPhase(phase: Phase): Any? {
        if (!enabled()) return null
        synchronized(this) {
            val frame = PhaseFrame(phase, now())
            stack.add(frame)
            return frame
        }
    }

    fun endPhase(handle: Any?) {
        val frame = handle as? PhaseFrame ?: return
        synchronized(this) {
            val elapsed = now() - frame.startMs
            val idx = stack.indexOf(frame)
            if (idx < 0) return
            stack.removeAt(idx)
            val parent = if (idx > 0) stack[idx - 1] else null
            if (parent is PhaseFrame) parent.childMs += elapsed
            val exclusive = elapsed - frame.childMs
            val cmd = stack.lastOrNull { it is CommandFrame } as? CommandFrame ?: return
            when (frame.phase) {
                Phase.SETTLE -> cmd.settleMs += exclusive
                Phase.FIND -> cmd.findMs += exclusive
            }
        }
    }

    // ---- settle calls (ScreenshotUtils) ----

    fun newSettleCall(): SettleCall? = if (enabled()) SettleCall() else null

    fun recordSettleCall(call: SettleCall?) {
        call ?: return
        synchronized(this) {
            settleCalls++
            settleCallMs += call.ms
            exitHistogram.merge(call.exit, 1, Int::plus)
            iterationHistogram.merge(call.dumpMs.size, 1, Int::plus)
            comparisonHistogram.merge(call.comparisons, 1, Int::plus)
            call.mismatches.forEach { mm ->
                mismatchCounts.merge(mm.kind, 1, Int::plus)
                mm.attrs.forEach { attrCounts.merge(it, 1, Int::plus) }
            }
            (stack.lastOrNull { it is CommandFrame } as? CommandFrame)?.settles?.add(call)
        }
    }

    // ---- flow summary ----

    fun reset() {
        synchronized(this) {
            stack.clear()
            byType.clear()
            exitHistogram.clear()
            iterationHistogram.clear()
            comparisonHistogram.clear()
            mismatchCounts.clear()
            attrCounts.clear()
            settleCalls = 0
            settleCallMs = 0
        }
    }

    /** Builds the flow summary line, prints it to stdout and the log, and resets. */
    fun emitFlowSummary(flowName: String?) {
        if (!enabled()) {
            reset()
            return
        }
        val line = try {
            synchronized(this) { summaryLine(flowName) }
        } catch (e: Exception) {
            LOGGER.warn("SettleStats: could not build the flow summary", e)
            null
        }
        reset()
        line ?: return
        println(line)
        LOGGER.info(line)
        listener?.invoke(line)
    }

    private fun summaryLine(flowName: String?): String {
        val commands = linkedMapOf<String, Any>()
        var totalMs = 0L
        var totalSettle = 0L
        var totalFind = 0L
        byType.forEach { (type, a) ->
            totalMs += a.ms; totalSettle += a.settleMs; totalFind += a.findMs
            commands[type] = linkedMapOf(
                "count" to a.count, "ms" to a.ms, "settle" to a.settleMs, "find" to a.findMs,
                "other" to (a.ms - a.settleMs - a.findMs),
            )
        }
        val m = linkedMapOf<String, Any?>(
            "flow" to flowName,
            "total" to linkedMapOf("ms" to totalMs, "settle" to totalSettle, "find" to totalFind,
                "other" to (totalMs - totalSettle - totalFind)),
            "commands" to commands,
            "settle" to linkedMapOf(
                "calls" to settleCalls,
                "ms" to settleCallMs,
                "exit" to exitHistogram.toMap(),
                "iters" to iterationHistogram.mapKeys { it.key.toString() },
                "cmps" to comparisonHistogram.mapKeys { it.key.toString() },
                "mismatch" to mismatchCounts.toMap(),
                "attrs" to attrCounts.toMap(),
            ),
        )
        return SUMMARY_PREFIX + MAPPER.writeValueAsString(m)
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val LOG_PREFIX = "SETTLE-STATS "
        const val SUMMARY_PREFIX = "MAESTRO-SETTLE-SUMMARY "
        const val PROPERTY = "maestro.settleStats"

        private val LOGGER = LoggerFactory.getLogger(SettleStats::class.java)
        private val MAPPER = ObjectMapper()
        private val recorders: MutableMap<Driver, SettleStats> =
            Collections.synchronizedMap(WeakHashMap())

        /** Test hook: sees every emitted line (both kinds). */
        @Volatile
        var listener: ((String) -> Unit)? = null

        fun enabled(): Boolean = System.getProperty(PROPERTY) != "off"

        fun of(driver: Driver): SettleStats = recorders.getOrPut(driver) { SettleStats() }

        /**
         * Why two consecutive dumps differ. Runs only on a mismatch, on hierarchies already in
         * memory: no device calls. `order-only` when the trees are equal once every node's
         * children are treated as a multiset; otherwise `content`, with up to 3 attribute names
         * whose name=value multisets differ (`(structure)` when none do).
         */
        fun classifyMismatch(a: TreeNode, b: TreeNode): Mismatch {
            if (unorderedHash(a) == unorderedHash(b)) return Mismatch(Mismatch.ORDER_ONLY)
            val counts = HashMap<String, Int>()
            collectPairs(a, counts, +1)
            collectPairs(b, counts, -1)
            val names = sortedSetOf<String>()
            counts.forEach { (pair, n) -> if (n != 0) names.add(pair.substringBefore('\u0000')) }
            val attrs = if (names.isEmpty()) listOf("(structure)") else names.take(3)
            return Mismatch(Mismatch.CONTENT, attrs)
        }

        private fun nodePairs(node: TreeNode): List<Pair<String, String>> {
            val pairs = node.attributes.entries.map { it.key to it.value }.toMutableList()
            node.clickable?.let { pairs.add("clickable" to it.toString()) }
            node.enabled?.let { pairs.add("enabled" to it.toString()) }
            node.focused?.let { pairs.add("focused" to it.toString()) }
            node.checked?.let { pairs.add("checked" to it.toString()) }
            node.selected?.let { pairs.add("selected" to it.toString()) }
            return pairs
        }

        private fun collectPairs(node: TreeNode, counts: HashMap<String, Int>, sign: Int) {
            nodePairs(node).forEach { (k, v) -> counts.merge(k + '\u0000' + v, sign, Int::plus) }
            node.children.forEach { collectPairs(it, counts, sign) }
        }

        private fun unorderedHash(node: TreeNode): Long {
            var h = FNV_OFFSET
            nodePairs(node).sortedWith(compareBy({ it.first }, { it.second })).forEach { (k, v) ->
                h = fnv(h, k); h = fnv(h, "\u0001"); h = fnv(h, v); h = fnv(h, "\u0002")
            }
            val childHashes = node.children.map { unorderedHash(it) }.sorted()
            h = fnv(h, "[")
            childHashes.forEach { c -> h = mix(h, c) }
            return mix(h, childHashes.size.toLong())
        }

        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L

        private fun fnv(seed: Long, s: String): Long {
            var h = seed
            for (ch in s) {
                h = (h xor ch.code.toLong()) * FNV_PRIME
            }
            return h
        }

        private fun mix(seed: Long, v: Long): Long {
            var h = seed
            var x = v
            repeat(8) {
                h = (h xor (x and 0xff)) * FNV_PRIME
                x = x ushr 8
            }
            return h
        }
    }
}
