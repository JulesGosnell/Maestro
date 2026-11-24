package maestro.drivers

import org.slf4j.LoggerFactory

/**
 * smithr: a near-side waitUntil is driven by the CLIENT, in bounded slices, up to the caller's
 * own deadline — so no inner bound is ever shorter than the caller's.
 *
 * The iOS XCTest runner serves /waitUntil from FlyingFox, whose handler timeout is 100 s
 * (XCTestHTTPServer.swift), and the client's okhttp call timeout is 200 s. loki lnfi2
 * 2026-11-24: one 300 s waitUntil was cancelled by FlyingFox at 100 s ("Task timed out before
 * completion") and came back as HTTP 500 with an empty body (UnknownFailure). Raising either
 * bound would only move the cliff: whatever fixed number the server has, a longer condition hits it.
 *
 * So every server call asks for at most [SLICE_MS], well under both. The runner's own poll loop
 * never overruns the timeoutMs it is given and answers ok=false when it ends unmet — a slice
 * that ends without the condition is a normal "not yet", and the next slice is asked for what is
 * left of the caller's deadline. The last answer is returned either way: unmet at the deadline
 * is the caller's to name (Orchestra's ElementNotFound / a false condition), never a 500.
 * A real error from a slice is NOT retried: it propagates as before.
 *
 * Only a READ-ONLY check is ever sliced — the near-side waitUntil condition and POST
 * /screenStatic (ScreenStatic.iosSettleStatic). Nothing that taps, types or otherwise acts on the
 * device goes through here, so no action is ever sent twice (artha#5603).
 */
object NearSideWaitSlices {

    /** Below the runner's 100 s handler timeout and okhttp's 200 s call timeout, with margin. */
    const val SLICE_MS = 60_000L

    private val LOGGER = LoggerFactory.getLogger(NearSideWaitSlices::class.java)

    // Seam for the test harness only.
    var clock: () -> Long = System::currentTimeMillis

    /**
     * Calls [slice] with a per-call timeout of at most [sliceMs] until [met] holds for its answer
     * or [totalMs] (the caller's deadline) has passed; returns the last answer. Always calls at
     * least once, so a zero budget is one check.
     */
    fun <R> await(
        totalMs: Long,
        sliceMs: Long = SLICE_MS,
        met: (R) -> Boolean,
        slice: (timeoutMs: Long) -> R,
    ): R {
        require(sliceMs > 0) { "sliceMs must be positive" }
        val startMs = clock()
        val deadlineMs = startMs + totalMs.coerceAtLeast(0)
        var slices = 0
        while (true) {
            val remainingMs = (deadlineMs - clock()).coerceAtLeast(0)
            val answer = slice(minOf(sliceMs, remainingMs))
            slices++
            if (met(answer)) {
                if (slices > 1) LOGGER.info("Near-side waitUntil: met after ${clock() - startMs}ms in $slices slices")
                return answer
            }
            if (clock() >= deadlineMs) {
                LOGGER.info("Near-side waitUntil: not met by the caller's deadline (${totalMs}ms, $slices slices)")
                return answer
            }
            LOGGER.info("Near-side waitUntil: slice $slices not yet; ${deadlineMs - clock()}ms of ${totalMs}ms left")
        }
    }
}
