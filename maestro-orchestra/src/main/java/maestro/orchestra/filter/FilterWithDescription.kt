package maestro.orchestra.filter

import maestro.ElementFilter

data class FilterWithDescription(
    val description: String,
    val filterFunc: ElementFilter,
    // smithr: every node the selector matches, before index/clickable-first picks one — what an
    // iOS tap re-evaluates to keep tracking the node it chose (TapStableBounds)
    val candidatesFunc: ElementFilter = filterFunc,
)
