# YDiag · 应用故障诊断

YDiag 是一个面向 Root / LSPosed Android 设备的系统级应用故障诊断工具。

## 核心目标

- 不要求每个目标 App 自带日志功能。
- 选择目标 App 后立即开始 Root 日志采集，不需要重启手机。
- LSPosed 深度诊断使用动态 scope；已加载进程可实时切换配置，首次进入 scope 时只需要重启目标 App 进程。
- UI 只显示摘要、异常和关键时间线；导出保留完整原始证据。
- 导出格式同时面向开发者与 AI：summary.json、issues.json、timeline.jsonl、manifest.json、README-AI.txt 与原始日志同时保留。

## 第一版能力

- 安装应用列表、搜索、用户/系统应用过滤、多选监控
- Root / LSPosed 状态显示
- Root 前台采集服务与环形缓冲
- Logcat main/system/crash/events
- Crash / ANR / ApplicationExitInfo / Tombstone / SELinux / Kernel 等诊断项开关
- 推荐 / 按需 / 深度 三档标记
- 快速、崩溃、Hook、WebView、卡顿、Root、完整、自定义预设
- “问题发生了”时间标记
- 关键异常自动识别与时间线
- 每个 App 独立保存诊断配置
- 导出到 Download/YDiag 或系统目录选择器
- 完整 ZIP 诊断包 + AI 可读结构化索引
- LSPosed API 102 模块入口、动态 scope 状态、远程配置基础设施
- GitHub Actions 自动编译 Debug

## 包名

`com.yagay.ydiag`

## 构建环境

- JDK 17
- Gradle 9.4.1
- Android Gradle Plugin 9.2.0
- compileSdk / targetSdk 37
- minSdk 31
- libxposed API / service 102

## 设计原则

YDiag 默认只启用低负载诊断。方法 Trace、完整调用栈、Perfetto、全量文件访问等重型能力必须显式开启，避免诊断工具本身影响目标 App。

> Root 负责全局证据采集；LSPosed 负责可选的进程内深度追踪。即使未启用 LSPosed，Root 基础诊断仍可独立工作。
