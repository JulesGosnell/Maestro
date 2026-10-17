package maestro.nearside

/**
 * The subset of Maestro's ElementSelector that can be resolved on the device (smithr#1184).
 *
 * The host builds one only when EVERY field of the selector is expressible here; anything else
 * stays on the full-dump path. Regexes travel as the pattern the host already compiled (after
 * `toRegexSafe`), so both sides compile the same string with [REGEX_OPTIONS].
 */
data class NearSideSelector(
    val textRegex: String? = null,
    val idRegex: String? = null,
    val enabled: Boolean? = null,
    val selected: Boolean? = null,
    val checked: Boolean? = null,
    val focused: Boolean? = null,
    val index: Int? = null,
    val below: NearSideSelector? = null,
    val above: NearSideSelector? = null,
    val leftOf: NearSideSelector? = null,
    val rightOf: NearSideSelector? = null,
) {

    companion object {
        /** Orchestra.REGEX_OPTIONS: the host compiles selector regexes with exactly these. */
        val REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)

        /** Advertised by a driver's capabilities call, in the v0 wire names. */
        val SELECTOR_FIELDS = listOf(
            "text", "textRegex", "id", "idRegex", "enabled", "selected", "checked", "focused",
            "index", "below", "above", "leftOf", "rightOf",
        )
    }

}
