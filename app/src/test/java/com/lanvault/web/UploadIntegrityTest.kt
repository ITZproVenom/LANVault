package com.lanvault.web

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.lanvault.settings.SettingsStore
import com.lanvault.storage.LocalStorageVolume
import com.lanvault.storage.StorageRepository
import com.lanvault.storage.VolumeKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets

/** End-to-end upload checks through the real server, the way the browser posts them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class UploadIntegrityTest {

    private lateinit var root: File
    private lateinit var settings: SettingsStore
    private lateinit var server: VaultServer
    private var port = 0
    private val volumeId = "tv-storage"

    @Before
    fun setUp() {
        root = File.createTempFile("lanvault-upload", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = SettingsStore(context)
        settings.resetToDefaults()
        port = ServerSocket(0).use { it.localPort }
        settings.update { it.copy(serverPort = port) }

        val volume = LocalStorageVolume("internal", "TV Storage", VolumeKind.INTERNAL, root, canWrite = true)
        server = VaultServer(context, settings, OneVolumeRepository(context, volume))
        server.start()
        assertTrue(server.isRunning)
    }

    @After
    fun tearDown() {
        server.stop()
        root.deleteRecursively()
    }

    private fun upload(name: String, payload: ByteArray): Int {
        val connection = URL("http://127.0.0.1:$port/api/put?path=$volumeId&name=$name")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(payload.size)
        connection.outputStream.use { it.write(payload) }
        val code = connection.responseCode
        connection.disconnect()
        return code
    }

    @Test
    fun `a multi megabyte upload arrives byte for byte`() {
        val payload = ByteArray(3 * 1024 * 1024) { (it % 253).toByte() }
        assertEquals(201, upload("movie.mp4", payload))
        val stored = File(root, "movie.mp4")
        assertEquals(payload.size.toLong(), stored.length())
        assertTrue(stored.readBytes().contentEquals(payload))
    }

    @Test
    fun `a second upload over a shorter file does not leave the old tail`() {
        val long = ByteArray(4 * 1024 * 1024) { 7 }
        assertEquals(201, upload("clip.mp4", long))

        val short = ByteArray(1024) { 5 }
        assertEquals(201, upload("clip.mp4", short))

        val stored = File(root, "clip.mp4")
        assertEquals(short.size.toLong(), stored.length())
        assertTrue(stored.readBytes().contentEquals(short))
    }

    @Test
    fun `uploads of awkward sizes assemble correctly`() {
        listOf(1, 999, 4096, 65535, 65536, 65537, 200003).forEachIndexed { index, size ->
            val payload = ByteArray(size) { ((it + index) % 251).toByte() }
            assertEquals(201, upload("f$index.bin", payload))
            val stored = File(root, "f$index.bin")
            assertEquals("size mismatch at $size", payload.size.toLong(), stored.length())
            assertTrue(stored.readBytes().contentEquals(payload))
        }
    }

    @Test
    fun `a transfer that stops early is reported and leaves no partial file`() {
        val connection = Socket("127.0.0.1", port)
        connection.use { socket ->
            socket.soTimeout = 10_000
            val request = "PUT /api/put?path=$volumeId&name=partial.bin HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\nConnection: close\r\nContent-Length: 1000000\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().write(ByteArray(100))
            socket.getOutputStream().flush()
            // Half close so the server sees EOF and can tell the body arrived short.
            socket.shutdownOutput()
            val status = socket.getInputStream().bufferedReader().readLine().orEmpty()
            assertTrue("expected a rejection, got: $status", status.contains("400"))
        }
        // Nothing must be left behind looking like a successful upload.
        assertTrue(!File(root, "partial.bin").exists())
    }

    private fun apiGet(path: String): java.net.HttpURLConnection {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as java.net.HttpURLConnection
        connection.getResponseCode()
        return connection
    }

    private val java.net.HttpURLConnection.code: Int get() = responseCode
    private val java.net.HttpURLConnection.body: String
        get() = (if (responseCode >= 400) errorStream else inputStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()

    @Test
    fun `state exposes every network address so a multi homed tv is not stuck on the wrong one`() {
        val connection = URL("http://127.0.0.1:$port/api/state").openConnection()
            as java.net.HttpURLConnection
        val code = connection.responseCode
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        assertEquals(200, code)
        assertTrue(body.contains("\"ipv4Addresses\":["));
        assertTrue(body.contains("\"hostnames\":["));
        assertTrue(body.contains("\"webInterfaceUrl\":\"http:"));
    }
}

private class OneVolumeRepository(
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
