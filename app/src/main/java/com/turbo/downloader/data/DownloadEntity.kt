package com.turbo.downloader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val url: String,
    val fileName: String,
    val savePath: String,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val status: Int = STATUS_PENDING,
    val threadCount: Int = 8,
    val category: Int = CATEGORY_OTHER,
    val mimeType: String = "",
    val referer: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L,
    val error: String = ""
) {
    /** UI 运行时字段：当前速度 bytes/s（修复#9）。lateinit 避免 data-class copy 重置。 */
    @androidx.room.Ignore
    lateinit var _speedBps: java.util.concurrent.atomic.AtomicLong
    @androidx.room.Ignore
    lateinit var _etaSeconds: java.util.concurrent.atomic.AtomicLong

    val speedBps: Long get() = if (::_speedBps.isInitialized) _speedBps.get() else 0L
    val etaSeconds: Long get() = if (::_etaSeconds.isInitialized) _etaSeconds.get() else -1L

    fun setRuntime(speedBps: Long, etaSeconds: Long) {
        if (!::_speedBps.isInitialized) _speedBps = java.util.concurrent.atomic.AtomicLong()
        if (!::_etaSeconds.isInitialized) _etaSeconds = java.util.concurrent.atomic.AtomicLong()
        _speedBps.set(speedBps)
        _etaSeconds.set(etaSeconds)
    }

    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_DOWNLOADING = 1
        const val STATUS_PAUSED = 2
        const val STATUS_COMPLETED = 3
        const val STATUS_FAILED = 4
        const val STATUS_QUEUED = 5

        const val CATEGORY_VIDEO = 0
        const val CATEGORY_AUDIO = 1
        const val CATEGORY_DOCUMENT = 2
        const val CATEGORY_ARCHIVE = 3
        const val CATEGORY_APK = 4
        const val CATEGORY_IMAGE = 5
        const val CATEGORY_OTHER = 6
    }
}
