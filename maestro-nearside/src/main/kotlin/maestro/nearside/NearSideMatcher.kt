package maestro.nearside

import maestro.nearside.NearSideSelector.Companion.REGEX_OPTIONS

private typealias NodeFilter = (List<NearSideNode>) -> List<NearSideNode>

/**
 * Resolves a [NearSideSelector] against a hierarchy ON THE DEVICE, with the host's semantics.
 *
 * Every function here mirrors one in the host's pipeline — `ViewHierarchy.from`
 * (filterOutOfBounds), `Orchestra.buildFilter` and `maestro.Filters` — and must stay in step
 * with it: equivalence with the full-dump path is the acceptance bar (smithr#1184), and
 * `NearSideEquivalenceTest` in maestro-orchestra holds the two side by side.
 */
class NearSideMatcher(
    private val screenWidth: Int,
    private val screenHeight: Int,
) {

    data class Result(
        val matches: List<NearSideNode>,
        val truncated: Boolean,
    )

    /** The host's `filter(ViewHierarchy.from(driver).aggregate())`, ordered as it orders them. */
    fun find(root: NearSideNode, selector: NearSideSelector, limit: Int): Result {
        val visibleRoot = filterOutOfBounds(root) ?: root
        val matches = filter(selector)(visibleRoot.aggregate())
        return Result(
            matches = matches.take(limit),
            truncated = matches.size > limit,
        )
    }

    // ViewHierarchy.kt: TreeNode.filterOutOfBounds
    private fun filterOutOfBounds(node: NearSideNode): NearSideNode? {
        if (node.attributes["ignoreBoundsFiltering"] == "true") {
            return node
        }

        val filtered = node.children.mapNotNull { filterOutOfBounds(it) }

        // parent can have missing bounds
        val bounds = runCatching { boundsOf(node) }.getOrNull()
        val visiblePercentage = bounds?.visiblePercentage() ?: 0.0

        if (visiblePercentage < 0.1 && filtered.isEmpty()) {
            return null
        }

        return node.copy(children = filtered)
    }

    // Orchestra.buildFilter, restricted to the fields NearSideSelector carries
    private fun filter(selector: NearSideSelector): NodeFilter {
        val basicFilters = mutableListOf<NodeFilter>()
        val relativeFilters = mutableListOf<NodeFilter>()

        selector.textRegex?.let { basicFilters += textMatches(Regex(it, REGEX_OPTIONS)) }
        selector.idRegex?.let { basicFilters += idMatches(Regex(it, REGEX_OPTIONS)) }

        selector.below?.let { relativeFilters += relativeTo(filter(it)) { b, other -> b.y > other.y } }
        selector.above?.let { relativeFilters += relativeTo(filter(it)) { b, other -> b.y < other.y } }
        selector.leftOf?.let { relativeFilters += relativeTo(filter(it)) { b, other -> b.x < other.x } }
        selector.rightOf?.let { relativeFilters += relativeTo(filter(it)) { b, other -> b.x > other.x } }

        selector.enabled?.let { expected -> basicFilters += { nodes -> nodes.filter { it.enabled == expected } } }
        selector.selected?.let { expected -> basicFilters += { nodes -> nodes.filter { it.selected == expected } } }
        selector.checked?.let { expected -> basicFilters += { nodes -> nodes.filter { it.checked == expected } } }
        selector.focused?.let { expected -> basicFilters += { nodes -> nodes.filter { it.focused == expected } } }

        val basicFilter: NodeFilter = if (basicFilters.isNotEmpty()) {
            deepestMatchingElement(intersect(basicFilters))
        } else {
            { nodes -> nodes }
        }

        val resultFilter = intersect(listOf(basicFilter) + relativeFilters)

        return selector.index
            ?.let { compose(resultFilter, index(it)) }
            ?: compose(resultFilter, clickableFirst())
    }

    // ---- maestro.Filters ----

    private fun intersect(filters: List<NodeFilter>): NodeFilter = { nodes ->
        filters
            .map { it(nodes).toSet() }
            .reduceOrNull { a, b -> a.intersect(b) }
            ?.toList() ?: nodes
    }

    private fun compose(first: NodeFilter, second: NodeFilter): NodeFilter = { nodes -> second(first(nodes)) }

    private fun textMatches(regex: Regex): NodeFilter {
        fun matches(value: String): Boolean {
            val strippedValue = value.replace('\n', ' ')

            return regex.matches(value)
                || regex.pattern == value
                || regex.matches(strippedValue)
                || regex.pattern == strippedValue
        }

        return { nodes ->
            val textMatches = nodes.filter { it.attributes["text"]?.let(::matches) ?: false }.toSet()
            val hintTextMatches = nodes.filter { it.attributes["hintText"]?.let(::matches) ?: false }
            val accessibilityTextMatches = nodes.filter { it.attributes["accessibilityText"]?.let(::matches) ?: false }.toSet()

            textMatches.union(hintTextMatches).union(accessibilityTextMatches).toList()
        }
    }

    private fun idMatches(regex: Regex): NodeFilter {
        return { nodes ->
            val exactMatches = nodes
                .filter { it.attributes["resource-id"]?.let { value -> regex.matches(value) } ?: false }
                .toSet()

            val idWithoutPrefixMatches = nodes
                .filter { it.attributes["resource-id"]?.let { value -> regex.matches(value.substringAfterLast('/')) } ?: false }
                .toSet()

            exactMatches.union(idWithoutPrefixMatches).toList()
        }
    }

    /**
     * Filters.relativeTo, as a membership test. The host also sorts the survivors by distance, but
     * a relative filter only ever feeds `intersect` behind the basic filter, which keeps the
     * basic filter's order — so that sort can never reach the result.
     */
    private fun relativeTo(otherFilter: NodeFilter, predicate: (Bounds, Bounds) -> Boolean): NodeFilter {
        return { nodes ->
            val matchingOthers = otherFilter(nodes).mapNotNull { boundsOf(it) }

            nodes.filter { node ->
                val bounds = boundsOf(node) ?: return@filter false
                matchingOthers.any { other -> predicate(bounds, other) }
            }
        }
    }

    private fun deepestMatchingElement(filter: NodeFilter): NodeFilter {
        return { nodes ->
            nodes.flatMap { node ->
                val matchingChildren = deepestMatchingElement(filter)(node.children)
                if (matchingChildren.isNotEmpty()) {
                    matchingChildren
                } else if (filter(listOf(node)).isNotEmpty()) {
                    listOf(node)
                } else {
                    emptyList()
                }
            }.distinct()
        }
    }

    private fun index(idx: Int): NodeFilter {
        val comparator: Comparator<NearSideNode> = compareBy(
            { boundsOf(it)?.y ?: Int.MAX_VALUE },
            { boundsOf(it)?.x ?: Int.MAX_VALUE },
        )

        return { nodes ->
            val sortedNodes = nodes.sortedWith(comparator)
            val resolvedIndex = if (idx >= 0) idx else sortedNodes.size + idx

            if (resolvedIndex < 0) {
                emptyList()
            } else {
                listOfNotNull(sortedNodes.getOrNull(resolvedIndex))
            }
        }
    }

    private fun clickableFirst(): NodeFilter {
        return { nodes -> nodes.sortedByDescending { it.clickable } }
    }

    // ---- maestro.UiElement ----

    private data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    // UiElement.toUiElementOrNull
    private fun boundsOf(node: NearSideNode): Bounds? {
        val boundsStr = node.attributes["bounds"] ?: return null

        val boundsArr = boundsStr
            .replace("][", ",")
            .removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .map { it.toInt() }

        return Bounds(
            x = boundsArr[0],
            y = boundsArr[1],
            width = boundsArr[2] - boundsArr[0],
            height = boundsArr[3] - boundsArr[1],
        )
    }

    // UiElement.getVisiblePercentage
    private fun Bounds.visiblePercentage(): Double {
        if (width == 0 && height == 0) {
            return 0.0
        }

        val overflow = (x <= 0) && (y <= 0) && (x + width >= screenWidth) && (y + height >= screenHeight)
        if (overflow) {
            return 1.0
        }

        val visibleX = maxOf(0, minOf(x + width, screenWidth) - maxOf(x, 0))
        val visibleY = maxOf(0, minOf(y + height, screenHeight) - maxOf(y, 0))
        val visibleArea = visibleX * visibleY
        val totalArea = width * height

        return visibleArea.toDouble() / totalArea.toDouble()
    }

}
