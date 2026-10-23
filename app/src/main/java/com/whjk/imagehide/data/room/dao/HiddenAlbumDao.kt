package com.whjk.imagehide.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.whjk.imagehide.data.room.entity.HiddenAlbumEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HiddenAlbumDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(album: HiddenAlbumEntity): Long

    @Query("UPDATE hidden_albums SET count = :count WHERE id = :id")
    suspend fun updateCount(id: Long, count: Int)

    @Query("SELECT * FROM hidden_albums ORDER BY hiddenAt DESC")
    fun observeAll(): Flow<List<HiddenAlbumEntity>>

    @Query("SELECT * FROM hidden_albums WHERE originalBucketId = :bucketId LIMIT 1")
    suspend fun findByBucketId(bucketId: Long): HiddenAlbumEntity?

    @Query("SELECT * FROM hidden_albums WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): HiddenAlbumEntity?

    @Query("DELETE FROM hidden_albums WHERE id = :id")
    suspend fun deleteById(id: Long)
}
