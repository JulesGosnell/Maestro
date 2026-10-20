package xcuitest

/**
 * artha#5049: the fraction of screenshot pixels allowed to differ between two frames the runner
 * still calls static (`-Dmaestro.iosStaticThreshold`). Upstream wants byte-identical frames, and
 * a focused field's blinking caret (0.0116% of an iPhone screen) never gives two, so every settle
 * beside a focused field ran out its 3 s budget. 0.05% passes the caret; a changed line of text
 * (over 1%) is still motion. 0 restores the exact comparison; an unreadable value is the default.
 */
object IosStaticThreshold {
    const val PROPERTY = "maestro.iosStaticThreshold"
    const val DEFAULT = 0.0005

    fun value(): Double =
        System.getProperty(PROPERTY)?.trim()?.toDoubleOrNull()?.takeIf { it >= 0.0 && it < 1.0 } ?: DEFAULT
}
