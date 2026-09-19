package com.turbo.downloader.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.turbo.downloader.R
import com.turbo.downloader.TurboApp
import com.turbo.downloader.core.DownloadEngine
import com.turbo.downloader.data.DownloadEntity
import com.turbo.downloader.data.DownloadRepository
import com.turbo.downloader.ui.MainActivity
import kotlinx.coroutines.*

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: DownloadEngine
    private lateinit var repository: DownloadRepository
    private var progressJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        engine = DownloadEngine(serviceScope)
        repository = DownloadRepository.getInstance()
        createChannel()
        startForeground(NOTIF_ID, buildSummaryNotification().build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_STICKY
                serviceScope.launch {
                    repository.getById(id)?.let { entity ->
                        postProgressNotification(entity, 0)
                        engine.start(entity)
                    }
                }
            }
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let { engine.pause(it) }
            ACTION_RESUME -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_STICKY
                serviceScope.launch { repository.getById(id)?.let { engine.start(it) } }
            }
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let { engine.pause(it) }
            ACTION_SHUTDOWN -> stopSelf()
        }
        return START_STICKY
    }

    /** 由外部调用以刷新某任务进度通知（修复#11：通知携带 taskId 正确路由）。 */
    fun postProgressNotification(entity: DownloadEntity, progress: Int) {
        val notif = buildDownloadNotification(entity, progress).build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID + entity.id.hashCode(), notif)
    }

    private fun buildDownloadNotification(entity: DownloadEntity, progress: Int): NotificationCompat.Builder {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ID, entity.id) // 修复#11：点击携带 taskId 路由
        }
        val pending = PendingIntent.getActivity(
            this, entity.id.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 暂停 / 取消 action 按钮同样带 taskId
        val pauseIntent = Intent(this, DownloadReceiver::class.java).apply {
            action = ACTION_PAUSE; putExtra(EXTRA_ID, entity.id)
        }
        val cancelIntent = Intent(this, DownloadReceiver::class.java).apply {
            action = ACTION_CANCEL; putExtra(EXTRA_ID, entity.id)
        }
        val pausePi = PendingIntent.getBroadcast(
            this, entity.id.hashCode() + 1, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancelPi = PendingIntent.getBroadcast(
            this, entity.id.hashCode() + 2, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(entity.fileName)
            .setContentText("${entity.statusText()} • ${progress}%")
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(pending)
            .setOngoing(entity.status == DownloadEntity.STATUS_DOWNLOADING)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .addAction(R.drawable.ic_pause, getString(R.string.pause_resume), pausePi)
            .addAction(R.drawable.ic_cancel, getString(R.string.cancel), cancelPi)
    }

    private fun buildSummaryNotification(): NotificationCompat.Builder {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("TurboDownloader running")
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_description) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        progressJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun DownloadEntity.statusText(): String = when (status) {
        DownloadEntity.STATUS_DOWNLOADING -> "Downloading"
        DownloadEntity.STATUS_PAUSED -> "Paused"
        DownloadEntity.STATUS_QUEUED -> "Queued"
        DownloadEntity.STATUS_COMPLETED -> "Completed"
        else -> "Processing"
    }

    companion object {
        const val ACTION_START = "com.turbo.downloader.action.START"
        const val ACTION_PAUSE = "com.turbo.downloader.action.PAUSE"
        const val ACTION_RESUME = "com.turbo.downloader.action.RESUME"
        const val ACTION_CANCEL = "com.turbo.downloader.action.CANCEL"
        const val ACTION_SHUTDOWN = "com.turbo.downloader.action.SHUTDOWN"
        const val EXTRA_ID = "extra_id"
        const val CHANNEL_ID = "turbo_downloads"
        const val NOTIF_ID = 1001

        fun startDownload(context: Context, id: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ID, id)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
