package com.lanvault.monitor

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lanvault.net.EventLog
import org.mindrot.jbcrypt.BCrypt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

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
    private val configFile: File get() = File(dataDir, CONFIG_FILE_NAME)

    fun status(): JSONObject {
        val assetPath = supportedAssetPath()
        val bundled = assetPath != null && hasAsset(assetPath)
        val child = process
        val running = child?.isAlive == true
        val adminPort = ADMIN_PORT
        val dnsPort = currentDnsPort()
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
            .put("adminCredentialsAvailable", readAdminPassword() != null)
            .put("autoStart", preferences.getBoolean(KEY_AUTOSTART, false))
            .put("adminUrls", adminUrls(adminPort))
            .put("workDirectory", dataDir.absolutePath)
            .put("lastStartError", lastStartError)
            .put("logTail", readLogTail())
            .put("message", when {
                assetPath == null -> "This TV ABI has no bundled AdGuard Home Android build."
                !bundled -> "The AdGuard Home Android binary is missing from this APK. Install a CI-built APK."
                running && probeTcp(adminPort) && dnsPort == UNPRIVILEGED_DNS_PORT ->
                    "AdGuard Home is running. Its dashboard is available, but DNS uses port 53535 because an ordinary Android app cannot bind port 53. Router-wide DNS filtering needs a privileged port-53 path."
                running && probeTcp(adminPort) ->
                    "AdGuard Home is running with DNS configured on port " + dnsPort + ". Verify the DNS listener before changing your router."
                running -> "AdGuard Home process is running; its admin interface has not answered on port " + adminPort + " yet."
                lastStartError.isNotBlank() -> lastStartError
                else -> "AdGuard Home is included. Start it to create the local configuration and open the dashboard."
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

        val dnsPort = prepareInitialConfig()
        preferences.edit().putInt(KEY_DNS_PORT, dnsPort).apply()

        val child = try {
            ProcessBuilder(
                binary.absolutePath,
                "--work-dir",
                dataDir.absolutePath,
                "--config",
                configFile.absolutePath,
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

    /**
     * AdGuard Home's first-run path exits on Android when it can't bind port 53. Create a valid,
     * authenticated config first so the dashboard can start without root. Use DNS port 53535 for
     * an ordinary Android process; standard router DNS requires a privileged port-53 arrangement.
     */
    private fun prepareInitialConfig(): Int {
        if (!configFile.exists()) {
            val password = generateAdminPassword()
            val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_COST))
            val yaml = """
                users:
                  - name: "$ADMIN_USERNAME"
                    password: '$passwordHash'
                dns:
                  port: $UNPRIVILEGED_DNS_PORT
                schema_version: $CONFIG_SCHEMA_VERSION
            """.trimIndent() + "\n"
            configFile.writeText(yaml, Charsets.UTF_8)
            configFile.setReadable(false, false)
            configFile.setReadable(true, true)
            configFile.setWritable(false, false)
            configFile.setWritable(true, true)
            saveAdminPassword(password)
            EventLog.shared.info("Prepared the first-run AdGuard Home configuration with DNS port $UNPRIVILEGED_DNS_PORT.")
            return UNPRIVILEGED_DNS_PORT
        }

        // Keep an existing config manageable if it was changed to a privileged DNS port.
        val lines = configFile.readLines(Charsets.UTF_8).toMutableList()
        val dnsIndex = lines.indexOfFirst { it.trim() == "dns:" }
        if (dnsIndex < 0) {
            lines += "dns:"
            lines += "  port: $UNPRIVILEGED_DNS_PORT"
            configFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
            EventLog.shared.warn("AdGuard Home config had no DNS section; configured port $UNPRIVILEGED_DNS_PORT for unprivileged Android.")
            return UNPRIVILEGED_DNS_PORT
        }
        val nextSection = (dnsIndex + 1 until lines.size).firstOrNull { index ->
            val line = lines[index]
            line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("#")
        } ?: lines.size
        val portIndex = (dnsIndex + 1 until nextSection).firstOrNull { index ->
            lines[index].matches(Regex("^  port: *[0-9]+ *$"))
        }
        val currentPort = portIndex?.let {
            Regex("[0-9]+").find(lines[it])?.value?.toIntOrNull()
        } ?: DNS_PORT
        return if (currentPort in 1..1023) {
            if (portIndex == null) lines.add(dnsIndex + 1, "  port: $UNPRIVILEGED_DNS_PORT")
            else lines[portIndex] = "  port: $UNPRIVILEGED_DNS_PORT"
            configFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
            EventLog.shared.warn("Changed AdGuard Home DNS port from $currentPort to $UNPRIVILEGED_DNS_PORT because LANVault cannot bind privileged ports without root.")
            UNPRIVILEGED_DNS_PORT
        } else {
            currentPort
        }
    }

    private fun currentDnsPort(): Int =
        if (configFile.isFile) readDnsPort(configFile) else preferences.getInt(KEY_DNS_PORT, UNPRIVILEGED_DNS_PORT)

    private fun readDnsPort(file: File): Int {
        val contents = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
        val dnsSection = contents.substringAfter("dns:", "")
        return Regex("(?m)^  port: *([0-9]+) *$")
            .find(dnsSection)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: UNPRIVILEGED_DNS_PORT
    }

    fun initialAdminCredentials(): JSONObject {
        val password = readAdminPassword()
        return if (password.isNullOrBlank()) {
            JSONObject()
                .put("available", false)
                .put("message", "No generated first-run password is available for this existing AdGuard Home configuration. Use the credentials already configured in AdGuard Home.")
        } else {
            JSONObject()
                .put("available", true)
                .put("username", ADMIN_USERNAME)
                .put("password", password)
                .put("message", "Generated first-run credentials. Sign in to AdGuard Home and change this password.")
        }
    }

    private fun generateAdminPassword(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun saveAdminPassword(password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val payload = cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_ADMIN_PASSWORD_ENCRYPTED, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    private fun readAdminPassword(): String? = runCatching {
        val raw = preferences.getString(KEY_ADMIN_PASSWORD_ENCRYPTED, null) ?: return@runCatching null
        val payload = Base64.decode(raw, Base64.NO_WRAP)
        if (payload.size <= GCM_IV_BYTES) return@runCatching null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            keystoreKey(),
            javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, payload.copyOfRange(0, GCM_IV_BYTES)),
        )
        String(cipher.doFinal(payload.copyOfRange(GCM_IV_BYTES, payload.size)), Charsets.UTF_8)
    }.getOrNull()

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
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
        private const val KEY_DNS_PORT = "dns_port"
        private const val KEY_ALIAS = "lanvault.adguardhome.aes.v1"
        private const val KEY_ADMIN_PASSWORD_ENCRYPTED = "admin_password_encrypted"
        private const val CONFIG_FILE_NAME = "AdGuardHome.yaml"
        private const val CONFIG_SCHEMA_VERSION = 34
        private const val ADMIN_USERNAME = "admin"
        private const val ADMIN_PORT = 3000
        private const val DNS_PORT = 53
        private const val UNPRIVILEGED_DNS_PORT = 53535
        private const val BCRYPT_COST = 10
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        @Volatile private var instance: AdGuardHomeRuntime? = null

        fun get(context: Context): AdGuardHomeRuntime {
            return instance ?: synchronized(this) {
                instance ?: AdGuardHomeRuntime(context.applicationContext).also { instance = it }
            }
        }
    }
}
