package com.lanvault

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.lanvault.net.EventLog

/** Brings the server up again after the TV boots or after the app is updated. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val supported = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!supported) return

        AppRuntime.initialize(context)
        if (!AppRuntime.settingsStore.current().autoStartOnBoot) {
            EventLog.shared.info("Auto start is off, not launching after ${action.substringAfterLast('.')}.")
            return
        }
        EventLog.shared.info("Device signal received (${action.substringAfterLast('.')}), starting the server.")
        ServerService.start(context)
    }
}

/** Grants all files access so USB drives surface without the SAF picker. */
object StoragePermissions {

    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            read == PackageManager.PERMISSION_GRANTED
        }

    fun request(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            val fallback = Intent(
                Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
            )
            runCatching { context.startActivity(intent) }
                .onFailure {
                    runCatching { context.startActivity(fallback) }
                }
        } else {
            if (context is android.app.Activity) {
                context.requestPermissions(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                    REQUEST_CODE_STORAGE,
                )
            }
        }
    }

    const val REQUEST_CODE_STORAGE = 4201
}
