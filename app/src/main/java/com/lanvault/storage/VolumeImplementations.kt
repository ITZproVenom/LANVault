package com.lanvault.storage

import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Filesystem-backed volume: the TV's own internal storage or a directly mounted USB disk. */
class LocalStorageVolume(
    override val id: String,
    override val displayName: String,
    override val kind: VolumeKind,
    private val root: File,
    private val canWrite: Boolean,
) : StorageVolume {

    override val isWritable: Boolean get() = canWrite
    override val rootPath: String = root.absolutePath

    private fun resolve(relativePath: String): File {
        val file = File(root, SafePath.normalise(relativePath))
        val canonicalRoot = root.canonicalPath
        val canonical = file.canonicalPath
        require(canonical == canonicalRoot || canonical.startsWith(canonicalRoot + File.separator)) {
            "path escapes volume root"
        }
        return file
    }

    override fun list(path: String, showHiddenFiles: Boolean): List<StorageEntry>? {
        val directory = resolve(path)
        if (!directory.isDirectory) return null
        val children = directory.listFiles() ?: return null
        return children
            .asSequence()
            .filter { showHiddenFiles || !it.name.startsWith(".") }
            .map { StorageEntry(it.name, joinPath(path, it.name), it.isDirectory, it.length(), it.lastModified()) }
            .sortedWith(compareByDescending<StorageEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()
    }

    override fun stat(path: String): StorageEntry? {
        val file = resolve(path)
        if (!file.exists()) return null
        return StorageEntry(file.name, SafePath.normalise(path), file.isDirectory, file.length(), file.lastModified())
    }

    override fun openReadSource(path: String, offsetBytes: Long): ReadSource? {
        val file = resolve(path)
        if (!file.isFile || !file.canRead()) return null
        val channel = runCatching { FileChannel.open(file.toPath(), StandardOpenOption.READ) }.getOrNull() ?: return null
        return try {
            channel.position(offsetBytes)
            val remaining = (channel.size() - offsetBytes).coerceAtLeast(0L)
            object : ReadSource {
                override val lengthBytes: Long get() = remaining
                override val stream: InputStream get() = Channels.newInputStream(channel)
                override fun close() {
                    runCatching { channel.close() }
                }
            }
        } catch (error: Throwable) {
            runCatching { channel.close() }
            null
        }
    }

    override fun openWriteSource(path: String, append: Boolean): OutputStream? {
        if (!canWrite) return null
        val file = resolve(path)
        val parent = file.parentFile
        if (parent == null || !parent.isDirectory) return null
        val options = buildList {
            add(StandardOpenOption.CREATE)
            add(StandardOpenOption.WRITE)
            if (append) add(StandardOpenOption.APPEND)
        }
        val channel = runCatching { FileChannel.open(file.toPath(), *options.toTypedArray()) }.getOrNull() ?: return null
        return Channels.newOutputStream(channel)
    }

    override fun createFile(parentPath: String, name: String): Boolean {
        if (!canWrite || !SafePath.isLegalFileName(name)) return false
        val parent = resolve(parentPath)
        if (!parent.isDirectory) return false
        return runCatching { File(parent, name).createNewFile() }.getOrDefault(false)
    }

    override fun createDirectory(parentPath: String, name: String): Boolean {
        if (!canWrite || !SafePath.isLegalFileName(name)) return false
        val parent = resolve(parentPath)
        if (!parent.isDirectory) return false
        return runCatching { File(parent, name).mkdir() }.getOrDefault(false)
    }

    override fun delete(path: String): Boolean {
        if (!canWrite) return false
        return runCatching { resolve(path).deleteRecursively() }.getOrDefault(false)
    }

    override fun rename(path: String, newName: String): Boolean {
        if (!canWrite || !SafePath.isLegalFileName(newName)) return false
        val file = resolve(path)
        val target = File(file.parentFile ?: return false, newName)
        return runCatching { file.renameTo(target) }.getOrDefault(false)
    }

    override fun availableBytes(): Long = runCatching { root.freeSpace }.getOrDefault(0L)

    override fun totalBytes(): Long = runCatching { root.totalSpace }.getOrDefault(0L)

    private fun joinPath(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
}

/** SAF-backed volume, used when the TV blocks direct filesystem access to a USB disk. */
class SafStorageVolume(
    override val id: String,
    override val displayName: String,
    private val treeUri: Uri,
    private val contentResolver: android.content.ContentResolver,
) : StorageVolume {

    override val isWritable: Boolean = runCatching {
        contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }
    }.getOrDefault(false)

    override val kind: VolumeKind = VolumeKind.OTHER
    override val rootPath: String = treeUri.toString()

    private val rootDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri)

    private fun documentId(relativePath: String): String {
        val segments = SafePath.segments(relativePath)
        if (segments.isEmpty()) return rootDocumentId
        return buildString {
            append(rootDocumentId)
            segments.forEach { append(':').append(it) }
        }
    }

    private fun childrenUri(relativePath: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId(relativePath))

    private fun documentUri(relativePath: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId(relativePath))

    private fun queryColumns(relativePath: String): Map<String, Any?>? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = runCatching {
            contentResolver.query(documentUri(relativePath), projection, null, null, null)
        }.getOrNull() ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return mapOf(
                "name" to it.getString(0),
                "mime" to it.getString(1),
                "size" to if (it.isNull(2)) 0L else it.getLong(2),
                "modified" to if (it.isNull(3)) 0L else it.getLong(3),
            )
        }
    }

    override fun list(path: String, showHiddenFiles: Boolean): List<StorageEntry>? {
        val parent = SafePath.normalise(path)
        val cursor = runCatching {
            contentResolver.query(childrenUri(path), null, null, null, null)
        }.getOrNull() ?: return null
        val entries = mutableListOf<StorageEntry>()
        cursor.use {
            val nameColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (nameColumn < 0) return null
            while (it.moveToNext()) {
                val name = it.getString(nameColumn) ?: continue
                if (!showHiddenFiles && name.startsWith(".")) continue
                val mime = if (mimeColumn >= 0) it.getString(mimeColumn) else null
                val size = if (sizeColumn >= 0 && !it.isNull(sizeColumn)) it.getLong(sizeColumn) else 0L
                val modified = if (modifiedColumn >= 0 && !it.isNull(modifiedColumn)) it.getLong(modifiedColumn) else 0L
                entries += StorageEntry(
                    name = name,
                    path = if (parent.isEmpty()) name else "$parent/$name",
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    sizeBytes = size,
                    lastModified = modified,
                )
            }
        }
        return entries.sortedWith(compareByDescending<StorageEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override fun stat(path: String): StorageEntry? {
        val columns = queryColumns(path) ?: return null
        val name = columns["name"] as? String ?: return null
        val mime = columns["mime"] as? String
        return StorageEntry(
            name = name,
            path = SafePath.normalise(path),
            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            sizeBytes = columns["size"] as? Long ?: 0L,
            lastModified = columns["modified"] as? Long ?: 0L,
        )
    }

    override fun openReadSource(path: String, offsetBytes: Long): ReadSource? {
        val descriptor = runCatching {
            contentResolver.openFileDescriptor(documentUri(path), "r")
        }.getOrNull() ?: return null
        val columns = queryColumns(path) ?: return null
        val length = columns["size"] as? Long ?: -1L
        return try {
            val stream = ParcelFileInputStream(descriptor)
            if (offsetBytes > 0) {
                if (!stream.skipFully(offsetBytes)) return null
            }
            object : ReadSource {
                override val lengthBytes: Long = if (length >= 0) (length - offsetBytes).coerceAtLeast(0L) else -1L
                override val stream: InputStream get() = stream
                override fun close() {
                    runCatching { stream.close() }
                }
            }
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            null
        }
    }

    override fun openWriteSource(path: String, append: Boolean): OutputStream? {
        val descriptor = runCatching {
            contentResolver.openFileDescriptor(documentUri(path), if (append) "wa" else "w")
        }.getOrNull() ?: return null
        val fileStream = java.io.FileOutputStream(descriptor.fileDescriptor)
        return object : java.io.FilterOutputStream(fileStream) {
            override fun close() {
                super.close()
                runCatching { descriptor.close() }
            }
        }
    }

    override fun createFile(parentPath: String, name: String): Boolean {
        if (!SafePath.isLegalFileName(name)) return false
        return runCatching {
            DocumentsContract.createDocument(
                contentResolver,
                documentUri(parentPath),
                "application/octet-stream",
                name,
            )
        }.getOrNull() != null
    }

    override fun createDirectory(parentPath: String, name: String): Boolean {
        if (!SafePath.isLegalFileName(name)) return false
        return runCatching {
            DocumentsContract.createDocument(
                contentResolver,
                documentUri(parentPath),
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )
        }.getOrNull() != null
    }

    override fun delete(path: String): Boolean = runCatching {
        DocumentsContract.deleteDocument(contentResolver, documentUri(path))
    }.getOrDefault(false)

    override fun rename(path: String, newName: String): Boolean {
        if (!SafePath.isLegalFileName(newName)) return false
        return runCatching {
            DocumentsContract.renameDocument(contentResolver, documentUri(path), newName)
        }.getOrNull() != null
    }

    override fun availableBytes(): Long = 0L

    override fun totalBytes(): Long = 0L
}

private class ParcelFileInputStream(
    descriptor: android.os.ParcelFileDescriptor,
) : InputStream() {

    private val stream = java.io.FileInputStream(descriptor.fileDescriptor)

    fun skipFully(count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
        return remaining == 0L
    }

    override fun read(): Int = stream.read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = stream.read(buffer, offset, length)

    override fun close() = stream.close()
}
