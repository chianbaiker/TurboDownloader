package com.turbo.downloader.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map

class DownloadRepository(private val dao: DownloadDao) {

    fun observeAll(): Flow<List<DownloadEntity>> = dao.observeAll().conflate()

    fun observeByCategory(category: Int): Flow<List<DownloadEntity>> =
        dao.observeAll().map { list -> list.filter { it.category == category } }.conflate()

    suspend fun getById(id: String): DownloadEntity? = dao.getById(id)

    suspend fun upsert(entity: DownloadEntity) = dao.upsert(entity)

    suspend fun deleteByStatus(status: Int) = dao.deleteByStatus(status)

    companion object {
        @Volatile private var INSTANCE: DownloadRepository? = null
        fun getInstance(): DownloadRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadRepository(AppDatabase.getInstance().downloadDao()).also { INSTANCE = it }
            }
    }
}
