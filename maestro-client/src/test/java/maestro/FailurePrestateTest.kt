package maestro

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import maestro.device.Platform
import okio.Sink
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * norn#486: the state before a failure is kept from samples Maestro takes anyway — one
 * screenshot and two hierarchies, tagged with what they served — written only for a failure.
 */
class FailurePrestateTest {

    @TempDir
    lateinit var dir: File

    @AfterEach
    fun restoreProperty() {
        System.clearProperty(FailurePrestate.PROPERTY)
    }

    private fun screen(label: String) =
        ViewHierarchy(TreeNode(attributes = mutableMapOf("text" to label, "bounds" to "[0,0][100,100]")))

    private fun image(w: Int = 4) = BufferedImage(w, 4, BufferedImage.TYPE_INT_RGB)

    // ---- retention and provenance ----

    @Test
    fun `a scroll's first read is its initial state and its latest read is last, never confused`() {
        val prestate = FailurePrestate()
        val scroll = prestate.beginCommand("Scrolling DOWN until \"Aspirin\" is visible", "\"Aspirin\"", search = true)
        prestate.onSearchRead(screen("top"))
        prestate.onSearchRead(screen("middle"))
        prestate.onSearchRead(screen("bottom"))
        prestate.endCommand(scroll)

        val snapshot = prestate.snapshot()!!
        assertThat(snapshot.initial!!.kind).isEqualTo(FailurePrestate.Kind.INITIAL)
        assertThat(snapshot.initial!!.pollIndex).isEqualTo(0)
        assertThat(snapshot.initial!!.hierarchy).isEqualTo(screen("top"))
        assertThat(snapshot.last!!.kind).isEqualTo(FailurePrestate.Kind.LAST)
        assertThat(snapshot.last!!.pollIndex).isEqualTo(2)
        assertThat(snapshot.last!!.hierarchy).isEqualTo(screen("bottom"))
        assertThat(snapshot.initial!!.provenance!!.selector).isEqualTo("\"Aspirin\"")
        assertThat(snapshot.last!!.provenance!!.command).startsWith("Scrolling DOWN")
    }

    @Test
    fun `a non-search command moves last but never initial`() {
        val prestate = FailurePrestate()
        prestate.endCommand(prestate.beginCommand("Assert that \"Home\" is visible", "\"Home\"", search = true)
            .also { prestate.onSearchRead(screen("home")) })
        val tap = prestate.beginCommand("Tap on \"Save\"", "\"Save\"", search = false)
        prestate.onSearchRead(screen("form"))
        prestate.endCommand(tap)

        val snapshot = prestate.snapshot()!!
        assertThat(snapshot.initial!!.hierarchy).isEqualTo(screen("home"))
        assertThat(snapshot.initial!!.provenance!!.command).isEqualTo("Assert that \"Home\" is visible")
        assertThat(snapshot.last!!.hierarchy).isEqualTo(screen("form"))
        assertThat(snapshot.last!!.provenance!!.command).isEqualTo("Tap on \"Save\"")
        assertThat(snapshot.last!!.pollIndex).isEqualTo(0)
    }

    @Test
    fun `a nested command's samples carry the nested command, and the parent's resume after it`() {
        val prestate = FailurePrestate()
        val flow = prestate.beginCommand("Run flow sub.yaml", null, search = false)
        val assert = prestate.beginCommand("Assert that \"A\" is visible", "\"A\"", search = true)
        prestate.onSearchRead(screen("a"))
        prestate.endCommand(assert)
        prestate.onSearchRead(screen("after"))
        prestate.endCommand(flow)

        val snapshot = prestate.snapshot()!!
        assertThat(snapshot.initial!!.provenance!!.selector).isEqualTo("\"A\"")
        assertThat(snapshot.last!!.provenance!!.command).isEqualTo("Run flow sub.yaml")
    }

    @Test
    fun `only the most recent pre-tap screenshot is kept, with its point, bounds and command`() {
        val prestate = FailurePrestate()
        val first = image(4)
        val second = image(8)
        val tap = prestate.beginCommand("Tap on \"Next\"", "\"Next\"", search = false)
        prestate.onPreTapScreenshot(first, Point(1, 1), null)
        prestate.onPreTapScreenshot(second, Point(195, 262), Bounds(48, 245, 294, 34))
        prestate.endCommand(tap)

        val shot = prestate.snapshot()!!.tap!!
        assertThat(shot.image).isSameInstanceAs(second)
        assertThat(shot.tapPoint).isEqualTo(Point(195, 262))
        assertThat(shot.aimedBounds).isEqualTo(Bounds(48, 245, 294, 34))
        assertThat(shot.provenance!!.command).isEqualTo("Tap on \"Next\"")
    }

    @Test
    fun `cleared (a flow that ended) keeps nothing and has nothing to write`() {
        val prestate = FailurePrestate()
        prestate.beginCommand("Tap on \"Next\"", "\"Next\"", search = true)
        prestate.onSearchRead(screen("x"))
        prestate.onPreTapScreenshot(image(), Point(1, 1), null)

        prestate.clear()

        assertThat(prestate.snapshot()).isNull()
    }

    @Test
    fun `switched off, nothing is kept`() {
        System.setProperty(FailurePrestate.PROPERTY, "off")
        val prestate = FailurePrestate()
        prestate.beginCommand("Tap on \"Next\"", "\"Next\"", search = true)
        prestate.onSearchRead(screen("x"))
        prestate.onPreTapScreenshot(image(), Point(1, 1), null)

        assertThat(prestate.snapshot()).isNull()
    }

    // ---- writing ----

