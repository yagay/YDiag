package com.yagay.ydiag.model

import kotlinx.serialization.Serializable

enum class Recommendation { RECOMMENDED, ON_DEMAND, DEEP }
enum class LoadLevel { VERY_LOW, LOW, MEDIUM, HIGH, VERY_HIGH }
enum class DiagnosticCategory { BASIC, ROOT, LSPOSED, SYSTEM, WEBVIEW, NETWORK, PERFORMANCE, FILES, NATIVE }
enum class Severity { INFO, WARNING, ERROR, FATAL }
enum class EventSource { APP, SYSTEM, LSPOSED, ROOT, NATIVE, KERNEL, YDIAG }

@Serializable
data class SessionMeta(
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val targetPackages: List<String>,
    val enabledOptions: List<String>,
    val problemMarks: List<Long> = emptyList(),
    val rootAvailable: Boolean = false,
    val lsposedConnected: Boolean = false,
)

@Serializable
data class TimelineEvent(
    val id: String,
    val timestamp: Long,
    val source: String,
    val severity: String,
    val category: String,
    val title: String,
    val detail: String = "",
    val packageName: String? = null,
    val processName: String? = null,
    val pid: Int? = null,
)

@Serializable
data class Issue(
    val id: String,
    val timestamp: Long,
    val severity: String,
    val category: String,
    val title: String,
    val detail: String,
    val relatedEventIds: List<String> = emptyList(),
)

data class InstalledApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val system: Boolean,
    val enabled: Boolean,
)
