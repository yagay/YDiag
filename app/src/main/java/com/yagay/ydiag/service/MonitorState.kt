package com.yagay.ydiag.service

import com.yagay.ydiag.model.Issue
import com.yagay.ydiag.model.TimelineEvent

data class MonitorState(
    val running: Boolean = false,
    val rootAvailable: Boolean = false,
    val sessionId: String? = null,
    val targetPackages: Set<String> = emptySet(),
    val processCount: Int = 0,
    val eventCount: Long = 0,
    val errorCount: Long = 0,
    val warningCount: Long = 0,
    val lastMessage: String = "",
    val recentEvents: List<TimelineEvent> = emptyList(),
    val recentIssues: List<Issue> = emptyList(),
)
