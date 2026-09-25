package com.yagay.ydiag.xposed

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import com.yagay.ydiag.YDiagApp
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class YDiagModule : XposedModule() {
    private var processName: String = ""
    private var packageName: String = ""
    private val installed = ConcurrentHashMap.newKeySet<String>()
    private val hits = ConcurrentHashMap<String, AtomicLong>()
    private val preferences by lazy(LazyThreadSafetyMode.PUBLICATION) {
        getRemotePreferences(YDiagApp.PREFS)
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        Log.i(TAG, "MODULE_LOADED process=$processName")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        packageName = param.packageName
        if (packageName == "com.yagay.ydiag") return
        val targets = preferences.getStringSet(YDiagApp.KEY_TARGETS, emptySet()).orEmpty()
        if (packageName !in targets) {
            Log.i(TAG, "PACKAGE_READY package=$packageName tracked=false")
            return
        }
        Log.i(TAG, "PACKAGE_READY package=$packageName process=$processName tracked=true")
        installActivityHooks()
        installIntentHooks()
        installWebViewHooks()
        installNetworkHooks()
    }

    private fun enabled(id: String): Boolean =
        id in preferences.getStringSet(YDiagApp.KEY_OPTIONS, emptySet()).orEmpty()

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
            trace("URL_OPEN_CONNECTION", "${url?.protocol}://${url?.host}${url?.path}".take(500))
        }
    }

    private fun hookMethod(
        id: String,
        executable: java.lang.reflect.Executable?,
        option: String,
        before: (XposedInterface.Chain) -> Unit,
    ) {
        if (executable == null) {
            Log.w(TAG, "HOOK_FAILED package=$packageName id=$id reason=method_not_found")
            return
        }
        if (!installed.add(id)) return
        runCatching {
            executable.isAccessible = true
            hook(executable).setId("ydiag-$id").intercept(XposedInterface.Hooker { chain ->
                if (enabled(option) || enabled("method_trace")) {
                    val count = hits.getOrPut(id) { AtomicLong() }.incrementAndGet()
                    runCatching { before(chain) }.onFailure {
                        Log.e(TAG, "HOOK_CALLBACK_ERROR package=$packageName id=$id error=${it.javaClass.name}")
                    }
                    if (enabled("hook_health") && (count == 1L || count % 100L == 0L)) {
                        Log.i(TAG, "HOOK_HIT package=$packageName process=$processName id=$id count=$count")
                    }
                    if (enabled("stack_trace")) {
                        val stack = Throwable().stackTrace.take(24).joinToString(" <- ") {
                            "${it.className}#${it.methodName}:${it.lineNumber}"
                        }
                        Log.i(TAG, "STACK package=$packageName id=$id $stack")
                    }
                }
                chain.proceed()
            })
            Log.i(TAG, "HOOK_INSTALLED package=$packageName process=$processName id=$id option=$option")
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
