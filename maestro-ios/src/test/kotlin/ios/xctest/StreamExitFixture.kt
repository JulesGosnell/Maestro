package ios.xctest

import kotlin.system.exitProcess

/**
 * artha#5055: a JVM that starts a touch stream and then ends the way Maestro does — args[0]:
 * "exit" (exitProcess, Maestro#4's uncaught path) or "hang" (until the parent signals it).
 * args[1] is the stream's command line. Prints "STREAM-UP" once the stream's process exists;
 * "exit" then waits for a line on stdin.
 */
fun main(args: Array<String>) {
    TouchVerdicts.SimulatorStream(listOf("/bin/sh", "-c", args[1]), name = "fixture")
    val deadline = System.currentTimeMillis() + 10_000
    while (ProcessHandle.current().descendants().noneMatch { it.info().commandLine().orElse("").contains(args[1].removePrefix("exec ")) } &&
        System.currentTimeMillis() < deadline) Thread.sleep(20)
    println("STREAM-UP")
    System.out.flush()
    when (args[0]) {
        "exit" -> {
            readLine() // the parent has seen the stream
            exitProcess(1)
        }
        else -> Thread.sleep(600_000)
    }
}
