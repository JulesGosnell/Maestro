package maestro

data class FindElementResult(
    val element: UiElement,
    val hierarchy: ViewHierarchy,
    // smithr: the selector that found [element], for TapStableBounds to re-evaluate (null: unknown)
    val candidates: ElementFilter? = null,
)