package com.yagay.ydiag.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yagay.ydiag.R
import com.yagay.ydiag.YDiagApp
import com.yagay.ydiag.data.Preferences
import com.yagay.ydiag.data.SessionStore
import com.yagay.ydiag.data.SessionWriter
import com.yagay.ydiag.diagnostics.IssueDetector
import com.yagay.ydiag.model.Severity
import com.yagay.ydiag.model.TimelineEvent
import com.yagay.ydiag.root.ProcessIdentity
import com.yagay.ydiag.root.ProcessTracker
import com.yagay.ydiag.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences by lazy { Preferences(this) }
    private val sessionStore by lazy { SessionStore(this) }

    @Volatile private var targets: Set<String> = emptySet()
    @Volatile private var options: Set<String> = emptySet()
    @Volatile private var processes: Map<Int, ProcessIdentity> = emptyMap()

    private var rootAvailable = false
    private var session: SessionWriter? = null
    private var logcatProcess: Process? = null
    private var logJob: Job? = null
    private var processJob: Job? = null
    private var eventCount = AtomicLong()
    private var errorCount = AtomicLong()
    private var warningCount = AtomicLong()
    private val recentEvents = ArrayDeque<TimelineEvent>()
    private val recentIssues = ArrayDeque<com.yagay.ydiag.model.Issue>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        rootAvailable = RootShell.isAvailable()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SYNC -> sync(
                intent.getStringArrayListExtra(EXTRA_PACKAGES)?.toSet().orEmpty(),
                intent.getStringArrayListExtra(EXTRA_OPTIONS)?.toSet().orEmpty(),
            )
            ACTION_MARK -> markProblem()
            ACTION_STOP -> stopMonitoring()
            else -> sync(preferences.selectedPackages, preferences.enabledOptions)
        }
        return START_STICKY
    }

    private fun sync(newTargets: Set<String>, newOptions: Set<String>) {
        if (newTargets.isEmpty()) {
            stopMonitoring()
            return
        }
        val previousOptions = options
        targets = newTargets
        options = newOptions
        preferences.selectedPackages = newTargets
        preferences.enabledOptions = newOptions

        if (session == null) {
            sessionStore.prune()
            session = sessionStore.start(
                packages = targets,
                options = options,
                rootAvailable = rootAvailable,
                lsposedConnected = (application as? YDiagApp)?.moduleState?.value?.connected == true,
                maxBytes = preferences.maxSessionMb.toLong() * 1024L * 1024L,
            )
            eventCount.set(0)
            errorCount.set(0)
            warningCount.set(0)
            recentEvents.clear()
            recentIssues.clear()
            startForeground(NOTIFICATION_ID, notification())
        } else {
            session?.updateTargets(targets, options)
        }

        addSystemEvent("监控目标已更新", targets.joinToString())
        startProcessTracker()
        if (logJob == null || bufferSignature(previousOptions) != bufferSignature(options)) {
            restartLogcat()
        }
        (application as? YDiagApp)?.syncDeepTracking(targets, options)
        publish("正在监控")
    }

    private fun restartLogcat() {
        logJob?.cancel()
        runCatching { logcatProcess?.destroy() }
        val buffers = buildList {
            if ("logcat" in options) { add("main"); add("system") }
            if ("crash" in options) add("crash")
            if ("events" in options) add("events")
            if (isEmpty()) add("main")
        }.distinct()
        val args = buffers.joinToString(" ") { "-b $it" }
        logJob = scope.launch {
            try {
                val process = if (rootAvailable) {
                    RootShell.start("logcat -v epoch $args")
                } else {
                    ProcessBuilder("logcat", "-v", "epoch", "-b", "main").redirectErrorStream(true).start()
                }
                logcatProcess = process
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (!isActive) return@forEach
                        consumeLine(line)
                    }
                }
            } catch (failure: Throwable) {
                addSystemIssue("日志采集失败", failure.toString())
            }
        }
    }

    private fun startProcessTracker() {
        if (processJob?.isActive == true) return
        processJob = scope.launch {
            while (isActive) {
                if (rootAvailable) {
                    val snapshot = ProcessTracker.snapshot(targets)
                    processes = snapshot
                    session?.appendProcessSnapshot(snapshot.values.joinToString("\n") {
                        "${it.pid}\t${it.uid}\t${it.name}\t${it.packageName}"
                    })
                    publish("正在监控")
                }
                delay(2500)
            }
        }
    }

    private fun consumeLine(line: String) {
        val parsed = LogParser.parse(line)
        val (relevant, packageName) = LogParser.relevant(parsed, targets, processes)
        if (!relevant) return

        val processName = parsed.pid?.let { processes[it]?.name }
        val event = LogParser.event(parsed, packageName, processName)
        session?.appendRaw(line)
        session?.appendTimeline(event)
        rememberEvent(event)

        val detection = IssueDetector.detect(line, parsed.timestamp, event.id)
        if (detection != null) {
            session?.appendIssue(detection.issue)
            rememberIssue(detection.issue)
        }
        if (eventCount.incrementAndGet() % 20L == 0L) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification())
            publish(event.title)
        } else {
            publish(event.title)
        }
    }

    private fun markProblem() {
        val now = System.currentTimeMillis()
        session?.markProblem(now)
        val event = TimelineEvent(
            id = "MARK-$now",
            timestamp = now,
            source = "YDIAG",
            severity = Severity.WARNING.name,
            category = "problem_marker",
            title = "用户标记：问题发生了",
            detail = "以此时间点为中心优先分析前后日志",
        )
        session?.appendTimeline(event)
        rememberEvent(event)
        publish("已标记问题时间点")
    }

    private fun addSystemEvent(title: String, detail: String) {
        val now = System.currentTimeMillis()
        val event = TimelineEvent(
            id = "SYS-$now",
            timestamp = now,
            source = "YDIAG",
            severity = Severity.INFO.name,
            category = "monitor",
            title = title,
            detail = detail,
        )
        session?.appendTimeline(event)
        rememberEvent(event)
    }

    private fun addSystemIssue(title: String, detail: String) {
        val now = System.currentTimeMillis()
        val issue = com.yagay.ydiag.model.Issue(
            id = "YDIAG-$now",
            timestamp = now,
            severity = Severity.ERROR.name,
            category = "collector",
            title = title,
            detail = detail,
        )
        session?.appendIssue(issue)
        rememberIssue(issue)
        publish(title)
    }

    private fun rememberEvent(event: TimelineEvent) {
        synchronized(recentEvents) {
            recentEvents.addFirst(event)
            while (recentEvents.size > 120) recentEvents.removeLast()
        }
    }

    private fun rememberIssue(issue: com.yagay.ydiag.model.Issue) {
        if (issue.severity == Severity.WARNING.name) warningCount.incrementAndGet()
        else errorCount.incrementAndGet()
        synchronized(recentIssues) {
            recentIssues.addFirst(issue)
            while (recentIssues.size > 80) recentIssues.removeLast()
        }
    }

    private fun publish(message: String) {
        val events = synchronized(recentEvents) { recentEvents.toList() }
        val issues = synchronized(recentIssues) { recentIssues.toList() }
        _state.value = MonitorState(
            running = session != null,
            rootAvailable = rootAvailable,
            sessionId = session?.directory?.name,
            targetPackages = targets,
            processCount = processes.size,
            eventCount = eventCount.get(),
            errorCount = errorCount.get(),
            warningCount = warningCount.get(),
            lastMessage = message,
            recentEvents = events,
            recentIssues = issues,
        )
    }

    private fun stopMonitoring() {
        targets = emptySet()
        preferences.selectedPackages = emptySet()
        logJob?.cancel(); logJob = null
        processJob?.cancel(); processJob = null
        runCatching { logcatProcess?.destroy() }
        logcatProcess = null
        session?.close()
        session = null
        processes = emptyMap()
        publish("监控已停止")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_ydiag)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text, targets.size, eventCount.get()))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun bufferSignature(value: Set<String>): String =
        listOf("logcat","crash","events").filter { it in value }.joinToString(",")

    override fun onDestroy() {
        runCatching { session?.close() }
        runCatching { logcatProcess?.destroy() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SYNC = "com.yagay.ydiag.SYNC"
        const val ACTION_MARK = "com.yagay.ydiag.MARK"
        const val ACTION_STOP = "com.yagay.ydiag.STOP"
        const val EXTRA_PACKAGES = "packages"
        const val EXTRA_OPTIONS = "options"
        private const val CHANNEL_ID = "ydiag_monitor"
        private const val NOTIFICATION_ID = 7301

        private val _state = MutableStateFlow(MonitorState())
        val state: StateFlow<MonitorState> = _state
    }
}
