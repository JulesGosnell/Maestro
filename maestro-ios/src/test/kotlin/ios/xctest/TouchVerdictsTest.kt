package ios.xctest

import com.google.common.truth.Truth.assertThat
import ios.xctest.TouchVerdicts.Verdict
import org.junit.jupiter.api.Test

/**
 * artha#5055. The fixtures are backboardd's TouchEvents lines from real CI sim logs: the three
 * known dropped taps (two "bad usage pair", one "accessory") and a passing family login whose
 * 54 taps were all delivered.
 */
class TouchVerdictsTest {

    private fun lines(name: String): List<String> =
        javaClass.getResource("/artha-5055/$name")!!.readText().lines().filter { it.isNotBlank() }

    /** Replays a log one touch at a time; the verdict each touch got, with its time. */
    private fun replay(name: String): List<Pair<String, Verdict>> {
        val verdicts = TouchVerdicts()
        val touches = mutableListOf<Pair<String, Verdict>>()
        var mark = -1
        var at = ""
        fun close() {
            if (mark >= 0) touches += at to verdicts.verdictSince(mark, timeoutMs = 0)
        }
        for (line in lines(name)) {
            if ("init: <BKDirectTouchState" in line) {
                close()
                mark = verdicts.mark()
                at = line.substring(11, 23)
            }
            verdicts.accept(line)
        }
        close()
        return touches
    }

    @Test
    fun `prog 39312347609 - only the Sign In touch is dropped (bad usage pair)`() {
        val touches = replay("prog-39312347609-badpair.log")
        assertThat(touches.filter { it.second == Verdict.DROPPED }.map { it.first }).containsExactly("19:49:27.684")
        assertThat(touches.count { it.second == Verdict.DELIVERED }).isEqualTo(10)
    }

    @Test
    fun `zeug 39367562537 - only the button-org-0 touch is dropped (accessory)`() {
        val touches = replay("zeug-39367562537-accessory.log")
        assertThat(touches.filter { it.second == Verdict.DROPPED }.map { it.first }).containsExactly("04:14:39.948")
        assertThat(touches.count { it.second == Verdict.DELIVERED }).isEqualTo(4)
    }

    @Test
    fun `famlogin r3 - only the button-org-0 touch is dropped (bad usage pair)`() {
        val touches = replay("famlogin-r3-badpair.log")
        assertThat(touches.filter { it.second == Verdict.DROPPED }.map { it.first }).containsExactly("17:15:17.179")
    }

    @Test
    fun `a passing login - no touch is ever called dropped`() {
        val touches = replay("famlogin-r2-all-delivered.log")
        assertThat(touches).hasSize(54)
        assertThat(touches.none { it.second == Verdict.DROPPED }).isTrue()
    }

    @Test
    fun `the ignored-event lines alone are a drop, whatever the init line said`() {
        val verdicts = TouchVerdicts()
        val bad = lines("prog-39312347609-badpair.log")
        val good = bad.first { "init:" in it } // a touchScreen; builtin init
        val sender = Regex("senderID: 0x([0-9A-F]+)").find(good)!!.groupValues[1]
        val mark = verdicts.mark()
        verdicts.accept(good)
        verdicts.accept(bad.first { "unsupported service" in it }.replace(Regex("id:0x[0-9a-f]+"), "id:0x${sender.lowercase()}"))
        assertThat(verdicts.verdictSince(mark, 0)).isEqualTo(Verdict.DROPPED)
    }

    @Test
    fun `the init line alone is a drop, before any event is ignored`() {
        for ((name, at) in listOf("prog-39312347609-badpair.log" to "19:49:27.684", "zeug-39367562537-accessory.log" to "04:14:39.948")) {
            val verdicts = TouchVerdicts()
            val mark = verdicts.mark()
            verdicts.accept(lines(name).first { "init:" in it && it.substring(11, 23) == at })
            assertThat(verdicts.verdictSince(mark, 0)).isEqualTo(Verdict.DROPPED)
        }
    }

    @Test
    fun `no line at all is UNKNOWN, never a drop`() {
        val verdicts = TouchVerdicts()
        assertThat(verdicts.verdictSince(verdicts.mark(), timeoutMs = 50)).isEqualTo(Verdict.UNKNOWN)
    }

    // ---- send(): the re-send rule ----

    /** A tap whose touches backboardd logs as [touchLines], one list per send. */
    private fun sends(verdicts: TouchVerdicts, vararg touchLines: List<String>): Pair<Int, Int> {
        var sent = 0
        var reported = 0
        verdicts.send({ touchLines.getOrNull(sent++)?.forEach(verdicts::accept) }) { reported++ }
        return sent to reported
    }

    private fun session(name: String, at: String): List<String> {
        val all = lines(name)
        val start = all.indexOfFirst { "init:" in it && it.substring(11, 23) == at }
        val sender = Regex("senderID: 0x([0-9A-F]+)").find(all[start])!!.groupValues[1]
        return all.drop(start).takeWhile { "init:" !in it || it === all[start] }
            .filter { sender in it.uppercase() }
    }

    private val dropped = session("prog-39312347609-badpair.log", "19:49:27.684")
    private val delivered = session("prog-39312347609-badpair.log", "19:49:22.624")

    @Test
    fun `a dropped tap is sent once more, and reported`() {
        val (sent, reported) = sends(TouchVerdicts(), dropped, delivered)
        assertThat(sent).isEqualTo(2)
        assertThat(reported).isEqualTo(1)
    }

    @Test
    fun `a delivered tap is never sent again`() {
        val (sent, reported) = sends(TouchVerdicts(), delivered)
        assertThat(sent).isEqualTo(1)
        assertThat(reported).isEqualTo(0)
    }

    @Test
    fun `a tap backboardd said nothing about is never sent again`() {
        val (sent, _) = sends(TouchVerdicts(), emptyList())
        assertThat(sent).isEqualTo(1)
    }

    @Test
    fun `the re-send is ONE, even when it is dropped too`() {
        val (sent, reported) = sends(TouchVerdicts(), dropped, dropped)
        assertThat(sent).isEqualTo(2)
        assertThat(reported).isEqualTo(1)
    }

    @Test
    fun `an earlier dropped touch does not condemn a later tap`() {
        val verdicts = TouchVerdicts()
        dropped.forEach(verdicts::accept)
        val (sent, _) = sends(verdicts, delivered)
        assertThat(sent).isEqualTo(1)
    }
}
