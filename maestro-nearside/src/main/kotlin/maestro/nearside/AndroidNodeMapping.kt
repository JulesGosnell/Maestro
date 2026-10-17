package maestro.nearside

/**
 * Turns the attributes the Android driver writes for one `<node>` of its XML dump into the
 * node the host builds from that element.
 *
 * Mirrors `AndroidDriver.mapHierarchy`; `AndroidNodeMappingEquivalenceTest` in maestro-client
 * runs both over the same XML.
 */
object AndroidNodeMapping {

    private const val TOAST_CLASS_NAME = "android.widget.Toast"

    // XML attribute name -> host attribute name, for attributes copied verbatim when present
    private val COPIED = listOf(
        "text" to "text",
        "content-desc" to "accessibilityText",
        "hintText" to "hintText",
        "resource-id" to "resource-id",
        "clickable" to "clickable",
        "bounds" to "bounds",
        "enabled" to "enabled",
        "focused" to "focused",
        "checked" to "checked",
        "scrollable" to "scrollable",
        "selected" to "selected",
        "class" to "class",
        "important-for-accessibility" to "important-for-accessibility",
        "error" to "error",
    )

    /** The `<hierarchy>` element that wraps every dump: no attributes the host copies. */
    fun hierarchyElement(children: List<NearSideNode>): NearSideNode = element(emptyMap(), children)

    /**
     * The driver serialises with indent-output on, and the host's DOM keeps that whitespace as
     * Text nodes, which mapHierarchy turns into empty nodes: one before, between and after the
     * child elements of any element that has some.
     */
    private fun withIndentation(children: List<NearSideNode>): List<NearSideNode> {
        if (children.isEmpty()) return children
        return children.flatMap { listOf(WHITESPACE, it) } + WHITESPACE
    }

    private val WHITESPACE = NearSideNode()

    /** The XML Document itself: not an Element, so no attributes and no booleans. */
    fun document(hierarchy: NearSideNode): NearSideNode = NearSideNode(children = listOf(hierarchy))

    /** One `<node>`, from the attributes the driver serialises for it. */
    fun element(xmlAttributes: Map<String, String>, children: List<NearSideNode>): NearSideNode {
        val attributes = mutableMapOf<String, String>()
        COPIED.forEach { (xmlName, name) ->
            xmlAttributes[xmlName]?.let { attributes[name] = it }
        }
        attributes["ignoreBoundsFiltering"] = (xmlAttributes["class"] == TOAST_CLASS_NAME).toString()

        // Element.getAttribute answers "" for a missing attribute, so missing reads as false
        fun bool(name: String) = xmlAttributes[name] == "true"

        return NearSideNode(
            attributes = attributes,
            children = withIndentation(children),
            clickable = bool("clickable"),
            enabled = bool("enabled"),
            focused = bool("focused"),
            checked = bool("checked"),
            selected = bool("selected"),
        )
    }

}
