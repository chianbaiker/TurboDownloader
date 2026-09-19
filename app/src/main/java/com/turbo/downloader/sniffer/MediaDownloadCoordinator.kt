package com.turbo.downloader.sniffer

import android.content.Context
import android.util.Log
import com.turbo.downloader.core.StorageChecker
import com.turbo.downloader.data.DownloadEntity
import com.turbo.downloader.data.DownloadRepository
import com.turbo.downloader.download.DownloadManager
import com.turbo.downloader.sniffer.dash.DashDownloader
import com.turbo.downloader.sniffer.hls.HlsDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 媒体下载协调器：嗅探得到 [MediaItem] 后，按类型自动路由——
 *   m3u8 → HlsDownloader
 *   mpd  → DashDownloader
 *   直链  → 普通多线程 DownloadEngine
 * 统一产出单个 .mp4，写入 Room 的 DownloadEntity（复用同一张任务表，UI 零改动）。
 *
 * 这是把「网页嗅探 → 下载」串成闭环的一层（IDM 的完整链路）。
 */
class MediaDownloadCoordinator(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = DownloadRepository.getInstance()
    val progress = MutableStateFlow<Pair<String, Int>?>(null) // taskId -> percent

    fun enqueue(item: MediaItem, saveName: String? = null) {
        scope.launch {
            try {
                when (item.type) {
                    MediaItem.TYPE_HLS -> downloadHls(item, saveName)
                    MediaItem.TYPE_DASH -> downloadDash(item, saveName)
                    else -> downloadDirect(item, saveName)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "media download failed: ${item.url}", t)
            }
        }
    }

    private suspend fun downloadHls(item: MediaItem, saveName: String?) {
        val dir = StorageChecker.defaultDir().apply { mkdirs() }
        val name = (saveName ?: item.title ?: "video").let { if (it.contains(".")) it else "$it.mp4" }
        val entity = makeEntity(item, name, totalBytes = 0L)
        repository.upsert(entity)
        HlsDownloader(
            onProgress = { done, total, bps -> updateProgress(entity.id, done, total, bps) },
            onLog = { Log.d(TAG, it) }
        ).download(item.url, dir, name).onSuccess { file ->
            completeEntity(entity.id, file.length())
        }.onFailure { completeEntity(entity.id, 0L, error = it.message ?: "HLS download failed") }
    }

    private suspend fun downloadDash(item: MediaItem, saveName: String?) {
        val dir = StorageChecker.defaultDir().apply { mkdirs() }
        val name = (saveName ?: item.title ?: "video").let { if (it.contains(".")) it.substringBeforeLast(".") else it }
        val entity = makeEntity(item, "$name.mp4", totalBytes = 0L)
        repository.upsert(entity)
        DashDownloader(
            onProgress = { done, total -> updateProgress(entity.id, done, total, 0L) },
            onLog = { Log.d(TAG, it) }
        ).download(item.url, dir, name).onSuccess { file ->
            completeEntity(entity.id, file.length())
        }.onFailure { completeEntity(entity.id, 0L, error = it.message ?: "DASH download failed") }
    }

    private fun downloadDirect(item: MediaItem, saveName: String?) {
        // 直链：直接走普通多线程下载（DownloadManager.enqueue 已含空间检查/重试/断点）
        DownloadManager.getInstance(context).enqueue(
            url = item.url,
            referer = item.pageUrl ?: "",
            threadCount = 8,
            saveName = saveName ?: item.title
        )
    }

    private suspend fun makeEntity(item: MediaItem, fileName: String, totalBytes: Long): DownloadEntity {
        val dir = StorageChecker.defaultDir()
        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return DownloadEntity(
            id = java.util.UUID.randomUUID().toString(),
            url = item.url,
            fileName = safeName,
            savePath = File(dir, safeName).absolutePath,
            totalBytes = totalBytes,
            status = DownloadEntity.STATUS_DOWNLOADING,
            threadCount = 1, // HLS/DASH 内部自行并发，外层单线程
            category = DownloadEntity.CATEGORY_VIDEO,
            mimeType = "video/mp4",
            referer = item.pageUrl ?: "",
            error = ""
        )
    }

    private suspend fun updateProgress(id: String, done: Int, total: Int, bps: Long) {
        repository.getById(id)?.let { e ->
            val percent = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
            repository.upsert(e.copy(downloadedBytes = percent.toLong(), totalBytes = total.toLong()))
            progress.value = id to percent
        }
    }

    private suspend fun completeEntity(id: String, size: Long, error: String = "") {
        repository.getById(id)?.let { e ->
            repository.upsert(
                e.copy(
                    status = if (error.isEmpty() && size > 0) DownloadEntity.STATUS_COMPLETED else DownloadEntity.STATUS_FAILED,
                    completedAt = System.currentTimeMillis(),
                    downloadedBytes = size,
                    totalBytes = if (e.totalBytes <= 0) size else e.totalBytes,
                    error = error
                )
            )
        }
    }

    companion object {
        private const val TAG = "MediaCoordinator"
    }
}
