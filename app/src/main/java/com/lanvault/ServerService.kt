package com.lanvault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.lanvault.net.EventLog
import com.lanvault.monitor.AdGuardHomeRuntime
import com.lanvault.web.NetworkAddresses

/** Keeps the LAN storage server alive while the TV is on. */
class ServerService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        AppRuntime.initialize(this)
        createNotificationChannel()
        startForegroundCompat(buildNotification())
        EventLog.shared.info("LANVault service started.")

        if (AppRuntime.settingsStore.current().keepCpuAwake) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LANVault:server").apply { acquire() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                AppRuntime.settingsStore.update { it.copy(autoStartOnBoot = false) }
                AppRuntime.stopServer()
                val homeStatus = AdGuardHomeRuntime.get(this).status()
                if (homeStatus.optBoolean("running") || homeStatus.optBoolean("autoStart")) {
                    // Stop file sharing without killing the AdGuard Home DNS server.
                    startForegroundCompat(buildNotification())
                    return START_STICKY
                }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val shouldStartServer = !(intent == null && !AppRuntime.settingsStore.current().autoStartOnBoot)
                if (shouldStartServer) {
                    AppRuntime.startServer(this)
                } else {
                    // START_STICKY may redeliver a null intent after file sharing was stopped.
                    AppRuntime.stopServer()
                    if (!AdGuardHomeRuntime.get(this).status().optBoolean("autoStart")) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
                Thread({
                    runCatching { AdGuardHomeRuntime.get(this).startIfEnabled() }
                }, "lanvault-adguardhome-autostart").start()
            }
        }
        startForegroundCompat(buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { AdGuardHomeRuntime.get(this).stop() }
        AppRuntime.stopServer()
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
        EventLog.shared.info("LANVault service stopped.")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val settings = AppRuntime.settingsStore.current()
        val port = AppRuntime.server?.port ?: settings.serverPort
        val addresses = NetworkAddresses.ipv4()
        val host = addresses.firstOrNull() ?: "this-tv"
        val sharing = AppRuntime.server?.isRunning == true
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, ServerService::class.java).setAction(ACTION_STOP)
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val content = if (sharing) {
            "http://" + host + ":" + port + "/ui/ · WebDAV on port " + port
        } else {
            "File sharing stopped; AdGuard Home may remain active."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(if (sharing) "LANVault is sharing storage" else "LANVault network services")
            .setContentText(content)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        buildString {
                            if (sharing) {
                                append("Web interface: ")
                                append(addresses.joinToString("  ") { "http://" + it + ":" + port + "/ui/" }.ifEmpty { host })
                                append("\niPhone Files > Connect to Server: ")
                                append(addresses.joinToString("  ") { "http://" + it + ":" + port + "/" }.ifEmpty { host })
                                append("\nUse the address on the same network as your device. ")
                                append("All settings and monitoring are controlled from the web interface.")
                            } else {
                                append("File sharing is stopped. The bundled AdGuard Home DNS service can remain active; ")
                                append("manage it from Network Monitor in the web interface.")
                            }
                        },
                    ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingOpen)
            .addAction(0, "Stop sharing", pendingStop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "lanvault_server"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.lanvault.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }
    }
}
