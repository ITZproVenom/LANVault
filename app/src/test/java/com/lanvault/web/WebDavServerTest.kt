package com.lanvault.web

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.lanvault.net.EventLog
import com.lanvault.net.LiteHttpServer
import com.lanvault.settings.SettingsStore
import com.lanvault.storage.LocalStorageVolume
import com.lanvault.storage.StorageRepository
import com.lanvault.storage.VolumeKind
import com.lanvault.storage.VaultFileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Exercises the WebDAV surface plus HTTP range serving - the two things the iPhone Files app
 * and the web video player depend on. Uses raw sockets because the JDK's HttpURLConnection
 * refuses WebDAV methods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class WebDavServerTest {

    private lateinit var root: File
    private lateinit var server: LiteHttpServer
    private val volumeId = "tv-storage"

    @Before
    fun setUp() {
        EventLog.shared.clear()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settingsStore = SettingsStore(context)
        root = File.createTempFile("lanvault-test", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
        val volume = LocalStorageVolume("internal", "TV Storage", VolumeKind.INTERNAL, root, canWrite = true)
        val repository = TestStorageRepository(context, volume)
        val fileSystem = VaultFileSystem(repository, settingsStore)
        val handler = WebDavHandler(fileSystem, settingsStore)
        server = LiteHttpServer(0, handler::handle)
        server.start()
        assertTrue(server.port > 0)
    }

    @After
    fun tearDown() {
        server.close()
        root.deleteRecursively()
    }

    private fun raw(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val port = server.port
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            val output = socket.getOutputStream()
            val payload = body?.toByteArray(StandardCharsets.UTF_8)
            val request = StringBuilder()
            request.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            request.append("Host: 127.0.0.1:").append(port).append("\r\n")
            headers.forEach { (key, value) -> request.append(key).append(": ").append(value).append("\r\n") }
            if (payload != null) request.append("Content-Length: ").append(payload.size).append("\r\n")
            request.append("Connection: close\r\n\r\n")
            output.write(request.toString().toByteArray(StandardCharsets.ISO_8859_1))
            if (payload != null) output.write(payload)
            output.flush()

            val input = socket.getInputStream().buffered()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(input, StandardCharsets.ISO_8859_1))
            val statusLine = reader.readLine() ?: error("no response")
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
            val responseHeaders = LinkedHashMap<String, String>()
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
                    val value = line.substring(separator + 1).trim()
                    responseHeaders[name] = value
                    if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
                }
            }
            // Read the body through the same reader: the JDK's buffering would otherwise
            // swallow bytes we then try to read from the raw stream.
            val body = buildString {
                var remaining = contentLength
                while (remaining > 0) {
                    val value = reader.read()
                    if (value < 0) break
                    append(value.toChar())
                    remaining -= 1
                }
            }
            return Response(code, body, responseHeaders)
        }
    }

    private fun get(path: String, range: String? = null, method: String = "GET"): Response {
        val connection = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (range != null) connection.setRequestProperty("Range", range)
        val code = connection.responseCode
        val body = (if (code >= 400) connection.errorStream else connection.inputStream)
            ?.bufferedReader(StandardCharsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
        val headers = connection.headerFields.mapNotNull { (name, values) ->
            if (name == null) null else name.lowercase(Locale.ROOT) to values.joinToString(", ")
        }.toMap()
        connection.disconnect()
        return Response(code, body, headers)
    }

    private fun putBinary(path: String, payload: ByteArray): Response {
        val connection = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(payload.size)
        connection.outputStream.use { it.write(payload) }
        val code = connection.responseCode
        connection.disconnect()
        return Response(code, "", emptyMap())
    }

    private fun readBytes(path: String, range: String? = null): Pair<Int, ByteArray> {
        val connection = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        if (range != null) connection.setRequestProperty("Range", range)
        val code = connection.responseCode
        val bytes = (connection.inputStream ?: connection.errorStream).use { it.readBytes() }
        connection.disconnect()
        return code to bytes
    }

    private fun options(): Response = raw("OPTIONS", "/")

    private fun propfind(path: String, depth: String, body: String? = null): Response =
        raw("PROPFIND", path, body, mapOf("Depth" to depth))

    /* ------------------------------------------------------------------ discovery */

    @Test
    fun `options advertises webdav classes and write methods`() {
        val response = options()
        assertEquals(200, response.code)
        assertTrue(response.headers["dav"].orEmpty().contains("1"))
        assertTrue(response.headers["allow"].orEmpty().contains("PROPFIND"))
        assertTrue(response.headers["allow"].orEmpty().contains("PUT"))
        assertTrue(response.headers["allow"].orEmpty().contains("MOVE"))
        assertTrue(response.headers["allow"].orEmpty().contains("LOCK"))
    }

    @Test
    fun `propfind on root lists the internal volume as a collection`() {
        val response = propfind("/", "1")
        assertEquals(207, response.code)
        assertEquals("application/xml; charset=utf-8", response.headers["content-type"])
        assertTrue(response.body.contains("<D:multistatus"))
        assertTrue(response.body.contains("<D:collection/>"))
        assertTrue(response.body.contains("TV Storage"))
        assertTrue(response.body.contains("/$volumeId/"))
        assertTrue(response.body.contains("<D:displayname>TV Storage</D:displayname>"))
    }

    @Test
    fun `propfind depth zero omits children`() {
        val response = propfind("/", "0")
        assertEquals(207, response.code)
        assertTrue(response.body.contains("<D:displayname>Android TV</D:displayname>"))
        assertTrue(!response.body.contains("TV Storage"))
    }

    @Test
    fun `propfind honours requested props only`() {
        val requestBody = "<?xml version=\"1.0\"?><D:propfind xmlns:D=\"DAV:\"><D:prop>" +
            "<D:getcontentlength/><D:getlastmodified/></D:prop></D:propfind>"
        val response = propfind("/", "0", requestBody)
        assertEquals(207, response.code)
        assertTrue(response.body.contains("<D:getlastmodified>"))
        assertTrue(!response.body.contains("<D:displayname>"))
    }

    @Test
    fun `propfind on the volume lists its children`() {
        propfind("/", "1")
        raw("PUT", "/$volumeId/photo.jpg", "binary-ish")
        val response = propfind("/$volumeId", "1")
        assertEquals(207, response.code)
        assertTrue(response.body.contains("photo.jpg"))
        assertTrue(response.body.contains("<D:href>/$volumeId/photo.jpg</D:href>"))
        assertTrue(response.body.contains("<D:getcontenttype>image/jpeg</D:getcontenttype>"))
    }

    @Test
    fun `missing path returns 404 with the dav error shape`() {
        val response = propfind("/does-not-exist", "0")
        assertEquals(404, response.code)
        assertEquals("text/plain; charset=utf-8", response.headers["content-type"])
    }

    /* ------------------------------------------------------------------ transfer */

    @Test
    fun `upload, read back and range read a file`() {
        val content = "0123456789"
        assertEquals(201, raw("PUT", "/$volumeId/hello.txt", content).code)

        val full = get("/$volumeId/hello.txt")
        assertEquals(200, full.code)
        assertEquals(content, full.body)
        assertEquals("text/plain; charset=utf-8", full.headers["content-type"])
        assertEquals("bytes", full.headers["accept-ranges"])

        val partial = get("/$volumeId/hello.txt", range = "bytes=2-5")
        assertEquals(206, partial.code)
        assertEquals("2345", partial.body)
        assertEquals("bytes 2-5/10", partial.headers["content-range"])
        assertEquals("4", partial.headers["content-length"])

        val head = get("/$volumeId/hello.txt", method = "HEAD")
        assertEquals(200, head.code)
        assertEquals("10", head.headers["content-length"])
        assertEquals("", head.body)
    }

    @Test
    fun `browser style open ended ranges work for video`() {
        raw("PUT", "/$volumeId/clip.mp4", "0123456789")
        val openEnded = raw("GET", "/$volumeId/clip.mp4", headers = mapOf("Range" to "bytes=0-"))
        assertEquals(206, openEnded.code)
        assertEquals("bytes 0-9/10", openEnded.headers["content-range"])
        assertEquals("0123456789", openEnded.body)

        val firstTwo = raw("GET", "/$volumeId/clip.mp4", headers = mapOf("Range" to "bytes=0-1"))
        assertEquals(206, firstTwo.code)
        assertEquals("bytes 0-1/10", firstTwo.headers["content-range"])
        assertEquals("01", firstTwo.body)
    }

    @Test
    fun `suffix ranges and out of range requests are handled`() {
        raw("PUT", "/$volumeId/data.bin", "abcdefghij")
        val suffix = get("/$volumeId/data.bin", range = "bytes=-3")
        assertEquals(206, suffix.code)
        assertEquals("hij", suffix.body)

        val beyond = get("/$volumeId/data.bin", range = "bytes=100-200")
        assertEquals(416, beyond.code)
        assertTrue(beyond.headers["content-range"].orEmpty().startsWith("bytes */"))
    }

    @Test
    fun `a 2 megabyte payload survives a range read`() {
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        assertEquals(201, putBinary("/$volumeId/big.bin", payload).code)

        val offset = 512 * 1024
        val length = 256 * 1024
        val (code, bytes) = readBytes("/$volumeId/big.bin", "bytes=$offset-${offset + length - 1}")
        assertEquals(206, code)
        assertEquals(length, bytes.size)
        assertTrue(bytes.contentEquals(payload.copyOfRange(offset, offset + length)))
    }

    @Test
    fun `video files report a video mime type and stream ranges`() {
        raw("PUT", "/$volumeId/clip.mkv", "0123456789abcdef")
        val response = get("/$volumeId/clip.mkv", range = "bytes=4-9")
        assertEquals(206, response.code)
        assertEquals("video/x-matroska", response.headers["content-type"])
        assertEquals("456789", response.body)
    }

    @Test
    fun `path traversal cannot escape the volume`() {
        val status = raw("PUT", "/$volumeId/..%2F..%2Fescape.txt", "pwned").code
        // The traversal is collapsed to a path inside the served volume, so no response
        // may ever place a file outside it.
        assertTrue(status in 200..299 || status in setOf(400, 404, 409))
        assertTrue(!File(root.parentFile, "escape.txt").exists())
        assertTrue(!File("/escape.txt").exists())
    }

    @Test
    fun `uploads outside any volume are rejected`() {
        assertEquals(404, raw("PUT", "/no-such-volume/nope.txt", "x").code)
    }

    /* ------------------------------------------------------------------ webdav mutations */

    @Test
    fun `mkdir move copy and delete`() {
        raw("PUT", "/$volumeId/movie.mkv", "video-bytes")

        assertEquals(201, raw("MKCOL", "/$volumeId/series").code)
        assertTrue(File(root, "series").isDirectory)

        val move = raw("MOVE", "/$volumeId/movie.mkv", headers = mapOf("Destination" to "http://127.0.0.1:${server.port}/$volumeId/renamed.mkv"))
        assertEquals(201, move.code)
        assertTrue(File(root, "renamed.mkv").exists())
        assertTrue(!File(root, "movie.mkv").exists())

        val copy = raw("COPY", "/$volumeId/renamed.mkv", headers = mapOf("Destination" to "http://127.0.0.1:${server.port}/$volumeId/series/ep1.mkv"))
        assertEquals(204, copy.code)
        assertEquals("video-bytes", File(File(root, "series"), "ep1.mkv").readText())

        val delete = raw("DELETE", "/$volumeId/series")
        assertEquals(204, delete.code)
        assertTrue(!File(root, "series").exists())
    }

    @Test
    fun `lock and unlock are accepted by clients that lock before writing`() {
        val lock = raw("LOCK", "/$volumeId/one.txt")
        assertEquals(200, lock.code)
        assertTrue(lock.body.contains("<D:activelock>"))
        assertTrue(lock.body.contains("opaquelocktoken"))
        assertTrue(lock.headers["lock-token"].orEmpty().startsWith("<opaquelocktoken"))

        assertEquals(204, raw("UNLOCK", "/$volumeId/one.txt", headers = mapOf("Lock-Token" to lock.headers["lock-token"].orEmpty())).code)
    }

    @Test
    fun `proppatch answers with a multistatus`() {
        val response = raw("PROPPATCH", "/$volumeId/one.txt", "<D:propertyupdate xmlns:D=\"DAV:\"><D:set><D:prop><D:foo>1</D:foo></D:prop></D:set></D:propertyupdate>")
        assertEquals(207, response.code)
        assertTrue(response.body.contains("multistatus"))
    }

    @Test
    fun `report does not crash the server`() {
        val response = raw("REPORT", "/$volumeId/one.txt", "<D:sync-collection xmlns:D=\"DAV:\"/>")
        assertEquals(207, response.code)
    }

    @Test
    fun `unknown method is rejected with 405 and a method allow header`() {
        val response = raw("BREW", "/")
        assertEquals(405, response.code)
        assertTrue(response.headers["allow"].orEmpty().contains("PROPFIND"))
    }

    /* ------------------------------------------------------------------ directories */

    @Test
    fun `a directory get returns a browsable html listing`() {
        assertEquals(201, raw("MKCOL", "/$volumeId/series").code)
        val response = get("/$volumeId/series")
        assertEquals(200, response.code)
        assertEquals("text/html; charset=utf-8", response.headers["content-type"])
        assertTrue(response.body.contains("<h2>/tv-storage/series</h2>"))

        val rootListing = get("/$volumeId")
        assertEquals(200, rootListing.code)
        assertTrue(rootListing.body.contains("series/"))
    }

    @Test
    fun `a deleted file stops being served`() {
        raw("PUT", "/$volumeId/temp.txt", "temporary")
        assertEquals(200, get("/$volumeId/temp.txt").code)
        assertEquals(204, raw("DELETE", "/$volumeId/temp.txt").code)
        assertEquals(404, get("/$volumeId/temp.txt").code)
    }

    /* ------------------------------------------------------------------ protocol */

    @Test
    fun `keep alive serves multiple requests on one connection`() {
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 10_000
            val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
            )
            repeat(3) {
                writer.write("PROPFIND / HTTP/1.1\r\nHost: localhost\r\nDepth: 0\r\n\r\n")
                writer.flush()
                val statusLine = reader.readLine() ?: error("no status line")
                assertTrue(statusLine.contains("207"))
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0 && line.substring(0, separator).equals("Content-Length", ignoreCase = true)) {
                        contentLength = line.substring(separator + 1).trim().toInt()
                    }
                }
                val body = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = reader.read(body, read, contentLength - read)
                    if (count < 0) break
                    read += count
                }
                assertTrue(String(body).contains("<D:multistatus"))
            }
        }
    }

    @Test
    fun `chunked upload bodies are accepted`() {
        val port = server.port
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            val output = socket.getOutputStream()
            val request = "PUT /$volumeId/chunked.txt HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Connection: close\r\n\r\n"
            output.write(request.toByteArray(StandardCharsets.ISO_8859_1))
            val chunks = listOf("abcde", "fghij")
            chunks.forEach { chunk ->
                output.write((Integer.toHexString(chunk.length) + "\r\n").toByteArray())
                output.write(chunk.toByteArray(StandardCharsets.UTF_8))
                output.write("\r\n".toByteArray())
            }
            output.write("0\r\n\r\n".toByteArray())
            output.flush()
            val statusLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
            assertTrue(statusLine.contains("201") || statusLine.contains("204"))
        }
        assertEquals("abcdefghij", File(root, "chunked.txt").readText())
    }
}

/** Publishes only the volume under test. */
private class TestStorageRepository(
    context: android.content.Context,
    private val volume: LocalStorageVolume,
) : StorageRepository(context) {

    override fun discover(): List<com.lanvault.storage.DiscoveredVolume> = listOf(
        com.lanvault.storage.DiscoveredVolume(
            id = "internal",
            displayName = "TV Storage",
            kind = VolumeKind.INTERNAL,
            mountPath = volume.rootPath,
            readable = true,
            writable = true,
            needsPermission = false,
            volume = volume,
        ),
    )

    override fun hasAllFilesAccess(): Boolean = true
}

data class Response(val code: Int, val body: String, val headers: Map<String, String>)
