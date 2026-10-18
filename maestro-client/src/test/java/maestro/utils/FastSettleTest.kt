package maestro.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** smithr#1246 Phase B: the Android window-settle loop, on a fake clock and a scripted device. */
internal class FastSettleTest {

    @AfterEach
    fun clear() {
        System.clearProperty(FastSettle.PROPERTY)
    }

    /** A device whose isWindowUpdating answers come from [answers], each costing its own ms. */
    private class Script(vararg val answers: Pair<Boolean, Long>) {
        var now = 0L
        var polls = 0
        var settles = 0
        var fallbacks = 0
        val log = mutableListOf<String>()

        fun run(fast: Boolean): String = FastSettle.windowSettle(
            windowMs = 750,
            fast = fast,
            clock = { now },
            poll = {
                val (updating, cost) = answers.getOrElse(polls) { answers.last() }
                polls++
                now += cost
                log += "poll=$updating"
                updating
            },
            settle = { settles++; now += 250; log += "settle"; "settled-$settles" },
            fallback = { fallbacks++; now += 250; log += "fallback"; "fallback" },
        )
    }

    private val quiet = false to 500L
    private val foreignApp = false to 5L
    private val updating = true to 20L

    @Test
    fun `off - a quiet screen costs two full polls, then the fallback settle (upstream)`() {
        val s = Script(quiet)
        assertThat(s.run(fast = false)).isEqualTo("fallback")
        assertThat(s.log).containsExactly("poll=false", "poll=false", "fallback").inOrder()
        assertThat(s.now).isEqualTo(1250)
    }

    @Test
    fun `on - a quiet screen ends the loop after ONE quiet poll, and still runs the fallback settle`() {
        val s = Script(quiet)
        assertThat(s.run(fast = true)).isEqualTo("fallback")
        assertThat(s.log).containsExactly("poll=false", "fallback").inOrder()
        assertThat(s.now).isEqualTo(750)
    }

    @Test
    fun `on - an instant false (app not in front) is not quiet, keeps polling to the deadline`() {
        val s = Script(foreignApp)
        s.run(fast = true)
        assertThat(s.polls).isEqualTo(150)
        assertThat(s.fallbacks).isEqualTo(1)
        assertThat(s.now).isAtLeast(750)
    }

    @Test
    fun `on - an update is settled, and a quiet poll after it ends the loop with that hierarchy`() {
        // the quiet poll ends at 720 ms, inside the 750 ms window: upstream would poll again
        val s = Script(updating, false to 450L, updating)
        assertThat(s.run(fast = true)).isEqualTo("settled-1")
        assertThat(s.log).containsExactly("poll=true", "settle", "poll=false").inOrder()
        assertThat(s.fallbacks).isEqualTo(0)
    }

    @Test
    fun `on - updates that continue are each settled until the deadline, as upstream`() {
        val s = Script(updating, updating, updating, updating)
        assertThat(s.run(fast = true)).isEqualTo("settled-3")
        assertThat(s.settles).isEqualTo(3)
        assertThat(s.polls).isEqualTo(3)
    }

    @Test
    fun `the flag is read from -Dmaestro_fastSettle and is off by default`() {
        assertThat(FastSettle.enabled()).isFalse()
        System.setProperty(FastSettle.PROPERTY, "on")
        assertThat(FastSettle.enabled()).isTrue()
        System.setProperty(FastSettle.PROPERTY, "off")
        assertThat(FastSettle.enabled()).isFalse()
    }

    @Test
    fun `the before-tap screenshot is skipped only when on and there is a single attempt`() {
        assertThat(FastSettle.skipBeforeTapScreenshot(1)).isFalse()
        System.setProperty(FastSettle.PROPERTY, "on")
        assertThat(FastSettle.skipBeforeTapScreenshot(1)).isTrue()
        assertThat(FastSettle.skipBeforeTapScreenshot(2)).isFalse()
    }
}
