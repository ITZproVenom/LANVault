package com.lanvault.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every value here is editable from the LAN web interface; the TV screen only reports state.
 */
data class Settings(
    val serverPort: Int = DEFAULT_PORT,
    val deviceName: String = "Android TV",
    val requireAuth: Boolean = false,
    val username: String = "lanvault",
    val password: String = "lanvault",
    val allowUploads: Boolean = true,
    val allowDeletes: Boolean = true,
    val allowCreateFolders: Boolean = true,
    val showHiddenFiles: Boolean = false,
    val autoStartOnBoot: Boolean = true,
    val keepCpuAwake: Boolean = true,
    val shareWebdav: Boolean = true,
) {
    companion object {
        const val DEFAULT_PORT = 8080
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }
}

class SettingsStore(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences("lanvault_settings", Context.MODE_PRIVATE)

    private val state = MutableStateFlow(read())

    val settings: StateFlow<Settings> = state.asStateFlow()

    fun current(): Settings = state.value

    fun update(transform: (Settings) -> Settings) {
        val updated = transform(state.value)
        write(updated)
        state.value = updated
    }

    fun resetToDefaults() {
        val defaults = Settings()
        write(defaults)
        state.value = defaults
    }

    private fun read(): Settings {
        val defaults = Settings()
        return Settings(
            serverPort = preferences.getInt("server_port", defaults.serverPort)
                .coerceIn(Settings.MIN_PORT, Settings.MAX_PORT),
            deviceName = preferences.getString("device_name", defaults.deviceName) ?: defaults.deviceName,
            requireAuth = preferences.getBoolean("require_auth", defaults.requireAuth),
            username = preferences.getString("username", defaults.username) ?: defaults.username,
            password = preferences.getString("password", defaults.password) ?: defaults.password,
            allowUploads = preferences.getBoolean("allow_uploads", defaults.allowUploads),
            allowDeletes = preferences.getBoolean("allow_deletes", defaults.allowDeletes),
            allowCreateFolders = preferences.getBoolean("allow_create_folders", defaults.allowCreateFolders),
            showHiddenFiles = preferences.getBoolean("show_hidden_files", defaults.showHiddenFiles),
            autoStartOnBoot = preferences.getBoolean("auto_start_on_boot", defaults.autoStartOnBoot),
            keepCpuAwake = preferences.getBoolean("keep_cpu_awake", defaults.keepCpuAwake),
            shareWebdav = preferences.getBoolean("share_webdav", defaults.shareWebdav),
        )
    }

    private fun write(value: Settings) {
        preferences.edit()
            .putInt("server_port", value.serverPort)
            .putString("device_name", value.deviceName)
            .putBoolean("require_auth", value.requireAuth)
            .putString("username", value.username)
            .putString("password", value.password)
            .putBoolean("allow_uploads", value.allowUploads)
            .putBoolean("allow_deletes", value.allowDeletes)
            .putBoolean("allow_create_folders", value.allowCreateFolders)
            .putBoolean("show_hidden_files", value.showHiddenFiles)
            .putBoolean("auto_start_on_boot", value.autoStartOnBoot)
            .putBoolean("keep_cpu_awake", value.keepCpuAwake)
            .putBoolean("share_webdav", value.shareWebdav)
            .apply()
    }
}
