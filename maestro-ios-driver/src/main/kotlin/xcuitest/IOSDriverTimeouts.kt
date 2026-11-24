package xcuitest

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * smithr (artha#5603): the iOS driver's request limits, in ONE place and set from the run's env.
 *
 * Before, three limits were hard-wired, each shorter than a flow's longest condition:
 * - the XCTest runner's FlyingFox handler timeout, 100 s (XCTestHTTPServer.swift): a 300 s
 *   waitUntil came back at 100 s as HTTP 500 with an empty body (loki lnfi2, zeug reid);
 * - the runner's /screenStatic cap, 30 s (ScreenDiffHandler.swift): longer settles were cut
 *   short as "not static";
 * - this client's okhttp read/call timeout, 200 s (XCTestDriverClient).
 *
 * Now [ENV] (milliseconds; default [DEFAULT_MS], at least 2x the longest wait a flow declares:
 * 300 s) sets the runner's handler timeout and /screenStatic cap — passed to the runner at launch
 * by [runnerEnvironment] — and the client's timeout is [CLIENT_MARGIN_MS] above it, so the
 * runner always answers (or names its own timeout) before the client gives up.
 * A value that is not a positive whole number keeps the default.
 */
object IOSDriverTimeouts {

    const val ENV = "MAESTRO_IOS_DRIVER_TIMEOUT_MS"
    const val DEFAULT_MS = 600_000L
    const val CLIENT_MARGIN_MS = 60_000L

    // Seam for the test harness only.
    var env: (String) -> String? = System::getenv

    /** The runner's per-request limit: its HTTP handler timeout and its /screenStatic cap. */
    fun serverMs(): Long = env(ENV)?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_MS

    /** The client's read and call timeout: always above the runner's own limit. */
    fun client(): Duration = (serverMs() + CLIENT_MARGIN_MS).milliseconds

    /**
     * What the runner process must be launched with so it uses [serverMs]: [prefix] is how the
     * launcher hands env to the runner (TEST_RUNNER_ for xcodebuild, SIMCTL_CHILD_ for simctl
     * and devicectl).
     */
    fun runnerEnvironment(prefix: String): Map<String, String> = mapOf("$prefix$ENV" to serverMs().toString())
}
