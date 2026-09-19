package com.turbo.downloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbo.downloader.data.DownloadEntity
import com.turbo.downloader.data.DownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

class CategoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = DownloadRepository.getInstance()
    val currentCategory = MutableStateFlow<Int?>(null)

    val downloads: Flow<List<DownloadEntity>> = currentCategory.flatMapLatest { cat ->
        if (cat == null) repository.observeAll() else repository.observeByCategory(cat)
    }

    fun setCategory(cat: Int?) {
        currentCategory.value = cat
    }

    fun clearCompleted() {
        viewModelScope.launch { repository.deleteByStatus(DownloadEntity.STATUS_COMPLETED) }
    }
}
