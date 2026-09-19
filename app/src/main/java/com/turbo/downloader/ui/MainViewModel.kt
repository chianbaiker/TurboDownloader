package com.turbo.downloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.turbo.downloader.TurboApp
import com.turbo.downloader.data.DownloadEntity
import com.turbo.downloader.data.DownloadRepository
import com.turbo.downloader.download.DownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主界面 ViewModel：把 DownloadEntity 填充 进度/速度/ETA 运行时字段后交给 Adapter。
 *  - 修复#8  进度 clamp 到 [0,100]
 *  - 修复#9  ETA 除零保护（speed<=0 返回 -1）
 *  - 修复#14  id 永不变，DiffUtil 列表不抖动
 */
class MainViewModel(application: Application) : ViewModel() {

    private val repository = DownloadRepository.getInstance()
    private val manager = DownloadManager.getInstance(application)

    private val _uiModels = MutableStateFlow<List<DownloadEntity>>(emptyList())
    val uiModels: StateFlow<List<DownloadEntity>> = _uiModels.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { list ->
                _uiModels.value = list.map { fillRuntime(it) }
            }
        }
    }

    private fun fillRuntime(entity: DownloadEntity): DownloadEntity {
        val engine = manager.getEngine()
        entity.setRuntime(
            engine.getSpeed(entity.id),
            engine.etaSeconds(entity.id, entity.totalBytes, entity.downloadedBytes)
        )
        return entity
    }

    fun pause(id: String) = manager.pause(id)
    fun resume(id: String) = manager.resume(id)
    fun cancel(id: String, deleteFiles: Boolean) = manager.cancel(id, deleteFiles)
}
