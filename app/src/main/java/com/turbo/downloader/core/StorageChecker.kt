package com.turbo.downloader.core

import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * 存储空间检查（对应 iOS 版修复 #16）。
 * 下载开始前校验目标分区可用空间，不足时提前报错，避免写到一半 ENOSPC。
 */
object StorageChecker {

    fun availableBytes(path: File): Long = runCatching {
        val stat = StatFs(path.absolutePath)
        stat.blockSizeLong * stat.availableBlocksLong
    }.getOrDefault(Long.MAX_VALUE)

    /** @return null = 通过；非 null = 错误信息 */
    fun check(path: File, requiredBytes: Long): String? {
        if (requiredBytes <= 0) return null
        val avail = availableBytes(path)
        val safe = avail - RESERVE_BYTES
        return if (safe < requiredBytes) {
            "存储空间不足：需要 ${DownloadAdapter.Companion.formatSize(requiredBytes)}，" +
                    "可用 ${DownloadAdapter.Companion.formatSize(safe)}"
        } else null
    }

    fun defaultDir(): File {
        val ext = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (ext != null && (ext.exists() || ext.mkdirs())) return ext
        return File(android.app.Application.getProcessName().ifBlank { "/data/local/tmp" })
    }

    private const val RESERVE_BYTES = 32L * 1024 * 1024 // 预留 32MB
}
