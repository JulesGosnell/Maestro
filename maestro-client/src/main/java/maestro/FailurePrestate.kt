package maestro

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * norn#486: the state BEFORE a failed tap, scroll or assert, kept from samples Maestro already
 * takes. It never takes a screenshot or reads a hierarchy itself, and it never replays anything.
 *
 * Kept in memory, at most one screenshot and two hierarchies, each replacing the last:
 *  - [tap]: the before-tap screenshot [Maestro]'s screenshotBasedTap takes anyway (iOS), with
 *    the tap point and the element bounds it was aimed at. Under `-Dmaestro.fastSettle=on` a
 *    single-attempt tap takes no such screenshot (FastSettle), so there is none to keep.
 *  - [initial]: the first hierarchy an element search read inside a scrollUntilVisible or
 *    assert, before any scroll or poll (poll 0): the start state.
 *  - [last]: the most recent hierarchy an element search read, in whatever command.
 * Each carries when it was taken and the command and selector it served, which Orchestra sets
 * with [beginCommand] / [endCommand]; a hierarchy also carries its kind and poll index, so the
 * start state is never mistaken for the final poll.
 *
 * The CLI calls [snapshot] when a command fails and [Snapshot.writeTo] writes it next to
 * `screenshot-❌`; Orchestra calls [clear] when the flow ends, so a passing flow writes nothing
 * and keeps nothing. Near-side lookups (smithr#1184) read no hierarchy, so they leave nothing.
 *
 * `-Dmaestro.failurePrestate=off` keeps nothing at all.
 */
class FailurePrestate {

    enum class Kind(val label: String) { INITIAL("initial"), LAST("last") }

    data class Provenance(val command: String?, val selector: String?)

    class TapShot(
        val image: BufferedImage,
        val atMs: Long,
        val provenance: Provenance?,
        val tapPoint: Point,
        val aimedBounds: Bounds?,
    )

    class HierarchyRead(
        val hierarchy: ViewHierarchy,
        val atMs: Long,
        val provenance: Provenance?,
        val kind: Kind,
        val pollIndex: Int,
    )

    private class Frame(val provenance: Provenance, val search: Boolean) {
        var reads = 0
    }

    // Guarded by `this`.
    private val frames = ArrayList<Frame>()
    private var tap: TapShot? = null
    private var initial: HierarchyRead? = null
    private var last: HierarchyRead? = null

    // ---- command context (Orchestra) ----

    /**
     * Opens a command's context: what the samples taken until [endCommand] served. [search] is
     * true for a scrollUntilVisible or assert, whose first search read becomes [initial].
     */
    fun beginCommand(command: String?, selector: String?, search: Boolean): Any? {
        if (!enabled()) return null
        synchronized(this) {
            val frame = Frame(Provenance(command, selector), search)
            frames.add(frame)
            return frame
        }
    }

    fun endCommand(handle: Any?) {
        val frame = handle as? Frame ?: return
        synchronized(this) { frames.remove(frame) }
    }

    // ---- samples (Maestro) ----

    /** A hierarchy an element search has just read. */
    fun onSearchRead(hierarchy: ViewHierarchy) {
        if (!enabled()) return
        synchronized(this) {
            val now = System.currentTimeMillis()
            val frame = frames.lastOrNull()
            val poll = frame?.reads ?: 0
            frame?.let { it.reads++ }
            if (frame != null && frame.search && poll == 0) {
                initial = HierarchyRead(hierarchy, now, frame.provenance, Kind.INITIAL, 0)
            }
            last = HierarchyRead(hierarchy, now, frame?.provenance, Kind.LAST, poll)
        }
    }

    /** The screenshot screenshotBasedTap has just taken before tapping [tapPoint]. */
    fun onPreTapScreenshot(image: BufferedImage, tapPoint: Point, aimedBounds: Bounds?) {
        if (!enabled()) return
        synchronized(this) {
            tap = TapShot(image, System.currentTimeMillis(), frames.lastOrNull()?.provenance, tapPoint, aimedBounds)
        }
    }

    // ---- failure / flow end ----

    /** What is kept now, for a failure; null when nothing is. Keeps it (see [clear]). */
    fun snapshot(): Snapshot? = synchronized(this) {
        if (tap == null && initial == null && last == null) null else Snapshot(tap, initial, last)
    }

    /** The flow ended: drop everything. */
    fun clear() {
        synchronized(this) {
            frames.clear()
            tap = null
            initial = null
            last = null
        }
    }

    class Snapshot(val tap: TapShot?, val initial: HierarchyRead?, val last: HierarchyRead?) {

        /**
         * Writes `<prefix>tap-<ts><suffix>.png` (+ `.json` provenance) and
         * `<prefix>hierarchy-{initial,last}-<ts><suffix>.json`, for whichever are kept.
         * Returns the files written; a file that cannot be written is logged and skipped.
         */
        fun writeTo(dir: File, prefix: String = "prestate-", suffix: String = ""): List<File> {
            val written = mutableListOf<File>()
            tap?.let { t ->
                val base = "${prefix}tap-${t.atMs}$suffix"
                write(dir, "$base.png", written) { ImageIO.write(t.image, "png", it) }
                write(dir, "$base.json", written) {
                    MAPPER.writeValue(it, linkedMapOf(
                        "timestampMs" to t.atMs,
                        "command" to t.provenance?.command,
                        "selector" to t.provenance?.selector,
                        "tapPoint" to mapOf("x" to t.tapPoint.x, "y" to t.tapPoint.y),
                        "aimedBounds" to t.aimedBounds?.let {
                            mapOf("x" to it.x, "y" to it.y, "width" to it.width, "height" to it.height)
                        },
                    ))
                }
            }
            listOfNotNull(initial, last).forEach { h ->
                write(dir, "${prefix}hierarchy-${h.kind.label}-${h.atMs}$suffix.json", written) {
                    MAPPER.writeValue(it, linkedMapOf(
                        "kind" to h.kind.label,
                        "pollIndex" to h.pollIndex,
                        "timestampMs" to h.atMs,
                        "command" to h.provenance?.command,
                        "selector" to h.provenance?.selector,
                        "hierarchy" to h.hierarchy.root,
                    ))
                }
            }
            return written
        }

        private fun write(dir: File, name: String, written: MutableList<File>, body: (File) -> Unit) {
            val file = File(dir, name)
            try {
                body(file)
                written.add(file)
            } catch (e: Exception) {
                LOGGER.warn("FailurePrestate: could not write $file", e)
            }
        }
    }

    companion object {
        const val PROPERTY = "maestro.failurePrestate"

        private val LOGGER = LoggerFactory.getLogger(FailurePrestate::class.java)
        private val MAPPER = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

        fun enabled(): Boolean = System.getProperty(PROPERTY) != "off"
    }
}
