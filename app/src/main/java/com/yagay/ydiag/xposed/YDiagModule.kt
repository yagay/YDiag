package com.yagay.ydiag.xposed

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import com.yagay.ydiag.YDiagApp
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class YDiagModule : XposedModule() {
    private var processName: String = ""
    private var packageName: String = ""
    private val installed = ConcurrentHashMap.newKeySet<String>()
    private val hits = ConcurrentHashMap<String, AtomicLong>()

    @Volatile private var tracked = false
    @Volatile private var optionSnapshot: Set<String> = emptySet()
    @Volatile private var listenerRegistered = false

    private val preferences by lazy(LazyThreadSafetyMode.PUBLICATION) {
        getRemotePreferences(YDiagApp.PREFS)
    }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == YDiagApp.KEY_TARGETS || key == YDiagApp.KEY_OPTIONS) {
            refreshConfiguration("preference_changed")
        }
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        Log.i(TAG, "MODULE_LOADED process=$processName")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        packageName = param.packageName
        if (packageName == "com.yagay.ydiag") return

        registerPreferenceListener()
        refreshConfiguration("package_ready")
        if (!tracked) {
            Log.i(TAG, "PACKAGE_READY package=$packageName process=$processName tracked=false")
        }
    }

    @Synchronized
    private fun registerPreferenceListener() {
        if (listenerRegistered) return
        runCatching {
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
            listenerRegistered = true
        }.onFailure {
            Log.e(TAG, "PREF_LISTENER_FAILED package=$packageName error=${it.javaClass.name}")
        }
    }

    @Synchronized
    private fun refreshConfiguration(reason: String) {
        val targets = runCatching {
            preferences.getStringSet(YDiagApp.KEY_TARGETS, emptySet()).orEmpty().toSet()
        }.getOrDefault(emptySet())
        val options = runCatching {
            preferences.getStringSet(YDiagApp.KEY_OPTIONS, emptySet()).orEmpty().toSet()
        }.getOrDefault(emptySet())

        val wasTracked = tracked
        tracked = packageName in targets
        optionSnapshot = options

        Log.i(
            TAG,
            "CONFIG package=$packageName process=$processName tracked=$tracked options=${options.sorted()} reason=$reason"
        )
        if (tracked) {
            installForCurrentOptions()
            if (!wasTracked) Log.i(TAG, "TRACKING_ENABLED package=$packageName process=$processName")
        } else if (wasTracked) {
            Log.i(TAG, "TRACKING_DISABLED package=$packageName process=$processName hooks_remain_passthrough=true")
        }
    }

    private fun enabled(id: String): Boolean = id in optionSnapshot

    private fun installForCurrentOptions() {
        if (enabled("lifecycle") || enabled("method_trace")) installActivityHooks()
        if (enabled("intent") || enabled("method_trace")) installIntentHooks()
        if (enabled("webview") || enabled("method_trace")) installWebViewHooks()
        if (enabled("network") || enabled("method_trace")) installNetworkHooks()
        if (enabled("file_io") || enabled("method_trace")) installFileHooks()

        if (enabled("hook_health")) {
            Log.i(
                TAG,
                "HOOK_HEALTH package=$packageName process=$processName installed=${installed.size} " +
                    "lifecycle=${enabled("lifecycle")} intent=${enabled("intent")} " +
                    "webview=${enabled("webview")} network=${enabled("network")} file=${enabled("file_io")}"
            )
        }
    }

    private fun installActivityHooks() {
        hookMethod(
            id = "activity_onCreate",
            executable = runCatching {
                Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
            }.getOrNull(),
            option = "lifecycle",
        ) { chain ->
            trace("ACTIVITY_CREATE", chain.thisObject?.javaClass?.name.orEmpty())
        }
        hookMethod(
            id = "activity_onResume",
            executable = runCatching { Activity::class.java.getDeclaredMethod("onResume") }.getOrNull(),
            option = "lifecycle",
        ) { chain ->
            trace("ACTIVITY_RESUME", chain.thisObject?.javaClass?.name.orEmpty())
        }
        hookMethod(
            id = "activity_onPause",
            executable = runCatching { Activity::class.java.getDeclaredMethod("onPause") }.getOrNull(),
            option = "lifecycle",
        ) { chain ->
            trace("ACTIVITY_PAUSE", chain.thisObject?.javaClass?.name.orEmpty())
        }
    }

    private fun installIntentHooks() {
        hookMethod(
            id = "context_startActivity",
            executable = runCatching {
                ContextWrapper::class.java.getDeclaredMethod("startActivity", Intent::class.java)
            }.getOrNull(),
            option = "intent",
        ) { chain ->
            val intent = chain.args.getOrNull(0) as? Intent
            trace(
                "START_ACTIVITY",
                "action=${intent?.action} data=${intent?.data?.scheme ?: "-"} component=${intent?.component}",
            )
        }
    }

    private fun installWebViewHooks() {
        hookMethod(
            id = "webview_loadUrl",
            executable = runCatching {
                WebView::class.java.getDeclaredMethod("loadUrl", String::class.java)
            }.getOrNull(),
            option = "webview",
        ) { chain ->
            val url = chain.args.getOrNull(0)?.toString().orEmpty()
            val safe = runCatching {
                val uri = android.net.Uri.parse(url)
                "${uri.scheme}://${uri.host ?: ""}${uri.path ?: ""}"
            }.getOrDefault("<unparseable>")
            trace("WEBVIEW_LOAD_URL", safe.take(500))
        }
    }

    private fun installNetworkHooks() {
        hookMethod(
            id = "url_openConnection",
            executable = runCatching { URL::class.java.getDeclaredMethod("openConnection") }.getOrNull(),
            option = "network",
        ) { chain ->
            val url = chain.thisObject as? URL
            trace(
                "URL_OPEN_CONNECTION",
                "${url?.protocol}://${url?.host ?: ""}${url?.path ?: ""}".take(500)
            )
        }
    }

    private fun installFileHooks() {
        hookMethod(
            id = "file_input_stream_file",
            executable = runCatching {
                FileInputStream::class.java.getDeclaredConstructor(File::class.java)
            }.getOrNull(),
            option = "file_io",
        ) { chain ->
            val file = chain.args.getOrNull(0) as? File
            trace("FILE_READ", file?.absolutePath.orEmpty().take(800))
        }
        hookMethod(
            id = "file_output_stream_file",
            executable = runCatching {
                FileOutputStream::class.java.getDeclaredConstructor(File::class.java)
            }.getOrNull(),
            option = "file_io",
        ) { chain ->
            val file = chain.args.getOrNull(0) as? File
            trace("FILE_WRITE", file?.absolutePath.orEmpty().take(800))
        }
        hookMethod(
            id = "file_output_stream_append",
            executable = runCatching {
                FileOutputStream::class.java.getDeclaredConstructor(
                    File::class.java,
                    Boolean::class.javaPrimitiveType,
                )
            }.getOrNull(),
            option = "file_io",
        ) { chain ->
            val file = chain.args.getOrNull(0) as? File
            val append = chain.args.getOrNull(1)
            trace("FILE_WRITE", "${file?.absolutePath.orEmpty().take(760)} append=$append")
        }
    }

    private fun hookMethod(
        id: String,
        executable: java.lang.reflect.Executable?,
        option: String,
        before: (XposedInterface.Chain) -> Unit,
    ) {
        if (executable == null) {
            if (enabled("hook_health")) {
                Log.w(TAG, "HOOK_FAILED package=$packageName id=$id reason=method_not_found")
            }
            return
        }
        if (!installed.add(id)) return
        runCatching {
            executable.isAccessible = true
            hook(executable).setId("ydiag-$id").intercept(XposedInterface.Hooker { chain ->
                if (!tracked) return@Hooker chain.proceed()

                val traceEnabled = enabled(option) || enabled("method_trace")
                val healthEnabled = enabled("hook_health")
                if (traceEnabled || healthEnabled) {
                    val count = hits.getOrPut(id) { AtomicLong() }.incrementAndGet()
                    if (traceEnabled) {
                        runCatching { before(chain) }.onFailure {
                            Log.e(
                                TAG,
                                "HOOK_CALLBACK_ERROR package=$packageName id=$id error=${it.javaClass.name}"
                            )
                        }
                    }
                    if (healthEnabled && (count == 1L || count % 100L == 0L)) {
                        Log.i(TAG, "HOOK_HIT package=$packageName process=$processName id=$id count=$count")
                    }
                    if (traceEnabled && enabled("stack_trace")) {
                        val stack = Throwable().stackTrace.take(24).joinToString(" <- ") {
                            "${it.className}#${it.methodName}:${it.lineNumber}"
                        }
                        Log.i(TAG, "STACK package=$packageName id=$id $stack")
                    }
                }
                chain.proceed()
            })
            if (enabled("hook_health")) {
                Log.i(TAG, "HOOK_INSTALLED package=$packageName process=$processName id=$id option=$option")
            }
        }.onFailure {
            installed.remove(id)
            Log.e(TAG, "HOOK_FAILED package=$packageName id=$id error=${it.javaClass.name}")
        }
    }

    private fun trace(event: String, detail: String) {
        Log.i(TAG, "$event package=$packageName process=$processName $detail")
    }

    companion object {
        private const val TAG = "YDiag.Hook"
    }
}
