package maestro.cli.util

import kotlin.system.exitProcess

/**
 * artha#5049: runs a CLI entry point so that a throwable escaping it EXITS the JVM.
 *
 * Without this, a throwable leaving `main` (picocli's execution-exception handler only takes
 * [Exception]; `XCUITestServerError` is a bare [Throwable]) prints `Exception in thread "main"`
 * and the main thread dies, but the JVM stays up while any non-daemon thread lives (the XCTest
 * runner client, analytics, update checks). The sidecar then sat out its whole 420 s budget on a
 * failure known at +82 s / +136 s.
 *
 * The throwable is reported as the JVM would report it, [onUncaught] runs (best-effort), and the
 * process exits [UNCAUGHT_EXIT_CODE] — non-zero, the same code picocli gives a failed command.
 * A normal return is left to [body] (which calls exitProcess with its own code).
 */
fun runMainExitingOnUncaught(
    onUncaught: () -> Unit = {},
    exit: (Int) -> Unit = { exitProcess(it) },
    body: () -> Unit,
) {
    try {
        body()
    } catch (e: Throwable) {
        System.err.print("Exception in thread \"${Thread.currentThread().name}\" ")
        e.printStackTrace()
        System.err.flush()
        runCatching { onUncaught() }
        exit(UNCAUGHT_EXIT_CODE)
    }
}

const val UNCAUGHT_EXIT_CODE = 1
