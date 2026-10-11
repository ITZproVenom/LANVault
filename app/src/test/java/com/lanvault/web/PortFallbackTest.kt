package com.lanvault.web

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.lanvault.net.EventLog
import com.lanvault.net.isPortInUse
import com.lanvault.settings.SettingsStore
import com.lanvault.storage.LocalStorageVolume
import com.lanvault.storage.StorageRepository
import com.lanvault.storage.VolumeKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket

/**
 * Covers the port fallback. Many Android TVs run their own remote control service on 8080,
 * which is also the default port here, so a busy port must not leave the TV unreachable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class PortFallbackTest {

    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var settingsStore: SettingsStore
    private lateinit var repository: StorageRepository
    private var server: VaultServer? = null
    private var squatter: ServerSocket? = null
    private var preferredPort = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsStore = SettingsStore(context)
        settingsStore.resetToDefaults()
        root = File.createTempFile("lanvault-port", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
        repository = FixedRepository(context, root)
        preferredPort = java.net.ServerSocket(0).use { it.localPort }
    }

    @After
    fun tearDown() {
        server?.stop()
        squatter?.close()
        root.deleteRecursively()
    }

    private fun startWith(port: Int): VaultServer {
        val created = VaultServer(context, settingsStore, repository)
        settingsStore.update { it.copy(serverPort = port) }
        created.start()
        server = created
        return created
    }

    @Test
    fun `a free preferred port is used as is`() {
        val created = startWith(preferredPort)
        assertTrue(created.isRunning)
        assertEquals(preferredPort, created.effectivePort)
        assertEquals(preferredPort, created.port)
        assertTrue(!created.portInUseOnPreferred)
    }

    @Test
    fun `a busy preferred port falls forward to the next free one`() {
        squatter = ServerSocket(preferredPort)
        val created = startWith(preferredPort)
        assertTrue(created.isRunning)
        assertNotEquals(preferredPort, created.effectivePort)
        assertTrue(created.effectivePort > preferredPort)
        assertTrue(created.portInUseOnPreferred)
        // The reported port must be the one that is actually reachable, not the requested one.
        assertEquals(created.effectivePort, created.port)
    }

    @Test
    fun `the fallback is still reachable and reports the real port`() {
        squatter = ServerSocket(preferredPort)
        val created = startWith(preferredPort)
        val ports = created.effectivePort
        val state = apiState(created, ports)
        assertTrue(state.contains("\"running\":true"))
        assertTrue(state.contains("\"port\":$ports"))
        assertTrue(state.contains("\"requestedPort\":$preferredPort"))
        assertTrue(state.contains("\"portInUseOnPreferred\":true"))
        assertTrue(state.contains("\"webdavUrl\":\"http:"))
    }

    @Test
    fun `the user setting is left untouched so a later restart can reclaim it`() {
        squatter = ServerSocket(preferredPort)
        startWith(preferredPort)
        squatter?.close()
        squatter = null

        val created = VaultServer(context, settingsStore, repository)
        settingsStore.resetToDefaults()
        settingsStore.update { it.copy(serverPort = preferredPort) }
        created.stop()
        // After the conflicting process is gone the same setting binds cleanly again.
        val restarted = VaultServer(context, settingsStore, repository)
        restarted.start()
        server = restarted
        assertEquals(preferredPort, restarted.effectivePort)
        assertTrue(!restarted.portInUseOnPreferred)
        assertEquals(preferredPort, settingsStore.current().serverPort)
    }

    @Test
    fun `realistic conflicts are detected as port in use`() {
        assertTrue(isPortInUse(java.net.BindException("Address already in use")))
        assertTrue(isPortInUse(java.io.IOException("bind failed: EADDRINUSE")))
        assertTrue(isPortInUse(java.io.IOException("Permission denied")))
        assertTrue(!isPortInUse(java.io.IOException("Network is unreachable")))
        assertTrue(!isPortInUse(IllegalStateException("socket closed")))
    }

    @Test
    fun `the fallback port answers requests end to end`() {
        squatter = ServerSocket(preferredPort)
        val created = startWith(preferredPort)
        val port = created.effectivePort

        val state = java.net.URL("http://127.0.0.1:$port/api/state").openConnection().apply {
            connectTimeout = 4000
            readTimeout = 4000
        }.getInputStream().bufferedReader().use { it.readText() }
        assertTrue(state.contains("\"running\":true"))
        assertTrue(state.contains("\"port\":$port"))
        assertTrue(state.contains("\"portInUseOnPreferred\":true"))

        // The WebDAV surface must answer on the fallback port too, not return a dead link.
        // A raw socket is needed because the JDK's HttpURLConnection refuses PROPFIND.
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val output = socket.getOutputStream()
            output.write(
                ("PROPFIND / HTTP/1.1\r\nHost: 127.0.0.1\r\nDepth: 0\r\nConnection: close\r\n\r\n")
                    .toByteArray(),
            )
            output.flush()
            val text = socket.getInputStream().bufferedReader().use { it.readText() }
            assertTrue(text.startsWith("HTTP/1.1 207"))
            assertTrue(text.contains("<D:multistatus"))
        }
    }

    private fun apiState(server: VaultServer, port: Int): String {
        val handler = ApiHandler(server, settingsStore, repository)
        val request = com.lanvault.net.HttpRequest(
            method = "GET",
            rawTarget = "/api/state",
            path = "/api/state",
            query = emptyMap(),
            headers = emptyMap(),
            body = com.lanvault.net.EmptyInputStream,
            remoteAddress = "127.0.0.1",
        )
        val response = handler.handle(request)
        return String(response.bodyBytes ?: ByteArray(0))
    }
}

private class FixedRepository(
    context: Context,
    private val root: File,
) : StorageRepository(context) {

    override fun discover(): List<com.lanvault.storage.DiscoveredVolume> {
        val volume = LocalStorageVolume("internal", "TV Storage", VolumeKind.INTERNAL, root, canWrite = true)
        return listOf(
            com.lanvault.storage.DiscoveredVolume(
                id = "internal",
                displayName = "TV Storage",
                kind = VolumeKind.INTERNAL,
                mountPath = root.absolutePath,
                readable = true,
                writable = true,
                needsPermission = false,
                volume = volume,
            ),
        )
    }

    override fun hasAllFilesAccess(): Boolean = true
}
