package com.yagay.ydiag.service

import com.yagay.ydiag.model.EventSource
import com.yagay.ydiag.model.Severity
import com.yagay.ydiag.model.TimelineEvent
import com.yagay.ydiag.root.ProcessIdentity
import java.util.concurrent.atomic.AtomicLong

data class ParsedLog(
    val timestamp: Long,
    val pid: Int?,
    val level: String,
    val tag: String,
    val message: String,
    val raw: String,
)

object LogParser {
    private val epoch = Regex("""^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s?(.*)$""")
    private val eventCounter = AtomicLong()

    fun parse(line: String): ParsedLog {
        val match = epoch.matchEntire(line)
        if (match == null) {
            return ParsedLog(System.currentTimeMillis(), null, "I", "", line, line)
        }
        val timestamp = ((match.groupValues[1].toDoubleOrNull() ?: 0.0) * 1000.0).toLong()
            .takeIf { it > 0 } ?: System.currentTimeMillis()
        return ParsedLog(
            timestamp = timestamp,
            pid = match.groupValues[2].toIntOrNull(),
            level = match.groupValues[4],
            tag = match.groupValues[5].trim(),
            message = match.groupValues[6],
            raw = line,
        )
    }

    fun relevant(
        parsed: ParsedLog,
        packages: Set<String>,
        processes: Map<Int, ProcessIdentity>,
    ): Pair<Boolean, String?> {
        val process = parsed.pid?.let(processes::get)
        if (process != null) return true to process.packageName
        val combined = parsed.tag + " " + parsed.message
        val packageName = packages.firstOrNull { combined.contains(it, ignoreCase = false) }
        if (packageName != null) return true to packageName
        if (parsed.tag.startsWith("YDiag.Hook")) {
            val hookPackage = packages.firstOrNull { parsed.message.contains(it) }
            if (hookPackage != null) return true to hookPackage
        }
        return false to null
    }

    fun event(parsed: ParsedLog, packageName: String?, processName: String?): TimelineEvent {
        val source = when {
            parsed.tag.startsWith("YDiag.Hook") -> EventSource.LSPOSED
            parsed.tag.contains("AndroidRuntime") -> EventSource.APP
            parsed.tag.contains("crash_dump") || parsed.tag == "libc" -> EventSource.NATIVE
            parsed.tag.contains("ActivityTaskManager") || parsed.tag.contains("ActivityManager") -> EventSource.SYSTEM
            else -> EventSource.APP
        }
        val severity = when (parsed.level) {
            "F" -> Severity.FATAL
            "E" -> Severity.ERROR
            "W" -> Severity.WARNING
            else -> Severity.INFO
        }
        val id = "E${eventCounter.incrementAndGet().toString().padStart(7, '0')}"
        return TimelineEvent(
            id = id,
            timestamp = parsed.timestamp,
            source = source.name,
            severity = severity.name,
            category = parsed.tag.ifBlank { "logcat" },
            title = parsed.message.take(180).ifBlank { parsed.raw.take(180) },
            detail = parsed.raw,
            packageName = packageName,
            processName = processName,
            pid = parsed.pid,
        )
    }
}
