package com.lanvault.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.lanvault.TreePickerActivity
import com.lanvault.net.EventLog
import com.lanvault.net.HttpRequest
import com.lanvault.net.HttpResponse
import com.lanvault.net.LiteHttpServer
import com.lanvault.settings.Settings
import com.lanvault.settings.SettingsStore
import com.lanvault.storage.SafePath
import com.lanvault.storage.StorageRepository
import com.lanvault.storage.VaultFileSystem
import com.lanvault.storage.VolumeKind
import java.io.ByteArrayOutputStream

/**
 * Owns the listening socket and dispatches between the JSON API, the web UI and the WebDAV
 * share. Everything binds to 0.0.0.0 so the TV is reachable over LAN.
 */
class VaultServer(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val storageRepository: StorageRepository,
) {

    private val fileSystem = VaultFileSystem(storageRepository, settingsStore)
    private val webDavHandler = WebDavHandler(fileSystem, settingsStore)
    private val apiHandler = ApiHandler(this, settingsStore, storageRepository)
    private var server: LiteHttpServer? = null

    private var pendingStorageRequest: ((Uri?) -> Unit)? = null

    val versionName: String = runCatching {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

    val isRunning: Boolean get() = server?.isRunning == true

    val port: Int get() = settingsStore.current().serverPort

    fun start() {
        val settings = settingsStore.current()
        if (isRunning) return
        try {
            val socket = LiteHttpServer(settings.serverPort, ::route)
            socket.start()
            server = socket
            val addresses = NetworkAddresses.ipv4()
            val host = addresses.firstOrNull() ?: "this-tv"
            EventLog.shared.info(
                "Server listening on http://$host:${settings.serverPort}/ " +
                    "(web UI /ui/, WebDAV /)",
            )
            if (!settings.shareWebdav) {
                EventLog.shared.warn("WebDAV sharing is switched off - iPhone Files cannot connect.")
            }
            if (!storageRepository.hasAllFilesAccess()) {
                EventLog.shared.warn("Full storage access is not granted - some drives may be hidden.")
            }
        } catch (error: Throwable) {
            server = null
            EventLog.shared.error(
                "Could not start on port ${settings.serverPort}: ${error.message}. " +
                    "Change the port in the web interface or free the port.",
            )
            throw error
        }
    }

    fun stop() {
        server?.close()
        server = null
        EventLog.shared.info("Server stopped.")
    }

    fun restart() {
        stop()
        try {
            start()
        } catch (error: Throwable) {
            EventLog.shared.error("Restart failed: ${error.message}")
        }
    }

    fun clearCache() {
        runCatching { context.cacheDir.deleteRecursively() }
        runCatching { context.codeCacheDir.deleteRecursively() }
        logCacheCleared()
    }

    fun logCacheCleared() {
        EventLog.shared.info("Cache cleared.")
    }

    fun requestStoragePicker() {
        val intent = Intent(context, TreePickerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                EventLog.shared.error("Could not open the drive picker: ${it.message}")
            }
        EventLog.shared.info("Opening the drive picker on the TV. Choose the USB or SD card to share.")
    }

    fun handlePickedTree(uri: Uri, displayName: String?) {
        storageRepository.storeSafTree(uri, displayName)
        EventLog.shared.info("Drive added: ${displayName ?: uri.lastPathSegment}")
    }

    private fun route(request: HttpRequest): HttpResponse {
        val settings = settingsStore.current()

        if (request.path.startsWith("/api/") || request.path == "/api") {
            if (settings.requireAuth && !authorized(request, settings)) {
                return HttpResponse.status(401, "Unauthorized").apply {
                    header("WWW-Authenticate", "Basic realm=\"LANVault\"")
                    header("Content-Type", "text/plain; charset=utf-8")
                    header("Content-Length", "0")
                }
            }
            return apiHandler.handle(request)
        }

        if (request.path == "/ui" || request.path.startsWith("/ui/")) {
            return serveWebUi(request)
        }

        if (request.path == "/favicon.ico") {
            return HttpResponse.status(204, "No Content")
        }

        if (settings.shareWebdav) {
            if (settings.requireAuth && !authorized(request, settings)) {
                return HttpResponse.status(401, "Unauthorized").apply {
                    header("WWW-Authenticate", "Basic realm=\"LANVault\"")
                    header("Content-Type", "text/plain; charset=utf-8")
                    bodyBytes = "Authentication required.".toByteArray(Charsets.UTF_8)
                }
            }
            return webDavHandler.handle(request)
        }

        return HttpResponse.status(404, "Not Found").apply {
            header("Content-Type", "text/plain; charset=utf-8")
            bodyBytes = "Not found. The web interface lives at /ui/".toByteArray(Charsets.UTF_8)
        }
    }

    private fun authorized(request: HttpRequest, settings: Settings): Boolean {
        val header = request.header("Authorization") ?: return false
        if (!header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching {
            Base64.decode(header.substring(6).trim(), Base64.DEFAULT).toString(Charsets.UTF_8)
        }.getOrNull() ?: return false
        val separator = decoded.indexOf(':')
        if (separator < 0) return false
        val user = decoded.substring(0, separator)
        val pass = decoded.substring(separator + 1)
        return user == settings.username && pass == settings.password
    }

    private fun serveWebUi(request: HttpRequest): HttpResponse {
        val assetPath = buildString {
            append("web/")
            val relative = request.path.removePrefix("/ui").trim('/')
            if (relative.isEmpty()) append("index.html")
            else append(SafePath.normalise(relative))
        }
        val bytes = try {
            context.assets.open(assetPath).use { input ->
                val output = ByteArrayOutputStream(64 * 1024)
                input.copyTo(output)
                output.toByteArray()
            }
        } catch (error: Throwable) {
            // Client-side routes have no extension; anything else is a genuine miss.
            val hasExtension = assetPath.substringAfterLast('/', "").contains('.')
            if (!hasExtension) {
                return serveWebUi(knownRequest(request, "/ui/"))
            }
            return HttpResponse.status(404, "Not Found").apply {
                header("Content-Type", "text/plain; charset=utf-8")
                bodyBytes = "Web interface asset missing".toByteArray(Charsets.UTF_8)
            }
        }
        val contentType = when (assetPath.substringAfterLast('.', "html")) {
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
        return HttpResponse.status(200, "OK").apply {
            header("Content-Type", contentType)
            header("Cache-Control", "no-store")
            header("Content-Length", bytes.size.toString())
            if (request.method != "HEAD") bodyBytes = bytes
        }
    }

    private fun knownRequest(request: HttpRequest, path: String): HttpRequest = HttpRequest(
        method = request.method,
        rawTarget = request.rawTarget,
        path = path,
        query = request.query,
        headers = request.headers,
        body = request.body,
        remoteAddress = request.remoteAddress,
    )
}

/** Names used by the volume kind picker in the web UI. */
fun VolumeKind.label(): String = when (this) {
    VolumeKind.INTERNAL -> "TV internal storage"
    VolumeKind.USB -> "USB drive"
    VolumeKind.SD_CARD -> "SD card"
    VolumeKind.OTHER -> "Shared folder"
}
