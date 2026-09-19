package com.turbo.downloader.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(DownloadService.EXTRA_ID) ?: return
        val serviceIntent = Intent(context, DownloadService::class.java).apply {
            putExtra(DownloadService.EXTRA_ID, id)
        }
        when (intent.action) {
            DownloadService.ACTION_PAUSE -> serviceIntent.action = DownloadService.ACTION_PAUSE
            DownloadService.ACTION_RESUME -> { DownloadService.startDownload(context, id); return }
            DownloadService.ACTION_CANCEL -> serviceIntent.action = DownloadService.ACTION_CANCEL
            else -> return
        }
        context.startService(serviceIntent)
    }
}
