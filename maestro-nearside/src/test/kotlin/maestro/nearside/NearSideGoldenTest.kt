package maestro.nearside

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The golden vectors every port of [NearSideMatcher] is held to (smithr#1184): the iOS runner's
 * Swift matcher reads the same `near-side-golden.json` and must produce the same ordered matches.
 *
 * The cases are defined here and the expected matches are what NearSideMatcher answers now —
 * NearSideEquivalenceTest (maestro-orchestra) is what ties NearSideMatcher to the host's Filters.
 * The file is checked against this; after a deliberate change, regenerate it with
 * `./gradlew :maestro-nearside:test -PnearSideGoldenWrite=true` and review the diff.
 */
class NearSideGoldenTest {

    private val goldenFile = File(System.getProperty("nearSide.golden.file") ?: "src/test/resources/near-side-golden.json")

    @Test
    fun `the golden file is what NearSideMatcher answers`() {
        val rendered = render()

        if (System.getProperty("nearSide.golden.write") == "true") {
            goldenFile.writeText(rendered)
        }

        assertTrue(goldenFile.exists(), "Missing $goldenFile — run with -PnearSideGoldenWrite=true")
        assertEquals(rendered, goldenFile.readText(), "$goldenFile is stale or NearSideMatcher changed behaviour")
    }

    @Test
    fun `the cases cover every selector field and exercise matches, misses and truncation`() {
        val results = cases().map { it to NearSideMatcher(it.width, it.height).find(it.tree, it.selector, it.limit) }

        fun used(field: (NearSideSelector) -> Any?) = cases().any { case -> case.selector.walk().any { field(it) != null } }
        listOf<(NearSideSelector) -> Any?>(
            { it.textRegex }, { it.idRegex }, { it.enabled }, { it.selected }, { it.checked }, { it.focused },
            { it.index }, { it.below }, { it.above }, { it.leftOf }, { it.rightOf },
        ).forEachIndexed { i, field -> assertTrue(used(field), "No golden case uses selector field #$i") }

        assertTrue(results.count { it.second.matches.isEmpty() } >= 5)
        assertTrue(results.count { it.second.matches.size > 1 } >= 5)
        assertTrue(results.any { it.second.truncated })
        assertEquals(cases().size, cases().map { it.name }.toSet().size, "Case names must be unique")
    }

    private fun NearSideSelector.walk(): List<NearSideSelector> =
        listOf(this) + listOfNotNull(below, above, leftOf, rightOf).flatMap { it.walk() }

    // ---- rendering ----

    private fun render(): String {
        val cases = cases().map { case ->
            val result = NearSideMatcher(case.width, case.height).find(case.tree, case.selector, case.limit)
            linkedMapOf(
                "name" to case.name,
                "screenWidth" to case.width,
                "screenHeight" to case.height,
                "limit" to case.limit,
                "tree" to node(case.tree, withChildren = true),
                "selector" to selector(case.selector),
                "expected" to result.matches.map { node(it, withChildren = false) },
                "truncated" to result.truncated,
            )
        }
        val printer = DefaultPrettyPrinter().withObjectIndenter(DefaultIndenter("  ", "\n"))
        return ObjectMapper().writer(printer).writeValueAsString(linkedMapOf("version" to 1, "cases" to cases)) + "\n"
    }

    private fun node(node: NearSideNode, withChildren: Boolean): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>("attributes" to node.attributes.toSortedMap())
        if (withChildren) out["children"] = node.children.map { node(it, withChildren = true) }
        out["clickable"] = node.clickable
        out["enabled"] = node.enabled
        out["focused"] = node.focused
        out["checked"] = node.checked
        out["selected"] = node.selected
        return out
    }

    private fun selector(selector: NearSideSelector): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        selector.textRegex?.let { out["textRegex"] = it }
        selector.idRegex?.let { out["idRegex"] = it }
        selector.enabled?.let { out["enabled"] = it }
        selector.selected?.let { out["selected"] = it }
        selector.checked?.let { out["checked"] = it }
        selector.focused?.let { out["focused"] = it }
        selector.index?.let { out["index"] = it }
        selector.below?.let { out["below"] = selector(it) }
        selector.above?.let { out["above"] = selector(it) }
        selector.leftOf?.let { out["leftOf"] = selector(it) }
        selector.rightOf?.let { out["rightOf"] = selector(it) }
        return out
    }

    // ---- cases ----

    private data class Case(
        val name: String,
        val tree: NearSideNode,
        val selector: NearSideSelector,
        val limit: Int = 10,
        val width: Int = W,
        val height: Int = H,
    )

    private fun el(
        text: String? = null,
        bounds: String? = null,
        id: String? = null,
        hint: String? = null,
        a11y: String? = null,
        clickable: Boolean? = false,
        enabled: Boolean? = true,
        focused: Boolean? = false,
        checked: Boolean? = false,
        selected: Boolean? = false,
        ignoreBounds: Boolean = false,
        children: List<NearSideNode> = emptyList(),
    ): NearSideNode {
        val attributes = linkedMapOf<String, String>()
        text?.let { attributes["text"] = it }
        bounds?.let { attributes["bounds"] = it }
        id?.let { attributes["resource-id"] = it }
        hint?.let { attributes["hintText"] = it }
        a11y?.let { attributes["accessibilityText"] = it }
        attributes["ignoreBoundsFiltering"] = ignoreBounds.toString()
        return NearSideNode(attributes, children, clickable, enabled, focused, checked, selected)
    }

    /** A screen: a bounds-less, flag-less root (like the host's Document node) over a full-screen container. */
    private fun screen(vararg children: NearSideNode) =
        NearSideNode(children = listOf(el(bounds = "[0,0][$W,$H]", children = children.toList())))

    private fun cases(): List<Case> {
        val ok = el(text = "OK", bounds = "[100,1000][300,1100]", clickable = true)
        val cancel = el(text = "Cancel", bounds = "[400,1000][600,1100]", clickable = true)
        val title = el(text = "Title", bounds = "[0,100][1080,200]")
        val footer = el(text = "Footer", bounds = "[0,1800][1080,1900]")
        // A grid of "Cell" items: two rows of three, listed out of reading order
        val cells = listOf(
            el(text = "Cell", id = "c5", bounds = "[400,600][600,700]"),
            el(text = "Cell", id = "c1", bounds = "[0,400][200,500]"),
            el(text = "Cell", id = "c3", bounds = "[800,400][1000,500]"),
            el(text = "Cell", id = "c4", bounds = "[0,600][200,700]"),
            el(text = "Cell", id = "c2", bounds = "[400,400][600,500]"),
            el(text = "Cell", id = "c6", bounds = "[800,600][1000,700]"),
        )

        return listOf(
            // text
            Case("text exact", screen(ok, cancel), NearSideSelector(textRegex = "OK")),
            Case("text is case-insensitive", screen(ok, cancel), NearSideSelector(textRegex = "ok")),
            Case("text regex must match the whole value", screen(ok, cancel), NearSideSelector(textRegex = "Can")),
            Case("text regex wildcard", screen(ok, cancel, title), NearSideSelector(textRegex = ".*a.*")),
            Case(
                "text dot matches newline", screen(el(text = "line one\nline two", bounds = "[0,300][500,400]")),
                NearSideSelector(textRegex = "line one.line two"),
            ),
            Case(
                "text newline-stripped variant", screen(el(text = "a\nb", bounds = "[0,300][500,400]")),
                NearSideSelector(textRegex = "a b.*"),
            ),
            Case(
                "text literal pattern equals value", screen(el(text = "Price [1]", bounds = "[0,300][500,400]")),
                NearSideSelector(textRegex = "Price [1]"),
            ),
            Case(
                "text literal pattern equals newline-stripped value", screen(el(text = "Price\n[1]", bounds = "[0,300][500,400]")),
                NearSideSelector(textRegex = "Price [1]"),
            ),
            Case(
                "hintText matches", screen(el(text = "", hint = "Email", bounds = "[0,300][1080,400]")),
                NearSideSelector(textRegex = "Email"),
            ),
            Case(
                "accessibilityText matches", screen(el(a11y = "Close", bounds = "[900,100][1000,200]")),
                NearSideSelector(textRegex = "Close"),
            ),
            Case(
                "text, hint and accessibilityText union keeps tree order",
                screen(
                    el(a11y = "Go", bounds = "[0,300][100,400]"),
                    el(hint = "Go", bounds = "[0,500][100,600]"),
                    el(text = "Go", bounds = "[0,700][100,800]"),
                ),
                NearSideSelector(textRegex = "Go"),
            ),
            Case("text no match", screen(ok, cancel), NearSideSelector(textRegex = "Nothing")),

            // id
            Case(
                "id full resource name",
                screen(el(id = "com.example:id/ok", bounds = "[0,300][100,400]"), el(id = "com.example:id/cancel", bounds = "[0,500][100,600]")),
                NearSideSelector(idRegex = "com.example:id/ok"),
            ),
            Case(
                "id without package prefix",
                screen(el(id = "com.example:id/ok", bounds = "[0,300][100,400]"), el(id = "com.example:id/cancel", bounds = "[0,500][100,600]")),
                NearSideSelector(idRegex = "ok"),
            ),
            Case(
                "id regex", screen(el(id = "com.example:id/ok", bounds = "[0,300][100,400]"), el(id = "com.example:id/cancel", bounds = "[0,500][100,600]")),
                NearSideSelector(idRegex = ".*c.*"),
            ),
            Case("text and id together", screen(*cells.toTypedArray()), NearSideSelector(textRegex = "Cell", idRegex = "c4")),

            // state flags
            Case(
                "enabled false",
                screen(el(text = "Save", bounds = "[0,300][100,400]", enabled = false), el(text = "Save", bounds = "[0,500][100,600]")),
                NearSideSelector(textRegex = "Save", enabled = false),
            ),
            Case(
                "enabled true",
                screen(el(text = "Save", bounds = "[0,300][100,400]", enabled = false), el(text = "Save", bounds = "[0,500][100,600]")),
                NearSideSelector(textRegex = "Save", enabled = true),
            ),
            Case(
                "selected", screen(el(text = "Tab", bounds = "[0,300][100,400]"), el(text = "Tab", bounds = "[200,300][300,400]", selected = true)),
                NearSideSelector(textRegex = "Tab", selected = true),
            ),
            Case(
                "checked false", screen(el(text = "Box", bounds = "[0,300][100,400]", checked = true), el(text = "Box", bounds = "[0,500][100,600]")),
                NearSideSelector(textRegex = "Box", checked = false),
            ),
            Case(
                "focused", screen(el(text = "Field", bounds = "[0,300][100,400]"), el(text = "Field", bounds = "[0,500][100,600]", focused = true)),
                NearSideSelector(textRegex = "Field", focused = true),
            ),
            Case(
                "a null flag never equals false",
                screen(el(text = "Odd", bounds = "[0,300][100,400]", enabled = null), el(text = "Odd", bounds = "[0,500][100,600]", enabled = false)),
                NearSideSelector(textRegex = "Odd", enabled = false),
            ),
            Case(
                "flags alone: the deepest enabled node is the full-screen container",
                screen(el(text = "Off", bounds = "[0,300][100,400]", enabled = false)),
                NearSideSelector(enabled = true),
            ),

            // deepest matching element and dedupe
            Case(
                "deepest match wins over a matching parent",
                screen(el(text = "Row", bounds = "[0,300][1080,500]", clickable = true, children = listOf(el(text = "Row", bounds = "[0,300][500,400]")))),
                NearSideSelector(textRegex = "Row"),
            ),
            Case(
                "a parent matches when no child does",
                screen(el(text = "Row", bounds = "[0,300][1080,500]", children = listOf(el(text = "Other", bounds = "[0,300][500,400]")))),
                NearSideSelector(textRegex = "Row"),
            ),
            Case(
                "structurally equal nodes collapse into one",
                screen(el(text = "Same", bounds = "[0,300][100,400]"), el(text = "Same", bounds = "[0,300][100,400]")),
                NearSideSelector(textRegex = "Same"),
            ),
            Case(
                "nodes differing only in a flag stay distinct",
                screen(el(text = "Same", bounds = "[0,300][100,400]"), el(text = "Same", bounds = "[0,300][100,400]", focused = true)),
                NearSideSelector(textRegex = "Same"),
            ),
            // Dedupe compares every flag: one case per flag, and null is not false
            Case(
                "nodes differing only in enabled stay distinct",
                screen(el(text = "Same", bounds = "[0,300][100,400]"), el(text = "Same", bounds = "[0,300][100,400]", enabled = false)),
                NearSideSelector(textRegex = "Same"),
            ),
            Case(
                "nodes differing only in checked stay distinct",
                screen(el(text = "Same", bounds = "[0,300][100,400]"), el(text = "Same", bounds = "[0,300][100,400]", checked = true)),
                NearSideSelector(textRegex = "Same"),
            ),
            Case(
                "nodes differing only in selected stay distinct",
                screen(el(text = "Same", bounds = "[0,300][100,400]"), el(text = "Same", bounds = "[0,300][100,400]", selected = true)),
                NearSideSelector(textRegex = "Same"),
            ),
            Case(
                "nodes differing only in clickable stay distinct",
                screen(el(text = "Same", bounds = "[0,300][100,400]"), el(text = "Same", bounds = "[0,300][100,400]", clickable = true)),
                NearSideSelector(textRegex = "Same"),
            ),
            Case(
                "nodes differing only in clickable null versus false stay distinct",
                screen(el(text = "Same", bounds = "[0,300][100,400]", clickable = null), el(text = "Same", bounds = "[0,300][100,400]", clickable = false)),
                NearSideSelector(textRegex = "Same"),
            ),

            // ordering
            Case(
                "clickable first, stable: true then false then null",
                screen(
                    el(text = "Item", bounds = "[0,300][100,400]", clickable = null),
                    el(text = "Item", bounds = "[0,500][100,600]", clickable = false),
                    el(text = "Item", bounds = "[0,700][100,800]", clickable = true),
                    el(text = "Item", bounds = "[0,900][100,1000]", clickable = false),
                    el(text = "Item", bounds = "[0,1100][100,1200]", clickable = true),
                ),
                NearSideSelector(textRegex = "Item"),
            ),
            Case("index 0 is top-left in reading order", screen(*cells.toTypedArray()), NearSideSelector(textRegex = "Cell", index = 0)),
            Case("index 2", screen(*cells.toTypedArray()), NearSideSelector(textRegex = "Cell", index = 2)),
            Case("index -1 is the last in reading order", screen(*cells.toTypedArray()), NearSideSelector(textRegex = "Cell", index = -1)),
            Case("index -6 is the first", screen(*cells.toTypedArray()), NearSideSelector(textRegex = "Cell", index = -6)),
            Case("index past the end", screen(*cells.toTypedArray()), NearSideSelector(textRegex = "Cell", index = 6)),
            Case("index before the start", screen(*cells.toTypedArray()), NearSideSelector(textRegex = "Cell", index = -7)),
            Case(
                "index sorts a node without bounds last",
                NearSideNode(children = listOf(el(text = "Cell", id = "nobounds", children = listOf(el(text = "x", bounds = "[0,0][100,100]"))), cells[0], cells[1])),
                NearSideSelector(textRegex = "Cell", index = -1),
            ),

            // relative selectors
            Case("below", screen(title, ok, cancel, footer), NearSideSelector(textRegex = ".*", below = NearSideSelector(textRegex = "Cancel"))),
            Case("above", screen(title, ok, cancel, footer), NearSideSelector(textRegex = "Title|OK|Footer", above = NearSideSelector(textRegex = "OK"))),
            Case("leftOf", screen(title, ok, cancel, footer), NearSideSelector(textRegex = "OK|Cancel", leftOf = NearSideSelector(textRegex = "Cancel"))),
            Case("rightOf", screen(title, ok, cancel, footer), NearSideSelector(textRegex = "OK|Cancel", rightOf = NearSideSelector(textRegex = "OK"))),
            Case("below a missing anchor matches nothing", screen(title, ok), NearSideSelector(textRegex = "OK", below = NearSideSelector(textRegex = "Nope"))),
            Case(
                "below an anchor with its own index", screen(*cells.toTypedArray()),
                NearSideSelector(textRegex = "Cell", below = NearSideSelector(textRegex = "Cell", index = 0)),
            ),
            Case(
                "two relative selectors intersect", screen(*cells.toTypedArray()),
                NearSideSelector(textRegex = "Cell", below = NearSideSelector(idRegex = "c2"), rightOf = NearSideSelector(idRegex = "c4")),
            ),
            Case(
                "relative selector with index on the result", screen(*cells.toTypedArray()),
                NearSideSelector(textRegex = "Cell", below = NearSideSelector(idRegex = "c1"), index = 1),
            ),
            Case(
                "a relative selector alone matches every node with bounds below the anchor", screen(title, ok, footer),
                NearSideSelector(below = NearSideSelector(textRegex = "OK")),
            ),

            // filterOutOfBounds
            Case(
                "an element entirely off screen is pruned",
                screen(el(text = "Gone", bounds = "[0,2000][100,2100]"), el(text = "Here", bounds = "[0,300][100,400]")),
                NearSideSelector(textRegex = "Gone|Here"),
            ),
            Case(
                "an element under 10% visible is pruned, at 10% it is kept",
                screen(el(text = "Edge", id = "8pct", bounds = "[0,1916][100,1966]"), el(text = "Edge", id = "10pct", bounds = "[0,1915][100,1965]")),
                NearSideSelector(textRegex = "Edge"),
            ),
            Case(
                "an element overflowing the whole screen counts as fully visible",
                screen(el(text = "Huge", bounds = "[-10,-10][2000,3000]")),
                NearSideSelector(textRegex = "Huge"),
            ),
            Case(
                "an off-screen parent survives for its visible child",
                screen(el(text = "Parent", bounds = "[0,2000][100,2100]", children = listOf(el(text = "Child", bounds = "[0,300][100,400]")))),
                NearSideSelector(textRegex = "Parent"),
            ),
            Case(
                "ignoreBoundsFiltering keeps an off-screen node",
                screen(el(text = "Toast", bounds = "[0,2000][100,2100]", ignoreBounds = true)),
                NearSideSelector(textRegex = "Toast"),
            ),
            Case(
                "zero-size bounds are pruned",
                screen(el(text = "Dot", bounds = "[10,10][10,10]")),
                NearSideSelector(textRegex = "Dot"),
            ),
            Case(
                "zero width with height is NaN visible, and kept",
                screen(el(text = "Line", bounds = "[10,10][10,50]")),
                NearSideSelector(textRegex = "Line"),
            ),
            Case(
                "unparseable bounds are pruned",
                screen(el(text = "Bad", bounds = "garbage"), el(text = "Bad", bounds = "")),
                NearSideSelector(textRegex = "Bad"),
            ),
            Case(
                "missing bounds are pruned unless a child is visible",
                screen(el(text = "NoBounds"), el(text = "NoBounds", id = "kept", children = listOf(el(text = "Child", bounds = "[0,300][100,400]")))),
                NearSideSelector(textRegex = "NoBounds"),
            ),
            Case(
                "a smaller screen prunes more",
                screen(el(text = "Wide", bounds = "[600,300][700,400]"), el(text = "Wide", bounds = "[0,300][100,400]")),
                NearSideSelector(textRegex = "Wide"), width = 500, height = 1000,
            ),

            // limit
            Case("limit truncates", screen(*cells.toTypedArray()), NearSideSelector(textRegex = "Cell"), limit = 2),
            Case("limit equal to the count is not truncated", screen(*cells.toTypedArray()), NearSideSelector(textRegex = "Cell"), limit = 6),
        )
    }

    companion object {
        private const val W = 1080
        private const val H = 1920
    }

}
