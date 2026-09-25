package com.yagay.ydiag

import android.app.Application
import android.content.Intent
import android.util.Log
import com.yagay.ydiag.data.Preferences
import com.yagay.ydiag.root.RootShell
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class ModuleState(
    val connected: Boolean = false,
    val apiVersion: Int? = null,
    val scope: Set<String> = emptySet(),
    val runningTargets: List<String> = emptyList(),
    val loadedPackages: Set<String> = emptySet(),
    val pendingScope: Set<String> = emptySet(),
    val activationPending: Set<String> = emptySet(),
    val restartRequired: Set<String> = emptySet(),
    val systemScoped: Boolean = false,
    val systemLoaded: Boolean = false,
    val message: String = "LSPosed 未连接",
)

class YDiagApp : Application(), XposedServiceHelper.OnServiceListener {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences by lazy { Preferences(this) }

    @Volatile private var xposedService: XposedService? = null
    @Volatile private var trackedTargets: Set<String> = emptySet()

    private val requestedScope = linkedSetOf<String>()
    private val activationInFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastActivationAttempt = ConcurrentHashMap<String, Long>()

    private val _moduleState = MutableStateFlow(ModuleState())
    val moduleState: StateFlow<ModuleState> = _moduleState

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        refreshModuleState()
        appScope.launch {
            delay(150)
            val granted = runCatching { service.scope.toSet() }.getOrDefault(emptySet())
            val missingDefault = DEFAULT_SCOPE - granted
            if (missingDefault.isNotEmpty()) {
                requestMissingScope(
                    service = service,
                    missing = missingDefault,
                    activationTargets = emptySet(),
                    activationMode = Preferences.ACTIVATION_MANUAL,
                )
            }
        }
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) xposedService = null
        activationInFlight.clear()
        _moduleState.value = ModuleState(message = "LSPosed 连接已断开")
    }

    /**
     * Root collection is independent from LSPosed. For deep diagnostics YDiag keeps only system
     * as a default scope and adds selected target apps dynamically.
     */
    fun syncDeepTracking(targets: Set<String>, options: Set<String>) {
        trackedTargets = targets
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

                val mode = preferences.deepActivationMode
                val granted = service.scope.toSet()
                val dynamicTargets = if (mode == Preferences.ACTIVATION_ROOT_ONLY) emptySet() else targets
                val desiredScope = DEFAULT_SCOPE + dynamicTargets
                val missing = desiredScope - granted

                _moduleState.value = moduleSnapshot(service, pending = missing)
                ensureTargetsLoaded(service, targets.intersect(granted), mode)

                if (missing.isNotEmpty()) {
                    requestMissingScope(
                        service = service,
                        missing = missing,
                        activationTargets = targets,
                        activationMode = mode,
                    )
                } else {
                    refreshModuleState()
                }
            }.onFailure {
                Log.e(TAG, "Deep tracking sync failed", it)
                _moduleState.value = _moduleState.value.copy(
                    message = "LSPosed 配置同步失败：${it.javaClass.simpleName}"
                )
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

    private fun requestMissingScope(
        service: XposedService,
        missing: Set<String>,
        activationTargets: Set<String>,
        activationMode: String,
    ) {
        val request = synchronized(requestedScope) {
            missing.filterNot { it in requestedScope }.also { requestedScope += it }.toSet()
        }
        if (request.isEmpty()) return

        _moduleState.value = moduleSnapshot(service, pending = request)

        runCatching {
            service.requestScope(request.toList(), object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    synchronized(requestedScope) { requestedScope.removeAll(request.toSet()) }
                    appScope.launch {
                        delay(250)
                        refreshModuleState()
                        ensureTargetsLoaded(service, activationTargets, activationMode)
                    }
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
            _moduleState.value = _moduleState.value.copy(
                pendingScope = request,
                message = "Root 日志已生效；Scope 请求失败：${it.javaClass.simpleName}",
            )
        }
    }

    /**
     * Adding a new LSPosed scope cannot inject into an already running process. In AUTO mode we
     * restart only ordinary launchable target apps. Critical system processes are never killed.
     */
    private suspend fun ensureTargetsLoaded(
        service: XposedService,
        targets: Set<String>,
        mode: String,
    ) {
        if (targets.isEmpty() || mode != Preferences.ACTIVATION_AUTO) return
        if (!RootShell.isAvailable()) {
            _moduleState.value = moduleSnapshot(service).copy(
                message = "深度 Scope 已授权；Root 不可用，请手动重新打开目标 App",
            )
            return
        }

        val granted = runCatching { service.scope.toSet() }.getOrDefault(emptySet())
        val loaded = loadedPackages(service)
        val now = System.currentTimeMillis()

        val candidates = targets.filter { packageName ->
            packageName in granted &&
                packageName !in loaded &&
                packageName !in NEVER_AUTO_RESTART &&
                PACKAGE_NAME.matches(packageName) &&
                now - (lastActivationAttempt[packageName] ?: 0L) >= ACTIVATION_COOLDOWN_MS
        }

        for (packageName in candidates) {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                Log.i(TAG, "No launch intent for $packageName; manual reopen required")
                continue
            }

            lastActivationAttempt[packageName] = System.currentTimeMillis()
            activationInFlight += packageName
            _moduleState.value = moduleSnapshot(service)

            val stopped = runCatching {
                RootShell.exec("am force-stop $packageName", 6).code == 0
            }.getOrDefault(false)

            if (!stopped) {
                activationInFlight -= packageName
                _moduleState.value = moduleSnapshot(service).copy(
                    message = "无法自动重启 $packageName，请手动重新打开",
                )
                continue
            }

            delay(350)
            runCatching {
                startActivity(
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                )
            }.onFailure {
                Log.w(TAG, "Unable to relaunch $packageName", it)
            }

            delay(1600)
            activationInFlight -= packageName
            _moduleState.value = moduleSnapshot(service)
        }
    }

    private fun moduleSnapshot(
        service: XposedService,
        pending: Set<String> = synchronized(requestedScope) { requestedScope.toSet() },
    ): ModuleState {
        val targets = if (service.apiVersion >= 102) {
            service.runningTargets.map { target: HookedTarget ->
                "${target.processName} · ${target.state.name} · v${target.loadedVersionCode}"
            }
        } else emptyList()

        val loaded = loadedPackages(service)
        val granted = service.scope.toSet()
        val pendingActivation = activationInFlight.toSet()
        val restartRequired = trackedTargets.filterTo(linkedSetOf()) {
            it in granted && it !in loaded && it !in pendingActivation
        }

        val message = when {
            pending.isNotEmpty() -> "Root 日志已生效；等待授权 ${pending.size} 个 Hook Scope"
            pendingActivation.isNotEmpty() ->
                "正在自动重新加载 ${pendingActivation.size} 个目标 App，使 Hook 立即生效"
            trackedTargets.isNotEmpty() && restartRequired.isEmpty() ->
                "深度 Hook 已加载 ${trackedTargets.size}/${trackedTargets.size}"
            restartRequired.isNotEmpty() && preferences.deepActivationMode == Preferences.ACTIVATION_AUTO ->
                "Scope 已授权；${restartRequired.size} 个目标等待自动/手动重新打开"
            restartRequired.isNotEmpty() ->
                "Scope 已授权；${restartRequired.size} 个目标需重新打开后启用深度 Hook"
            else -> "LSPosed API ${service.apiVersion} 已连接"
        }

        return ModuleState(
            connected = true,
            apiVersion = service.apiVersion,
            scope = granted,
            runningTargets = targets,
            loadedPackages = loaded,
            pendingScope = pending,
            activationPending = pendingActivation,
            restartRequired = restartRequired,
            systemScoped = "system" in granted,
            systemLoaded = "system" in loaded,
            message = message,
        )
    }

    private fun loadedPackages(service: XposedService): Set<String> {
        if (service.apiVersion < 102) return emptySet()
        return service.runningTargets.mapTo(linkedSetOf()) { target ->
            if (target.processName == "system") "system" else target.processName.substringBefore(':')
        }
    }

    companion object {
        const val PREFS = "ydiag_tracking"
        const val KEY_TARGETS = "targets"
        const val KEY_OPTIONS = "options"
        const val KEY_REVISION = "revision"

        val DEFAULT_SCOPE: Set<String> = setOf("system")

        private val NEVER_AUTO_RESTART = setOf(
            "android",
            "system",
            "com.android.systemui",
            "com.android.phone",
        )
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private const val ACTIVATION_COOLDOWN_MS = 15_000L
        private const val TAG = "YDiag.App"
    }
}
