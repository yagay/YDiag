package com.yagay.ydiag.service

import com.yagay.ydiag.root.RootShell
import java.io.File
import java.util.concurrent.TimeUnit

class PerfettoController(
    private val sessionDir: File,
    private val appUid: Int,
) {
    private val tempPath = "/data/local/tmp/ydiag-${sessionDir.name}.perfetto-trace"
    @Volatile private var process: Process? = null

    fun start(): Boolean {
        if (process?.isAlive == true) return true
        return runCatching {
            RootShell.exec("rm -f '$tempPath'", 5)
            process = RootShell.start(
                "exec perfetto -o '$tempPath' -t 30m sched freq idle am wm gfx view binder_driver"
            )
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun stopAndCollect(): File? {
        val running = process
        process = null
        runCatching {
            if (running?.isAlive == true) {
                running.destroy()
                if (!running.waitFor(1500, TimeUnit.MILLISECONDS)) running.destroyForcibly()
            }
        }

        val perfettoDir = File(sessionDir, "perfetto").apply { mkdirs() }
        val destination = File(perfettoDir, "trace.perfetto-trace")
        val escaped = destination.absolutePath.replace("'", "'\\''")
        RootShell.exec(
            "if [ -s '$tempPath' ]; then " +
                "cp '$tempPath' '$escaped' && chown $appUid:$appUid '$escaped' && chmod 600 '$escaped'; " +
                "rm -f '$tempPath'; fi",
            20,
        )
        return destination.takeIf { it.isFile && it.length() > 0L }
    }
}
