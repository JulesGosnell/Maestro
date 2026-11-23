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
 * A wait that times out FAILS the tap ([MaestroException.ElementStillMoving]) — it never taps
 * a point it could not confirm.
 *
 * Timeout: env [TIMEOUT_ENV] in seconds (default [DEFAULT_TIMEOUT_SECONDS]); a value that is
 * not a positive number keeps the default.
 */
object TapStableBounds {

    const val TIMEOUT_ENV = "MAESTRO_TAP_STABLE_TIMEOUT"
    const val DEFAULT_TIMEOUT_SECONDS = 5.0
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

    private fun describe(node: TreeNode): String {
        val id = node.attributes["resource-id"].orEmpty()
        val text = node.attributes["accessibilityText"].orEmpty().ifEmpty { node.attributes["text"].orEmpty() }
        return listOfNotNull(
            id.takeIf { it.isNotEmpty() }?.let { "id=\"$it\"" },
            text.takeIf { it.isNotEmpty() }?.let { "text=\"$it\"" },
        ).joinToString(" ").ifEmpty { node.attributes.toString() }
    }

    /**
     * Polls [readHierarchy] until [node]'s bounds agree on two consecutive reads and returns them.
     * Throws [MaestroException.ElementStillMoving] when they do not within [timeoutMs].
     * Returns [fallback] when the element stops resolving to exactly one node (unchanged
     * pre-existing behaviour: the tap goes where the element was last known).
     */
    fun await(node: TreeNode, fallback: Bounds, readHierarchy: () -> ViewHierarchy): Bounds {
        val timeoutMs = timeoutMs()
        val startMs = System.currentTimeMillis()
        var beforeLast: Bounds? = null
        var previous: Bounds? = null
        var latest = fallback
        var reads = 0
        var misses = 0
        while (true) {
            var missed = false
            val current = try {
                readHierarchy().refreshElement(node)?.toUiElementOrNull()?.bounds
            } catch (e: XCUITestServerError.TransientIssue) {
                // A transient XCTest miss is "ask again", within the same timeout.
                missed = true
                misses++
                null
            }
            reads++
            val elapsedMs = System.currentTimeMillis() - startMs
            if (current == null && !missed) {
                // The element no longer resolves to exactly one node (it changed, vanished or
                // has a twin). Waiting cannot fix that; tap what we last knew, as before.
                LOGGER.warn("TAP-STABLE-LOST reads=$reads elapsedMs=$elapsedMs tapping=$latest")
                return latest
            }
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
