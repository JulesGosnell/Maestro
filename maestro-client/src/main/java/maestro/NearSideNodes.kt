package maestro

import maestro.nearside.NearSideNode

/** Converts between the host's [TreeNode] and the device's [NearSideNode], which mirror each other field for field. */
object NearSideNodes {

    fun NearSideNode.toTreeNode(): TreeNode = TreeNode(
        attributes = attributes.toMutableMap(),
        children = children.map { it.toTreeNode() },
        clickable = clickable,
        enabled = enabled,
        focused = focused,
        checked = checked,
        selected = selected,
    )

    fun TreeNode.toNearSideNode(): NearSideNode = NearSideNode(
        attributes = attributes.toMap(),
        children = children.map { it.toNearSideNode() },
        clickable = clickable,
        enabled = enabled,
        focused = focused,
        checked = checked,
        selected = selected,
    )

}
