package com.yagay.ydiag.root

import java.io.BufferedReader
import java.util.concurrent.TimeUnit

data class ShellResult(val code: Int, val stdout: String, val stderr: String)

object RootShell {
    fun isAvailable(): Boolean = runCatching {
        val result = exec("id", 4)
        result.code == 0 && result.stdout.contains("uid=0")
    }.getOrDefault(false)

    fun exec(command: String, timeoutSeconds: Long = 15): ShellResult {
        val process = ProcessBuilder("su", "-c", command).start()
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return ShellResult(-1, "", "timeout")
        }
        val out = process.inputStream.bufferedReader().use(BufferedReader::readText)
        val err = process.errorStream.bufferedReader().use(BufferedReader::readText)
        return ShellResult(process.exitValue(), out, err)
    }

    fun start(command: String): Process =
        ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
}
