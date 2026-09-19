package com.turbo.downloader

import android.app.Application
import com.turbo.downloader.core.StartupRestore
import com.turbo.downloader.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class TurboApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob())

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 修复#6：应用启动后恢复所有未完成任务（断点续传）
        StartupRestore.restoreAsync(this)
    }

    companion object {
        lateinit var instance: TurboApp
            private set
    }
}
