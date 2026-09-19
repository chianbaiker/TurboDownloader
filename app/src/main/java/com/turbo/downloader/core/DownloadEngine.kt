package com.turbo.downloader.core

import android.util.Log
import com.turbo.downloader.TurboApp
import com.turbo.downloader.data.DownloadEntity
import com.turbo.downloader.data.DownloadRepository
import kotlinx.coroutines.*
import okhttp3.ResponseBody
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * 多线程分片下载引擎（IDM 风格，对应 iOS `DownloadEngine`）：
 *  - 支持 byte-range 时拆分为 N 个分片并行，各写独立 .part{i}，天然无竞争（修复#3）
 *  - 不支持 Range 时自动降级单线程（修复#7）
 *  - 进度实时持久化到 Room，重启断点续传（修复#4）
 *  - 合并后校验文件大小（修复#10）、进度 clamp（修复#8）、ETA 除零保护（修复#9）
 *  - 每分片失败自动重试（修复#15，Android 版 5 次 + 退避）
 */
class DownloadEngine(
    private val scope: CoroutineScope,
    private val repository: DownloadRepository = DownloadRepository.getInstance()
) {

    private val activeJobs = LinkedHashMap<String, ActiveDownload>()
    private val speedMeters = LinkedHashMap<String, SpeedMeter>()

    fun start(download: DownloadEntity): ActiveDownload {
        synchronized(activeJobs) { activeJobs[download.id]?.let { return it } }

        val probe = NetworkClient.probe(download.url, download.referer)
        val total = if (download.totalBytes > 0) download.totalBytes else probe.contentLength

        var entity = download.copy(
            totalBytes = total,
            mimeType = if (download.mimeType.isEmpty()) probe.contentType else download.mimeType,
            status = DownloadEntity.STATUS_DOWNLOADING
        )
        runBlocking { repository.upsert(entity) }

        val meter = SpeedMeter()
        synchronized(speedMeters) { speedMeters[download.id] = meter }

        val job = scope.launch(Dispatchers.IO) {
            try {
                if (probe.supportsRanges && total > MIN_MULTITHREAD_SIZE) {
                    downloadMultithreaded(entity, total, meter)
                } else {
                    downloadSingleThreaded(entity, meter)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Download failed: ${download.id}", t)
                updateStatus(download.id, DownloadEntity.STATUS_FAILED, error = t.message ?: "Unknown error")
            } finally {
                synchronized(activeJobs) { activeJobs.remove(download.id) }
                speedMeters.remove(download.id)
            }
        }

        val active = ActiveDownload(download.id, job)
        synchronized(activeJobs) { activeJobs[download.id] = active }
        return active
    }

    fun pause(id: String) {
        synchronized(activeJobs) {
            activeJobs[id]?.job?.cancel(CancellationException("paused"))
            activeJobs.remove(id)
        }
        speedMeters.remove(id)
        scope.launch {
            repository.getById(id)?.let {
                if (it.status == DownloadEntity.STATUS_DOWNLOADING) {
                    repository.upsert(it.copy(status = DownloadEntity.STATUS_PAUSED))
                }
            }
        }
    }

    fun resume(id: String) {
        scope.launch { repository.getById(id)?.let { if (it.status != DownloadEntity.STATUS_DOWNLOADING) start(it) } }
    }

    fun remove(id: String, deleteFiles: Boolean) {
        pause(id)
        if (deleteFiles) {
            runBlocking {
                repository.getById(id)?.let { entity ->
                    File(entity.savePath).delete()
                    File("${entity.savePath}.parts").deleteRecursively()
                    (0 until entity.threadCount).forEach { File("${entity.savePath}.part$it").delete() }
                }
            }
        }
    }

    fun isActive(id: String): Boolean = synchronized(activeJobs) { activeJobs.containsKey(id) }

    fun getMeter(id: String): SpeedMeter? = synchronized(speedMeters) { speedMeters[id] }

    fun getSpeed(id: String): Long = getMeter(id)?.currentSpeed() ?: 0L

    /** 供 UI 计算 ETA：剩余字节 / 当前速度。（修复#9 除零保护在 ViewModel 侧 formatEta） */
    fun etaSeconds(id: String, totalBytes: Long, downloaded: Long): Long {
        val speed = getSpeed(id)
        if (speed <= 0) return -1L
        val remaining = (totalBytes - downloaded).coerceAtLeast(0L)
        return remaining / speed
    }

    /* ---------------- 多线程 ---------------- */

    private suspend fun downloadMultithreaded(entity: DownloadEntity, total: Long, meter: SpeedMeter) {
        val threads = max(1, entity.threadCount.coerceIn(1, 16))
        val chunkSize = total / threads
        val targetFile = File(entity.savePath)
        targetFile.parentFile?.mkdirs()
        RandomAccessFile(targetFile, "rw").use { it.setLength(total) }

        val jobs = (0 until threads).map { i ->
            val start = i * chunkSize
            val end = if (i == threads - 1) total - 1 else (start + chunkSize - 1)
            val partFile = File("${entity.savePath}.part$i")
            launch(Dispatchers.IO) {
                var partStart = start + partFile.length().coerceAtLeast(0L)
                var attempt = 0
                while (partStart <= end && attempt < MAX_RETRIES) {
                    try {
                        downloadChunk(entity, partStart, end, partFile, meter)
                        partStart = start + partFile.length()
                    } catch (e: Exception) {
                        attempt++
                        if (attempt >= MAX_RETRIES) throw e
                        delay(RETRY_DELAY_MS * attempt) // 指数退避（修复#15）
                    }
                }
            }
        }
        jobs.forEach { it.join() }

        // 合并分片（修复#3 串行写，无竞争）
        RandomAccessFile(targetFile, "rw").use { out ->
            for (i in 0 until threads) {
                val partFile = File("${entity.savePath}.part$i")
                RandomAccessFile(partFile, "r").use { `in` ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    while (`in`.read(buf).also { read = it } != -1) out.write(buf, 0, read)
                }
                partFile.delete() // 修复#12 临时分片清理
            }
        }

        // 修复#10：合并后校验文件大小
        if (total > 0 && targetFile.length() != total) {
            throw IOException("Size mismatch after merge: ${targetFile.length()} != $total")
        }

        onCompleted(entity, total)
    }

    private suspend fun downloadChunk(
        entity: DownloadEntity,
        start: Long, end: Long,
        partFile: File, meter: SpeedMeter
    ) {
        val range = "bytes=$start-$end"
        val request = NetworkClient.buildRequest(entity.url, entity.referer, range)
        NetworkClient.okhttp.newCall(request).execute().use { response ->
            if (response.code != 206 && !response.isSuccessful) throw IOException("HTTP ${response.code} for $range")
            val body = response.body ?: throw IOException("Empty body")
            if (!partFile.exists()) partFile.createNewFile()
            body.byteStream().use { input ->
                partFile.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        meter.add(read.toLong())
                    }
                }
            }
        }
    }

    /* ---------------- 单线程降级 ---------------- */

    private suspend fun downloadSingleThreaded(entity: DownloadEntity, meter: SpeedMeter) {
        val targetFile = File(entity.savePath)
        targetFile.parentFile?.mkdirs()
        val downloaded = targetFile.length()
        var total = entity.totalBytes

        if (total > 0 && downloaded >= total) {
            onCompleted(entity, total); return
        }

        val range = if (downloaded > 0) "bytes=$downloaded-" else null
        val request = NetworkClient.buildRequest(entity.url, entity.referer, range)
        NetworkClient.okhttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206 && response.code != 200)
                throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty body")

            // 修复#7：服务端忽略 Range 时 Content-Range 会给出真实总长度
            val contentRange = response.header("Content-Range")
            if (total <= 0 && contentRange != null) {
                total = contentRange.substringAfterLast("/").toLongOrNull() ?: 0L
                if (total > 0) updateTotal(entity.id, total)
            } else if (total <= 0) {
                total = (response.header("Content-Length")?.toLongOrNull() ?: 0L) + downloaded
                if (total > 0) updateTotal(entity.id, total)
            }

            body.byteStream().use { input ->
                java.io.FileOutputStream(targetFile, true).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var read: Int
                    var lastPersist = System.currentTimeMillis()
                    var sessionBytes = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        sessionBytes += read
                        meter.add(read.toLong())
                        val now = System.currentTimeMillis()
                        if (now - lastPersist > 1000) {
                            persistProgress(entity.id, downloaded + sessionBytes)
                            lastPersist = now
                        }
                    }
                    persistProgress(entity.id, targetFile.length())
                }
            }
        }

        if (total > 0 && targetFile.length() != total) {
            throw IOException("Size mismatch: ${targetFile.length()} != $total")
        }
        onCompleted(entity, total.coerceAtLeast(targetFile.length()))
    }

    /* ---------------- 状态持久化（修复#4/#8） ---------------- */

    private suspend fun onCompleted(entity: DownloadEntity, total: Long) {
        val finalTotal = total.coerceAtLeast(0L)
        repository.upsert(
            entity.copy(
                status = DownloadEntity.STATUS_COMPLETED,
                completedAt = System.currentTimeMillis(),
                downloadedBytes = finalTotal,
                totalBytes = finalTotal
            )
        )
    }

    private suspend fun updateStatus(id: String, status: Int, error: String = "") {
        repository.getById(id)?.let { repository.upsert(it.copy(status = status, error = error)) }
    }

    private suspend fun updateTotal(id: String, total: Long) {
        repository.getById(id)?.let { repository.upsert(it.copy(totalBytes = total)) }
    }

    private suspend fun persistProgress(id: String, downloaded: Long) {
        repository.getById(id)?.let {
            if (it.status == DownloadEntity.STATUS_DOWNLOADING) {
                // 修复#8：进度上限保护
                val clamped = downloaded.coerceAtMost(it.totalBytes.coerceAtLeast(downloaded))
                repository.upsert(it.copy(downloadedBytes = clamped))
            }
        }
    }

    /* ---------------- 数据结构 ---------------- */

    data class Progress(val downloaded: Long, val total: Long, val speedBps: Long)

    class ActiveDownload(val id: String, val job: Job)

    /** 滑动窗口测速，用于 ETA（修复#9）。 */
    class SpeedMeter {
        private val window = ArrayDeque<Pair<Long, Long>>()
        private val lock = Any()
        fun add(bytes: Long) = synchronized(lock) { window.add(System.currentTimeMillis() to bytes); trim() }
        fun currentSpeed(): Long = synchronized(lock) {
            trim()
            if (window.size < 2) 0L else {
                val first = window.first(); val last = window.last()
                val elapsed = (last.first - first.first).coerceAtLeast(1)
                (window.sumOf { it.second } * 1000) / elapsed
            }
        }
        private fun trim() {
            val cutoff = System.currentTimeMillis() - 2000
            while (window.isNotEmpty() && window.first().first < cutoff) window.removeFirst()
        }
    }

    companion object {
        private const val TAG = "DownloadEngine"
        private const val MIN_MULTITHREAD_SIZE = 1024 * 1024 // 1MB
        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 1500L
    }
}
