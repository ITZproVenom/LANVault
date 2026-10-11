package com.lanvault.web

import com.lanvault.storage.StorageEntry

object MimeTypes {

    private val map = HashMap<String, String>().apply {
        put("txt", "text/plain")
        put("log", "text/plain")
        put("md", "text/markdown")
        put("markdown", "text/markdown")
        put("html", "text/html")
        put("htm", "text/html")
        put("css", "text/css")
        put("js", "text/javascript")
        put("mjs", "text/javascript")
        put("json", "application/json")
        put("xml", "application/xml")
        put("csv", "text/csv")
        put("pdf", "application/pdf")
        put("zip", "application/zip")
        put("gz", "application/gzip")
        put("tar", "application/x-tar")
        put("7z", "application/x-7z-compressed")
        put("rar", "application/vnd.rar")
        put("apk", "application/vnd.android.package-archive")
        put("epub", "application/epub+zip")
    }

    private val video = HashMap<String, String>().apply {
        put("mp4", "video/mp4")
        put("m4v", "video/mp4")
        put("mkv", "video/x-matroska")
        put("webm", "video/webm")
        put("mov", "video/quicktime")
        put("avi", "video/x-msvideo")
        put("wmv", "video/x-ms-wmv")
        put("flv", "video/x-flv")
        put("mpeg", "video/mpeg")
        put("mpg", "video/mpeg")
        put("3gp", "video/3gpp")
        put("ts", "video/mp2t")
        put("m2ts", "video/mp2t")
        put("ogv", "video/ogg")
        put("divx", "video/divx")
        put("asf", "video/x-ms-asf")
    }

    private val audio = HashMap<String, String>().apply {
        put("mp3", "audio/mpeg")
        put("m4a", "audio/mp4")
        put("aac", "audio/aac")
        put("wav", "audio/wav")
        put("flac", "audio/flac")
        put("ogg", "audio/ogg")
        put("oga", "audio/ogg")
        put("opus", "audio/opus")
        put("wma", "audio/x-ms-wma")
        put("mid", "audio/midi")
        put("midi", "audio/midi")
        put("aiff", "audio/aiff")
        put("amr", "audio/amr")
    }

    private val image = HashMap<String, String>().apply {
        put("jpg", "image/jpeg")
        put("jpeg", "image/jpeg")
        put("png", "image/png")
        put("gif", "image/gif")
        put("webp", "image/webp")
        put("bmp", "image/bmp")
        put("heic", "image/heic")
        put("heif", "image/heif")
        put("tif", "image/tiff")
        put("tiff", "image/tiff")
        put("svg", "image/svg+xml")
        put("ico", "image/x-icon")
        put("avif", "image/avif")
    }

    fun forName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val type = video[extension] ?: audio[extension] ?: image[extension] ?: map[extension]
            ?: "application/octet-stream"
        return if (type.startsWith("text/")) "$type; charset=utf-8" else type
    }

    fun isVideo(fileName: String): Boolean =
        video.containsKey(fileName.substringAfterLast('.', "").lowercase())

    fun isAudio(fileName: String): Boolean =
        audio.containsKey(fileName.substringAfterLast('.', "").lowercase())

    fun isImage(fileName: String): Boolean =
        image.containsKey(fileName.substringAfterLast('.', "").lowercase())

    fun canPreviewInBrowser(entry: StorageEntry): Boolean =
        !entry.isDirectory && (
            isVideo(entry.name) || isAudio(entry.name) || isImage(entry.name) ||
                listOf("txt", "md", "markdown", "json", "csv", "pdf", "html", "xml")
                    .contains(entry.extension())
            )

    fun isSubtitle(fileName: String): Boolean =
        listOf("srt", "vtt", "sub", "ass", "ssa").contains(fileName.substringAfterLast('.', "").lowercase())
}
