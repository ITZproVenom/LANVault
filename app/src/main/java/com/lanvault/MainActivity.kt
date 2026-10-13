package com.lanvault

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.lanvault.net.EventLog
import com.lanvault.storage.VolumeKind
import com.lanvault.web.NetworkAddresses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Status-only screen: it shows the LAN links, the shared drives and a live log. Nothing here is
 * navigable by design - every setting lives in the web interface.
 */
class MainActivity : ComponentActivity() {

    private lateinit var rootScroll: ScrollView
    private lateinit var statusLine: TextView
    private lateinit var statusBadge: TextView
    private lateinit var webInterfaceUrl: TextView
    private lateinit var webdavUrl: TextView
    private lateinit var volumesValue: TextView
    private lateinit var permissionValue: TextView
    private lateinit var portFallbackValue: TextView
    private lateinit var authStateValue: TextView
    private lateinit var webdavHint: TextView
    private lateinit var networkNoteValue: TextView
    private lateinit var logView: TextView
    private lateinit var grantAccessButton: Button
    private lateinit var addDriveButton: Button
    private lateinit var restartButton: Button

    @Volatile
    private var visibleDrives: List<String> = emptyList()

    @Volatile
    private var driveSummary: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppRuntime.initialize(this)
        bindViews()

        ServerService.start(this)
        lifecycleScope.launch { AppRuntime.startServer(this@MainActivity) }

        grantAccessButton.setOnClickListener { StoragePermissions.request(this) }
        addDriveButton.setOnClickListener {
            startActivity(android.content.Intent(this, TreePickerActivity::class.java))
        }
        restartButton.setOnClickListener { AppRuntime.restartServer(this) }

