package maestro.nearside

/**
 * A view-hierarchy node as the host sees it, built ON THE DEVICE.
 *
 * Mirrors `maestro.TreeNode` field for field. Data-class equality is part of the matching
 * semantics: the host's Filters dedupe with `distinct()`/`toSet()`, so two structurally equal
 * nodes collapse into one there and must collapse here too.
 */
data class NearSideNode(
    val attributes: Map<String, String> = emptyMap(),
    val children: List<NearSideNode> = emptyList(),
    val clickable: Boolean? = null,
    val enabled: Boolean? = null,
    val focused: Boolean? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
) {

    fun aggregate(): List<NearSideNode> {
        return listOf(this) + children.flatMap { it.aggregate() }
    }

}
