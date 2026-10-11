package com.lanvault

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.lanvault.net.EventLog
import com.lanvault.settings.SettingsStore
import com.lanvault.storage.SafePath
import com.lanvault.storage.StorageRepository
import com.lanvault.web.VaultServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Process wide state shared by the service, the status screen and the web API. */
object AppRuntime {

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var storageRepository: StorageRepository
        private set

    var server: VaultServer? = null
        private set

    private val runningState = MutableStateFlow(false)
    val running: StateFlow<Boolean> = runningState.asStateFlow()

    private val errorState = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = errorState.asStateFlow()

    private val launchedAt = System.currentTimeMillis()
    val startedAtMillis: Long get() = launchedAt

    fun initialize(context: android.content.Context) {
        if (::settingsStore.isInitialized) return
        val applicationContext = context.applicationContext
        settingsStore = SettingsStore(applicationContext)
        storageRepository = StorageRepository(applicationContext)
    }

    fun clearCache(context: android.content.Context) {
        val directories = listOfNotNull(context.cacheDir, context.codeCacheDir, context.externalCacheDir?.parentFile?.let { File(it, "cache") })
        directories.forEach { directory ->
            runCatching { directory.deleteRecursively() }
        }
        EventLog.shared.info("Cache cleared.")
    }

    fun startServer(context: android.content.Context) {
        val target = context.applicationContext
        if (server?.isRunning == true) {
            runningState.value = true
            return
        }
        try {
            val created = VaultServer(target, settingsStore, storageRepository)
            created.start()
            server = created
            runningState.value = created.isRunning
            errorState.value = null
        } catch (error: Throwable) {
            runningState.value = false
            errorState.value = error.message ?: error.javaClass.simpleName
        }
    }

    fun stopServer() {
        server?.stop()
        server = null
        runningState.value = false
    }

    fun restartServer(context: android.content.Context) {
        stopServer()
        startServer(context)
    }

    fun handlePickedTree(uri: Uri, displayName: String?) {
        storageRepository.storeSafTree(uri, displayName)
    }
}

class LanVaultApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppRuntime.initialize(this)
        // Requirement: the cache is wiped on every restart.
        AppRuntime.clearCache(this)

        val settings = AppRuntime.settingsStore.current()
        if (settings.autoStartOnBoot) {
            startServerService()
        }
    }

    private fun startServerService() {
        val intent = Intent(this, ServerService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
    }
}