    @Test
    fun `a snapshot writes the screenshot, its provenance and both hierarchies, tagged`() {
        val prestate = FailurePrestate()
        val scroll = prestate.beginCommand("Scrolling DOWN until \"Aspirin\" is visible", "\"Aspirin\"", search = true)
        prestate.onSearchRead(screen("top"))
        prestate.onSearchRead(screen("bottom"))
        prestate.onPreTapScreenshot(image(), Point(10, 20), Bounds(0, 10, 20, 20))
        prestate.endCommand(scroll)

        val files = prestate.snapshot()!!.writeTo(dir, prefix = "prestate-", suffix = "-(flow.yaml)")

        val names = files.map { it.name }
        assertThat(names).hasSize(4)
        assertThat(names.filter { it.matches(Regex("""prestate-tap-\d+-\(flow\.yaml\)\.png""")) }).hasSize(1)
        assertThat(names.filter { it.matches(Regex("""prestate-tap-\d+-\(flow\.yaml\)\.json""")) }).hasSize(1)
        assertThat(names.filter { it.matches(Regex("""prestate-hierarchy-initial-\d+-\(flow\.yaml\)\.json""")) }).hasSize(1)
        assertThat(names.filter { it.matches(Regex("""prestate-hierarchy-last-\d+-\(flow\.yaml\)\.json""")) }).hasSize(1)

        val mapper = ObjectMapper()
        val initial = mapper.readTree(files.first { "hierarchy-initial" in it.name })
        assertThat(initial["kind"].asText()).isEqualTo("initial")
        assertThat(initial["pollIndex"].asInt()).isEqualTo(0)
        assertThat(initial["selector"].asText()).isEqualTo("\"Aspirin\"")
        assertThat(initial["hierarchy"]["attributes"]["text"].asText()).isEqualTo("top")
        val last = mapper.readTree(files.first { "hierarchy-last" in it.name })
        assertThat(last["kind"].asText()).isEqualTo("last")
        assertThat(last["pollIndex"].asInt()).isEqualTo(1)
        assertThat(last["hierarchy"]["attributes"]["text"].asText()).isEqualTo("bottom")
        val tap = mapper.readTree(files.first { it.name.startsWith("prestate-tap-") && it.name.endsWith(".json") })
        assertThat(tap["tapPoint"]["x"].asInt()).isEqualTo(10)
        assertThat(tap["aimedBounds"]["height"].asInt()).isEqualTo(20)
        assertThat(tap["command"].asText()).startsWith("Scrolling DOWN")
        assertThat(ImageIO.read(files.first { it.name.endsWith(".png") })).isNotNull()
    }

    // ---- no added capture calls ----

    private fun png(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image(), "png", it) }.toByteArray()

    private fun driver(): Driver = mockk(relaxed = true) {
        every { deviceInfo() } returns DeviceInfo(Platform.ANDROID, 390, 844, 390, 844)
        every { capabilities() } returns emptyList()   // no FAST_HIERARCHY: screenshotBasedTap
        every { contentDescriptor(any()) } returnsMany listOf(
            screen("loading").root, screen("loading").root, screen("ready").root,
        )
        every { takeScreenshot(any(), any()) } answers {
            firstArg<Sink>().buffer().use { it.write(png()) }
        }
    }

    // Counts the device's capture calls. (Overridden, not stubbed: mockk cannot match the
    // ViewHierarchy value-class parameter of waitForAppToSettle.)
    private class Counting(val mock: Driver) : Driver by mock {
        var reads = 0
        var shots = 0
        private var settles = 0
        override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode =
            mock.contentDescriptor(excludeKeyboardElements).also { reads++ }
        override fun takeScreenshot(out: Sink, compressed: Boolean) {
            shots++
            mock.takeScreenshot(out, compressed)
        }
        override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int?): ViewHierarchy? =
            ViewHierarchy(TreeNode(attributes = mutableMapOf("text" to "settled ${++settles}")))   // the tap changed the UI
    }

    /** One search (three polls) then a tap: (hierarchy reads, screenshots, what was kept). */
    private fun searchThenTap(): Triple<Int, Int, FailurePrestate.Snapshot?> {
        val driver = Counting(driver())
        val maestro = Maestro(driver)
        val handle = maestro.failurePrestate.beginCommand("Tap on \"ready\"", "\"ready\"", search = true)
        val found = maestro.findElementWithTimeout(5_000, { nodes -> nodes.filter { it.attributes["text"] == "ready" } })!!
        maestro.tap(element = found.element, initialHierarchy = found.hierarchy)
        maestro.failurePrestate.endCommand(handle)
        return Triple(driver.reads, driver.shots, maestro.failurePrestate.snapshot())
    }

    @Test
    fun `retention adds no screenshot and no hierarchy read`() {
        System.setProperty(FailurePrestate.PROPERTY, "off")
        val (readsOff, shotsOff, keptOff) = searchThenTap()
        System.clearProperty(FailurePrestate.PROPERTY)
        val (readsOn, shotsOn, keptOn) = searchThenTap()

        assertThat(readsOn).isEqualTo(readsOff)
        assertThat(shotsOn).isEqualTo(shotsOff)
        assertThat(keptOff).isNull()

        // ... yet the samples those calls already took are the ones kept
        assertThat(readsOn).isEqualTo(3)
        assertThat(shotsOn).isEqualTo(1)
        assertThat(keptOn!!.initial!!.hierarchy).isEqualTo(screen("loading"))
        assertThat(keptOn.last!!.hierarchy).isEqualTo(screen("ready"))
        assertThat(keptOn.last!!.pollIndex).isEqualTo(2)
        assertThat(keptOn.tap!!.tapPoint).isEqualTo(Point(50, 50))
        assertThat(keptOn.tap!!.aimedBounds).isEqualTo(Bounds(0, 0, 100, 100))
        assertThat(keptOn.tap!!.provenance!!.selector).isEqualTo("\"ready\"")
    }
}
