package ios.hosttouch

import kotlin.system.exitProcess

/**
 * smithr#1313: a JVM that starts a helper and then ends the way Maestro does — args[0]: "exit"
 * (exitProcess, Maestro#4's uncaught path) or "hang" (until the parent signals it). args[1] is the
 * stand-in helper script, args[2] a unique marker on its command line. Prints "HELPER-UP" once the
 * helper has said ready; "exit" then waits for a line on stdin.
 */
fun main(args: Array<String>) {
    HostTouchHelper(listOf("/bin/sh", args[1], "ok", "/dev/null", args[2]), name = "fixture")
    println("HELPER-UP")
    System.out.flush()
    when (args[0]) {
        "exit" -> {
            readLine() // the parent has seen the helper
            exitProcess(1)
        }
        else -> Thread.sleep(600_000)
    }
}
