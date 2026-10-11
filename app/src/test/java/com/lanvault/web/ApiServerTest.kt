package com.lanvault.web

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.lanvault.net.EventLog
import com.lanvault.settings.Settings
import com.lanvault.settings.SettingsStore
import com.lanvault.storage.LocalStorageVolume
import com.lanvault.storage.StorageRepository
import com.lanvault.storage.VolumeKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.Base64
import java.util.Locale

/**
 * Covers the JSON API that backs the web interface and the /ui/ static asset serving.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ApiServerTest {

    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var server: VaultServer
    private lateinit var settingsStore: SettingsStore
    private var port = 0

    @Before
    fun setUp() {
        EventLog.shared.clear()
        context = ApplicationProvider.getApplicationContext()
        settingsStore = SettingsStore(context)
        settingsStore.resetToDefaults()
        // Ask the OS for a free port: a random one can collide with a port a previous test
        // method has not released yet, which makes assertions run against the wrong server.
        port = java.net.ServerSocket(0).use { it.localPort }
        settingsStore.update { it.copy(serverPort = port, requireAuth = false, password = "secret") }

        root = File.createTempFile("lanvault-api", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
        val volume = LocalStorageVolume("internal", "TV Storage", VolumeKind.INTERNAL, root, canWrite = true)
        val repository = FixedStorageRepository(context, volume)
        server = VaultServer(context, settingsStore, repository)
        server.start()
        assertTrue(server.isRunning)
    }

    @After
    fun tearDown() {
        server.stop()
        root.deleteRecursively()
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        credentials: Pair<String, String>? = null,
    ): RawResponse {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        if (credentials != null) {
            val token = Base64.getEncoder()
                .encodeToString("${credentials.first}:${credentials.second}".toByteArray(StandardCharsets.UTF_8))
            connection.setRequestProperty("Authorization", "Basic $token")
        }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
        }
        val code = connection.responseCode
        val text = (if (code >= 400) connection.errorStream else connection.inputStream)
            ?.bufferedReader(StandardCharsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
        val responseHeaders = connection.headerFields.mapNotNull { (name, values) ->
            if (name == null) null else name.lowercase(Locale.ROOT) to values.joinToString(", ")
        }.toMap()
        connection.disconnect()
        return RawResponse(code, text, responseHeaders)
    }

    private fun json(method: String, path: String, body: String? = null): RawResponse =
        request(method, path, body).also {
            assertEquals("application/json; charset=utf-8", it.headers["content-type"])
        }

    @Test
    fun `state reports the server, links and settings`() {
        val response = json("GET", "/api/state")
        assertEquals(200, response.code)
        assertTrue(response.body.contains("\"running\":true"))
        // Both the requested and the actually bound port must be reported so the web
        // interface can explain a fallback instead of showing a dead link.
        assertTrue(response.body.contains("\"port\":$port"))
        assertTrue(response.body.contains("\"requestedPort\":$port"))
        assertTrue(response.body.contains("\"portInUseOnPreferred\":false"))
        assertTrue(response.body.contains("\"port\":$port"))
        assertTrue(response.body.contains("tv-storage"))
        assertTrue(response.body.contains("\"allFilesAccess\":true"))
        assertTrue(response.body.contains("\"settings\""))
        // org.json escapes slashes, so assert on the decoded prefix only.
        assertTrue(response.body.contains("\"webInterfaceUrl\":\"http:"))
        assertTrue(response.body.contains("\"webdavUrl\":\"http:"))
    }

    @Test
    fun `volumes lists drives with capacity`() {
        val response = json("GET", "/api/volumes")
        assertEquals(200, response.code)
        assertTrue(response.body.contains("TV Storage"))
        assertTrue(response.body.contains("\"writable\":true"))
        assertTrue(response.body.contains("\"needsPermission\":false"))
        assertTrue(response.body.contains("\"availableBytes\""))
    }

    @Test
    fun `list returns entries with kinds`() {
        request("PUT", "/tv-storage/movie.mp4")
        File(root, "movie.mp4").writeText("video")
        File(root, "album").mkdirs()
        val response = json("GET", "/api/list?path=tv-storage")
        assertEquals(200, response.code)
        assertTrue(response.body.contains("\"kind\":\"folder\""))
        assertTrue(response.body.contains("\"kind\":\"video\""))
        assertTrue(response.body.contains("\"previewable\":true"))
    }

    @Test
    fun `files stream with range support and downloads force an attachment`() {
        File(root, "clip.mp4").writeText("0123456789")
        val stream = request("GET", "/api/file?path=tv-storage/clip.mp4")
        assertEquals("video/mp4", stream.headers["content-type"])
        assertEquals("bytes", stream.headers["accept-ranges"])

        val partial = rawSocket("/api/file?path=tv-storage/clip.mp4", mapOf("Range" to "bytes=3-6"))
        assertEquals(206, partial.code)
        assertEquals("bytes 3-6/10", partial.headers["content-range"])
        assertEquals("3456", partial.body)

        val download = rawSocket("/api/download?path=tv-storage/clip.mp4", emptyMap())
        assertTrue(download.headers["content-disposition"].orEmpty().startsWith("attachment;"))
        assertEquals("application/octet-stream", download.headers["content-type"])
    }

    @Test
    fun `upload writes to the chosen folder`() {
        File(root, "inbox").mkdirs()
        val response = request("PUT", "/api/put?path=tv-storage/inbox&name=photo.jpg", body = null)
        assertEquals(201, response.code)
        assertTrue(File(root, "inbox/photo.jpg").exists())
    }

    @Test
    fun `mkdir rename delete and move round trip through the api`() {
        assertEquals(201, request("POST", "/api/mkdir?path=tv-storage&name=archive").code)
        assertTrue(File(root, "archive").isDirectory)

        File(root, "note.txt").writeText("hello")
        assertEquals(200, request("POST", "/api/rename?path=tv-storage/note.txt&name=journal.txt").code)
        assertTrue(File(root, "journal.txt").exists())

        assertEquals(200, request("POST", "/api/move?from=tv-storage/journal.txt&to=tv-storage/archive/moved.txt").code)
        assertTrue(File(File(root, "archive"), "moved.txt").exists())
        assertTrue(!File(root, "journal.txt").exists())

        assertEquals(200, request("DELETE", "/api/delete?path=tv-storage/archive").code)
        assertTrue(!File(root, "archive").exists())
    }

    @Test
    fun `rename and delete report a clean error for missing files`() {
        assertEquals(404, request("POST", "/api/rename?path=tv-storage/ghost.txt&name=x.txt").code)
        assertEquals(404, request("DELETE", "/api/delete?path=tv-storage/ghost.txt").code)
    }

    @Test
    fun `settings can be changed from the web interface`() {
        val body = listOf(
            "deviceName=Living Room",
            "requireAuth=true",
            "username=lola",
            "password=hunter2",
            "allowUploads=false",
            "showHiddenFiles=true",
            "serverPort=$port",
        ).joinToString("&")
        val response = request("POST", "/api/settings", body)
        assertEquals(200, response.code)
        assertTrue(response.body.contains("Living Room"))

        val current = settingsStore.current()
        assertEquals("Living Room", current.deviceName)
        assertTrue(current.requireAuth)
        assertTrue(current.showHiddenFiles)
        assertFalse(current.allowUploads)
    }

    @Test
    fun `uploads are blocked once uploads are disabled`() {
        settingsStore.update { it.copy(allowUploads = false) }
        File(root, "inbox").mkdirs()
        val response = request("PUT", "/api/put?path=tv-storage/inbox&name=blocked.txt")
        assertEquals(403, response.code)
        assertTrue(!File(root, "inbox/blocked.txt").exists())
    }

    @Test
    fun `logs endpoint returns captured activity`() {
        json("GET", "/api/state")
        val response = json("GET", "/api/logs")
        assertEquals(200, response.code)
        assertTrue(response.body.contains("\"lines\""))
        assertTrue(response.body.contains("Server listening"))
    }

    @Test
    fun `the web interface is served from the assets folder`() {
        val index = request("GET", "/ui/")
        assertEquals(200, index.code)
        assertEquals("text/html; charset=utf-8", index.headers["content-type"])
        assertTrue(index.body.contains("LANVault"))

        val script = request("GET", "/ui/app.js")
        assertEquals(200, script.code)
        assertEquals("text/javascript; charset=utf-8", script.headers["content-type"])
        assertTrue(script.body.contains("/api/list"))

        val styles = request("GET", "/ui/style.css")
        assertEquals(200, styles.code)
        assertTrue(styles.body.contains("--accent"))
    }

    @Test
    fun `unknown api endpoints return json errors`() {
        val response = json("GET", "/api/nope")
        assertEquals(404, response.code)
        assertTrue(response.body.contains("Unknown endpoint"))
    }

    @Test
    fun `traversal into the assets folder is refused`() {
        val response = request("GET", "/ui/..%2F..%2Flocal.properties")
        // The path is collapsed to inside the assets folder, so nothing outside it is reachable.
        assertEquals(404, response.code)
        assertFalse(response.body.contains("sdk.dir"))
    }

    @Test
    fun `basic authentication protects the api and webdav when enabled`() {
        settingsStore.update { it.copy(requireAuth = true, username = "lola", password = "hunter2") }

        assertEquals(401, request("GET", "/api/state").code)
        assertEquals(401, rawSocket("/", mapOf("Depth" to "0")).code)
        assertTrue(request("GET", "/api/state").headers["www-authenticate"].orEmpty().contains("Basic"))

        val allowed = request("GET", "/api/state", credentials = "lola" to "hunter2")
        assertEquals(200, allowed.code)

        val wrong = request("GET", "/api/state", credentials = "lola" to "nope")
        assertEquals(401, wrong.code)
    }

    @Test
    fun `restarting the server keeps the port`() {
        assertEquals(200, request("POST", "/api/restart").code)
        assertEquals(200, json("GET", "/api/state").code)
        assertEquals(port, settingsStore.current().serverPort)
    }

    @Test
    fun `clearing the cache reports success`() {
        assertEquals(200, request("POST", "/api/clear-cache").code)
    }

    private fun rawSocket(path: String, headers: Map<String, String>): RawResponse {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            val output = socket.getOutputStream()
            val requestText = StringBuilder()
            requestText.append("PROPFIND ").append(path).append(" HTTP/1.1\r\nHost: 127.0.0.1\r\n")
            headers.forEach { (key, value) -> requestText.append(key).append(": ").append(value).append("\r\n") }
            requestText.append("Connection: close\r\n\r\n")
            output.write(requestText.toString().toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()
            val input = socket.getInputStream().buffered()
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.ISO_8859_1))
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
                    responseHeaders[name] = line.substring(separator + 1).trim()
                    if (name == "content-length") contentLength = responseHeaders[name]?.toIntOrNull() ?: 0
                }
            }
            val body = buildString {
                var remaining = contentLength
                while (remaining > 0) {
                    val value = reader.read()
                    if (value < 0) break
                    append(value.toChar())
                    remaining -= 1
                }
            }
            return RawResponse(code, body, responseHeaders)
        }
    }

}

private class FixedStorageRepository(
    context: Context,
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

data class RawResponse(val code: Int, val body: String, val headers: Map<String, String>)
