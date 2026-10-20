package ios.hosttouch

/**
 * smithr#1313: where a Maestro point lands in the space the simulator's built-in digitizer
 * takes — fractions (0..1) of the PORTRAIT screen.
 *
 * Maestro's points are interface points of the current orientation, the coordinates the view
 * hierarchy reports. The XCTest runner turns them into portrait points with
 * ScreenSizeHelper.orientationAwarePoint; this is the same transform, keyed by the same device
 * orientation names Maestro's setOrientation sends, followed by the division by the portrait
 * screen in points. Scale needs no handling: points and fractions are both scale-free.
 */
object HostTouchGeometry {

    const val PORTRAIT = "portrait"
    private const val LANDSCAPE_LEFT = "landscapeLeft"
    private const val LANDSCAPE_RIGHT = "landscapeRight"
    private const val UPSIDE_DOWN = "upsideDown"

    /** Every device orientation this transform knows; anything else is not converted. */
    val ORIENTATIONS = setOf(PORTRAIT, LANDSCAPE_LEFT, LANDSCAPE_RIGHT, UPSIDE_DOWN)

    fun isLandscape(orientation: String) = orientation == LANDSCAPE_LEFT || orientation == LANDSCAPE_RIGHT

    /**
     * The interface point ([x], [y]) as fractions of the portrait screen [portraitWidth] x
     * [portraitHeight] points, or null when it is off that screen or [orientation] is unknown.
     */
    fun toPortraitFraction(
        x: Double,
        y: Double,
        orientation: String,
        portraitWidth: Double,
        portraitHeight: Double,
    ): Pair<Double, Double>? {
        val (px, py) = when (orientation) {
            PORTRAIT -> x to y
            LANDSCAPE_LEFT -> (portraitWidth - y) to x
            LANDSCAPE_RIGHT -> y to (portraitHeight - x)
            UPSIDE_DOWN -> (portraitWidth - x) to (portraitHeight - y)
            else -> return null
        }
        val fx = px / portraitWidth
        val fy = py / portraitHeight
        if (fx !in 0.0..1.0 || fy !in 0.0..1.0) return null
        return fx to fy
    }

    /**
     * The interface size, in points, the XCTest runner must report for [orientation] on a screen
     * that is [portraitWidth] x [portraitHeight] in portrait.
     */
    fun expectedInterfaceSize(orientation: String, portraitWidth: Double, portraitHeight: Double): Pair<Double, Double> =
        if (isLandscape(orientation)) portraitHeight to portraitWidth else portraitWidth to portraitHeight
}
