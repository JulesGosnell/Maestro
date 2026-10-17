package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import maestro.DeviceInfo
import maestro.Driver
import maestro.NearSideNodes.toTreeNode
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.Platform
import maestro.nearside.AndroidNodeMapping
import maestro.nearside.NearSideMatcher
import maestro.nearside.NearSideNode
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * smithr#1184's acceptance bar: for every selector the device accepts, the near-side lookup
 * answers the SAME elements, in the SAME order, as the host's full-dump lookup —
 * `Orchestra.buildFilter` applied to `ViewHierarchy.from` — over the same screen.
 *
 * The host tree is the device tree converted field for field; AndroidNodeMappingEquivalenceTest
 * (maestro-client) proves that conversion equals what the host parses out of the XML dump.
 */
class NearSideEquivalenceTest {

    private val orchestra = Orchestra(mockk(relaxed = true))

    @Test
    fun `near-side matches equal full-dump matches over random screens and selectors`() {
        val random = Random(1184)
        var screensWithAMatch = 0
        var ambiguous = 0
        var comparisons = 0

        repeat(400) {
            val screen = randomScreen(random)
            repeat(10) {
                val selector = randomSelector(random, depth = 0)
                val host = hostMatches(screen, selector)
                val device = deviceMatches(screen, selector)

                assertThat(device).isEqualTo(host)

                comparisons++
                if (host.isNotEmpty()) screensWithAMatch++
                if (host.size > 1) ambiguous++
            }
        }

        // The comparison is only worth something if the selectors actually match things
        assertThat(screensWithAMatch).isGreaterThan(comparisons / 5)
        assertThat(ambiguous).isGreaterThan(comparisons / 20)
    }

    @Test
    fun `structurally equal nodes collapse into one match on both sides`() {
        val button = element("OK", "[0,100][200,200]")
        val screen = listOf(element("", "[0,0][1080,2400]", children = listOf(button, button)))

        val selector = ElementSelector(textRegex = "OK")

        assertThat(hostMatches(screen, selector)).hasSize(1)
        assertThat(deviceMatches(screen, selector)).isEqualTo(hostMatches(screen, selector))
    }

    @Test
    fun `an element entirely off screen is filtered out on both sides`() {
        val screen = listOf(
            element(
                "", "[0,0][1080,2400]",
                children = listOf(element("Offscreen", "[0,3000][200,3100]"), element("Onscreen", "[0,100][200,200]")),
            )
        )

        assertThat(hostMatches(screen, ElementSelector(textRegex = "Offscreen"))).isEmpty()
        assertThat(deviceMatches(screen, ElementSelector(textRegex = "Offscreen"))).isEmpty()
        assertThat(deviceMatches(screen, ElementSelector(textRegex = "Onscreen")))
            .isEqualTo(hostMatches(screen, ElementSelector(textRegex = "Onscreen")))
        assertThat(hostMatches(screen, ElementSelector(textRegex = "Onscreen"))).hasSize(1)
    }

    private fun hostMatches(screen: List<NearSideNode>, selector: ElementSelector): List<TreeNode> {
        val root = document(screen).toTreeNode()
        val driver = mockk<Driver> {
            every { deviceInfo() } returns DeviceInfo(Platform.ANDROID, WIDTH, HEIGHT, WIDTH, HEIGHT)
            every { contentDescriptor(any()) } returns root
        }
        val hierarchy = ViewHierarchy.from(driver, excludeKeyboardElements = false)
        return orchestra.buildFilter(selector).filterFunc(hierarchy.aggregate())
    }

    private fun deviceMatches(screen: List<NearSideNode>, selector: ElementSelector): List<TreeNode> {
        val nearSide = NearSideSelectors.from(selector)
            ?: throw AssertionError("Generated a selector the device does not accept: $selector")
        return NearSideMatcher(WIDTH, HEIGHT)
            .find(document(screen), nearSide, limit = Int.MAX_VALUE)
            .matches
            .map { it.toTreeNode() }
    }

