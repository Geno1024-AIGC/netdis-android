package com.geno1024.ai.netdis.storage

import android.content.Context
import com.geno1024.ai.netdis.model.AppSettings
import com.geno1024.ai.netdis.model.ThemeMode

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            theme = prefs.getString(KEY_THEME, null)
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: defaults.theme,
            subnetOverride = prefs.getString(KEY_SUBNET, null).orEmpty(),
            deepScan = prefs.getBoolean(KEY_DEEP, defaults.deepScan),
            probeTimeoutMs = prefs.getInt(KEY_TIMEOUT, defaults.probeTimeoutMs)
                .coerceIn(120, 1500),
            showOffline = prefs.getBoolean(KEY_OFFLINE, defaults.showOffline),
            updateMirrorPrefix = prefs.getString(KEY_MIRROR, null).orEmpty(),
            readArpWithRoot = prefs.getBoolean(KEY_ROOT_ARP, defaults.readArpWithRoot),
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_THEME, settings.theme.name)
            .putString(KEY_SUBNET, settings.subnetOverride.trim())
            .putBoolean(KEY_DEEP, settings.deepScan)
            .putInt(KEY_TIMEOUT, settings.probeTimeoutMs.coerceIn(120, 1500))
            .putBoolean(KEY_OFFLINE, settings.showOffline)
            .putString(KEY_MIRROR, settings.updateMirrorPrefix.trim())
            .putBoolean(KEY_ROOT_ARP, settings.readArpWithRoot)
            .apply()
    }

    private companion object {
        const val PREFS = "netdis.settings"
        const val KEY_THEME = "theme"
        const val KEY_SUBNET = "subnetOverride"
        const val KEY_DEEP = "deepScan"
        const val KEY_TIMEOUT = "probeTimeoutMs"
        const val KEY_OFFLINE = "showOffline"
        const val KEY_MIRROR = "updateMirrorPrefix"
        const val KEY_ROOT_ARP = "readArpWithRoot"
    }
}
