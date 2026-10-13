package com.lanvault.monitor

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.util.Collections
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Read-only AdGuard and local-network monitoring integrations. */
class NetworkMonitoring(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile private var cloudCache: JSONObject? = null
    @Volatile private var cloudCacheTime = 0L

    fun status(): JSONObject {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = manager?.activeNetwork
        val capabilities = if (manager != null && activeNetwork != null) manager.getNetworkCapabilities(activeNetwork) else null
        val memoryInfo = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memoryInfo)
        val runtime = Runtime.getRuntime()
        val proxyPort = preferences.getInt(KEY_PROXY_PORT, 0)
        val home = readHomeConfig()
        val cloud = readCloudTokens()
        val transports = JSONArray().apply {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) put("vpn")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) put("ethernet")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) put("wifi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) put("cellular")
        }
        return JSONObject()
            .put("vpnActive", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
            .put("activeTransports", transports)
            .put("systemMemoryTotalMb", if (memoryInfo.totalMem > 0) memoryInfo.totalMem / MIB else JSONObject.NULL)
            .put("systemMemoryAvailableMb", if (memoryInfo.availMem > 0) memoryInfo.availMem / MIB else JSONObject.NULL)
            .put("appHeapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / MIB)
            .put("appHeapMaxMb", runtime.maxMemory() / MIB)
            .put("thermal", readThermalSensor() ?: JSONObject.NULL)
            .put("uptimeSeconds", readUptimeSeconds() ?: JSONObject.NULL)
            .put("networkInterfaces", networkInterfaces())
            .put("routerNeighbors", readArpNeighbors())
            .put("homeConfigured", home != null)
            .put("homeAddress", home?.optString("baseUrl").orEmpty())
            .put("cloudConnected", cloud != null)
            .put("cloudEmail", cloud?.optString("email").orEmpty())
            .put("everyProxyPort", proxyPort)
            .put("everyProxyListening", proxyPort > 0 && probeLoopbackPort(proxyPort))
            .put("lastUpdated", System.currentTimeMillis())
    }

    fun setEveryProxyPort(port: Int): JSONObject {
        if (port !in 0..65535) throw MonitoringException(400, "Port must be between 0 and 65535.")
        preferences.edit().putInt(KEY_PROXY_PORT, port).apply()
        return JSONObject().put("ok", true).put("port", port)
            .put("listening", port > 0 && probeLoopbackPort(port))
    }

    fun configureHome(rawUrl: String, username: String, password: String): JSONObject {
        val baseUrl = normalizeHomeUrl(rawUrl)
        val value = JSONObject().put("baseUrl", baseUrl)
            .put("username", username.take(128)).put("password", password.take(256))
        writeSecret(KEY_HOME_CONFIG, value.toString())
        return JSONObject().put("ok", true).put("configured", true).put("baseUrl", baseUrl)
    }

    fun forgetHome(): JSONObject {
        preferences.edit().remove(KEY_HOME_CONFIG).apply()
        return JSONObject().put("ok", true).put("configured", false)
    }

    fun homeSnapshot(): JSONObject {
        val config = readHomeConfig() ?: return JSONObject()
            .put("configured", false).put("connected", false)
            .put("message", "Add the address of an already-running AdGuard Home instance.")
            .put("stats", JSONObject.NULL).put("clients", JSONObject.NULL).put("queryLog", JSONObject.NULL)

        val baseUrl = config.optString("baseUrl")
        val credentials = config.optString("username") to config.optString("password")
        val status = homeResult(baseUrl, credentials, "/control/status")
        val stats = homeResult(baseUrl, credentials, "/control/stats")
        val clients = homeResult(baseUrl, credentials, "/control/clients")
        val queryLog = homeResult(baseUrl, credentials, "/control/querylog?limit=40")
        val connected = status.optBoolean("ok", false)
        return JSONObject()
            .put("configured", true).put("connected", connected).put("baseUrl", baseUrl)
            .put("message", if (connected) "Connected to AdGuard Home. Monitoring is read-only."
                else status.optString("error", "Could not connect to AdGuard Home."))
            .put("status", dataOrNull(status)).put("stats", dataOrNull(stats))
            .put("clients", dataOrNull(clients)).put("queryLog", dataOrNull(queryLog))
            .put("errors", JSONObject().put("status", errorOrNull(status))
                .put("stats", errorOrNull(stats)).put("clients", errorOrNull(clients))
                .put("queryLog", errorOrNull(queryLog)))
            .put("lastUpdated", System.currentTimeMillis())
    }

    fun signInCloud(email: String, password: String, mfaCode: String): JSONObject {
        val cleanedEmail = email.trim().take(254)
        if (cleanedEmail.isBlank() || password.isBlank()) {
            throw MonitoringException(400, "Enter your AdGuard DNS account email and password.")
        }
        if (password.length > 512 || mfaCode.length > 32) {
            throw MonitoringException(400, "The supplied sign-in fields are too long.")
        }
        val form = linkedMapOf("grant_type" to "password", "username" to cleanedEmail, "password" to password)
        if (mfaCode.isNotBlank()) form["mfa_token"] = mfaCode.trim()
        val tokenResponse = try {
            requestJson(TOKEN_URL, "POST", form = form) as? JSONObject
                ?: throw MonitoringException(502, "AdGuard DNS returned an invalid sign-in response.")
        } catch (error: RemoteFailure) {
            if (error.code in 400..499) {
                throw MonitoringException(401, "AdGuard DNS sign-in failed. Check the credentials and two-factor code if enabled.")
            }
            throw MonitoringException(502, "Couldn't reach the AdGuard DNS sign-in service. Check the TV's internet connection.")
        } catch (error: MonitoringException) {
            throw error
        } catch (_: Throwable) {
            throw MonitoringException(502, "Couldn't reach the AdGuard DNS sign-in service. Check the TV's internet connection.")
        }

        val accessToken = tokenResponse.optString("access_token")
        val refreshToken = tokenResponse.optString("refresh_token")
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            throw MonitoringException(401, "AdGuard DNS sign-in failed. Check the credentials and two-factor code if enabled.")
        }
        val saved = JSONObject().put("email", cleanedEmail)
            .put("accessToken", accessToken).put("refreshToken", refreshToken)
            .put("expiresAt", System.currentTimeMillis() + tokenResponse.optLong("expires_in", 1800L).coerceAtLeast(60L) * 1000L)
        writeSecret(KEY_CLOUD_TOKENS, saved.toString())
        cloudCache = null
        cloudCacheTime = 0L
        return cloudSnapshot(forceRefresh = true)
    }

    fun cloudSnapshot(forceRefresh: Boolean = false): JSONObject {
        val saved = readCloudTokens() ?: return JSONObject()
            .put("connected", false).put("email", JSONObject.NULL)
            .put("message", "Sign in to sync your existing AdGuard DNS server profiles and devices.")
        synchronized(lock) {
            val cached = cloudCache
            if (!forceRefresh && cached != null && System.currentTimeMillis() - cloudCacheTime < CACHE_TTL_MS) {
                return JSONObject(cached.toString())
            }
            val accessToken = try {
                ensureAccessToken(saved)
            } catch (error: MonitoringException) {
                return JSONObject().put("connected", false).put("email", saved.optString("email"))
                    .put("message", error.message ?: "AdGuard DNS session expired. Sign in again.")
            }
            val now = System.currentTimeMillis()
            val from = now - DAY_MS
            val servers = cloudResult("/oapi/v2/dns_servers?limit=100", accessToken)
            val devices = cloudResult("/oapi/v2/devices?limit=100", accessToken)
            val deviceStats = cloudResult("/oapi/v1/stats/devices?time_from_millis=" + from + "&time_to_millis=" + now + "&limit=100", accessToken)
            val domainStats = cloudResult("/oapi/v1/stats/domains?time_from_millis=" + from + "&time_to_millis=" + now + "&limit=20", accessToken)
            val timeStats = cloudResult("/oapi/v1/stats/time?time_from_millis=" + from + "&time_to_millis=" + now + "&limit=48", accessToken)
            val user = cloudResult("/oapi/v1/user", accessToken)
            val connected = user.optBoolean("ok", false)
            val userData = dataOrNull(user)
            val email = if (userData is JSONObject) userData.optString("email").ifBlank { saved.optString("email") }
                else saved.optString("email")
            val result = JSONObject()
                .put("connected", connected).put("email", email)
                .put("message", if (connected) "AdGuard DNS account connected. Profiles and statistics are read-only."
                    else user.optString("error", "Could not validate the AdGuard DNS session."))
                .put("user", userData).put("dnsServers", servers).put("devices", devices)
                .put("deviceStats24h", deviceStats).put("domainStats24h", domainStats)
                .put("timeStats24h", timeStats).put("windowStart", from).put("windowEnd", now)
                .put("lastUpdated", now)
            cloudCache = JSONObject(result.toString())
            cloudCacheTime = now
            return result
        }
    }

    fun signOutCloud(): JSONObject {
        val saved = readCloudTokens()
        if (saved != null) runCatching {
            val refreshToken = saved.optString("refreshToken")
            val encodedToken = URLEncoder.encode(refreshToken, "UTF-8")
            requestJson(REVOKE_URL + "?refresh_token=" + encodedToken, "POST")
        }
        preferences.edit().remove(KEY_CLOUD_TOKENS).apply()
        cloudCache = null
        cloudCacheTime = 0L
        return JSONObject().put("ok", true).put("connected", false)
    }

    private fun ensureAccessToken(saved: JSONObject): String {
        val accessToken = saved.optString("accessToken")
        if (accessToken.isNotBlank() && saved.optLong("expiresAt", 0L) > System.currentTimeMillis() + TOKEN_REFRESH_SKEW_MS) {
            return accessToken
        }
        val refreshToken = saved.optString("refreshToken")
        if (refreshToken.isBlank()) throw MonitoringException(401, "AdGuard DNS session expired. Sign in again.")
        val response = try {
            requestJson(TOKEN_URL, "POST", form = mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken)) as? JSONObject
                ?: throw MonitoringException(502, "Invalid refresh response.")
        } catch (_: Throwable) {
            throw MonitoringException(401, "AdGuard DNS session expired. Sign in again.")
        }
        val refreshed = response.optString("access_token")
        if (refreshed.isBlank()) throw MonitoringException(401, "AdGuard DNS session expired. Sign in again.")
        val updated = JSONObject(saved.toString())
            .put("accessToken", refreshed)
            .put("refreshToken", response.optString("refresh_token").ifBlank { refreshToken })
            .put("expiresAt", System.currentTimeMillis() + response.optLong("expires_in", 1800L).coerceAtLeast(60L) * 1000L)
        writeSecret(KEY_CLOUD_TOKENS, updated.toString())
        return refreshed
    }

    private fun cloudResult(path: String, token: String): JSONObject = try {
        JSONObject().put("ok", true).put("data", requestJson(API_BASE + path, "GET", bearer = token))
    } catch (error: Throwable) {
        JSONObject().put("ok", false).put("error", when {
            error is RemoteFailure && error.code == 401 -> "AdGuard DNS rejected the saved session. Sign in again."
            error is RemoteFailure && error.code == 403 -> "The account does not have permission for this data."
            error is RemoteFailure && error.code == 429 -> "AdGuard DNS rate limit reached. Try again later."
            else -> "Could not load this AdGuard DNS resource."
        })
    }

    private fun homeResult(baseUrl: String, credentials: Pair<String, String>, path: String): JSONObject = try {
        JSONObject().put("ok", true).put(
            "data",
            requestJson(baseUrl + path, "GET", basic = if (credentials.first.isNotBlank() || credentials.second.isNotBlank()) credentials else null),
        )
    } catch (error: Throwable) {
        val message = when {
            error is RemoteFailure && error.code == 401 -> "AdGuard Home rejected the username or password."
            error is RemoteFailure && error.code == 404 -> "That URL did not return the expected AdGuard Home API endpoint."
            error is RemoteFailure && error.code == 403 -> "AdGuard Home denied this request."
            error is RemoteFailure -> "AdGuard Home returned HTTP " + error.code + "."
            else -> "Couldn't reach AdGuard Home. Check its address, network, and credentials."
        }
        JSONObject().put("ok", false).put("error", message)
    }

    private fun dataOrNull(result: JSONObject): Any =
        if (result.optBoolean("ok") && result.has("data")) result.get("data") else JSONObject.NULL

    private fun errorOrNull(result: JSONObject): Any =
        if (result.optBoolean("ok")) JSONObject.NULL else result.optString("error")

    private fun requestJson(
        url: String,
        method: String,
        form: Map<String, String>? = null,
        bearer: String? = null,
        basic: Pair<String, String>? = null,
    ): Any {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer " + it) }
            if (basic != null) {
                val encoded = Base64.encodeToString((basic.first + ":" + basic.second).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                connection.setRequestProperty("Authorization", "Basic " + encoded)
            }
            if (form != null) {
                val body = form.entries.joinToString("&") {
                    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
                }
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(MAX_RESPONSE_CHARS) }.orEmpty()
            if (code !in 200..299) throw RemoteFailure(code)
            val trimmed = body.trim()
            return when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.isEmpty() -> JSONObject()
                else -> throw MonitoringException(502, "Remote service returned an unexpected response.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readHomeConfig(): JSONObject? = readSecret(KEY_HOME_CONFIG)?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun readCloudTokens(): JSONObject? = readSecret(KEY_CLOUD_TOKENS)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun writeSecret(name: String, cleartext: String) {
        preferences.edit().putString(name, encrypt(cleartext)).apply()
    }

    private fun readSecret(name: String): String? =
        preferences.getString(name, null)?.let { runCatching { decrypt(it) }.getOrNull() }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, payload.copyOfRange(0, GCM_IV_BYTES)))
        return String(cipher.doFinal(payload.copyOfRange(GCM_IV_BYTES, payload.size)), Charsets.UTF_8)
    }

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun normalizeHomeUrl(raw: String): String {
        val uri = try { URI(raw.trim()) } catch (_: Throwable) {
            throw MonitoringException(400, "Enter a valid AdGuard Home URL, for example http://192.168.1.2:3000.")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host
        if ((scheme != "http" && scheme != "https") || host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null ||
            (uri.rawPath.orEmpty().isNotBlank() && uri.rawPath != "/")) {
            throw MonitoringException(400, "Use the AdGuard Home origin only, without a path or credentials.")
        }
        if (scheme == "http" && !isLocalHost(host)) {
            throw MonitoringException(400, "Plain HTTP is allowed only for local/private AdGuard Home addresses. Use HTTPS for remote servers.")
        }
        val port = if (uri.port == -1) "" else ":" + uri.port
        return (scheme + "://" + host + port).trimEnd('/')
    }

    private fun isLocalHost(host: String): Boolean {
        val value = host.trim('[', ']').lowercase(Locale.ROOT)
        if (value == "localhost" || value.endsWith(".local") || value.endsWith(".ts.net")) return true
        val address = runCatching { InetAddress.getByName(value) }.getOrNull() ?: return false
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        if (address is Inet4Address) {
            val bytes = address.address.map { it.toInt() and 0xff }
            if (bytes[0] == 100 && bytes[1] in 64..127) return true
        }
        return false
    }

    private fun probeLoopbackPort(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS) }
        true
    }.getOrDefault(false)

    private fun networkInterfaces(): JSONArray {
        val result = JSONArray()
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
        interfaces.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }.forEach { ni ->
            val addresses = JSONArray().apply {
                ni.interfaceAddresses.forEach { binding -> binding.address?.hostAddress?.let { put(it) } }
            }
            val statistics = File("/sys/class/net/" + ni.name + "/statistics")
            result.put(JSONObject().put("name", ni.name).put("addresses", addresses)
                .put("rxBytesSinceBoot", readLong(File(statistics, "rx_bytes")) ?: JSONObject.NULL)
                .put("txBytesSinceBoot", readLong(File(statistics, "tx_bytes")) ?: JSONObject.NULL))
        }
        return result
    }

    private fun readArpNeighbors(): JSONArray {
        val rows = JSONArray()
        runCatching {
            File("/proc/net/arp").useLines { lines ->
                lines.drop(1).forEach { line ->
                    val fields = line.trim().split(Regex("\\s+"))
                    if (fields.size < 6) return@forEach
                    val flags = fields[2].removePrefix("0x").toIntOrNull(16) ?: 0
                    val mac = fields[3].lowercase(Locale.ROOT)
                    if (flags == 0 || mac == "00:00:00:00:00:00" || mac.count { it == ':' } != 5) return@forEach
                    rows.put(JSONObject().put("ip", fields[0]).put("mac", mac).put("interface", fields[5]))
                }
            }
        }
        return rows
    }

    private fun readThermalSensor(): JSONObject? {
        val candidates = mutableListOf<Pair<String, Double>>()
        File("/sys/class/thermal").listFiles().orEmpty()
            .filter { it.name.matches(Regex("thermal_zone\\d+")) }
            .forEach { zone ->
                val raw = runCatching { File(zone, "temp").readText().trim().toDoubleOrNull() }.getOrNull() ?: return@forEach
                val celsius = if (raw > 1000.0) raw / 1000.0 else raw
                if (celsius in 5.0..125.0) {
                    val name = runCatching { File(zone, "type").readText().trim() }.getOrDefault(zone.name)
                    candidates += name to celsius
                }
            }
        val hottest = candidates.maxByOrNull { it.second } ?: return null
        return JSONObject().put("label", hottest.first).put("celsius", (hottest.second * 10).toInt() / 10.0)
    }

    private fun readUptimeSeconds(): Double? = runCatching {
        File("/proc/uptime").readText().trim().substringBefore(' ').toDoubleOrNull()
    }.getOrNull()

    private fun readLong(file: File): Long? = runCatching { file.readText().trim().toLongOrNull() }.getOrNull()

    companion object {
        private const val PREFS = "lanvault_monitor_secrets"
        private const val KEY_ALIAS = "lanvault.monitor.aes.v1"
        private const val KEY_HOME_CONFIG = "home_config_encrypted"
        private const val KEY_CLOUD_TOKENS = "adguard_dns_tokens_encrypted"
        private const val KEY_PROXY_PORT = "every_proxy_port"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val MIB = 1024L * 1024L
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val CACHE_TTL_MS = 60_000L
        private const val TOKEN_REFRESH_SKEW_MS = 60_000L
        private const val HTTP_TIMEOUT_MS = 2500
        private const val PROBE_TIMEOUT_MS = 400
        private const val MAX_RESPONSE_CHARS = 1_000_000
        private const val API_BASE = "https://api.adguard-dns.io"
        private const val TOKEN_URL = "$API_BASE/oapi/v1/oauth_token"
        private const val REVOKE_URL = "$API_BASE/oapi/v1/revoke_token"
    }
}

class MonitoringException(val statusCode: Int, message: String) : Exception(message)
private class RemoteFailure(val code: Int) : Exception()
