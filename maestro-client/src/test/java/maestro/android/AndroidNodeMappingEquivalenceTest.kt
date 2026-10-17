package maestro.android

import com.google.common.truth.Truth.assertThat
import dadb.Dadb
import io.mockk.mockk
import maestro.NearSideNodes.toTreeNode
import maestro.drivers.AndroidDriver
import maestro.nearside.AndroidNodeMapping
import maestro.nearside.NearSideNode
import org.junit.jupiter.api.Test
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.random.Random

/**
 * smithr#1184: the device builds, from the attributes it would have serialised, the same tree
 * the host builds by parsing that XML (AndroidDriver.mapHierarchy) — whitespace nodes, the
 * Document and `<hierarchy>` wrappers and all, because the host's matching dedupes nodes by
 * structural equality and a near-side lookup must match exactly what a full-dump lookup matches.
 */
class AndroidNodeMappingEquivalenceTest {

    private val driver = AndroidDriver(mockk<Dadb>(relaxed = true))

    @Test
    fun `device mapping equals host parsing for a hand-built dump`() {
        val dump = listOf(
            RawNode(
                regularAttributes("android.widget.FrameLayout", text = "", bounds = "[0,0][1080,2400]"),
                listOf(
                    RawNode(regularAttributes("android.widget.Button", text = "Sign in", bounds = "[40,200][1040,320]", clickable = true)),
                    RawNode(regularAttributes("android.widget.TextView", text = "line one\nline two & <three> \"quoted\"", bounds = "[40,400][1040,520]")),
                    RawNode(regularAttributes("android.widget.EditText", text = "", hintText = "Email", bounds = "[40,600][1040,720]", focused = true)),
                ),
            ),
            // ViewHierarchy.addToastNode writes only these
            RawNode(
                linkedMapOf(
                    "index" to "0", "class" to "android.widget.Toast", "text" to "Saved",
                    "visible-to-user" to "true", "checkable" to "false", "clickable" to "false",
                    "bounds" to "[300,2000][780,2100]",
                ),
            ),
        )

        assertEquivalent(dump)
    }

    @Test
    fun `device mapping equals host parsing for random dumps`() {
        val random = Random(1184)
        repeat(300) {
            assertEquivalent(List(random.nextInt(1, 4)) { randomNode(random, depth = 0) })
        }
    }

    private fun assertEquivalent(roots: List<RawNode>) {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(serialise(roots).byteInputStream())
        val host = driver.mapHierarchy(document)

        val device = AndroidNodeMapping.document(AndroidNodeMapping.hierarchyElement(roots.map { it.toNearSideNode() }))

        assertThat(device.toTreeNode()).isEqualTo(host)
    }

    private data class RawNode(
        val attributes: Map<String, String>,
        val children: List<RawNode> = emptyList(),
    ) {
        fun toNearSideNode(): NearSideNode = AndroidNodeMapping.element(attributes, children.map { it.toNearSideNode() })
    }

    // ViewHierarchy.dumpNodeRec's attributes, in its order
    private fun regularAttributes(
        className: String,
        text: String,
        bounds: String,
        hintText: String = "",
        clickable: Boolean = false,
        focused: Boolean = false,
        enabled: Boolean = true,
        checked: Boolean = false,
        selected: Boolean = false,
        resourceId: String = "",
        contentDesc: String = "",
    ) = linkedMapOf(
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
        "focusable" to "true",
        "focused" to focused.toString(),
        "scrollable" to "false",
        "long-clickable" to "false",
        "password" to "false",
        "selected" to selected.toString(),
        "visible-to-user" to "true",
        "important-for-accessibility" to "true",
        "error" to "",
        "bounds" to bounds,
    )

    private fun randomNode(random: Random, depth: Int): RawNode {
        val texts = listOf("", "OK", "ok", "Cancel", "a\nb", "x & y", "<tag>", "\"q\"", "tab\there", "émoji 🙂")
        val classes = listOf("android.widget.TextView", "android.widget.Button", "android.view.ViewGroup", "android.widget.Toast")
        val left = random.nextInt(-200, 1200)
        val top = random.nextInt(-200, 2600)
        val attributes = regularAttributes(
            className = classes.random(random),
            text = texts.random(random),
            bounds = "[$left,$top][${left + random.nextInt(0, 600)},${top + random.nextInt(0, 400)}]",
            hintText = texts.random(random),
            clickable = random.nextBoolean(),
            focused = random.nextBoolean(),
            enabled = random.nextBoolean(),
            checked = random.nextBoolean(),
            selected = random.nextBoolean(),
            resourceId = listOf("", "com.example:id/ok", "ok").random(random),
            contentDesc = texts.random(random),
        )
        val childCount = if (depth < 3) random.nextInt(0, 4) else 0
        return RawNode(attributes, List(childCount) { randomNode(random, depth + 1) })
    }

    /** The shape KXmlSerializer writes with indent-output on, as the driver's dump does. */
    private fun serialise(roots: List<RawNode>): String {
        val out = StringBuilder("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>")
        out.append("\n<hierarchy rotation=\"0\">")
        roots.forEach { write(it, out, 1) }
        if (roots.isNotEmpty()) out.append("\n")
        out.append("</hierarchy>")
        return out.toString()
    }

    private fun write(node: RawNode, out: StringBuilder, depth: Int) {
        out.append("\n").append("  ".repeat(depth)).append("<node")
        node.attributes.forEach { (name, value) -> out.append(" ").append(name).append("=\"").append(escape(value)).append("\"") }
        if (node.children.isEmpty()) {
            out.append(" />")
            return
        }
        out.append(">")
        node.children.forEach { write(it, out, depth + 1) }
        out.append("\n").append("  ".repeat(depth)).append("</node>")
    }

    private fun escape(value: String) = buildString {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\n', '\r', '\t' -> append("&#").append(c.code).append(";")
                else -> append(c)
            }
        }
    }

}
