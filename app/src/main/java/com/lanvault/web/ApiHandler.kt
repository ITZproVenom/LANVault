package com.lanvault.web

import com.lanvault.net.EventLog
import com.lanvault.net.HttpRequest
import com.lanvault.net.HttpResponse
import com.lanvault.net.formatHttpDate
import com.lanvault.settings.Settings
import com.lanvault.settings.SettingsStore
import com.lanvault.storage.SafTree
import com.lanvault.storage.SafePath
import com.lanvault.storage.StorageEntry
import com.lanvault.storage.StorageRepository
import com.lanvault.storage.VaultFileSystem
import com.lanvault.storage.VaultNode
import com.lanvault.storage.VolumeDirectorySnapshot
import com.lanvault.storage.VolumeKind
import com.lanvault.storage.slug
import com.lanvault.monitor.NetworkMonitoring
import com.lanvault.monitor.MonitoringException
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Human-readable LAN addresses of the TV. */
object NetworkAddresses {

    fun ipv4(): List<String> {
        val found = LinkedHashSet<String>()
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().forEach { networkInterface ->
                if (networkInterface.isLoopback || !networkInterface.isUp) return@forEach
                networkInterface.interfaceAddresses.forEach { binding ->
                    val address = binding.address
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        found += address.hostAddress ?: return@forEach
                    }
                }
            }
        }
        return found.toList()
    }

    fun hostnames(): List<String> {
        val names = LinkedHashSet<String>()
        ipv4().forEach { names += it }
        runCatching { InetAddress.getLocalHost().hostName?.let { names += it } }
        return names.toList()
    }
}

