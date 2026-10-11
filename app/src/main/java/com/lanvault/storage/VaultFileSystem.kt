package com.lanvault.storage

import com.lanvault.settings.SettingsStore

/** URL-safe identifier for a volume, stable for the lifetime of the mounted disk. */
fun DiscoveredVolume.slug(): String {
    val base = if (id.equals("internal", ignoreCase = true)) {
        "tv-storage"
    } else {
        id.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }
    return base.ifEmpty { "volume" }
}

sealed interface VaultNode {

    /** The WebDAV root: a virtual folder whose children are the discovered volumes. */
    data object VolumeRoot : VaultNode

    /** A mounted volume, addressed by its first path segment. */
    data class VolumeDirectory(val volume: DiscoveredVolume) : VaultNode

    /** A concrete file or folder inside a volume. */
    data class FileNode(
        val volume: DiscoveredVolume,
        val relativePath: String,
        val entry: StorageEntry,
    ) : VaultNode
}

class VolumeDirectorySnapshot(
    val volumes: List<DiscoveredVolume>,
) {
    private val bySlug = volumes.associateBy { it.slug() }

    fun find(slug: String): DiscoveredVolume? = bySlug[slug]

    fun resolve(path: String): VaultNode? {
        val segments = SafePath.segments(path)
        if (segments.isEmpty()) return VaultNode.VolumeRoot
        val volume = find(segments[0]) ?: return null
        if (segments.size == 1) return VaultNode.VolumeDirectory(volume)
        val targetVolume = volume.volume ?: return null
        val relativePath = segments.drop(1).joinToString("/")
        val entry = targetVolume.stat(relativePath) ?: return null
        return VaultNode.FileNode(volume, relativePath, entry)
    }

    fun children(path: String, showHiddenFiles: Boolean): List<StorageEntry>? {
        val segments = SafePath.segments(path)
        if (segments.isEmpty()) {
            return volumes.map {
                StorageEntry(
                    name = it.displayName,
                    path = it.slug(),
                    isDirectory = true,
                    sizeBytes = 0L,
                    lastModified = 0L,
                )
            }
        }
        val volume = find(segments[0])?.volume ?: return null
        val relativePath = segments.drop(1).joinToString("/")
        val entries = volume.list(relativePath, showHiddenFiles) ?: return null
        val prefix = segments[0]
        return entries.map { entry ->
            entry.copy(path = "$prefix/${entry.path}")
        }
    }
}

class VaultFileSystem(
    private val repository: StorageRepository,
    private val settings: SettingsStore,
) {

    fun snapshot(): VolumeDirectorySnapshot {
        val volumes = repository.discover().filter { it.volume != null }
        val distinct = LinkedHashMap<String, DiscoveredVolume>()
        volumes.forEach { volume ->
            var slug = volume.slug()
            var counter = 2
            while (distinct.containsKey(slug)) {
                slug = "${volume.slug()}-$counter"
                counter += 1
            }
            distinct[slug] = volume
        }
        return VolumeDirectorySnapshot(distinct.values.toList())
    }

    fun resolve(path: String): VaultNode? = snapshot().resolve(path)

    fun children(path: String): List<StorageEntry>? =
        snapshot().children(path, settings.current().showHiddenFiles)
}
