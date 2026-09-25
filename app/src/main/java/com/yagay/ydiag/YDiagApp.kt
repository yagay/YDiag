package com.yagay.ydiag

import android.app.Application
import android.util.Log
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ModuleState(
    val connected: Boolean = false,
    val apiVersion: Int? = null,
    val scope: Set<String> = emptySet(),
    val runningTargets: List<String> = emptyList(),
    val pendingScope: Set<String> = emptySet(),
    val message: String = "LSPosed 未连接",
)

class YDiagApp : Application(), XposedServiceHelper.OnServiceListener {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var xposedService: XposedService? = null
    private val requestedScope = linkedSetOf<String>()

    private val _moduleState = MutableStateFlow(ModuleState())
    val moduleState: StateFlow<ModuleState> = _moduleState

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        refreshModuleState()
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) xposedService = null
        _moduleState.value = ModuleState(message = "LSPosed 连接已断开")
    }

    fun syncDeepTracking(targets: Set<String>, options: Set<String>) {
        appScope.launch {
            val service = xposedService ?: run {
                _moduleState.value = ModuleState(message = "Root 监控正常；LSPosed 深度追踪未连接")
                return@launch
            }
            runCatching {
                service.getRemotePreferences(PREFS)
                    .edit()
                    .putStringSet(KEY_TARGETS, targets)
                    .putStringSet(KEY_OPTIONS, options)
                    .putLong(KEY_REVISION, System.currentTimeMillis())
                    .commit()

                val granted = service.scope.toSet()
                val missing = targets - granted
                _moduleState.value = moduleSnapshot(service, pending = missing)
                if (missing.isNotEmpty()) requestMissingScope(service, missing)
            }.onFailure {
                Log.e(TAG, "Deep tracking sync failed", it)
                _moduleState.value = _moduleState.value.copy(message = "LSPosed 配置同步失败：${it.javaClass.simpleName}")
            }
        }
    }

    fun refreshModuleState() {
        appScope.launch {
            val service = xposedService
            _moduleState.value = if (service == null) {
                ModuleState(message = "LSPosed 未连接")
            } else runCatching { moduleSnapshot(service) }.getOrElse {
                ModuleState(connected = true, message = "读取 LSPosed 状态失败")
            }
        }
    }

    private fun requestMissingScope(service: XposedService, missing: Set<String>) {
        val request = synchronized(requestedScope) {
            missing.filterNot { it in requestedScope }.also { requestedScope += it }.toSet()
        }
        if (request.isEmpty()) return
        runCatching {
            service.requestScope(request.toList(), object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    synchronized(requestedScope) { requestedScope.removeAll(request.toSet()) }
                    refreshModuleState()
                }

                override fun onScopeRequestFailed(message: String) {
                    synchronized(requestedScope) { requestedScope.removeAll(request.toSet()) }
                    _moduleState.value = _moduleState.value.copy(
                        pendingScope = request,
                        message = "Root 日志已生效；深度 Scope 未授权：$message",
                    )
                }
            })
        }.onFailure {
            synchronized(requestedScope) { requestedScope.removeAll(request.toSet()) }
            Log.e(TAG, "Scope request failed", it)
        }
    }

    private fun moduleSnapshot(service: XposedService, pending: Set<String> = emptySet()): ModuleState {
        val targets = if (service.apiVersion >= 102) {
            service.runningTargets.map { target: HookedTarget ->
                "${target.processName} · ${target.state.name} · v${target.loadedVersionCode}"
            }
        } else emptyList()
        return ModuleState(
            connected = true,
            apiVersion = service.apiVersion,
            scope = service.scope.toSet(),
            runningTargets = targets,
            pendingScope = pending,
            message = when {
                pending.isNotEmpty() -> "Root 日志已生效；等待授权 ${pending.size} 个深度 Scope"
                else -> "LSPosed API ${service.apiVersion} 已连接"
            },
        )
    }

    companion object {
        const val PREFS = "ydiag_tracking"
        const val KEY_TARGETS = "targets"
        const val KEY_OPTIONS = "options"
        const val KEY_REVISION = "revision"
        private const val TAG = "YDiag.App"
    }
}
