package com.lanvault.storage

import java.io.Closeable
import java.io.InputStream

/** Where a volume comes from, used for the icon shown in the web UI. */
enum class VolumeKind { INTERNAL, USB, SD_CARD, OTHER }

data class StorageEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
) {
    fun extension(): String = name.substringAfterLast('.', "").lowercase()
}

/** A random-access readable region of a file. */
interface ReadSource : Closeable {
    val lengthBytes: Long
    val stream: InputStream
}

interface StorageVolume {
    val id: String
    val displayName: String
    val kind: VolumeKind
    val isWritable: Boolean
    val rootPath: String

    fun list(path: String, showHiddenFiles: Boolean): List<StorageEntry>?
    fun stat(path: String): StorageEntry?
    fun openReadSource(path: String, offsetBytes: Long = 0L): ReadSource?
    fun openWriteSource(path: String, append: Boolean = false): java.io.OutputStream?
    fun createFile(parentPath: String, name: String): Boolean
    fun createDirectory(parentPath: String, name: String): Boolean
    fun delete(path: String): Boolean
    fun rename(path: String, newName: String): Boolean
    fun availableBytes(): Long
    fun totalBytes(): Long
}

/** Normalises a request path into a safe relative path with "/" separators. */
object SafePath {

    private val illegalCharacters = Regex("""[\\\x00]""")

    fun normalise(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var value = raw.replace('\\', '/')
        if (value.startsWith("/")) value = value.trimStart('/')
        if (value.endsWith("/")) value = value.trimEnd('/')
        val segments = value.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        return segments.joinToString("/")
    }

    fun segments(relativePath: String): List<String> =
        normalise(relativePath).split('/').filter { it.isNotEmpty() }

    fun parent(relativePath: String): String {
        val segments = segments(relativePath)
        if (segments.size <= 1) return ""
        return segments.dropLast(1).joinToString("/")
    }

    fun fileName(relativePath: String): String =
        segments(relativePath).lastOrNull().orEmpty()

    fun isLegalFileName(name: String): Boolean {
        if (name.isBlank()) return false
        if (illegalCharacters.containsMatchIn(name)) return false
        if (name.contains('/')) return false
        return name != "." && name != ".."
    }
}
