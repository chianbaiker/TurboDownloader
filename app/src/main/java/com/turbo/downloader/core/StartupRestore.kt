package com.turbo.downloader.core

import android.content.Context
import com.turbo.downloader.data.DownloadEntity
import com.turbo.downloader.data.DownloadRepository
import com.turbo.downloader.download.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 应用启动恢复（对应 iOS 修复 #6 `getTasksWithCompletionHandler`）。
 * 启动时扫描 PAUSED / QUEUED / FAILED 任务，自动重新入队续传。
 */
object StartupRestore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun restoreAsync(context: Context) {
        scope.launch { restoreNow(context) }
    }

    /** 一次性拉取全量列表并恢复未完成下载。 */
    fun restoreNow(context: Context) {
        val repository = DownloadRepository.getInstance()
        val pending = runCatching {
            repository.observeAll().first().filter {
                it.status == DownloadEntity.STATUS_PAUSED ||
                        it.status == DownloadEntity.STATUS_QUEUED ||
                        it.status == DownloadEntity.STATUS_FAILED
            }
        }.getOrDefault(emptyList())

        pending.forEach { entity ->
            val resumed = entity.copy(status = DownloadEntity.STATUS_QUEUED, error = "")
            kotlinx.coroutines.runBlocking { repository.upsert(resumed) }
            DownloadService.startDownload(context, entity.id)
        }
    }
}
