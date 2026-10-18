package maestro.utils

/**
 * smithr#1246 Phase B: opt-in settle shortcuts, OFF unless `-Dmaestro.fastSettle=on`.
 *
 * Off, every path below is byte-for-byte the upstream behaviour. On:
 *  - Android: [windowSettle] stops polling for window updates after the first poll that
 *    really observed a quiet window (no TYPE_WINDOW_CONTENT_CHANGED for the app for the
 *    device's whole 500 ms wait), instead of always polling past the 750 ms deadline (which
 *    costs two 500 ms polls, ~1.1 s, before every tap).
 *  - Taps without a capable hierarchy (iOS): the before-tap screenshot is not taken when it
 *    cannot change the outcome (a single attempt, no retry-if-no-change).
 */
object FastSettle {

    const val PROPERTY = "maestro.fastSettle"

    /**
     * A poll that returned "not updating" counts as a quiet window only if it blocked at least
     * this long. The device waits 500 ms; an immediate `false` means the app is not in the
     * foreground (uiautomator skips the wait), which is NOT evidence of quiet.
     */
    const val QUIET_POLL_MIN_MS = 400L

    fun enabled(): Boolean = System.getProperty(PROPERTY)?.trim()?.lowercase() == "on"

    /**
     * smithr#1246 Phase C: `-Dmaestro.fastWindow=on` ends the window loop as soon as a hierarchy
     * settle has returned inside it. On artha's RN app the first poll usually SEES an update (the
     * previous command's tail), so fastSettle's quiet-poll exit never fires (A/B, 229 flows: 0 ms);
     * the loop then settles and spends the rest of the 750 ms window on one more poll. A settle
     * that returned early ended on two equal dumps — the same evidence Maestro accepts, alone,
     * after every tap (hierarchyBasedTap) — so that last poll is dropped.
     */
    const val WINDOW_PROPERTY = "maestro.fastWindow"

    fun windowEnabled(): Boolean = System.getProperty(WINDOW_PROPERTY)?.trim()?.lowercase() == "on"

    /**
     * Maestro.screenshotBasedTap compares a before-tap screenshot only to choose between
     * "proceed" and "nothing changed, try again"; with a single attempt both end the loop, so
     * the screenshot (a full device round trip, ~0.2-0.5 s on iOS) cannot change the outcome.
     */
    fun skipBeforeTapScreenshot(retries: Int): Boolean = enabled() && retries <= 1

    /**
     * The AndroidDriver.waitForWindowToSettle loop, with time and device calls injected.
     *
     * Upstream (fast = false): poll until [windowMs] has elapsed, running [settle] after every
     * poll that saw an update; if none did, run [fallback]. Because each quiet poll blocks
     * 500 ms on the device, a 750 ms window always costs two polls.
     *
     * fast = true: identical, except a poll that returned false after blocking for at least
     * [QUIET_POLL_MIN_MS] ends the loop.
     */
    fun <H : Any> windowSettle(
        windowMs: Long,
        fast: Boolean,
        clock: () -> Long,
        poll: () -> Boolean,
        settle: () -> H,
        fallback: () -> H,
        stopAfterSettle: Boolean = false,
    ): H {
        val endTime = clock() + windowMs
        var hierarchy: H? = null
        do {
            val pollStart = clock()
            if (poll()) {
                hierarchy = settle()
                if (stopAfterSettle) break
            } else if (fast && clock() - pollStart >= QUIET_POLL_MIN_MS) {
                break
            }
        } while (clock() < endTime)
        return hierarchy ?: fallback()
    }
}
