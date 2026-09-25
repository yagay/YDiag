package com.yagay.ydiag.diagnostics

import com.yagay.ydiag.model.Issue
import com.yagay.ydiag.model.Severity
import java.util.concurrent.atomic.AtomicLong

data class Detection(val issue: Issue, val title: String)

object IssueDetector {
    private val counter = AtomicLong()

    fun detect(line: String, timestamp: Long, relatedEventId: String?): Detection? {
        val lower = line.lowercase()
        val spec = when {
            "fatal exception" in lower -> Triple(Severity.FATAL, "crash", "Java/Kotlin 崩溃")
            "fatal signal 11" in lower || "sigsegv" in lower -> Triple(Severity.FATAL, "native", "Native SIGSEGV")
            "fatal signal 6" in lower || "sigabrt" in lower -> Triple(Severity.FATAL, "native", "Native SIGABRT")
            "anr in " in lower -> Triple(Severity.ERROR, "anr", "应用无响应 ANR")
            "outofmemoryerror" in lower -> Triple(Severity.FATAL, "memory", "内存不足 OOM")
            "transactiontoolargeexception" in lower -> Triple(Severity.ERROR, "binder", "Binder 数据过大")
            "deadobjectexception" in lower -> Triple(Severity.ERROR, "binder", "Binder 目标进程死亡")
            "securityexception" in lower -> Triple(Severity.ERROR, "permission", "权限或系统限制")
            "avc: denied" in lower -> Triple(Severity.WARNING, "selinux", "SELinux 拒绝")
            "renderer process" in lower && ("gone" in lower || "crash" in lower) ->
                Triple(Severity.ERROR, "webview", "WebView Renderer 异常")
            "nosuchmethod" in lower || "no such method" in lower ->
                Triple(Severity.ERROR, "hook", "方法签名不存在")
            "classnotfoundexception" in lower ->
                Triple(Severity.ERROR, "hook", "目标 Class 不存在")
            "ydiag.hook" in lower && ("hook_failed" in lower || "error=" in lower) ->
                Triple(Severity.ERROR, "hook", "LSPosed Hook 异常")
            else -> null
        } ?: return null
        val issueId = "I${counter.incrementAndGet().toString().padStart(6, '0')}"
        val issue = Issue(
            id = issueId,
            timestamp = timestamp,
            severity = spec.first.name,
            category = spec.second,
            title = spec.third,
            detail = line.take(4000),
            relatedEventIds = listOfNotNull(relatedEventId),
        )
        return Detection(issue, spec.third)
    }
}
