package com.lanvault.monitor

import android.content.Context
import android.os.Build
import com.lanvault.net.EventLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Owns the AdGuard Home executable bundled into the APK. The executable is built from upstream
 * source for GOOS=android, not a Linux/x86 binary.
 */
class AdGuardHomeRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile private var process: Process? = null
    @Volatile private var lastStartError: String = ""

    private val rootDir: File get() = File(appContext.filesDir, "adguardhome")
    private val dataDir: File get() = File(rootDir, "data")
    private val logFile: File get() = File(rootDir, "adguardhome.log")

    fun status(): JSONObject {
        val assetPath = supportedAssetPath()
        val bundled = assetPath != null && hasAsset(assetPath)
        val child = process
        val running = child?.isAlive == true
        val adminPort = ADMIN_PORT
        val dnsPort = DNS_PORT
        return JSONObject()
            .put("embeddedHome", true)
            .put("version", AGH_VERSION)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .put("supportedAbi", assetPath != null)
            .put("binaryBundled", bundled)
            .put("running", running)
            .put("adminPort", adminPort)
            .put("adminReachable", probeTcp(adminPort))
            .put("dnsPort", dnsPort)
            .put("dnsTcpReachable", probeTcp(dnsPort))
            .put("autoStart", preferences.getBoolean(KEY_AUTOSTART, false))
            .put("adminUrls", adminUrls(adminPort))
            .put("workDirectory", dataDir.absolutePath)
            .put("lastStartError", lastStartError)
            .put("logTail", readLogTail())
            .put("message", when {
                assetPath == null -> "This TV ABI has no bundled AdGuard Home Android build."
                !bundled -> "The AdGuard Home Android binary is missing from this APK. Install a CI-built APK."
                running && probeTcp(adminPort) -> "AdGuard Home is running. Open its web interface for first-run setup or DNS settings."
                running -> "AdGuard Home process is running; its admin interface has not answered on port " + adminPort + " yet."
                lastStartError.isNotBlank() -> lastStartError
                else -> "AdGuard Home is included. Start it to open the setup wizard."
            })
            .put("lastUpdated", System.currentTimeMillis())
    }

    fun start(): JSONObject = synchronized(lock) {
        if (process?.isAlive == true) return@synchronized status()
        lastStartError = ""
        val assetPath = supportedAssetPath()
            ?: throw IllegalStateException("Unsupported TV ABI: " + Build.SUPPORTED_ABIS.joinToString())
        if (!hasAsset(assetPath)) {
            throw IllegalStateException("This APK does not contain the AdGuard Home Android binary. Install the CI-built APK.")
        }

        if (!dataDir.exists() && !dataDir.mkdirs()) {
            throw IllegalStateException("Could not create the AdGuard Home data directory.")
        }
        val logParent = logFile.parentFile
        if (logParent != null && !logParent.exists()) logParent.mkdirs()

        val installedVersion = preferences.getString(KEY_INSTALLED_VERSION, null)
        val binary = File(rootDir, "bin/AdGuardHome")
        if (!binary.exists() || installedVersion != AGH_VERSION) {
            installBinary(assetPath, binary)
            preferences.edit().putString(KEY_INSTALLED_VERSION, AGH_VERSION).apply()
        }
        if (!binary.canExecute() && !binary.setExecutable(true, true)) {
            throw IllegalStateException("Android would not mark the bundled AdGuard Home executable as runnable.")
        }

        val child = try {
            ProcessBuilder(
                binary.absolutePath,
                "--work-dir",
                dataDir.absolutePath,
                "--no-check-update",
            ).directory(dataDir)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .start()
        } catch (error: Throwable) {
            lastStartError = "Could not launch AdGuard Home on Android: " + (error.message ?: error.javaClass.simpleName)
            EventLog.shared.error(lastStartError)
            throw IllegalStateException(lastStartError)
        }

        process = child
        preferences.edit().putLong(KEY_LAST_START, System.currentTimeMillis()).apply()

        // Detect an immediate exec/ABI/config failure, while giving lower-powered TV boards time.
        try {
            if (child.waitFor(1200, TimeUnit.MILLISECONDS)) {
                process = null
                val tail = readLogTail()
                lastStartError = "AdGuard Home exited during startup." + if (tail.isNotBlank()) " Log: " + tail else ""
                EventLog.shared.error(lastStartError)
                throw IllegalStateException(lastStartError)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            lastStartError = "Interrupted while starting AdGuard Home."
            throw IllegalStateException(lastStartError)
        }

        EventLog.shared.info("Bundled AdGuard Home " + AGH_VERSION + " started.")
        status()
    }

    fun stop(): JSONObject = synchronized(lock) {
        val child = process
        if (child != null && child.isAlive) {
            runCatching { child.destroy() }
            val exited = runCatching { child.waitFor(1800, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!exited && child.isAlive) runCatching { child.destroyForcibly() }
            EventLog.shared.info("Bundled AdGuard Home stopped.")
        }
        process = null
        status()
    }

    fun setAutoStart(enabled: Boolean): JSONObject {
        preferences.edit().putBoolean(KEY_AUTOSTART, enabled).apply()
        EventLog.shared.info("AdGuard Home auto-start " + if (enabled) "enabled." else "disabled.")
        return status()
    }

    fun startIfEnabled(): JSONObject? {
        if (!preferences.getBoolean(KEY_AUTOSTART, false)) return null
        return try {
            start()
        } catch (error: Throwable) {
            lastStartError = error.message ?: "AdGuard Home auto-start failed."
            EventLog.shared.error(lastStartError)
            status()
        }
    }

    private fun installBinary(assetPath: String, destination: File) {
        val parent = destination.parentFile
            ?: throw IllegalStateException("Invalid AdGuard Home executable path.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("Could not create the AdGuard Home executable directory.")
        }
        val temporary = File(parent, "AdGuardHome.tmp")
        appContext.assets.open(assetPath).use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        if (!temporary.setExecutable(true, true)) {
            temporary.delete()
            throw IllegalStateException("Could not set executable permission on the bundled AdGuard Home binary.")
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IllegalStateException("Could not replace the previous AdGuard Home binary.")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException("Could not install the AdGuard Home binary.")
        }
    }

    private fun supportedAssetPath(): String? {
        val abis = Build.SUPPORTED_ABIS.toList()
        return when {
            abis.contains("arm64-v8a") -> "adguardhome/AdGuardHome-android-arm64"
            abis.any { it == "armeabi-v7a" || it == "armeabi" } -> "adguardhome/AdGuardHome-android-arm"
            else -> null
        }
    }

    private fun hasAsset(path: String): Boolean = runCatching {
        appContext.assets.open(path).use { it.read() }
        true
    }.getOrDefault(false)

    private fun probeTcp(port: Int): Boolean {
        if (port !in 1..65535) return false
        return runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
            true
        }.getOrDefault(false)
    }

    private fun adminUrls(port: Int): JSONArray {
        val addresses = linkedSetOf<String>()
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                ni.interfaceAddresses.forEach { binding ->
                    val ip = binding.address
                    if (ip is Inet4Address && !ip.isLoopbackAddress) addresses += ip.hostAddress
                }
            }
        }
        return JSONArray(addresses.map { "http://" + it + ":" + port + "/" })
    }

    private fun readLogTail(): String = runCatching {
        if (!logFile.isFile) return@runCatching ""
        logFile.readLines().takeLast(18).joinToString("\n").takeLast(2400)
    }.getOrDefault("")

    companion object {
        const val AGH_VERSION = "v0.107.79"
        private const val PREFS = "lanvault_adguardhome_runtime"
        private const val KEY_AUTOSTART = "auto_start"
        private const val KEY_INSTALLED_VERSION = "installed_version"
        private const val KEY_LAST_START = "last_start"
        private const val ADMIN_PORT = 3000
        private const val DNS_PORT = 53

        @Volatile private var instance: AdGuardHomeRuntime? = null

        fun get(context: Context): AdGuardHomeRuntime {
            return instance ?: synchronized(this) {
                instance ?: AdGuardHomeRuntime(context.applicationContext).also { instance = it }
            }
        }
    }
}
