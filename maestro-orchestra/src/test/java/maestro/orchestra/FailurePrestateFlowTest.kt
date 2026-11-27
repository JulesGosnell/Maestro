package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.Driver
import maestro.FailurePrestate
import maestro.Maestro
import maestro.ScrollDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.Platform
import maestro.js.RhinoJsEngine
import org.junit.jupiter.api.Test

/**
 * norn#486: Orchestra tags what [FailurePrestate] keeps with the command it served; a failure
 * hands the CLI a snapshot from onCommandFailed, and the end of the flow drops everything, so a
 * passing flow has nothing to write.
 */
class FailurePrestateFlowTest {

    private fun screen(vararg labels: String) = TreeNode(
        attributes = mutableMapOf("bounds" to "[0,0][390,844]"),
        children = labels.mapIndexed { i, label ->
            TreeNode(attributes = mutableMapOf("text" to label, "bounds" to "[0,${i * 100}][390,${i * 100 + 80}]"))
        },
    )

    // The lookups poll without sleeping, so the screens are served by hand: a mock would record
    // every one of those calls and hold them for the rest of the test JVM. (waitForAppToSettle is
    // overridden anyway: mockk cannot match its ViewHierarchy value-class parameter.)
    private class Screens(val mock: Driver, val screens: List<TreeNode>) : Driver by mock {
        private var next = 0
        override fun deviceInfo() = DeviceInfo(Platform.IOS, 390, 844, 390, 844)
        override fun capabilities() = emptyList<maestro.Capability>()
        override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode =
            screens[minOf(next++, screens.lastIndex)]
        override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int?): ViewHierarchy? = null
    }

    private fun maestro(screens: List<TreeNode>): Maestro = Maestro(Screens(mockk(relaxed = true), screens))

    private fun run(maestro: Maestro, vararg commands: Command): Pair<Boolean, FailurePrestate.Snapshot?> {
        var atFailure: FailurePrestate.Snapshot? = null
        val orchestra = Orchestra(
            maestro,
            jsEngineFactory = { RhinoJsEngine(platform = "ios") },
            onCommandFailed = { _, _, _ ->
                if (atFailure == null) atFailure = maestro.failurePrestate.snapshot()
                Orchestra.ErrorResolution.FAIL
            },
        )
        val passed = runBlocking { orchestra.runFlow(commands.map { MaestroCommand(it) }) }
        return passed to atFailure
    }

    private val aspirin = ElementSelector(textRegex = "Aspirin")

    private fun scrollTo(selector: ElementSelector) = ScrollUntilVisibleCommand(
        selector = selector,
        direction = ScrollDirection.DOWN,
        visibilityPercentage = 100,
        timeout = "1500",
        centerElement = false,
    )

    @Test
    fun `a failed scroll keeps its start state as initial and its final poll as last`() {
        val start = screen("Ibuprofen", "Paracetamol")
        val scrolled = screen("Codeine", "Morphine")
        val maestro = maestro(listOf(start, scrolled))

        val (passed, snapshot) = run(maestro, scrollTo(aspirin))

        assertThat(passed).isFalse()
        val initial = snapshot!!.initial!!
        val last = snapshot.last!!
        assertThat(initial.kind).isEqualTo(FailurePrestate.Kind.INITIAL)
        assertThat(initial.pollIndex).isEqualTo(0)
        assertThat(initial.hierarchy.root).isEqualTo(start)
        assertThat(initial.provenance!!.selector).isEqualTo(aspirin.description())
        assertThat(initial.provenance!!.command).startsWith("Scrolling DOWN until")
        assertThat(last.kind).isEqualTo(FailurePrestate.Kind.LAST)
        assertThat(last.pollIndex).isGreaterThan(0)
        assertThat(last.hierarchy.root).isEqualTo(scrolled)
        // the flow is over: nothing is kept past it
        assertThat(maestro.failurePrestate.snapshot()).isNull()
    }

    @Test
    fun `a passing flow is never asked for a snapshot and keeps nothing`() {
        val maestro = maestro(listOf(screen("Aspirin")))

        val (passed, snapshot) = run(
            maestro,
            AssertConditionCommand(Condition(visible = aspirin)),
            scrollTo(aspirin),
        )

        assertThat(passed).isTrue()
        assertThat(snapshot).isNull()
        assertThat(maestro.failurePrestate.snapshot()).isNull()
    }
}