class ApiHandler(
    private val server: VaultServer,
    private val settingsStore: SettingsStore,
    private val storageRepository: StorageRepository,
) {

    private val fileSystem = VaultFileSystem(storageRepository, settingsStore)
    private val networkMonitoring = NetworkMonitoring(server.monitoringContext)

    fun handle(request: HttpRequest): HttpResponse {
        val action = request.path.removePrefix("/api").trim('/')
        return when (action) {
            "" -> root(request)
            "state" -> state(request)
            "volumes" -> volumes(request)
            "list" -> list(request)
            "file" -> file(request, download = false)
            "download" -> file(request, download = true)
            "thumbnail" -> file(request, download = false)
            "put" -> upload(request)
            "mkdir" -> mkdir(request)
            "rename" -> rename(request)
            "delete" -> delete(request)
            "move" -> move(request)
            "settings" -> settings(request)
            "storage" -> storage(request)
            "logs" -> logs(request)
            "restart" -> restart(request)
            "clear-cache" -> clearCache(request)
            "monitoring/status" -> monitoringStatus()
            "monitoring/home" -> monitoringHome()
            "monitoring/home/config" -> configureMonitoringHome(request)
            "monitoring/home/forget" -> forgetMonitoringHome(request)
            "monitoring/cloud" -> monitoringCloud()
            "monitoring/cloud/login" -> signInMonitoringCloud(request)
            "monitoring/cloud/refresh" -> refreshMonitoringCloud(request)
            "monitoring/cloud/logout" -> signOutMonitoringCloud(request)
            "monitoring/proxy" -> configureEveryProxy(request)
            else -> json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "Unknown endpoint"))
        }
    }

    private fun root(request: HttpRequest): HttpResponse = json(
        HttpResponse.status(200, "OK"),
        JSONObject()
            .put("name", "LANVault")
            .put("endpoints", JSONArray(listOf(
                "/api/state", "/api/volumes", "/api/list", "/api/file", "/api/upload",
                "/api/mkdir", "/api/rename", "/api/delete", "/api/move",
                "/api/settings", "/api/storage", "/api/logs",
            )))
            .put("webInterface", "/ui/")
            .put("webdavBase", "/"),
    )

    private fun state(request: HttpRequest): HttpResponse {
        val settings = settingsStore.current()
        val addresses = NetworkAddresses.ipv4()
        val volumes = storageRepository.discover()
        val port = server.port
        return json(
            HttpResponse.status(200, "OK"),
            JSONObject()
                .put("running", server.isRunning)
                .put("port", port)
                .put("requestedPort", settings.serverPort)
                .put("portInUseOnPreferred", server.portInUseOnPreferred)
                .put("deviceName", settings.deviceName)
                .put("ipv4Addresses", JSONArray(addresses))
                .put("hostnames", JSONArray(NetworkAddresses.hostnames()))
                .put(
                    "webInterfaceUrl",
                    if (addresses.isEmpty()) null else "http://${addresses.first()}:$port/ui/",
                )
                .put(
                    "webdavUrl",
                    if (addresses.isEmpty()) null else "http://${addresses.first()}:$port/",
                )
                .put("requiresAuth", settings.requireAuth)
                .put("allFilesAccess", storageRepository.hasAllFilesAccess())
                .put("versionName", server.versionName)
                .put("volumeCount", volumes.count { it.volume != null })
                .put("volumes", JSONArray(volumes.map { volumeJson(it.id, it, true) }))
                .put("settings", settingsJson(settings))
                .put("freeSpaceTotal", volumes.mapNotNull { it.volume?.availableBytes() }.sum()),
        )
    }

    private fun volumeJson(id: String, volume: com.lanvault.storage.DiscoveredVolume, short: Boolean): JSONObject {
        val json = JSONObject()
            .put("id", id)
            .put("slug", volume.slug())
            .put("displayName", volume.displayName)
            .put("kind", volume.kind.name.lowercase())
            .put("readable", volume.readable)
            .put("writable", volume.writable)
            .put("needsPermission", volume.needsPermission)
            .put("mountPath", volume.mountPath)
        if (!short) {
            json.put("availableBytes", volume.volume?.availableBytes() ?: 0L)
            json.put("totalBytes", volume.volume?.totalBytes() ?: 0L)
        }
        return json
    }

    private fun settingsJson(settings: Settings): JSONObject = JSONObject()
        .put("serverPort", settings.serverPort)
        .put("deviceName", settings.deviceName)
        .put("requireAuth", settings.requireAuth)
        .put("username", settings.username)
        .put("password", settings.password)
        .put("allowUploads", settings.allowUploads)
        .put("allowDeletes", settings.allowDeletes)
        .put("allowCreateFolders", settings.allowCreateFolders)
        .put("showHiddenFiles", settings.showHiddenFiles)
        .put("autoStartOnBoot", settings.autoStartOnBoot)
        .put("keepCpuAwake", settings.keepCpuAwake)
        .put("shareWebdav", settings.shareWebdav)

    private fun volumes(request: HttpRequest): HttpResponse {
        val volumes = storageRepository.discover()
        return json(
            HttpResponse.status(200, "OK"),
            JSONObject().put("volumes", JSONArray(volumes.map { volumeJson(it.id, it, false) })),
        )
    }

    private fun list(request: HttpRequest): HttpResponse {
        val rawPath = request.query["path"] ?: ""
        val snapshot = fileSystem.snapshot()
        val entries = snapshot.children(rawPath, settingsStore.current().showHiddenFiles)
            ?: return json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "Folder not found"))
        return json(
            HttpResponse.status(200, "OK"),
            JSONObject()
                .put("path", rawPath)
                .put("entries", JSONArray(entries.map { entryJson(it) })),
        )
    }

    private fun entryJson(entry: StorageEntry): JSONObject = JSONObject()
        .put("name", entry.name)
        .put("path", entry.path)
        .put("isDirectory", entry.isDirectory)
        .put("size", entry.sizeBytes)
        .put("lastModified", entry.lastModified)
        .put("mimeType", if (entry.isDirectory) null else MimeTypes.forName(entry.name))
        .put("kind", kindOf(entry))
        .put("previewable", MimeTypes.canPreviewInBrowser(entry))

    private fun kindOf(entry: StorageEntry): String = when {
        entry.isDirectory -> "folder"
        MimeTypes.isVideo(entry.name) -> "video"
        MimeTypes.isAudio(entry.name) -> "audio"
        MimeTypes.isImage(entry.name) -> "image"
        MimeTypes.isSubtitle(entry.name) -> "subtitle"
        else -> "file"
    }

    private fun file(request: HttpRequest, download: Boolean): HttpResponse {
        val rawPath = request.query["path"]
            ?: return json(HttpResponse.status(400, "Bad Request"), JSONObject().put("error", "path is required"))
        val snapshot = fileSystem.snapshot()
        val resolved = snapshot.resolve(rawPath)
        if (resolved !is com.lanvault.storage.VaultNode.FileNode) {
            return json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "File not found"))
        }
        if (resolved.entry.isDirectory) return json(
            HttpResponse.status(400, "Bad Request"),
            JSONObject().put("error", "This is a folder"),
        )
        val volume = resolved.volume.volume
            ?: return json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "Volume unavailable"))
        val name = resolved.entry.name
        val mime = if (download) "application/octet-stream" else MimeTypes.forName(name)
        val totalBytes = resolved.entry.sizeBytes
        val rangeHeader = request.header("Range")?.takeIf { it.startsWith("bytes=") }

        if (rangeHeader != null) {
            val bounds = parseRange(rangeHeader.removePrefix("bytes=").trim(), totalBytes) ?: return rangeNotSatisfiable(totalBytes)
            val source = volume.openReadSource(resolved.relativePath, bounds.first) ?: return gone()
            val length = (bounds.second - bounds.first + 1)
                .coerceAtMost(if (source.lengthBytes >= 0) source.lengthBytes else bounds.second - bounds.first + 1)
                .coerceAtLeast(0L)
            return HttpResponse.status(206, "Partial Content").apply {
                header("Content-Type", mime)
                header("Accept-Ranges", "bytes")
                header("Content-Range", "bytes ${bounds.first}-${bounds.second}/$totalBytes")
                header("Content-Length", length.toString())
                bodyStream = source.stream
                contentLength = length
            }
        }

        val source = volume.openReadSource(resolved.relativePath, 0L) ?: return gone()
        val length = if (totalBytes > 0) totalBytes else source.lengthBytes
        val lengthValue = if (length >= 0) length else source.lengthBytes
        return HttpResponse.status(200, "OK").apply {
            header("Content-Type", mime)
            header("Accept-Ranges", "bytes")
            header("Content-Length", if (lengthValue >= 0) lengthValue.toString() else "0")
            header("Last-Modified", formatHttpDate(resolved.entry.lastModified))
            if (download) {
                header("Content-Disposition", "attachment; filename=\"${escapeHeader(name)}\"")
            }
            bodyStream = source.stream
            contentLength = if (lengthValue >= 0) lengthValue else -1L
        }
    }

    private fun upload(request: HttpRequest): HttpResponse {
        if (!settingsStore.current().allowUploads) {
            return json(HttpResponse.status(403, "Forbidden"), JSONObject().put("error", "Uploads are disabled."))
        }
        val directory = request.query["path"] ?: ""
        val name = request.query["name"] ?: request.header("X-File-Name") ?: "upload.bin"
        if (!SafePath.isLegalFileName(name)) {
            return json(HttpResponse.status(400, "Bad Request"), JSONObject().put("error", "Invalid file name."))
        }
        val snapshot = fileSystem.snapshot()
        val segments = SafePath.segments("$directory/$name")
        if (segments.size < 2) {
            return json(HttpResponse.status(400, "Bad Request"), JSONObject().put("error", "Invalid destination."))
        }
        val volume = snapshot.find(segments.first())?.volume
            ?: return json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "Volume unavailable."))
        if (!volume.isWritable) {
            return json(HttpResponse.status(403, "Forbidden"), JSONObject().put("error", "Volume is read-only."))
        }
        val relativeDirectory = segments.drop(1).dropLast(1).joinToString("/")
        if (relativeDirectory.isNotEmpty() && volume.stat(relativeDirectory)?.isDirectory != true) {
            return json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "Folder not found."))
        }
        val existed = volume.stat("$relativeDirectory/$name".trimStart('/')) != null
        if (!existed && relativeDirectory.isNotEmpty()) {
            if (!volume.createFile(relativeDirectory, name)) return gone()
        } else if (!existed) {
            if (!volume.createFile("", name)) return gone()
        }
        val relativePath = if (relativeDirectory.isEmpty()) name else "$relativeDirectory/$name"
        val output = volume.openWriteSource(relativePath, append = false) ?: return gone()
        val written = output.use { destination -> request.body.copyTo(destination, 128 * 1024) }
        val expected = request.header("Content-Length")?.toLongOrNull()
        if (expected != null && written != expected) {
            // A dropped connection on a phone otherwise leaves a half written file that looks
            // like a successful upload.
            runCatching { volume.delete(relativePath) }
            EventLog.shared.warn("UPLOAD $name aborted after $written of $expected bytes")
            return json(
                HttpResponse.status(400, "Bad Request"),
                JSONObject().put(
                    "error",
                    "Transfer stopped after $written of $expected bytes. The file was not saved.",
                ),
            )
        }
        EventLog.shared.http("UPLOAD $name -> $relativePath ($written bytes)")
        return json(
            HttpResponse.status(201, "Created"),
            JSONObject().put("ok", true).put("path", "$directory/$name".replace("//", "/")),
        )
    }

    private fun mkdir(request: HttpRequest): HttpResponse {
        if (!settingsStore.current().allowCreateFolders) {
            return json(HttpResponse.status(403, "Forbidden"), JSONObject().put("error", "Creating folders is disabled."))
        }
        val parent = request.query["path"] ?: ""
        val name = request.query["name"] ?: ""
        if (!SafePath.isLegalFileName(name)) {
            return json(HttpResponse.status(400, "Bad Request"), JSONObject().put("error", "Invalid folder name."))
        }
        val snapshot = fileSystem.snapshot()
        val segments = SafePath.segments(parent)
        if (segments.isEmpty()) {
            return json(HttpResponse.status(400, "Bad Request"), JSONObject().put("error", "Pick a volume first."))
        }
        val volume = snapshot.find(segments.first())?.volume ?: return gone()
        if (!volume.isWritable) {
            return json(HttpResponse.status(403, "Forbidden"), JSONObject().put("error", "Volume is read-only."))
        }
        val relativeParent = segments.drop(1).joinToString("/")
        if (relativeParent.isNotEmpty() && volume.stat(relativeParent)?.isDirectory != true) {
            return json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "Folder not found."))
        }
        return if (volume.createDirectory(relativeParent, name)) {
            json(HttpResponse.status(201, "Created"), JSONObject().put("ok", true))
        } else {
            json(HttpResponse.status(409, "Conflict"), JSONObject().put("error", "Folder already exists."))
        }
    }

    private fun rename(request: HttpRequest): HttpResponse {
        val path = request.query["path"] ?: ""
        val newName = request.query["name"] ?: ""
        if (!SafePath.isLegalFileName(newName)) {
            return json(HttpResponse.status(400, "Bad Request"), JSONObject().put("error", "Invalid name."))
        }
        val snapshot = fileSystem.snapshot()
        val target = resolveTarget(snapshot, path)
            ?: return json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "Not found."))
        val volume = target.volume.volume
            ?: return json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "Volume unavailable."))
        if (volume.stat(target.relativePath) == null) {
            return json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "Not found."))
        }
        if (!target.volume.writable) {
            return json(HttpResponse.status(403, "Forbidden"), JSONObject().put("error", "Volume is read-only."))
        }
        return if (volume.rename(target.relativePath, newName)) {
            json(HttpResponse.status(200, "OK"), JSONObject().put("ok", true))
        } else {
            json(HttpResponse.status(500, "Internal Server Error"), JSONObject().put("error", "Rename failed."))
        }
    }

    private fun delete(request: HttpRequest): HttpResponse {
        if (!settingsStore.current().allowDeletes) {
            return json(HttpResponse.status(403, "Forbidden"), JSONObject().put("error", "Deletion is disabled."))
        }
        val path = request.query["path"] ?: ""
        val snapshot = fileSystem.snapshot()
        val target = resolveTarget(snapshot, path)
            ?: return json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "Not found."))
        val volume = target.volume.volume
            ?: return json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "Volume unavailable."))
        if (volume.stat(target.relativePath) == null) {
            return json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "Not found."))
        }
        if (!target.volume.writable) {
            return json(HttpResponse.status(403, "Forbidden"), JSONObject().put("error", "Volume is read-only."))
        }
        return if (volume.delete(target.relativePath)) {
            json(HttpResponse.status(200, "OK"), JSONObject().put("ok", true))
        } else {
            json(HttpResponse.status(500, "Internal Server Error"), JSONObject().put("error", "Delete failed."))
        }
    }

    private fun move(request: HttpRequest): HttpResponse {
        if (!settingsStore.current().allowUploads) {
            return json(HttpResponse.status(403, "Forbidden"), JSONObject().put("error", "Write access is disabled."))
        }
        val source = request.query["from"] ?: ""
        val targetPath = request.query["to"] ?: ""
        val snapshot = fileSystem.snapshot()
        val sourceTarget = resolveTarget(snapshot, source)
            ?: return json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "Source not found."))
        val destinationSegments = SafePath.segments(targetPath)
        if (destinationSegments.isEmpty()) {
            return json(HttpResponse.status(400, "Bad Request"), JSONObject().put("error", "Invalid destination."))
        }
        val destinationVolume = snapshot.find(destinationSegments.first())
            ?: return json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "Destination volume not found."))
        if (!destinationVolume.writable || !sourceTarget.volume.writable) {
            return json(HttpResponse.status(403, "Forbidden"), JSONObject().put("error", "Volume is read-only."))
        }
        val destinationRelative = destinationSegments.drop(1).joinToString("/")
        val sourceRelative = sourceTarget.relativePath
        val destinationVolumeImplementation = destinationVolume.volume ?: return gone()

        if (sourceTarget.volume.slug() == destinationVolume.slug()) {
            val sourceParent = sourceRelative.substringBeforeLast('/', "")
            val destinationParent = destinationRelative.substringBeforeLast('/', "")
            if (sourceParent == destinationParent) {
                return if (sourceTarget.volume.volume?.rename(sourceRelative, SafePath.fileName(destinationRelative)) == true) {
                    json(HttpResponse.status(200, "OK"), JSONObject().put("ok", true))
                } else {
                    json(HttpResponse.status(500, "Internal Server Error"), JSONObject().put("error", "Rename failed."))
                }
            }
        }

        val sourceVolumeImplementation = sourceTarget.volume.volume ?: return gone()
        val sourceEntry = sourceVolumeImplementation.stat(sourceRelative) ?: return gone()
        if (sourceEntry.isDirectory) {
            return json(HttpResponse.status(409, "Conflict"), JSONObject().put("error", "Only files can be moved."))
        }
        val destinationParent = destinationRelative.substringBeforeLast('/', "")
        if (destinationParent.isNotEmpty() && destinationVolumeImplementation.stat(destinationParent)?.isDirectory != true) {
            return json(HttpResponse.status(404, "Not Found"), JSONObject().put("error", "Destination folder not found."))
        }
        if (!destinationVolumeImplementation.createFile(destinationParent, SafePath.fileName(destinationRelative))) {
            return json(HttpResponse.status(409, "Conflict"), JSONObject().put("error", "File already exists."))
        }
        val sourceStream = sourceVolumeImplementation.openReadSource(sourceRelative, 0L) ?: return gone()
        destinationVolumeImplementation.openWriteSource(destinationRelative, append = false)?.use { target ->
            sourceStream.use { input -> input.stream.copyTo(target, 128 * 1024) }
        } ?: return gone()
        if (!sourceVolumeImplementation.delete(sourceRelative)) return gone()
        return json(HttpResponse.status(200, "OK"), JSONObject().put("ok", true))
    }

    private fun resolveTarget(
        snapshot: com.lanvault.storage.VolumeDirectorySnapshot,
        path: String,
    ): com.lanvault.storage.VaultNode.FileNode? {
        val segments = SafePath.segments(path)
        if (segments.isEmpty()) return null
        val volume = snapshot.find(segments.first()) ?: return null
        val relativePath = segments.drop(1).joinToString("/")
        if (relativePath.isEmpty()) return null
        return com.lanvault.storage.VaultNode.FileNode(
            volume = volume,
            relativePath = relativePath,
            entry = StorageEntry(
                name = SafePath.fileName(relativePath),
                path = relativePath,
                isDirectory = false,
                sizeBytes = 0L,
                lastModified = 0L,
            ),
        )
    }

    private fun settings(request: HttpRequest): HttpResponse {
        if (request.method == "POST" || request.method == "PUT") {
            val form = if (request.header("Content-Type")?.contains("application/json") == true) {
                val body = readBody(request)
                runCatching { JSONObject(body) }.getOrNull()?.let { json ->
                    val flattened = LinkedHashMap<String, String>()
                    json.keys().forEach { key ->
                        flattened[key] = json.get(key)?.toString().orEmpty()
                    }
                    flattened
                } ?: emptyMap()
            } else {
                parseForm(readBody(request))
            }
            if (form.isEmpty()) {
                return json(HttpResponse.status(400, "Bad Request"), JSONObject().put("error", "Empty settings body."))
            }
            settingsStore.update { current ->
                var next = current
                form["serverPort"]?.toIntOrNull()?.let {
                    next = next.copy(serverPort = it.coerceIn(Settings.MIN_PORT, Settings.MAX_PORT))
                }
                form["deviceName"]?.takeIf { it.isNotBlank() }?.let { next = next.copy(deviceName = it.take(64)) }
                form.forEach { (key, value) ->
                    val flag = value.equals("true", true) || value == "1" || value.equals("on", true)
                    when (key) {
                        "requireAuth" -> next = next.copy(requireAuth = flag)
                        "allowUploads" -> next = next.copy(allowUploads = flag)
                        "allowDeletes" -> next = next.copy(allowDeletes = flag)
                        "allowCreateFolders" -> next = next.copy(allowCreateFolders = flag)
                        "showHiddenFiles" -> next = next.copy(showHiddenFiles = flag)
                        "autoStartOnBoot" -> next = next.copy(autoStartOnBoot = flag)
                        "keepCpuAwake" -> next = next.copy(keepCpuAwake = flag)
                        "shareWebdav" -> next = next.copy(shareWebdav = flag)
                    }
                }
                form["username"]?.let { if (it.isNotBlank()) next = next.copy(username = it.take(64)) }
                form["password"]?.let { next = next.copy(password = it.take(128)) }
                next
            }
            val portChanged = true
            EventLog.shared.info("Settings updated from the web interface.")
            return json(
                HttpResponse.status(200, "OK"),
                JSONObject().put("ok", true)
                    .put("restartRequired", portChanged)
                    .put("settings", settingsJson(settingsStore.current())),
            )
        }
        return json(
            HttpResponse.status(200, "OK"),
            JSONObject().put("settings", settingsJson(settingsStore.current())),
        )
    }

    private fun storage(request: HttpRequest): HttpResponse {
        val action = request.query["action"].orEmpty()
        return when (action) {
            "pick" -> {
                server.requestStoragePicker()
                json(HttpResponse.status(202, "Accepted"), JSONObject()
                    .put("ok", true)
                    .put("message", "Use the TV remote to pick the drive in the dialog that just opened."))
            }
            "remove" -> {
                val id = request.query["id"].orEmpty()
                storageRepository.removeSafTree(id)
                json(HttpResponse.status(200, "OK"), JSONObject().put("ok", true))
            }
            else -> {
                val trees = storageRepository.storedSafTrees().map { tree: SafTree ->
                    JSONObject().put("id", tree.id).put("displayName", tree.displayName).put("uri", tree.uri.toString())
                }
                json(HttpResponse.status(200, "OK"), JSONObject().put("trees", JSONArray(trees)))
            }
        }
    }

    private fun logs(request: HttpRequest): HttpResponse {
        val lines = EventLog.shared.snapshot.value.takeLast(300).map { line ->
            JSONObject()
                .put("timestamp", line.timestamp)
                .put("level", line.level.name.lowercase())
                .put("message", line.message)
        }
        return json(HttpResponse.status(200, "OK"), JSONObject().put("lines", JSONArray(lines)))
    }


    private fun monitoringStatus(): HttpResponse =
        json(HttpResponse.status(200, "OK"), networkMonitoring.status())

    private fun monitoringHome(): HttpResponse =
        json(HttpResponse.status(200, "OK"), networkMonitoring.homeSnapshot())

    private fun configureMonitoringHome(request: HttpRequest): HttpResponse {
        if (request.method != "POST") return json(HttpResponse.status(405, "Method Not Allowed"), JSONObject().put("error", "POST required."))
        val body = parseJsonBody(request) ?: return json(HttpResponse.status(400, "Bad Request"), JSONObject().put("error", "Invalid JSON body."))
        return try {
            json(HttpResponse.status(200, "OK"), networkMonitoring.configureHome(
                body.optString("baseUrl"), body.optString("username"), body.optString("password"),
            ))
        } catch (error: MonitoringException) {
            json(HttpResponse.status(error.statusCode, "Integration Error"), JSONObject().put("error", error.message ?: "Invalid AdGuard Home configuration."))
        } catch (_: Throwable) {
            json(HttpResponse.status(500, "Internal Server Error"), JSONObject().put("error", "Could not save AdGuard Home settings."))
        }
    }

    private fun forgetMonitoringHome(request: HttpRequest): HttpResponse {
        if (request.method != "POST") return json(HttpResponse.status(405, "Method Not Allowed"), JSONObject().put("error", "POST required."))
        return json(HttpResponse.status(200, "OK"), networkMonitoring.forgetHome())
    }

    private fun monitoringCloud(): HttpResponse =
        json(HttpResponse.status(200, "OK"), networkMonitoring.cloudSnapshot())

    private fun signInMonitoringCloud(request: HttpRequest): HttpResponse {
        if (request.method != "POST") return json(HttpResponse.status(405, "Method Not Allowed"), JSONObject().put("error", "POST required."))
        val body = parseJsonBody(request) ?: return json(HttpResponse.status(400, "Bad Request"), JSONObject().put("error", "Invalid JSON body."))
        return try {
            json(HttpResponse.status(200, "OK"), networkMonitoring.signInCloud(
                body.optString("email"), body.optString("password"), body.optString("mfaCode"),
            ))
        } catch (error: MonitoringException) {
            json(HttpResponse.status(error.statusCode, "Integration Error"), JSONObject().put("error", error.message ?: "AdGuard DNS sign-in failed."))
        } catch (_: Throwable) {
            json(HttpResponse.status(502, "Bad Gateway"), JSONObject().put("error", "AdGuard DNS sign-in failed."))
        }
    }

    private fun refreshMonitoringCloud(request: HttpRequest): HttpResponse {
        if (request.method != "POST") return json(HttpResponse.status(405, "Method Not Allowed"), JSONObject().put("error", "POST required."))
        return try { json(HttpResponse.status(200, "OK"), networkMonitoring.cloudSnapshot(forceRefresh = true)) }
        catch (_: Throwable) { json(HttpResponse.status(502, "Bad Gateway"), JSONObject().put("error", "Could not refresh AdGuard DNS data.")) }
    }

    private fun signOutMonitoringCloud(request: HttpRequest): HttpResponse {
        if (request.method != "POST") return json(HttpResponse.status(405, "Method Not Allowed"), JSONObject().put("error", "POST required."))
        return json(HttpResponse.status(200, "OK"), networkMonitoring.signOutCloud())
    }

    private fun configureEveryProxy(request: HttpRequest): HttpResponse {
        if (request.method != "POST") return json(HttpResponse.status(405, "Method Not Allowed"), JSONObject().put("error", "POST required."))
        val body = parseJsonBody(request) ?: return json(HttpResponse.status(400, "Bad Request"), JSONObject().put("error", "Invalid JSON body."))
        return try {
            json(HttpResponse.status(200, "OK"), networkMonitoring.setEveryProxyPort(body.optInt("port", 0)))
        } catch (error: MonitoringException) {
            json(HttpResponse.status(error.statusCode, "Bad Request"), JSONObject().put("error", error.message ?: "Invalid port."))
        }
    }

    private fun parseJsonBody(request: HttpRequest): JSONObject? {
        if (!request.header("Content-Type").orEmpty().contains("application/json", ignoreCase = true)) return null
        val body = readBody(request)
        if (body.isBlank()) return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun restart(request: HttpRequest): HttpResponse {
        server.restart()
        return json(HttpResponse.status(200, "OK"), JSONObject().put("ok", true))
    }

    private fun clearCache(request: HttpRequest): HttpResponse {
        server.clearCache()
        return json(HttpResponse.status(200, "OK"), JSONObject().put("ok", true))
    }

    private fun json(response: HttpResponse, payload: JSONObject): HttpResponse {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        return response.apply {
            header("Content-Type", "application/json; charset=utf-8")
            header("Content-Length", bytes.size.toString())
            bodyBytes = bytes
        }
    }

    private fun readBody(request: HttpRequest): String {
        val length = request.header("content-length")?.toIntOrNull() ?: return ""
        if (length <= 0) return ""
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = request.body.read(buffer, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return String(buffer, 0, offset, Charsets.UTF_8)
    }

    private fun parseForm(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        body.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val keyValue = pair.split('=', limit = 2)
            @Suppress("DEPRECATION")
            val key = java.net.URLDecoder.decode(keyValue[0], "UTF-8")
            @Suppress("DEPRECATION")
            val value = if (keyValue.size > 1) java.net.URLDecoder.decode(keyValue[1], "UTF-8") else ""
            result[key] = value
        }
        return result
    }

    private fun parseRange(range: String, total: Long): Pair<Long, Long>? {
        if (range.contains(',')) return null
        val separator = range.indexOf('-')
        if (separator < 0) return null
        val startText = range.substring(0, separator).trim()
        val endText = range.substring(separator + 1).trim()
        if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull() ?: return null
            if (suffix <= 0L || total <= 0L) return null
            return ((total - suffix).coerceAtLeast(0L)) to (total - 1)
        }
        val start = startText.toLongOrNull() ?: return null
        if (start < 0L || total <= 0L || start >= total) return null
        val end = if (endText.isEmpty()) total - 1 else (endText.toLongOrNull() ?: return null)
        if (end < start) return null
        return start to end.coerceAtMost(total - 1)
    }

    private fun gone() = json(HttpResponse.status(500, "Internal Server Error"), JSONObject().put("error", "Operation failed."))

    private fun rangeNotSatisfiable(total: Long) = HttpResponse.status(416, "Range Not Satisfiable").apply {
        header("Content-Range", "bytes */$total")
        header("Content-Length", "0")
    }

    private fun escapeHeader(value: String) =
        value.map { character ->
            when (character) {
                '"', '\\', '\n', '\r' -> '_'
                else -> character
            }
        }.joinToString("")
}
