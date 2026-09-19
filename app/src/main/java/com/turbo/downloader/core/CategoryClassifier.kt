package com.turbo.downloader.core

import com.turbo.downloader.data.DownloadEntity

/** 按扩展名 / MIME 分类，供首页 Tab 筛选（对应 iOS 版 CategoryClassifier）。 */
object CategoryClassifier {

    fun classify(fileName: String, mimeType: String): Int {
        val ext = fileName.substringAfterLast(".", "").lowercase()
        val mime = mimeType.lowercase()
        return when {
            mime.startsWith("video/") || ext in VIDEO -> DownloadEntity.CATEGORY_VIDEO
            mime.startsWith("audio/") || ext in AUDIO -> DownloadEntity.CATEGORY_AUDIO
            mime.startsWith("image/") || ext in IMAGE -> DownloadEntity.CATEGORY_IMAGE
            ext in ARCHIVE -> DownloadEntity.CATEGORY_ARCHIVE
            ext == "apk" || ext == "xapk" -> DownloadEntity.CATEGORY_APK
            ext in DOCUMENT || mime.startsWith("application/pdf") || mime.startsWith("text/") -> DownloadEntity.CATEGORY_DOCUMENT
            else -> DownloadEntity.CATEGORY_OTHER
        }
    }

    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "ts", "m3u8")
    private val AUDIO = setOf("mp3", "wav", "ogg", "flac", "aac", "m4a", "wma", "opus")
    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "heic")
    private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tar.gz", "tar.bz2")
    private val DOCUMENT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub", "mobi", "txt", "csv", "json", "xml")
}
