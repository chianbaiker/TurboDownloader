package com.turbo.downloader.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Update
    suspend fun update(entity: DownloadEntity)

    @Delete
    suspend fun delete(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE status = :status")
    suspend fun deleteByStatus(status: Int)

    @Query("DELETE FROM downloads WHERE status = ${DownloadEntity.STATUS_COMPLETED} AND completedAt < :before")
    suspend fun deleteCompletedBefore(before: Long)

    @Query("SELECT COUNT(*) FROM downloads WHERE status = ${DownloadEntity.STATUS_DOWNLOADING}")
    suspend fun countActive(): Int
}
