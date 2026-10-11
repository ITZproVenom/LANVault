package com.lanvault.storage

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

data class SafTree(
    val id: String,
    val displayName: String,
    val uri: Uri,
)

data class DiscoveredVolume(
    val id: String,
    val displayName: String,
    val kind: VolumeKind,
    val mountPath: String,
    val readable: Boolean,
    val writable: Boolean,
    val needsPermission: Boolean,
    val volume: StorageVolume?,
)

open class StorageRepository(private val context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences("lanvault_volumes", Context.MODE_PRIVATE)

    open fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    /** Best-effort map of canonical mount path to user facing label, e.g. "USB Drive". */
    private fun volumeLabels(): Map<String, String> {
        val storageManager = context.getSystemService(StorageManager::class.java) ?: return emptyMap()
        return runCatching {
            val result = HashMap<String, String>()
            storageManager.storageVolumes.forEach { volume ->
                val path = runCatching {
                    volume.javaClass.getMethod("getPath").invoke(volume) as? String
                }.getOrNull() ?: return@forEach
                val label = runCatching { volume.getDescription(context) }.getOrNull()
                if (label.isNullOrBlank()) return@forEach
                result[File(path).canonicalPath] = label
            }
            result
        }.getOrDefault(HashMap())
    }

    open fun discover(): List<DiscoveredVolume> {
        val found = LinkedHashMap<String, DiscoveredVolume>()
        val labels = volumeLabels()
        val storageManager = context.getSystemService(StorageManager::class.java)

        val internalRoot = Environment.getExternalStorageDirectory()
        val internalWritable = hasAllFilesAccess() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        val internalReadable = internalRoot.canRead()
        found["internal"] = DiscoveredVolume(
            id = "internal",
            displayName = labels[internalRoot.canonicalPath]
                ?: runCatching { storageManager?.primaryStorageVolume?.getDescription(context) }.getOrNull()
                ?: "TV Storage",
            kind = VolumeKind.INTERNAL,
            mountPath = internalRoot.absolutePath,
            readable = internalReadable,
            writable = internalWritable,
            needsPermission = !internalReadable ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()),
            volume = if (internalReadable) {
                LocalStorageVolume("internal", "TV Storage", VolumeKind.INTERNAL, internalRoot, internalWritable)
            } else {
                null
            },
        )

        // Removable disks are mounted under /storage/<uuid>.
        val scanRoots = listOfNotNull(
            File("/storage"),
            Environment.getExternalStorageDirectory().parentFile,
        ).distinctBy { it.canonicalPath }

        scanRoots.forEach { scanRoot ->
            val entries = runCatching { scanRoot.listFiles() }.getOrNull() ?: return@forEach
            entries.forEach { candidate ->
                if (!candidate.isDirectory) return@forEach
                if (candidate.name == "self" || candidate.name == "emulated") return@forEach
                val canonical = runCatching { candidate.canonicalPath }.getOrNull() ?: return@forEach
                if (canonical == internalRoot.canonicalPath) return@forEach
                val id = "usb_${candidate.name}"
                if (found.containsKey(id)) return@forEach
                val state = Environment.getExternalStorageState(candidate)
                val mounted = state == Environment.MEDIA_MOUNTED ||
                    state == Environment.MEDIA_MOUNTED_READ_ONLY
                val readable = mounted && candidate.canRead()
                val writable = readable &&
                    state == Environment.MEDIA_MOUNTED &&
                    (hasAllFilesAccess() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
                val label = labels[canonical] ?: candidate.name.uppercase()
                found[id] = DiscoveredVolume(
                    id = id,
                    displayName = label,
                    kind = VolumeKind.USB,
                    mountPath = candidate.absolutePath,
                    readable = readable,
                    writable = writable,
                    needsPermission = !readable,
                    volume = if (readable) {
                        LocalStorageVolume(id, label, VolumeKind.USB, candidate, writable)
                    } else {
                        null
                    },
                )
            }
        }

        storedSafTrees().forEach { tree ->
            val volume = SafStorageVolume(tree.id, tree.displayName, tree.uri, context.contentResolver)
            found[tree.id] = DiscoveredVolume(
                id = tree.id,
                displayName = tree.displayName,
                kind = VolumeKind.OTHER,
                mountPath = tree.uri.toString(),
                readable = volume.stat("") != null,
                writable = volume.isWritable,
                needsPermission = false,
                volume = volume,
            )
        }

        return found.values.sortedWith(
            compareBy<DiscoveredVolume> { it.kind == VolumeKind.INTERNAL }.thenBy { it.displayName.lowercase() },
        )
    }

    fun storeSafTree(uri: Uri, displayName: String?) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        val id = "saf_${Integer.toHexString(uri.toString().hashCode())}"
        val label = displayName?.takeIf { it.isNotBlank() } ?: "External Drive"
        val existing = storedSafTrees().toMutableList()
        existing.removeAll { it.id == id }
        existing += SafTree(id, label, uri)
        persistSafTrees(existing)
    }

    fun removeSafTree(id: String) {
        val existing = storedSafTrees().toMutableList()
        val tree = existing.firstOrNull { it.id == id } ?: return
        existing -= tree
        persistSafTrees(existing)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                tree.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun storedSafTrees(): List<SafTree> {
        val raw = preferences.getStringSet("trees", emptySet()) ?: emptySet()
        return raw.mapNotNull { entry ->
            val parts = entry.split('\u0000')
            if (parts.size != 3) return@mapNotNull null
            val uri = runCatching { Uri.parse(parts[2]) }.getOrNull() ?: return@mapNotNull null
            SafTree(parts[0], parts[1], uri)
        }.sortedBy { it.displayName }
    }

    private fun persistSafTrees(trees: List<SafTree>) {
        val encoded = trees.map { "${it.id}\u0000${it.displayName}\u0000${it.uri}" }.toSet()
        preferences.edit().putStringSet("trees", encoded).apply()
    }
}
