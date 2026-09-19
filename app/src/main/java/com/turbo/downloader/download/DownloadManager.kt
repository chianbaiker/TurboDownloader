package com.turbo.downloader.download

import android.content.Context
import com.turbo.downloader.TurboApp
import com.turbo.downloader.core.DownloadEngine
import com.turbo.downloader.core.NetworkClient
import com.turbo.downloader.core.StorageChecker
import com.turbo.downloader.data.DownloadEntity
import com.turbo.downloader.data.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 高层门面（对应 iOS `DownloadManager`）：
 *   enqueue / pause / resume / cancel + 下载队列 + 并发限制 + 断点恢复。
 * 集成：
 *   - 修复#16 存储空间检查
 *   - 修复#6  启动断点恢复
 *   - 修复#14 DiffUtil 稳定 id（entity.id 永不变）
 */
class DownloadManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = DownloadEngine(scope)
    private val repository = DownloadRepository.getInstance()
    private val queue = ConcurrentLinkedQueue<String>()
    private val maxConcurrent = 3

    /** 入队前做一次空间检查 + 探测，再进入队列。 */
    fun enqueue(
        url: String,
        referer: String = "",
        threadCount: Int = 8,
        saveName: String? = null,
        onError: (String) -> Unit = {}
    ) {
        scope.launch {
            try {
                val probe = NetworkClient.probe(url, referer)
                val fileName = (saveName ?: probe.fileName).ifBlank { "download.bin" }
                val dir = StorageChecker.defaultDir().apply { mkdirs() }
                val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val savePath = File(dir, safeName).absolutePath

                // 修复#16：空间不足提前拒绝
                val total = probe.contentLength
                StorageChecker.check(dir, total).let { err ->
                    if (err != null) {
                        onError(err)
                        return@launch
                    }
                }

                val entity = DownloadEntity(
                    id = UUID.randomUUID().toString(),
                    url = probe.finalUrl,
                    fileName = safeName,
                    savePath = savePath,
                    totalBytes = total,
                    mimeType = probe.contentType,
                    referer = referer,
                    threadCount = threadCount.coerceIn(1, 16),
                    category = com.turbo.downloader.core.CategoryClassifier.classify(safeName, probe.contentType),
                    status = DownloadEntity.STATUS_QUEUED
                )
                repository.upsert(entity)
                queue.offer(entity.id)
                pumpQueue()
            } catch (t: Throwable) {
                onError(t.message ?: "Enqueue failed")
            }
        }
    }

    /** 串行队列泵送，遵守 maxConcurrent 并发上限。 */
    private fun pumpQueue() {
        scope.launch {
            val activeCount = currentActiveCount()
            repeat(maxConcurrent - activeCount) {
                val id = queue.poll() ?: return@repeat
                DownloadService.startDownload(context, id)
            }
        }
    }

    private suspend fun currentActiveCount(): Int =
        repository.observeAll().first().count { it.status == DownloadEntity.STATUS_DOWNLOADING }

    fun pause(id: String) = engine.pause(id)

    fun resume(id: String) = DownloadService.startDownload(context, id)

    fun cancel(id: String, deleteFiles: Boolean) = engine.remove(id, deleteFiles)

    fun getEngine(): DownloadEngine = engine

    companion object {
        @Volatile private var INSTANCE: DownloadManager? = null
        fun getInstance(context: Context = TurboApp.instance): DownloadManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadManager(context).also { INSTANCE = it }
            }
    }
}
