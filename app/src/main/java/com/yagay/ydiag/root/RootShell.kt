package com.yagay.ydiag.root

import java.util.concurrent.TimeUnit

data class ShellResult(val code: Int, val stdout: String, val stderr: String)

object RootShell {
    private const val MAX_CAPTURE_CHARS = 24 * 1024 * 1024

    fun isAvailable(): Boolean = runCatching {
        val result = exec("id", 4)
        result.code == 0 && result.stdout.contains("uid=0")
    }.getOrDefault(false)

    fun exec(command: String, timeoutSeconds: Long = 15): ShellResult {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        val readerThread = Thread({
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (output.length < MAX_CAPTURE_CHARS) {
                            output.appendLine(line)
                        }
                    }
                }
            }
        }, "YDiag-root-reader").apply {
            isDaemon = true
            start()
        }

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        readerThread.join(1500)
        return ShellResult(
            code = if (completed) process.exitValue() else -1,
            stdout = output.toString(),
            stderr = if (completed) "" else "timeout",
        )
    }

    fun start(command: String): Process =
        ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
}
