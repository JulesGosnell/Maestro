package maestro.orchestra

import maestro.nearside.NearSideSelector
import maestro.utils.StringUtils.toRegexSafe

/**
 * Decides whether an [ElementSelector] can be resolved on the device (smithr#1184), and builds
 * the [NearSideSelector] that does it with the same semantics as Orchestra.buildFilter.
 */
object NearSideSelectors {

    /**
     * The near-side form of [selector], or null when any field it sets is not expressible on the
     * device — that lookup then stays on the full-dump path.
     *
     * Expressibility is decided by removing every field the device understands and requiring
     * nothing to be left, so a field added to ElementSelector later falls back until it is
     * supported here, rather than being silently dropped.
     */
    fun from(selector: ElementSelector): NearSideSelector? {
        @Suppress("DEPRECATION")
        val unsupported = selector.copy(
            textRegex = null,
            idRegex = null,
            enabled = null,
            selected = null,
            checked = null,
            focused = null,
            index = null,
            below = null,
            above = null,
            leftOf = null,
            rightOf = null,
            // Not a matching field: buildFilter ignores it
            optional = false,
        )
        if (unsupported != ElementSelector()) {
            return null
        }

        return NearSideSelector(
            textRegex = selector.textRegex?.toRegexSafe(Orchestra.REGEX_OPTIONS)?.pattern,
            idRegex = selector.idRegex?.toRegexSafe(Orchestra.REGEX_OPTIONS)?.pattern,
            enabled = selector.enabled,
            selected = selector.selected,
            checked = selector.checked,
            focused = selector.focused,
            // buildFilter: index?.toDouble()?.toInt()
            index = selector.index?.let { it.toDoubleOrNull()?.toInt() ?: return null },
            below = selector.below?.let { from(it) ?: return null },
            above = selector.above?.let { from(it) ?: return null },
            leftOf = selector.leftOf?.let { from(it) ?: return null },
            rightOf = selector.rightOf?.let { from(it) ?: return null },
        )
    }

}
