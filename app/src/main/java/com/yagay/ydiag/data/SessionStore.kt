package com.yagay.ydiag.data

import android.content.Context
import com.yagay.ydiag.model.Issue
import com.yagay.ydiag.model.SessionMeta
import com.yagay.ydiag.model.TimelineEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class SessionStore(private val context: Context) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val compactJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    val root: File get() = File(context.filesDir, "sessions").apply { mkdirs() }

    fun start(
        packages: Set<String>,
        options: Set<String>,
        rootAvailable: Boolean,
        lsposedConnected: Boolean,
        maxBytes: Long,
    ): SessionWriter {
        val id = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault()).format(Instant.now()) + "-" + UUID.randomUUID().toString().take(6)
        val dir = File(root, id).apply { mkdirs() }
        val meta = SessionMeta(
            id = id,
            startedAt = System.currentTimeMillis(),
            targetPackages = packages.sorted(),
            enabledOptions = options.sorted(),
            rootAvailable = rootAvailable,
            lsposedConnected = lsposedConnected,
        )
        File(dir, "meta.json").writeText(json.encodeToString(meta))
        return SessionWriter(dir, meta, maxBytes, json, compactJson)
    }

    fun listSessions(): List<Pair<File, SessionMeta>> =
        root.listFiles().orEmpty().mapNotNull { dir ->
            runCatching {
                dir to json.decodeFromString<SessionMeta>(File(dir, "meta.json").readText())
            }.getOrNull()
        }.sortedByDescending { it.second.startedAt }

    fun prune(keep: Int = 40) {
        listSessions().drop(keep).forEach { it.first.deleteRecursively() }
    }
}

class SessionWriter(
    val directory: File,
    private var meta: SessionMeta,
    private val maxBytes: Long,
    private val prettyJson: Json,
    private val compactJson: Json,
) : Closeable {
    private var part = 1
    private var rawFile = rawPart(part)
    private var rawWriter = FileWriter(rawFile, true)
    private val timelineWriter = FileWriter(File(directory, "timeline.jsonl"), true)
    private val issuesWriter = FileWriter(File(directory, "issues.jsonl"), true)
    private val processWriter = FileWriter(File(directory, "process-snapshots.txt"), true)

    @Synchronized
    fun appendRaw(line: String) {
        if (rawFile.length() >= maxBytes) {
            rawWriter.flush()
            rawWriter.close()
            part += 1
            rawFile = rawPart(part)
            rawWriter = FileWriter(rawFile, true)
        }
        rawWriter.appendLine(line)
    }

    @Synchronized
    fun appendTimeline(event: TimelineEvent) {
        timelineWriter.appendLine(compactJson.encodeToString(event))
        timelineWriter.flush()
    }

    @Synchronized
    fun appendIssue(issue: Issue) {
        issuesWriter.appendLine(compactJson.encodeToString(issue))
        issuesWriter.flush()
    }

    @Synchronized
    fun appendProcessSnapshot(text: String) {
        processWriter.appendLine("=== ${System.currentTimeMillis()} ===")
        processWriter.appendLine(text)
        processWriter.flush()
    }

    @Synchronized
    fun updateTargets(packages: Set<String>, options: Set<String>) {
        meta = meta.copy(targetPackages = packages.sorted(), enabledOptions = options.sorted())
        writeMeta()
    }

    @Synchronized
    fun markProblem(timestamp: Long) {
        meta = meta.copy(problemMarks = meta.problemMarks + timestamp)
        writeMeta()
    }

    @Synchronized
    fun finish() {
        meta = meta.copy(endedAt = System.currentTimeMillis())
        writeMeta()
    }

    private fun writeMeta() {
        File(directory, "meta.json").writeText(prettyJson.encodeToString(meta))
    }

    private fun rawPart(index: Int) = File(directory, "logcat-full-${index.toString().padStart(3, '0')}.txt")

    override fun close() {
        runCatching { finish() }
        runCatching { rawWriter.flush(); rawWriter.close() }
        runCatching { timelineWriter.flush(); timelineWriter.close() }
        runCatching { issuesWriter.flush(); issuesWriter.close() }
        runCatching { processWriter.flush(); processWriter.close() }
    }
}
