package com.yagay.ydiag.data

import android.content.Context
import com.yagay.ydiag.model.DiagnosticCatalog

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("ydiag", Context.MODE_PRIVATE)

    var selectedPackages: Set<String>
        get() = prefs.getStringSet("selected_packages", emptySet())?.toSet().orEmpty()
        set(value) { prefs.edit().putStringSet("selected_packages", value).apply() }

    var enabledOptions: Set<String>
        get() = prefs.getStringSet("enabled_options", DiagnosticCatalog.defaultEnabled)?.toSet()
            ?: DiagnosticCatalog.defaultEnabled
        set(value) { prefs.edit().putStringSet("enabled_options", value).apply() }

    var presetId: String
        get() = prefs.getString("preset_id", "quick") ?: "quick"
        set(value) { prefs.edit().putString("preset_id", value).apply() }

    var customExportTree: String?
        get() = prefs.getString("custom_export_tree", null)
        set(value) { prefs.edit().putString("custom_export_tree", value).apply() }

    var exportMode: String
        get() = prefs.getString("export_mode", "download") ?: "download"
        set(value) { prefs.edit().putString("export_mode", value).apply() }

    var maxSessionMb: Int
        get() = prefs.getInt("max_session_mb", 128)
        set(value) { prefs.edit().putInt("max_session_mb", value.coerceIn(32, 1024)).apply() }

    fun optionsFor(packageName: String): Set<String> =
        prefs.getStringSet("options:$packageName", null)?.toSet() ?: enabledOptions

    fun setOptionsFor(packageName: String, options: Set<String>) {
        prefs.edit().putStringSet("options:$packageName", options).apply()
    }
}
