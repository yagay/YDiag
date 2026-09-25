package com.yagay.ydiag.model

data class DiagnosticOption(
    val id: String,
    val title: String,
    val description: String,
    val category: DiagnosticCategory,
    val recommendation: Recommendation,
    val load: LoadLevel,
    val defaultEnabled: Boolean = false,
)

data class DiagnosticPreset(
    val id: String,
    val title: String,
    val description: String,
    val options: Set<String>,
)

object DiagnosticCatalog {
    val options = listOf(
        DiagnosticOption("logcat", "Logcat", "App 与系统最常用运行日志", DiagnosticCategory.BASIC, Recommendation.RECOMMENDED, LoadLevel.LOW, true),
        DiagnosticOption("crash", "Crash", "Java/Kotlin 崩溃与 crash buffer", DiagnosticCategory.BASIC, Recommendation.RECOMMENDED, LoadLevel.VERY_LOW, true),
        DiagnosticOption("exit_info", "ApplicationExitInfo", "判断崩溃、ANR、OOM、Signal 与系统杀进程原因", DiagnosticCategory.BASIC, Recommendation.RECOMMENDED, LoadLevel.VERY_LOW, true),
        DiagnosticOption("anr", "ANR", "分析卡死、主线程无响应与 ANR traces", DiagnosticCategory.BASIC, Recommendation.RECOMMENDED, LoadLevel.LOW, true),
        DiagnosticOption("events", "系统 Events", "ActivityManager 等系统事件缓冲", DiagnosticCategory.SYSTEM, Recommendation.ON_DEMAND, LoadLevel.LOW),
        DiagnosticOption("process", "进程关联", "关联目标 App 的主进程、子进程与 UID", DiagnosticCategory.SYSTEM, Recommendation.RECOMMENDED, LoadLevel.VERY_LOW, true),

        DiagnosticOption("lsposed_status", "LSPosed 模块状态", "模块、Scope 与运行目标状态", DiagnosticCategory.LSPOSED, Recommendation.RECOMMENDED, LoadLevel.VERY_LOW, true),
        DiagnosticOption("hook_health", "Hook 健康检查", "记录 Class/Method/Hook 安装和命中", DiagnosticCategory.LSPOSED, Recommendation.RECOMMENDED, LoadLevel.LOW, true),
        DiagnosticOption("lifecycle", "生命周期", "Activity 生命周期关键事件", DiagnosticCategory.LSPOSED, Recommendation.ON_DEMAND, LoadLevel.LOW),
        DiagnosticOption("intent", "Intent", "startActivity 等关键 Intent 调用", DiagnosticCategory.LSPOSED, Recommendation.ON_DEMAND, LoadLevel.LOW),
        DiagnosticOption("method_trace", "方法调用 Trace", "深度方法调用追踪入口", DiagnosticCategory.LSPOSED, Recommendation.DEEP, LoadLevel.HIGH),
        DiagnosticOption("stack_trace", "完整调用栈", "异常或深度事件记录 StackTrace", DiagnosticCategory.LSPOSED, Recommendation.DEEP, LoadLevel.VERY_HIGH),

        DiagnosticOption("webview", "WebView", "页面加载、Renderer 与文件选择相关事件", DiagnosticCategory.WEBVIEW, Recommendation.ON_DEMAND, LoadLevel.LOW),
        DiagnosticOption("network", "网络元数据", "DNS/Socket/TLS/URL 错误元数据，不记录消息正文", DiagnosticCategory.NETWORK, Recommendation.ON_DEMAND, LoadLevel.MEDIUM),
        DiagnosticOption("file_io", "文件访问", "关键文件打开/写入失败与路径", DiagnosticCategory.FILES, Recommendation.DEEP, LoadLevel.HIGH),
        DiagnosticOption("binder", "Binder", "Binder 调用异常与系统服务错误", DiagnosticCategory.SYSTEM, Recommendation.DEEP, LoadLevel.HIGH),

        DiagnosticOption("memory", "内存", "PSS/RSS、meminfo 与 OOM 证据", DiagnosticCategory.PERFORMANCE, Recommendation.ON_DEMAND, LoadLevel.LOW),
        DiagnosticOption("perfetto", "Perfetto", "系统调度与性能 Trace，适合卡顿复现", DiagnosticCategory.PERFORMANCE, Recommendation.DEEP, LoadLevel.HIGH),
        DiagnosticOption("kernel", "Kernel / dmesg", "内核与驱动日志", DiagnosticCategory.ROOT, Recommendation.ON_DEMAND, LoadLevel.MEDIUM),
        DiagnosticOption("selinux", "SELinux denial", "权限拒绝与 avc denied", DiagnosticCategory.ROOT, Recommendation.ON_DEMAND, LoadLevel.LOW),
        DiagnosticOption("root_module", "Root 模块日志", "Magisk/KernelSU 模块相关日志", DiagnosticCategory.ROOT, Recommendation.ON_DEMAND, LoadLevel.MEDIUM),
        DiagnosticOption("lsposed_log", "LSPosed 完整日志", "LSPosed 框架与模块日志文件", DiagnosticCategory.LSPOSED, Recommendation.ON_DEMAND, LoadLevel.MEDIUM),
        DiagnosticOption("tombstone", "Tombstone", "Native SIGSEGV/SIGABRT 崩溃回溯", DiagnosticCategory.NATIVE, Recommendation.RECOMMENDED, LoadLevel.LOW, true),
        DiagnosticOption("native", "Native Crash", "crash_dump/linker/libc 等 Native 线索", DiagnosticCategory.NATIVE, Recommendation.ON_DEMAND, LoadLevel.LOW),
    )

    private fun ids(vararg value: String) = value.toSet()

    val presets = listOf(
        DiagnosticPreset("quick", "快速诊断", "低负载，适合日常使用",
            ids("logcat","crash","exit_info","anr","process","lsposed_status","hook_health","tombstone")),
        DiagnosticPreset("crash", "崩溃诊断", "Java / Native / 系统退出原因",
            ids("logcat","crash","exit_info","anr","process","tombstone","native","lsposed_status")),
        DiagnosticPreset("hook", "Hook 诊断", "LSPosed 功能无效或 Hook 未命中",
            ids("logcat","process","lsposed_status","hook_health","lifecycle","intent","lsposed_log")),
        DiagnosticPreset("webview", "WebView 诊断", "页面、上传、Renderer 与回调问题",
            ids("logcat","crash","exit_info","process","lsposed_status","hook_health","lifecycle","intent","webview","network")),
        DiagnosticPreset("freeze", "卡顿诊断", "ANR、线程、内存与 Perfetto",
            ids("logcat","anr","exit_info","process","memory","perfetto","binder")),
        DiagnosticPreset("root", "Root 诊断", "Root、SELinux、内核与模块问题",
            ids("logcat","crash","exit_info","process","kernel","selinux","root_module","lsposed_log")),
        DiagnosticPreset("complete", "完整诊断", "启用全部证据源，负载较高", options.map { it.id }.toSet()),
    )

    val defaultEnabled: Set<String> = options.filter { it.defaultEnabled }.mapTo(linkedSetOf()) { it.id }
}
