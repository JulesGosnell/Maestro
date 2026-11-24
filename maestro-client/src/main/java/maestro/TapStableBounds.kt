package maestro

import maestro.UiElement.Companion.toUiElementOrNull
import maestro.utils.network.XCUITestServerError
import org.slf4j.LoggerFactory
import kotlin.math.abs

/**
 * smithr: an iOS element tap aims at bounds that have STOPPED MOVING.
 *
 * On iOS, [Maestro.tap] used to "refresh" the element against the hierarchy its lookup returned
 * — when the screen settles, IOSDriver.waitForAppToSettle returns null, so that hierarchy is the
 * lookup's own read, taken BEFORE the settle wait. A modal sliding up for the keyboard was
 * tapped at where the label had been (artha CI 45088801189: hint read at y 274-308, really at
 * ~245-262; the tap at y 291 landed in the text input).
 *
 * So: re-read the element's bounds from fresh hierarchies until two consecutive reads agree
 * (every edge within [TOLERANCE] pt), bounded by [timeoutMs]; the tap goes to the centre of the
 * LATEST read. A condition wait, never a fixed sleep: each read is a device round trip.
 * A wait that times out FAILS the tap ([MaestroException.ElementStillMoving]), and so does an
 * element the selector no longer matches at all ([MaestroException.ElementLostWhileStabilising]):
 * it never taps a point it could not confirm.
 *
 * IDENTITY (artha 2026-11-24): each read tracks the SAME node the selector chose — see [track].
 * It never compares all attributes: a native iOS alert's title, button and a third node all carry
 * {accessibilityText="Sign Out", resource-id=""} (3 "matches", nothing tapped), and a card whose
 * accessibility text re-rendered between two reads matched nothing ("vanished after 276ms").
 *
 * Timeout: env [TIMEOUT_ENV] in seconds (default [DEFAULT_TIMEOUT_SECONDS]); a value that is
 * not a positive number keeps the default.
 */
object TapStableBounds {

    const val TIMEOUT_ENV = "MAESTRO_TAP_STABLE_TIMEOUT"
    const val DEFAULT_TIMEOUT_SECONDS = 60.0
    const val TOLERANCE = 2

    private val LOGGER = LoggerFactory.getLogger(TapStableBounds::class.java)

    // Seam for the test harness only.
    var env: (String) -> String? = System::getenv

    fun timeoutMs(): Long {
        val seconds = env(TIMEOUT_ENV)?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
            ?: DEFAULT_TIMEOUT_SECONDS
        return (seconds * 1000).toLong()
    }

    fun agree(a: Bounds, b: Bounds): Boolean =
        abs(a.x - b.x) <= TOLERANCE &&
            abs(a.y - b.y) <= TOLERANCE &&
            abs(a.width - b.width) <= TOLERANCE &&
            abs(a.height - b.height) <= TOLERANCE

    private fun lost(node: TreeNode, reads: Int, elapsedMs: Long, latest: Bounds, why: String): Nothing {
        val message = "TAP-STABLE-LOST element ${describe(node)} $why after ${elapsedMs}ms " +
            "(reads=$reads); last bounds read: $latest; nothing tapped"
        LOGGER.warn(message)
        throw MaestroException.ElementLostWhileStabilising(message)
    }

    private fun describe(node: TreeNode): String {
        val id = node.attributes["resource-id"].orEmpty()
        val text = node.attributes["accessibilityText"].orEmpty().ifEmpty { node.attributes["text"].orEmpty() }
        return listOfNotNull(
            id.takeIf { it.isNotEmpty() }?.let { "id=\"$it\"" },
            text.takeIf { it.isNotEmpty() }?.let { "text=\"$it\"" },
        ).joinToString(" ").ifEmpty { node.attributes.toString() }
    }

    /**
     * The node in [nodes] that IS the element the selector chose, or null when there is none.
     *
     * - The pool: nodes the original selector still matches ([candidates], re-evaluated on this
     *   read) — or, with no selector, nodes equal to [node] in every attribute but bounds.
     * - With a resource-id set on [node], only nodes with that id are it; an attribute change
     *   (re-rendered text) on that node is still the same element.
     * - Several left (siblings sharing every attribute): the one nearest [last], the bounds it
     *   was last seen at. Siblings sharing attributes never fail the tap.
     */
    fun track(nodes: List<TreeNode>, node: TreeNode, last: Bounds, candidates: ElementFilter?): TreeNode? {
        val id = node.attributes["resource-id"].orEmpty()
        val pool = (candidates?.invoke(nodes) ?: nodes.filter {
            id.isNotEmpty() || (it.attributes - "bounds") == (node.attributes - "bounds")
        })
            .filter { id.isEmpty() || it.attributes["resource-id"] == id }
            .distinct()
            .mapNotNull { candidate -> candidate.toUiElementOrNull()?.let { candidate to it.bounds } }
        if (pool.size > 1) {
            LOGGER.info("TAP-STABLE ${pool.size} nodes match ${describe(node)}; tracking the one nearest $last")
        }
        return pool.minByOrNull { (_, bounds) -> distance(bounds, last) }?.first
    }

    private fun distance(a: Bounds, b: Bounds): Int =
        abs(a.x - b.x) + abs(a.y - b.y) + abs(a.width - b.width) + abs(a.height - b.height)

    /**
     * Polls [readHierarchy] until [node]'s bounds agree on two consecutive reads and returns them.
     * Throws [MaestroException.ElementStillMoving] when they do not within [timeoutMs].
     * Throws [MaestroException.ElementLostWhileStabilising] when a read finds no node that [track]
     * accepts as [node] — waiting cannot fix that, and tapping the last known bounds would be a guess.
     * [lastKnown] is the lookup's bounds: where tracking starts, and what that error reports.
     * [candidates] is the tap's selector (null: match on [node]'s own attributes, see [track]).
     */
    fun await(
        node: TreeNode,
        lastKnown: Bounds,
        candidates: ElementFilter? = null,
        readHierarchy: () -> ViewHierarchy,
    ): Bounds {
        val timeoutMs = timeoutMs()
        val startMs = System.currentTimeMillis()
        var beforeLast: Bounds? = null
        var previous: Bounds? = null
        var latest = lastKnown
        var reads = 0
        var misses = 0
        while (true) {
            val current = try {
                track(readHierarchy().aggregate(), node, latest, candidates)?.toUiElementOrNull()?.bounds
                    ?: lost(node, reads + 1, System.currentTimeMillis() - startMs, latest, "vanished")
            } catch (e: XCUITestServerError.TransientIssue) {
                // A transient XCTest miss is "ask again", within the same timeout.
                misses++
                null
            }
            reads++
            val elapsedMs = System.currentTimeMillis() - startMs
            if (current != null) {
                if (previous != null && agree(previous, current)) {
                    LOGGER.info("TAP-STABLE reads=$reads elapsedMs=$elapsedMs bounds=$current")
                    return current
                }
                beforeLast = previous
                previous = current
                latest = current
            }
            if (elapsedMs >= timeoutMs) {
                val message = "TAP-STABLE-TIMEOUT element ${describe(node)} still moving after " +
                    "${elapsedMs}ms (reads=$reads misses=$misses, timeout ${timeoutMs}ms, $TIMEOUT_ENV); " +
                    "last two bounds read: ${beforeLast ?: "none"} -> ${previous ?: "none"}"
                LOGGER.warn(message)
                throw MaestroException.ElementStillMoving(message)
            }
        }
    }
}