    private fun document(screen: List<NearSideNode>) =
        AndroidNodeMapping.document(AndroidNodeMapping.hierarchyElement(screen))

    private fun element(
        text: String,
        bounds: String,
        hintText: String = "",
        contentDesc: String = "",
        resourceId: String = "",
        className: String = "android.widget.TextView",
        clickable: Boolean = false,
        enabled: Boolean = true,
        focused: Boolean = false,
        checked: Boolean = false,
        selected: Boolean = false,
        children: List<NearSideNode> = emptyList(),
    ): NearSideNode = AndroidNodeMapping.element(
        mapOf(
            "index" to "0",
            "hintText" to hintText,
            "text" to text,
            "resource-id" to resourceId,
            "class" to className,
            "package" to "com.example",
            "content-desc" to contentDesc,
            "checkable" to "false",
            "checked" to checked.toString(),
            "clickable" to clickable.toString(),
            "enabled" to enabled.toString(),
            "focusable" to "false",
            "focused" to focused.toString(),
            "scrollable" to "false",
            "long-clickable" to "false",
            "password" to "false",
            "selected" to selected.toString(),
            "visible-to-user" to "true",
            "important-for-accessibility" to "true",
            "error" to "",
            "bounds" to bounds,
        ),
        children,
    )

    private val texts = listOf("", "OK", "ok", "Okay", "Cancel", "a\nb", "a b", "x & y", "Sign in", "Price [1]")
    private val ids = listOf("", "com.example:id/ok", "ok", "com.example:id/cancel", "cancel")

    private fun randomScreen(random: Random): List<NearSideNode> {
        val pool = mutableListOf<NearSideNode>()
        fun node(depth: Int): NearSideNode {
            // Reusing an earlier subtree yields structurally equal nodes, which both sides dedupe
            if (pool.isNotEmpty() && random.nextInt(8) == 0) return pool.random(random)
            val left = random.nextInt(-300, WIDTH + 100)
            val top = random.nextInt(-300, HEIGHT + 300)
            val childCount = if (depth < 3) random.nextInt(0, 4) else 0
            val created = element(
                text = texts.random(random),
                bounds = "[$left,$top][${left + random.nextInt(0, 700)},${top + random.nextInt(0, 500)}]",
                hintText = if (random.nextInt(4) == 0) texts.random(random) else "",
                contentDesc = if (random.nextInt(4) == 0) texts.random(random) else "",
                resourceId = ids.random(random),
                className = if (random.nextInt(20) == 0) "android.widget.Toast" else "android.widget.TextView",
                clickable = random.nextBoolean(),
                enabled = random.nextInt(5) != 0,
                focused = random.nextInt(5) == 0,
                checked = random.nextInt(4) == 0,
                selected = random.nextInt(4) == 0,
                children = List(childCount) { node(depth + 1) },
            )
            pool += created
            return created
        }
        return List(random.nextInt(1, 3)) { node(0) }
    }

    private fun randomSelector(random: Random, depth: Int): ElementSelector {
        fun <T> maybe(oneIn: Int, value: () -> T): T? = if (random.nextInt(oneIn) == 0) value() else null
        fun bool() = maybe(4) { random.nextBoolean() }
        fun relative() = if (depth == 0) maybe(6) { randomSelector(random, depth + 1) } else null

        return ElementSelector(
            textRegex = maybe(2) { listOf("OK", "ok", "O.*", "Cancel", "a b", "a b.*", ".*", "Price [", "Sign.in", "x & y").random(random) },
            idRegex = maybe(3) { listOf("ok", "com.example:id/ok", ".*cancel", "cancel").random(random) },
            enabled = bool(),
            selected = bool(),
            checked = bool(),
            focused = bool(),
            index = maybe(4) { listOf("0", "1", "2", "-1", "-2", "1.0").random(random) },
            below = relative(),
            above = relative(),
            leftOf = relative(),
            rightOf = relative(),
        )
    }

    companion object {
        private const val WIDTH = 1080
        private const val HEIGHT = 2400
    }

}
