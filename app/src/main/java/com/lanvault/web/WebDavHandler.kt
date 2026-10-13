package com.lanvault.web

import com.lanvault.net.EventLog
import com.lanvault.net.HttpRequest
import com.lanvault.net.HttpResponse
import com.lanvault.net.formatHttpDate
import com.lanvault.settings.SettingsStore
import com.lanvault.storage.SafePath
import com.lanvault.storage.StorageEntry
import com.lanvault.storage.VaultFileSystem
import com.lanvault.storage.VaultNode
import com.lanvault.storage.slug
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val COPY_BUFFER_SIZE = 128 * 1024

/**
 * RFC 4918 WebDAV endpoint, plus HTTP range serving so media plays straight from the TV.
 * Use a WebDAV-compatible client; the native iOS Files "Connect to Server" flow rejects this
 * plain HTTP URL. LANVault deliberately does not implement SMB.
 */
class WebDavHandler(
    private val fileSystem: VaultFileSystem,
    private val settings: SettingsStore,
) {

    private val iso8601 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val allowedMethods =
        "OPTIONS, GET, HEAD, POST, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK"

    private val lockToken = "opaquelocktoken:lanvault-${System.currentTimeMillis()}"

    fun handle(request: HttpRequest): HttpResponse = when (request.method) {
        "OPTIONS" -> options()
        "PROPFIND" -> propfind(request)
        "PROPPATCH" -> proppatch()
        "GET" -> readFileOrDirectory(request, withBody = true)
        "HEAD" -> readFileOrDirectory(request, withBody = false)
        "PUT" -> put(request)
        "DELETE" -> delete(request.path)
        "MKCOL" -> mkcol(request.path)
        "MOVE" -> move(request)
        "COPY" -> copy(request)
        "LOCK" -> lock(request.path)
        "UNLOCK" -> HttpResponse.status(204, "No Content")
        "REPORT" -> HttpResponse.status(207, "Multi-Status").apply {
            header("Content-Type", "application/xml; charset=utf-8")
            bodyBytes = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:"></D:multistatus>""".toByteArray(Charsets.UTF_8)
        }
        else -> HttpResponse.status(405, "Method Not Allowed").apply {
            header("Allow", allowedMethods)
            header("Content-Type", "text/plain; charset=utf-8")
            bodyBytes = "Method Not Allowed".toByteArray(Charsets.UTF_8)
        }
    }

    private fun options(): HttpResponse = HttpResponse.status(200, "OK").apply {
        if (!settings.current().shareWebdav) {
            header("Content-Type", "text/plain; charset=utf-8")
            bodyBytes = "WebDAV sharing is disabled in the LANVault settings.".toByteArray(Charsets.UTF_8)
            return@apply
        }
        header("Allow", allowedMethods)
        header("DAV", "1, 2")
        header("MS-Author-Via", "DAV")
        header("Accept-Ranges", "bytes")
        header("Content-Length", "0")
    }

    private fun propfind(request: HttpRequest): HttpResponse {
        val depth = request.header("Depth")?.lowercase(Locale.ROOT) ?: "1"
        val requestedProps = parsePropNames(readBody(request))
        val resolved = fileSystem.resolve(request.path) ?: return notFound()

        val resources = ArrayList<VaultNode>()
        resources += resolved
        val recursive = depth == "infinity"
        if (depth != "0") collectDescendants(request.path, resources, recursive)

        val body = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
            append("""<D:multistatus xmlns:D="DAV:">""").append('\n')
            resources.forEach { append(renderResponse(it, requestedProps)) }
            append("</D:multistatus>")
        }

        EventLog.shared.http("PROPFIND ${request.rawTarget} 207 (${resources.size})")
        return HttpResponse.status(207, "Multi-Status").apply {
            header("Content-Type", "application/xml; charset=utf-8")
            header("Content-Length", body.toByteArray(Charsets.UTF_8).size.toString())
            bodyBytes = body.toByteArray(Charsets.UTF_8)
        }
    }

    private fun collectDescendants(path: String, into: MutableList<VaultNode>, recursive: Boolean) {
        val snapshot = fileSystem.snapshot()
        val children = snapshot.children(path, settings.current().showHiddenFiles) ?: return
        children.forEach { child ->
            val node = snapshot.resolve(child.path) ?: return@forEach
            into += node
            if (recursive && child.isDirectory) collectDescendants(child.path, into, recursive)
        }
    }

    private fun renderResponse(node: VaultNode, requestedProps: Set<String>): String {
        val href = hrefFor(node)
        val props = propsFor(node, requestedProps)
        return buildString {
            append("  <D:response>\n")
            append("    <D:href>").append(href).append("</D:href>\n")
            if (props.isEmpty()) {
                append("    <D:propstat>\n      <D:prop/>\n")
                append("      <D:status>HTTP/1.1 404 Not Found</D:status>\n    </D:propstat>\n")
            } else {
                append("    <D:propstat>\n      <D:prop>\n")
                props.forEach { (name, value) ->
                    append("        <D:").append(name).append('>').append(value)
                        .append("</D:").append(name).append(">\n")
                }
                append("      </D:prop>\n      <D:status>HTTP/1.1 200 OK</D:status>\n    </D:propstat>\n")
            }
            append("  </D:response>\n")
        }
    }

    private fun hrefFor(node: VaultNode): String = encodeUriPath(
        when (node) {
            is VaultNode.VolumeRoot -> "/"
            is VaultNode.VolumeDirectory -> "/${node.volume.slug()}/"
            is VaultNode.FileNode ->
                if (node.entry.isDirectory) "/${node.volume.slug()}/${node.relativePath}/"
                else "/${node.volume.slug()}/${node.relativePath}"
        },
    )

    private fun propsFor(node: VaultNode, requestedProps: Set<String>): List<Pair<String, String>> {
        fun wants(name: String) = requestedProps.isEmpty() || name in requestedProps

        val displayName: String
        val isDirectory: Boolean
        val size: Long
        val modified: Long
        val mime: String

        when (node) {
            is VaultNode.VolumeRoot -> {
                displayName = settings.current().deviceName
                isDirectory = true
                size = 0L
                modified = System.currentTimeMillis()
                mime = ""
            }

            is VaultNode.VolumeDirectory -> {
                displayName = node.volume.displayName
                isDirectory = true
                size = 0L
                modified = System.currentTimeMillis()
                mime = ""
            }

            is VaultNode.FileNode -> {
                displayName = node.entry.name
                isDirectory = node.entry.isDirectory
                size = node.entry.sizeBytes
                modified = if (node.entry.lastModified > 0L) {
                    node.entry.lastModified
                } else {
                    System.currentTimeMillis()
                }
                mime = if (isDirectory) "" else MimeTypes.forName(node.entry.name)
            }
        }

        val props = ArrayList<Pair<String, String>>()
        if (wants("displayname")) props += "displayname" to escapeXml(displayName)
        if (wants("resourcetype")) {
            props += "resourcetype" to if (isDirectory) "<D:collection/>" else ""
        }
        if (wants("getcontentlength") && !isDirectory) props += "getcontentlength" to size.toString()
        if (wants("getcontenttype") && !isDirectory && mime.isNotEmpty() && mime != "application/octet-stream") {
            props += "getcontenttype" to escapeXml(mime)
        }
        if (wants("getlastmodified")) props += "getlastmodified" to formatHttpDate(modified)
        if (wants("getetag")) {
            props += "getetag" to escapeXml("\"${Integer.toHexString((displayName + modified).hashCode())}\"")
        }
        if (wants("creationdate")) props += "creationdate" to iso8601.format(Date(modified))
        if (wants("supportedlock")) {
            props += "supportedlock" to
                "<D:lockentry><D:lockscope><D:exclusive/></D:lockscope>" +
                "<D:locktype><D:write/></D:locktype></D:lockentry>"
        }
        if (wants("quota-available-bytes") && node is VaultNode.VolumeDirectory) {
            props += "quota-available-bytes" to node.volume.volume?.availableBytes().toString()
        }
        return props
    }

    private fun proppatch() = HttpResponse.status(207, "Multi-Status").apply {
        header("Content-Type", "application/xml; charset=utf-8")
        bodyBytes = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/</D:href>
    <D:propstat>
      <D:prop/>
      <D:status>HTTP/1.1 403 Forbidden</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>""".toByteArray(Charsets.UTF_8)
    }

    private fun readFileOrDirectory(request: HttpRequest, withBody: Boolean): HttpResponse {
        val resolved = fileSystem.resolve(request.path) ?: return notFound()
        when (resolved) {
            is VaultNode.VolumeRoot, is VaultNode.VolumeDirectory -> {
                val html = directoryHtmlBody(request.path) ?: return notFound()
                val encoded = html.toByteArray(Charsets.UTF_8)
                return HttpResponse.status(200, "OK").apply {
                    header("Content-Type", "text/html; charset=utf-8")
                    header("Content-Length", encoded.size.toString())
                    if (withBody) bodyBytes = encoded
                }
            }

            is VaultNode.FileNode -> {
                if (resolved.entry.isDirectory) {
                    val html = directoryHtmlBody(request.path) ?: return notFound()
                    val encoded = html.toByteArray(Charsets.UTF_8)
                    return HttpResponse.status(200, "OK").apply {
                        header("Content-Type", "text/html; charset=utf-8")
                        header("Content-Length", encoded.size.toString())
                        if (withBody) bodyBytes = encoded
                    }
                }
                val volume = resolved.volume.volume ?: return notFound()
                val mime = MimeTypes.forName(resolved.entry.name)
                val totalBytes = resolved.entry.sizeBytes
                val rangeHeader = request.header("Range")

                val effectiveRange = rangeHeader?.takeIf { it.startsWith("bytes=") }
                if (effectiveRange != null) {
                    val bounds = parseRange(effectiveRange.removePrefix("bytes=").trim(), totalBytes)
                        ?: return rangeNotSatisfiable(totalBytes)
                    val source = volume.openReadSource(resolved.relativePath, bounds.first) ?: return serverError()
                    val length = (bounds.second - bounds.first + 1)
                        .coerceAtMost(if (source.lengthBytes >= 0) source.lengthBytes else bounds.second - bounds.first + 1)
                        .coerceAtLeast(0L)
                    if (length <= 0L) {
                        source.close()
                        return rangeNotSatisfiable(totalBytes)
                    }
                    EventLog.shared.http("GET ${request.rawTarget} 206 ${bounds.first}-${bounds.second}/$totalBytes")
                    return if (withBody) {
                        HttpResponse.status(206, "Partial Content").apply {
                            header("Content-Type", mime)
                            header("Accept-Ranges", "bytes")
                            header("Content-Range", "bytes ${bounds.first}-${bounds.second}/$totalBytes")
                            header("Content-Length", length.toString())
                            bodyStream = source.stream
                            contentLength = length
                        }
                    } else {
                        source.close()
                        HttpResponse.status(206, "Partial Content").apply {
                            header("Content-Type", mime)
                            header("Accept-Ranges", "bytes")
                            header("Content-Range", "bytes ${bounds.first}-${bounds.second}/$totalBytes")
                            header("Content-Length", length.toString())
                        }
                    }
                }

                val source = volume.openReadSource(resolved.relativePath, 0L) ?: return serverError()
                val length = if (totalBytes > 0) totalBytes else source.lengthBytes
                val lengthHeader = if (length >= 0) length.toString() else null
                return if (withBody) {
                    HttpResponse.status(200, "OK").apply {
                        header("Content-Type", mime)
                        header("Accept-Ranges", "bytes")
                        header("Content-Length", lengthHeader ?: "0")
                        header("Last-Modified", formatHttpDate(resolved.entry.lastModified))
                        bodyStream = source.stream
                        contentLength = lengthHeader?.toLong() ?: -1L
                    }
                } else {
                    source.close()
                    HttpResponse.status(200, "OK").apply {
                        header("Content-Type", mime)
                        header("Accept-Ranges", "bytes")
                        header("Content-Length", lengthHeader ?: "0")
                        header("Last-Modified", formatHttpDate(resolved.entry.lastModified))
                    }
                }
            }
        }
    }

    private fun directoryHtmlBody(path: String): String? {
        val entries = fileSystem.snapshot().children(path, settings.current().showHiddenFiles) ?: return null
        val builder = StringBuilder()
        builder.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>LANVault</title>")
        builder.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        builder.append("<style>body{font-family:sans-serif;background:#0b0f14;color:#e2e8f0;margin:2rem}")
        builder.append("a{color:#4ade80;text-decoration:none}li{margin:.35rem 0}h2{font-weight:500}</style></head><body>")
        builder.append("<h2>").append(escapeXml(path)).append("</h2><ul>")
        if (path != "/") builder.append("<li><a href=\"../\">../</a></li>")
        entries.forEach { entry ->
            val href = if (path == "/") "/${entry.name}" else "$path/${entry.name}"
            builder.append("<li><a href=\"").append(href).append("\">")
            builder.append(escapeXml(entry.name)).append(if (entry.isDirectory) "/" else "").append("</a></li>")
        }
        builder.append("</ul><p>Web interface: <a href=\"/ui/\">/ui/</a></p></body></html>")
        return builder.toString()
    }

    private fun put(request: HttpRequest): HttpResponse {
        if (!settings.current().allowUploads) {
            return HttpResponse.status(403, "Forbidden").apply {
                header("Content-Type", "text/plain; charset=utf-8")
                bodyBytes = "Uploads are disabled in the LANVault settings.".toByteArray(Charsets.UTF_8)
            }
        }
        val target = resolveWriteTarget(request.path) ?: return notFound()
        val volume = target.volume.volume ?: return notFound()
        if (!volume.isWritable) return forbidden("This volume is read-only.")

        val relativePath = target.relativePath
        val parent = SafePath.parent(relativePath)
        val existed = volume.stat(relativePath) != null
        if (!existed) {
            if (parent.isNotEmpty() && volume.stat(parent)?.isDirectory != true) return conflict()
            if (!volume.createFile(parent, SafePath.fileName(relativePath))) return serverError()
        }

        val output = volume.openWriteSource(relativePath, append = false) ?: return serverError()
        val declared = request.header("Content-Length")?.toLongOrNull()
        val written = output.use { destination -> request.body.copyTo(destination, COPY_BUFFER_SIZE) }
        if (declared != null && written != declared) {
            runCatching { volume.delete(relativePath) }
            EventLog.shared.warn("PUT ${request.rawTarget} aborted after $written of $declared bytes")
            return HttpResponse.status(400, "Bad Request").apply {
                header("Content-Type", "text/plain; charset=utf-8")
                bodyBytes = ("Transfer stopped after $written of $declared bytes, nothing was saved.")
                    .toByteArray(Charsets.UTF_8)
            }
        }
        EventLog.shared.http("PUT ${request.rawTarget} ${if (existed) "204" else "201"} ($written bytes)")
        return if (existed) HttpResponse.status(204, "No Content") else HttpResponse.status(201, "Created")
    }

    private fun delete(path: String): HttpResponse {
        if (!settings.current().allowDeletes) return forbidden("Deletion is disabled in the LANVault settings.")
        val target = resolveWriteTarget(path) ?: return notFound()
        val volume = target.volume.volume ?: return notFound()
        if (!volume.isWritable) return forbidden("This volume is read-only.")
        return if (volume.delete(target.relativePath)) {
            EventLog.shared.http("DELETE $path 204")
            HttpResponse.status(204, "No Content")
        } else {
            notFound()
        }
    }

    private fun mkcol(path: String): HttpResponse {
        if (!settings.current().allowCreateFolders) {
            return forbidden("Creating folders is disabled in the LANVault settings.")
        }
        val target = resolveWriteTarget(path) ?: return notFound()
        val volume = target.volume.volume ?: return notFound()
        if (!volume.isWritable) return forbidden("This volume is read-only.")
        if (volume.stat(target.relativePath) != null) {
            return HttpResponse.status(405, "Forbidden").also { }
        }
        val parent = SafePath.parent(target.relativePath)
        if (parent.isNotEmpty() && volume.stat(parent)?.isDirectory != true) return conflict()
        return if (volume.createDirectory(parent, SafePath.fileName(target.relativePath))) {
            HttpResponse.status(201, "Created")
        } else {
            serverError()
        }
    }

    private fun move(request: HttpRequest): HttpResponse {
        if (!settings.current().allowUploads) return forbidden("Write access is disabled.")
        val destinationHeader = request.header("Destination") ?: return badRequest("Destination header is missing.")
        val destinationPath = UriHelper.pathOf(destinationHeader) ?: return badRequest("Destination header is invalid.")

        val source = resolveWriteTarget(request.path) ?: return notFound()
        val destination = resolveWriteTarget(destinationPath) ?: return notFound()
        val sourceVolume = source.volume.volume ?: return notFound()
        val destinationVolume = destination.volume.volume ?: return notFound()
        if (!sourceVolume.isWritable) return forbidden("Source volume is read-only.")
        if (!destinationVolume.isWritable) return forbidden("Destination volume is read-only.")

        val sourceEntry = sourceVolume.stat(source.relativePath) ?: return notFound()
        val destinationParent = SafePath.parent(destination.relativePath)
        if (destinationVolume.stat(destinationParent)?.isDirectory != true) return conflict()
        if (source.volume.slug() == destination.volume.slug() && !sourceEntry.isDirectory) {
            val sourceParent = SafePath.parent(source.relativePath)
            if (sourceParent == destinationParent) {
                val renamed = sourceVolume.rename(source.relativePath, SafePath.fileName(destination.relativePath))
                return if (renamed) HttpResponse.status(201, "Created") else conflict()
            }
        }
        if (sourceEntry.isDirectory) return conflict("Moving folders between locations is not supported.")
        if (!destinationVolume.createFile(destinationParent, SafePath.fileName(destination.relativePath))) {
            return conflict()
        }
        if (!copyBetween(sourceVolume, source.relativePath, destinationVolume, destination.relativePath)) {
            return serverError()
        }
        if (!sourceVolume.delete(source.relativePath)) return serverError()
        EventLog.shared.http("MOVE ${request.rawTarget} -> $destinationPath 201")
        return HttpResponse.status(201, "Created")
    }

    private fun copy(request: HttpRequest): HttpResponse {
        if (!settings.current().allowUploads) return forbidden("Write access is disabled.")
        val destinationHeader = request.header("Destination") ?: return badRequest("Destination header is missing.")
        val destinationPath = UriHelper.pathOf(destinationHeader) ?: return badRequest("Destination header is invalid.")

        val source = resolveWriteTarget(request.path) ?: return notFound()
        val destination = resolveWriteTarget(destinationPath) ?: return notFound()
        val sourceVolume = source.volume.volume ?: return notFound()
        val destinationVolume = destination.volume.volume ?: return notFound()
        if (!destinationVolume.isWritable) return forbidden("Destination volume is read-only.")

        val sourceEntry = sourceVolume.stat(source.relativePath) ?: return notFound()
        if (sourceEntry.isDirectory) return conflict("Copying folders is not supported.")
        val destinationParent = SafePath.parent(destination.relativePath)
        if (destinationVolume.stat(destinationParent)?.isDirectory != true) return conflict()
        if (!destinationVolume.createFile(destinationParent, SafePath.fileName(destination.relativePath))) {
            return conflict()
        }
        if (!copyBetween(sourceVolume, source.relativePath, destinationVolume, destination.relativePath)) {
            return serverError()
        }
        return HttpResponse.status(204, "No Content")
    }

    private fun copyBetween(
        sourceVolume: com.lanvault.storage.StorageVolume,
        sourcePath: String,
        destinationVolume: com.lanvault.storage.StorageVolume,
        destinationPath: String,
    ): Boolean {
        val sourceStream = sourceVolume.openReadSource(sourcePath, 0L) ?: return false
        val output = destinationVolume.openWriteSource(destinationPath, append = false)
        if (output == null) {
            sourceStream.close()
            return false
        }
        output.use { target ->
            sourceStream.use { source -> source.stream.copyTo(target, COPY_BUFFER_SIZE) }
        }
        return true
    }

    private fun lock(path: String): HttpResponse {
        val href = encodeUriPath(if (path.endsWith("/")) path else "$path/")
        val body = """<?xml version="1.0" encoding="utf-8"?>
<D:prop xmlns:D="DAV:">
  <D:lockdiscovery>
    <D:activelock>
      <D:locktype><D:write/></D:locktype>
      <D:lockscope><D:exclusive/></D:lockscope>
      <D:depth>infinity</D:depth>
      <D:timeout>Second-3600</D:timeout>
      <D:locktoken><D:href>$lockToken</D:href></D:locktoken>
    </D:activelock>
  </D:lockdiscovery>
</D:prop>"""
        return HttpResponse.status(200, "OK").apply {
            header("Content-Type", "application/xml; charset=utf-8")
            header("Lock-Token", "<$lockToken>")
            bodyBytes = body.toByteArray(Charsets.UTF_8)
        }
    }

    private fun resolveWriteTarget(path: String): VaultNode.FileNode? {
        val snapshot = fileSystem.snapshot()
        val segments = SafePath.segments(path)
        if (segments.isEmpty()) return null
        val volume = snapshot.find(segments.first()) ?: return null
        val relativePath = segments.drop(1).joinToString("/")
        if (relativePath.isEmpty()) return null
        return VaultNode.FileNode(
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

    private fun notFound() = simpleStatus(404, "Not Found")

    private fun conflict(message: String = "Conflict") = simpleStatus(409, message)

    private fun forbidden(message: String) = simpleStatus(403, message)

    private fun badRequest(message: String) = simpleStatus(400, message)

    private fun serverError() = simpleStatus(500, "Internal Server Error")

    private fun rangeNotSatisfiable(total: Long) = HttpResponse.status(416, "Range Not Satisfiable").apply {
        header("Content-Range", "bytes */$total")
        header("Content-Length", "0")
    }

    private fun simpleStatus(code: Int, message: String) = HttpResponse.status(code).apply {
        header("Content-Type", "text/plain; charset=utf-8")
        bodyBytes = message.toByteArray(Charsets.UTF_8)
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
        if (start < 0L) return null
        if (total <= 0L || start >= total) return null
        val end = if (endText.isEmpty()) total - 1 else (endText.toLongOrNull() ?: return null)
        if (end < start) return null
        return start to end.coerceAtMost(total - 1)
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

    private fun parsePropNames(xml: String): Set<String> {
        if (xml.isBlank()) return emptySet()
        val propfindStart = xml.indexOf("<propfind").takeIf { it >= 0 } ?: 0
        val closeIndex = xml.indexOf("</propfind>")
        val region = xml.substring(
            propfindStart,
            if (closeIndex > propfindStart) closeIndex + "</propfind>".length else xml.length,
        )
        if (region.contains("<allprop", ignoreCase = true)) return emptySet()
        if (region.contains("<propname", ignoreCase = true)) return emptySet()
        if (region.contains("<include", ignoreCase = true)) return emptySet()

        val names = LinkedHashSet<String>()
        val pattern = Regex("""<(?:[A-Za-z0-9_-]+:)?([A-Za-z0-9_-]+)[\s/>]""")
        pattern.findAll(region).forEach { match ->
            val name = match.groupValues[1]
            if (name !in RESERVED_PROP_TAGS) names += name
        }
        return names
    }

    private companion object {
        val RESERVED_PROP_TAGS = setOf("propfind", "prop", "allprop", "include", "propname")
    }

    private fun escapeXml(value: String) = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }
}

private val safeCodePoints: Set<Int> =
    (('A'..'Z') + ('a'..'z') + ('0'..'9')).map { it.code }.toSet() +
        listOf('-', '_', '.', '~', '!', '$', '\'', '(', ')', '*', '+', ',', ';', '=').map { it.code }

fun encodeUriPath(path: String): String {
    val builder = StringBuilder(path.length + 16)
    var index = 0
    while (index < path.length) {
        val codePoint = path.codePointAt(index)
        val character = path[index]
        when {
            character == '/' -> builder.append('/')
            character == ':' -> builder.append(':')
            codePoint in safeCodePoints -> builder.appendCodePoint(codePoint)
            else -> {
                String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).forEach { byte ->
                    builder.append('%')
                    val hex = (byte.toInt() and 0xFF).toString(16).uppercase(Locale.ROOT)
                    if (hex.length == 1) builder.append('0')
                    builder.append(hex)
                }
            }
        }
        index += Character.charCount(codePoint)
    }
    return builder.toString()
}

/** Parses a WebDAV Destination header back into a request path. */
object UriHelper {
    fun pathOf(headerValue: String): String? {
        val trimmed = headerValue.trim().trimStart('<').trimEnd('>')
        if (trimmed.isEmpty()) return null
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        val rawPath = uri.path?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            java.net.URLDecoder.decode(rawPath, "UTF-8")
        }.getOrNull() ?: rawPath
    }
}
