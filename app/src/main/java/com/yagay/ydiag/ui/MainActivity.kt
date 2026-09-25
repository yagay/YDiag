package com.yagay.ydiag.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yagay.ydiag.model.DiagnosticCatalog
import com.yagay.ydiag.model.DiagnosticCategory
import com.yagay.ydiag.model.LoadLevel
import com.yagay.ydiag.model.Recommendation
import com.yagay.ydiag.model.Severity
import com.yagay.ydiag.service.MonitorState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                YDiagRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YDiagRoot(vm: YDiagViewModel = viewModel()) {
    val monitor by vm.monitorState.collectAsStateWithLifecycle()
    val module by vm.moduleState.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val enabled by vm.enabledOptions.collectAsStateWithLifecycle()
    val preset by vm.presetId.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val exportMessage by vm.exportMessage.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }
    var showPicker by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setCustomTree(uri)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                vm.getApplication<Application>(),
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(exportMessage) {
        val message = exportMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.clearExportMessage()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("YDiag", fontWeight = FontWeight.Bold)
                        Text("应用故障诊断", style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    StatusPill("Root", monitor.rootAvailable)
                    Spacer(Modifier.width(6.dp))
                    StatusPill("LSPosed", module.connected)
                    Spacer(Modifier.width(12.dp))
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(Modifier.navigationBarsPadding()) {
                val labels = listOf("监控", "历史", "诊断", "设置")
                val glyphs = listOf("●", "◷", "◆", "⚙")
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = {
                            tab = index
                            if (index == 1) vm.refreshHistory()
                        },
                        icon = { Text(glyphs[index]) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> MonitorScreen(
                    monitor = monitor,
                    selected = selected,
                    appLabels = apps.associate { it.packageName to it.label },
                    onSelectApps = { showPicker = true },
                    onMark = vm::markProblem,
                    onStop = vm::stopMonitoring,
                    onExport = vm::exportLatest,
                )
                1 -> HistoryScreen(history = history, onExport = vm::export)
                2 -> DiagnosticConfigScreen(
                    enabled = enabled,
                    presetId = preset,
                    onPreset = vm::applyPreset,
                    onToggle = vm::toggleOption,
                )
                3 -> SettingsScreen(
                    exportMode = vm.exportMode(),
                    customTree = vm.customTree()?.toString(),
                    maxSessionMb = vm.maxSessionMb(),
                    onExportMode = vm::setExportMode,
                    onChooseTree = { treeLauncher.launch(vm.customTree()) },
                    onMaxSessionMb = vm::setMaxSessionMb,
                )
            }
        }
    }

    if (showPicker) {
        AppPickerDialog(
            apps = apps,
            selected = selected,
            search = search,
            filter = filter,
            onSearch = vm::setSearch,
            onFilter = vm::setFilter,
            onToggle = vm::togglePackage,
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun StatusPill(label: String, ok: Boolean) {
    AssistChip(
        onClick = { },
        label = { Text("$label ${if (ok) "●" else "○"}") },
    )
}

@Composable
private fun MonitorScreen(
    monitor: MonitorState,
    selected: Set<String>,
    appLabels: Map<String, String>,
    onSelectApps: () -> Unit,
    onMark: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (monitor.running) "● 正在诊断" else "○ 未开始",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (selected.isEmpty()) "选择应用后立即开始采集" else
                                    "${selected.size} 个应用 · ${monitor.processCount} 个关联进程",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Button(onClick = onSelectApps) { Text("选择应用") }
                    }

                    if (selected.isNotEmpty()) {
                        selected.take(5).forEach { pkg ->
                            Text(
                                "• ${appLabels[pkg] ?: pkg}  ·  $pkg",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (selected.size > 5) Text("还有 ${selected.size - 5} 个…")
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("异常", monitor.errorCount.toString(), Modifier.weight(1f))
                StatCard("警告", monitor.warningCount.toString(), Modifier.weight(1f))
                StatCard("事件", monitor.eventCount.toString(), Modifier.weight(1f))
            }
        }

        if (monitor.running) {
            item {
                Button(
                    onClick = onMark,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("问题发生了", fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Text("发现的问题", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (monitor.recentIssues.isEmpty()) {
            item {
                Text(
                    if (monitor.running) "暂未发现明确异常。YDiag 会继续保留完整证据。" else "开始监控后，这里只显示高价值异常。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(monitor.recentIssues.take(12), key = { it.id }) { issue ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "${severityGlyph(issue.severity)} ${issue.title}",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${issue.category} · ${formatTime(issue.timestamp)}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (issue.detail.isNotBlank()) {
                            Text(
                                issue.detail,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        item {
            Text("关键时间线", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(monitor.recentEvents.take(18), key = { it.id }) { event ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(formatTime(event.timestamp), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(event.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${event.source} · ${event.category}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("导出完整日志") }
                if (monitor.running) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("停止") }
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun HistoryScreen(history: List<HistoryItem>, onExport: (HistoryItem) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("历史会话", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("界面保持简洁；导出时生成完整诊断包。", style = MaterialTheme.typography.bodyMedium)
        }
        if (history.isEmpty()) {
            item { Text("暂无诊断记录") }
        }
        items(history, key = { it.meta.id }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.meta.targetPackages.joinToString().ifBlank { "未知目标" },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(formatDate(item.meta.startedAt), style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${item.meta.enabledOptions.size} 项诊断 · ${item.meta.problemMarks.size} 个问题标记",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = { onExport(item) }) { Text("导出") }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticConfigScreen(
    enabled: Set<String>,
    presetId: String,
    onPreset: (String) -> Unit,
    onToggle: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("诊断配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("推荐项默认低负载；深度项目只建议复现问题时开启。")
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DiagnosticCatalog.presets.forEach { preset ->
                    FilterChip(
                        selected = presetId == preset.id,
                        onClick = { onPreset(preset.id) },
                        label = { Text(preset.title) },
                    )
                }
                FilterChip(
                    selected = presetId == "custom",
                    onClick = { },
                    label = { Text("自定义") },
                )
            }
        }

        DiagnosticCategory.entries.forEach { category ->
            val options = DiagnosticCatalog.options.filter { it.category == category }
            if (options.isNotEmpty()) {
                item {
                    Text(
                        categoryTitle(category),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(options, key = { it.id }) { option ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(option.title, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        recommendationText(option.recommendation),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Text(option.description, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "负载：${loadText(option.load)}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Switch(
                                checked = option.id in enabled,
                                onCheckedChange = { onToggle(option.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    exportMode: String,
    customTree: String?,
    maxSessionMb: Int,
    onExportMode: (String) -> Unit,
    onChooseTree: () -> Unit,
    onMaxSessionMb: (Int) -> Unit,
) {
    var localLimit by remember(maxSessionMb) { mutableIntStateOf(maxSessionMb) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("导出位置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = exportMode == "download",
                            onClick = { onExportMode("download") },
                        )
                        Column {
                            Text("Download/YDiag  ★ 推荐")
                            Text("一键导出，不需要每次选择目录", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = exportMode == "custom",
                            onClick = { onExportMode("custom") },
                        )
                        Column(Modifier.weight(1f)) {
                            Text("自定义目录")
                            Text(
                                customTree ?: "尚未选择目录",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = onChooseTree) { Text("选择") }
                    }
                }
            }
        }
        item {
            Text("单个日志分片上限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        localLimit = (localLimit - 32).coerceAtLeast(32)
                        onMaxSessionMb(localLimit)
                    }
                ) { Text("−") }
                Spacer(Modifier.width(12.dp))
                Text("${localLimit} MB", modifier = Modifier.width(90.dp))
                OutlinedButton(
                    onClick = {
                        localLimit = (localLimit + 32).coerceAtMost(1024)
                        onMaxSessionMb(localLimit)
                    }
                ) { Text("+") }
            }
            Text(
                "超过上限会自动创建新的 logcat 分片，导出 ZIP 时全部保留。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            HorizontalDivider()
            Text("隐私说明", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "YDiag 只在本机采集与导出。完整日志可能包含应用路径、URL、系统状态等敏感信息，分享诊断包前请确认接收方。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<com.yagay.ydiag.model.InstalledApp>,
    selected: Set<String>,
    search: String,
    filter: AppFilter,
    onSearch: (String) -> Unit,
    onFilter: (AppFilter) -> Unit,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val visible = remember(apps, selected, search, filter) {
        apps.filter { app ->
            val typeMatch = when (filter) {
                AppFilter.ALL -> true
                AppFilter.USER -> !app.system
                AppFilter.SYSTEM -> app.system
                AppFilter.MONITORED -> app.packageName in selected
            }
            typeMatch && (
                search.isBlank() ||
                    app.label.contains(search, true) ||
                    app.packageName.contains(search, true)
                )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = {
            Column {
                Text("选择监控应用")
                Text("勾选后立即开始 Root 日志采集", style = MaterialTheme.typography.bodySmall)
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = search,
                    onValueChange = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索应用或包名") },
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppFilter.entries.forEach { value ->
                        FilterChip(
                            selected = filter == value,
                            onClick = { onFilter(value) },
                            label = {
                                Text(
                                    when (value) {
                                        AppFilter.ALL -> "全部"
                                        AppFilter.USER -> "用户"
                                        AppFilter.SYSTEM -> "系统"
                                        AppFilter.MONITORED -> "已监控"
                                    }
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(480.dp)) {
                    items(visible, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = app.packageName in selected,
                                onCheckedChange = { onToggle(app.packageName) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (app.system) Text("系统", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
    )
}

private fun recommendationText(value: Recommendation): String = when (value) {
    Recommendation.RECOMMENDED -> "★ 推荐"
    Recommendation.ON_DEMAND -> "◆ 按需"
    Recommendation.DEEP -> "⚠ 深度"
}

private fun loadText(value: LoadLevel): String = when (value) {
    LoadLevel.VERY_LOW -> "极低"
    LoadLevel.LOW -> "低"
    LoadLevel.MEDIUM -> "中"
    LoadLevel.HIGH -> "高"
    LoadLevel.VERY_HIGH -> "很高"
}

private fun categoryTitle(value: DiagnosticCategory): String = when (value) {
    DiagnosticCategory.BASIC -> "基础日志"
    DiagnosticCategory.ROOT -> "Root"
    DiagnosticCategory.LSPOSED -> "LSPosed"
    DiagnosticCategory.SYSTEM -> "系统服务"
    DiagnosticCategory.WEBVIEW -> "WebView"
    DiagnosticCategory.NETWORK -> "网络"
    DiagnosticCategory.PERFORMANCE -> "性能"
    DiagnosticCategory.FILES -> "文件"
    DiagnosticCategory.NATIVE -> "Native"
}

private fun severityGlyph(value: String): String = when (value) {
    Severity.FATAL.name, Severity.ERROR.name -> "🔴"
    Severity.WARNING.name -> "🟡"
    else -> "●"
}

private fun formatTime(value: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(value))

private fun formatDate(value: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
