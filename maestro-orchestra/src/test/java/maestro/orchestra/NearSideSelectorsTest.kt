package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import maestro.nearside.NearSideSelector
import maestro.utils.StringUtils.toRegexSafe
import org.junit.jupiter.api.Test

/** smithr#1184: which selectors are resolved on the device, and which stay on the full dump. */
class NearSideSelectorsTest {

    @Test
    fun `the device compiles regexes with the host's options`() {
        assertThat(NearSideSelector.REGEX_OPTIONS).isEqualTo(Orchestra.REGEX_OPTIONS)
    }

    @Test
    fun `text and id carry the host's compiled pattern`() {
        val nearSide = NearSideSelectors.from(ElementSelector(textRegex = "Sign in", idRegex = "submit"))

        assertThat(nearSide).isEqualTo(NearSideSelector(textRegex = "Sign in", idRegex = "submit"))
    }

    @Test
    fun `an invalid regex travels escaped, exactly as toRegexSafe made it`() {
        val nearSide = NearSideSelectors.from(ElementSelector(textRegex = "Price ["))

        assertThat(nearSide?.textRegex).isEqualTo("Price [".toRegexSafe(Orchestra.REGEX_OPTIONS).pattern)
        assertThat(nearSide?.textRegex).isNotEqualTo("Price [")
    }

    @Test
    fun `state filters, index and relative selectors are expressible`() {
        val nearSide = NearSideSelectors.from(
            ElementSelector(
                textRegex = "OK",
                enabled = true,
                selected = false,
                checked = true,
                focused = false,
                index = "1.0",
                below = ElementSelector(textRegex = "Title"),
                above = ElementSelector(idRegex = "footer"),
                leftOf = ElementSelector(textRegex = "Right"),
                rightOf = ElementSelector(textRegex = "Left", index = "-1"),
            )
        )

        assertThat(nearSide).isEqualTo(
            NearSideSelector(
                textRegex = "OK",
                enabled = true,
                selected = false,
                checked = true,
                focused = false,
                index = 1,
                below = NearSideSelector(textRegex = "Title"),
                above = NearSideSelector(idRegex = "footer"),
                leftOf = NearSideSelector(textRegex = "Right"),
                rightOf = NearSideSelector(textRegex = "Left", index = -1),
            )
        )
    }

    @Test
    fun `the deprecated optional flag does not affect matching, so it does not force a fallback`() {
        @Suppress("DEPRECATION")
        assertThat(NearSideSelectors.from(ElementSelector(textRegex = "OK", optional = true)))
            .isEqualTo(NearSideSelector(textRegex = "OK"))
    }

    @Test
    fun `every unsupported field falls back to the full dump`() {
        val unsupported = listOf(
            ElementSelector(textRegex = "OK", size = ElementSelector.SizeSelector(width = 10)),
            ElementSelector(textRegex = "OK", containsChild = ElementSelector(textRegex = "child")),
            ElementSelector(textRegex = "OK", containsDescendants = listOf(ElementSelector(textRegex = "child"))),
            ElementSelector(textRegex = "OK", traits = listOf(ElementTrait.TEXT)),
            ElementSelector(textRegex = "OK", childOf = ElementSelector(idRegex = "parent")),
            ElementSelector(css = "button"),
        )

        unsupported.forEach { selector ->
            assertThat(NearSideSelectors.from(selector)).isNull()
        }
    }

    @Test
    fun `an unsupported field inside a relative selector falls back the whole lookup`() {
        val selector = ElementSelector(
            textRegex = "OK",
            below = ElementSelector(textRegex = "Title", childOf = ElementSelector(idRegex = "header")),
        )

        assertThat(NearSideSelectors.from(selector)).isNull()
    }

    @Test
    fun `an index that is not a number falls back`() {
        assertThat(NearSideSelectors.from(ElementSelector(textRegex = "OK", index = "\${notEvaluated}"))).isNull()
    }

}
