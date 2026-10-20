package ios.hosttouch

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** smithr#1313: Maestro interface points to fractions of the portrait screen (iPhone 14, 390x844). */
class HostTouchGeometryTest {

    private val w = 390.0
    private val h = 844.0

    private fun at(x: Double, y: Double, orientation: String) =
        HostTouchGeometry.toPortraitFraction(x, y, orientation, w, h)

    @Test
    fun `portrait is a plain division by the screen`() {
        assertThat(at(195.0, 415.0, "portrait")).isEqualTo(0.5 to 415.0 / 844.0)
        assertThat(at(0.0, 0.0, "portrait")).isEqualTo(0.0 to 0.0)
        assertThat(at(390.0, 844.0, "portrait")).isEqualTo(1.0 to 1.0)
    }

    // The XCTest runner's ScreenSizeHelper.orientationAwarePoint, case for case: the interface
    // point (x, y) is the portrait point below.
    @Test
    fun `landscapeLeft matches the runner - (width - y, x)`() {
        assertThat(at(100.0, 50.0, "landscapeLeft")).isEqualTo((390.0 - 50.0) / w to 100.0 / h)
    }

    @Test
    fun `landscapeRight matches the runner - (y, height - x)`() {
        assertThat(at(100.0, 50.0, "landscapeRight")).isEqualTo(50.0 / w to (844.0 - 100.0) / h)
    }

    @Test
    fun `upsideDown turns the point through 180 degrees`() {
        assertThat(at(100.0, 50.0, "upsideDown")).isEqualTo((390.0 - 100.0) / w to (844.0 - 50.0) / h)
    }

    @Test
    fun `the corners of a landscape interface are the corners of the portrait screen`() {
        // interface 844x390 in landscape
        for (o in listOf("landscapeLeft", "landscapeRight")) {
            for ((x, y) in listOf(0.0 to 0.0, 844.0 to 0.0, 0.0 to 390.0, 844.0 to 390.0)) {
                val (fx, fy) = at(x, y, o)!!
                assertThat(fx).isAnyOf(0.0, 1.0)
                assertThat(fy).isAnyOf(0.0, 1.0)
            }
        }
    }

    @Test
    fun `a point off the screen is not converted`() {
        assertThat(at(391.0, 10.0, "portrait")).isNull()
        assertThat(at(-1.0, 10.0, "portrait")).isNull()
        assertThat(at(10.0, 845.0, "portrait")).isNull()
        // inside a portrait screen, but past the short side of a landscape one
        assertThat(at(10.0, 400.0, "landscapeLeft")).isNull()
    }

    @Test
    fun `an unknown orientation is not converted`() {
        assertThat(at(10.0, 10.0, "faceUp")).isNull()
    }

    @Test
    fun `the interface size XCTest must report swaps only in landscape`() {
        assertThat(HostTouchGeometry.expectedInterfaceSize("portrait", w, h)).isEqualTo(w to h)
        assertThat(HostTouchGeometry.expectedInterfaceSize("upsideDown", w, h)).isEqualTo(w to h)
        assertThat(HostTouchGeometry.expectedInterfaceSize("landscapeLeft", w, h)).isEqualTo(h to w)
        assertThat(HostTouchGeometry.expectedInterfaceSize("landscapeRight", w, h)).isEqualTo(h to w)
    }
}
