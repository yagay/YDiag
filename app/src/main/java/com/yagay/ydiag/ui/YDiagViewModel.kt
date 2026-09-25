package com.yagay.ydiag.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yagay.ydiag.YDiagApp
import com.yagay.ydiag.data.AppRepository
import com.yagay.ydiag.data.Preferences
import com.yagay.ydiag.data.SessionStore
import com.yagay.ydiag.export.DiagnosticExporter
import com.yagay.ydiag.model.DiagnosticCatalog
import com.yagay.ydiag.model.InstalledApp
import com.yagay.ydiag.model.SessionMeta
import com.yagay.ydiag.service.MonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AppFilter { ALL, USER, SYSTEM, MONITORED }

data class HistoryItem(val directory: File, val meta: SessionMeta)

class YDiagViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val prefs = Preferences(context)
    private val appsRepo = AppRepository(context)
    private val sessions = SessionStore(context)

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    private val _selected = MutableStateFlow(prefs.selectedPackages)
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _enabledOptions = MutableStateFlow(prefs.enabledOptions)
    val enabledOptions: StateFlow<Set<String>> = _enabledOptions.asStateFlow()

    private val _presetId = MutableStateFlow(prefs.presetId)
    val presetId: StateFlow<String> = _presetId.asStateFlow()

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    private val _filter = MutableStateFlow(AppFilter.ALL)
    val filter: StateFlow<AppFilter> = _filter.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    val monitorState = MonitorService.state
    val moduleState = (application as YDiagApp).moduleState

    init {
        refreshApps()
        refreshHistory()
        (application as YDiagApp).refreshModuleState()
        if (_selected.value.isNotEmpty()) syncService()
    }

    fun refreshApps() {
        viewModelScope.launch {
            _apps.value = appsRepo.installedApps()
        }
    }

    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            _history.value = sessions.listSessions().map { HistoryItem(it.first, it.second) }
        }
    }

    fun setSearch(value: String) { _search.value = value }
    fun setFilter(value: AppFilter) { _filter.value = value }

    fun visibleApps(): List<InstalledApp> {
        val query = _search.value.trim()
        return _apps.value.filter { app ->
            val typeMatch = when (_filter.value) {
                AppFilter.ALL -> true
                AppFilter.USER -> !app.system
                AppFilter.SYSTEM -> app.system
                AppFilter.MONITORED -> app.packageName in _selected.value
            }
            typeMatch && (query.isBlank() ||
                app.label.contains(query, true) ||
                app.packageName.contains(query, true))
        }
    }

    fun togglePackage(packageName: String) {
        val next = _selected.value.toMutableSet().apply {
            if (!add(packageName)) remove(packageName)
        }.toSet()
        _selected.value = next
        prefs.selectedPackages = next
        syncService()
    }

    fun applyPreset(id: String) {
        val preset = DiagnosticCatalog.presets.firstOrNull { it.id == id } ?: return
        _presetId.value = id
        prefs.presetId = id
        _enabledOptions.value = preset.options
        prefs.enabledOptions = preset.options
        syncService()
    }

    fun toggleOption(id: String) {
        val next = _enabledOptions.value.toMutableSet().apply {
            if (!add(id)) remove(id)
        }.toSet()
        _presetId.value = "custom"
        prefs.presetId = "custom"
        _enabledOptions.value = next
        prefs.enabledOptions = next
        syncService()
    }

    fun markProblem() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_MARK),
        )
    }

    fun stopMonitoring() {
        _selected.value = emptySet()
        prefs.selectedPackages = emptySet()
        ContextCompat.startForegroundService(
            context,
            Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_STOP),
        )
        refreshHistory()
    }

    fun setExportMode(mode: String) {
        prefs.exportMode = mode
    }

    fun exportMode(): String = prefs.exportMode
    fun customTree(): Uri? = prefs.customExportTree?.let(Uri::parse)

    fun setCustomTree(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs.customExportTree = uri.toString()
        prefs.exportMode = "custom"
    }

    fun exportLatest() {
        viewModelScope.launch {
            _exportMessage.value = "正在生成完整诊断包…"
            val uri = withContext(Dispatchers.IO) {
                val tree = if (prefs.exportMode == "custom") customTree() else null
                DiagnosticExporter(context).exportLatest(tree)
            }
            _exportMessage.value = if (uri != null) {
                if (prefs.exportMode == "custom") "已导出到自定义目录" else "已导出到 Download/YDiag"
            } else "导出失败：没有可用会话或目录不可写"
            refreshHistory()
        }
    }

    fun export(item: HistoryItem) {
        viewModelScope.launch {
            _exportMessage.value = "正在导出 ${item.meta.id}…"
            val uri = withContext(Dispatchers.IO) {
                val tree = if (prefs.exportMode == "custom") customTree() else null
                DiagnosticExporter(context).export(item.directory, item.meta, tree)
            }
            _exportMessage.value = if (uri != null) "导出完成" else "导出失败"
        }
    }

    fun clearExportMessage() { _exportMessage.value = null }

    fun setMaxSessionMb(value: Int) { prefs.maxSessionMb = value }
    fun maxSessionMb(): Int = prefs.maxSessionMb

    private fun syncService() {
        val packages = ArrayList(_selected.value)
        if (packages.isEmpty()) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_STOP),
            )
            return
        }
        val intent = Intent(context, MonitorService::class.java)
            .setAction(MonitorService.ACTION_SYNC)
            .putStringArrayListExtra(MonitorService.EXTRA_PACKAGES, packages)
            .putStringArrayListExtra(MonitorService.EXTRA_OPTIONS, ArrayList(_enabledOptions.value))
        ContextCompat.startForegroundService(context, intent)
    }
}