        lifecycleScope.launch {
            while (isActive) {
                renderState()
                renderLogs()
                refreshVolumes()
                delay(3_000)
            }
        }
    }

    private fun bindViews() {
        rootScroll = findViewById(R.id.rootScroll)
        statusLine = findViewById(R.id.statusLine)
        statusBadge = findViewById(R.id.statusBadge)
        webInterfaceUrl = findViewById(R.id.webInterfaceUrl)
        webdavUrl = findViewById(R.id.webdavUrl)
        volumesValue = findViewById(R.id.volumesValue)
        permissionValue = findViewById(R.id.permissionValue)
        portFallbackValue = findViewById(R.id.portFallbackValue)
        authStateValue = findViewById(R.id.authStateValue)
        webdavHint = findViewById(R.id.webdavHint)
        networkNoteValue = findViewById(R.id.networkNoteValue)
        logView = findViewById(R.id.logView)
        grantAccessButton = findViewById(R.id.grantAccessButton)
        addDriveButton = findViewById(R.id.addDriveButton)
        restartButton = findViewById(R.id.restartButton)
    }

    @SuppressLint("SetTextI18n")
    private fun renderState() {
        val settings = AppRuntime.settingsStore.current()
        // A TV often has multiple interfaces, so list them all and let the browser UI identify
        // the working address. The HTTP WebDAV URL is for compatible clients, not native iOS Files.
        val addresses = NetworkAddresses.ipv4()
        val server = AppRuntime.server
        val running = AppRuntime.running.value || server?.isRunning == true
        val port = server?.port ?: settings.serverPort
        val preferredPort = settings.serverPort
        val fellBack = port != preferredPort

        statusBadge.text = if (running) "ONLINE" else "OFFLINE"
        statusBadge.setTextColor(resources.getColor(if (running) R.color.accent else R.color.danger, theme))
        statusLine.text = if (running) {
            buildString {
                append("Sharing files on this network, port ")
                append(port)
                append(". ")
                append(visibleDrives.size)
                append(" drive(s) shared, ")
                append(driveSummary)
                if (fellBack) {
                    append(". Port ")
                    append(preferredPort)
                    append(" was already in use, so the TV picked ")
                    append(port)
                    append(".")
                }
            }
        } else {
            "Server not running. Open the web interface to manage it."
        }

        if (addresses.isEmpty()) {
            webInterfaceUrl.text = "No network connection"
            webdavUrl.text = "Connect the TV to your network"
        } else {
            webInterfaceUrl.text = addresses.joinToString("\n") { "http://$it:$port/ui/" }
            webdavUrl.text = addresses.joinToString("\n") { "http://$it:$port/" }
        }
        networkNoteValue.text = if (addresses.size > 1) {
            "This TV has ${addresses.size} network addresses. Use the one on the same network " +
                "as your device - the web interface marks the one that reached it."
        } else {
            ""
        }
        networkNoteValue.visibility = if (addresses.size > 1) View.VISIBLE else View.GONE

        webdavHint.text =
            "The iPhone Files server endpoint above is still enabled (HTTP WebDAV). The native iOS Files " +
                "Connect to Server dialog rejects this plain HTTP URL; that is a client-compatibility issue, " +
                "not a removed server. Use the web interface in Safari or a WebDAV-compatible client. LANVault does not run SMB."
        if (settings.requireAuth) {
            authStateValue.text = "A username and password are required for protected server/API access."
            authStateValue.setTextColor(resources.getColor(R.color.warning, theme))
        } else {
            authStateValue.text = "Open access: any device on this network can connect without a password."
            authStateValue.setTextColor(resources.getColor(R.color.text_secondary, theme))
        }

        portFallbackValue.text = if (fellBack) {
            "Port $preferredPort is taken by another app on this TV, so LANVault is using $port. " +
                "Change it in the web interface if you want a different one."
        } else {
            ""
        }
        portFallbackValue.visibility = if (fellBack) View.VISIBLE else View.GONE

        val hasAccess = StoragePermissions.isGranted(this)
        grantAccessButton.visibility = if (hasAccess) View.GONE else View.VISIBLE
        addDriveButton.visibility = if (hasAccess) View.GONE else View.VISIBLE
        if (hasAccess) {
            permissionValue.visibility = View.GONE
        } else {
            permissionValue.visibility = View.VISIBLE
            permissionValue.text = "Tap “Grant storage access” so the TV can read USB drives."
        }
    }

    private suspend fun refreshVolumes() {
        val volumes = withContext(Dispatchers.IO) { AppRuntime.storageRepository.discover() }
        val readable = volumes.filter { it.readable }
        visibleDrives = readable.map { it.displayName }
        driveSummary = if (readable.isEmpty()) {
            "no drive is available yet"
        } else {
            readable.joinToString(", ") { it.displayName }
        }
        val unreadable = volumes.filter { !it.readable && it.needsPermission }
        volumesValue.text = buildString {
            if (readable.isEmpty()) append("No drives available yet.")
            readable.forEach { volume ->
                append("• ").append(kindLabel(volume.kind)).append(" — ").append(volume.displayName)
                if (!volume.writable) append(" (read-only)")
                append('\n')
            }
            if (unreadable.isNotEmpty()) {
                append('\n').append("Waiting for access: ").append(unreadable.joinToString(", ") { it.displayName })
            }
        }.trim()
    }

    private fun kindLabel(kind: VolumeKind): String = when (kind) {
        VolumeKind.INTERNAL -> "TV storage"
        VolumeKind.USB -> "USB drive"
        VolumeKind.SD_CARD -> "SD card"
        VolumeKind.OTHER -> "Shared folder"
    }

    private fun renderLogs() {
        val lines = EventLog.shared.snapshot.value.takeLast(60)
        if (lines.isEmpty()) {
            logView.text = "Waiting for activity…"
            return
        }
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        val text = lines.joinToString("\n") { line ->
            "[${timeFormat.format(Date(line.timestamp))}] ${line.message}"
        }
        if (logView.text.toString() != text) {
            logView.text = text
            rootScroll.post { rootScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
