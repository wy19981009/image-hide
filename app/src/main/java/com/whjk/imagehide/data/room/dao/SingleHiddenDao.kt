package com.whjk.imagehide.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.whjk.imagehide.data.room.entity.SingleHiddenEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SingleHiddenDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: SingleHiddenEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<SingleHiddenEntity>)

    @Query("SELECT * FROM single_hidden_media ORDER BY hiddenAt DESC")
    fun observeAll(): Flow<List<SingleHiddenEntity>>

    @Query("SELECT * FROM single_hidden_media WHERE mediaStoreId IN (:ids)")
    suspend fun listByIds(ids: List<Long>): List<SingleHiddenEntity>

    @Query("SELECT COUNT(*) FROM single_hidden_media")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM single_hidden_media WHERE mediaStoreId = :id")
    suspend fun countById(id: Long): Int

    @Query("DELETE FROM single_hidden_media WHERE mediaStoreId = :id")
    suspend fun deleteById(id: Long)
}
