package com.whjk.imagehide.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.whjk.imagehide.data.room.entity.HiddenMediaEntity

@Dao
interface HiddenMediaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<HiddenMediaEntity>)

    @Query("SELECT * FROM hidden_media WHERE albumId = :albumId")
    suspend fun listByAlbum(albumId: Long): List<HiddenMediaEntity>

    @Query("DELETE FROM hidden_media WHERE albumId = :albumId")
    suspend fun deleteByAlbum(albumId: Long)

    @Query("SELECT * FROM hidden_media WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): HiddenMediaEntity?

    @Query("SELECT * FROM hidden_media WHERE id IN (:ids)")
    suspend fun listByIds(ids: List<Long>): List<HiddenMediaEntity>

    @Query("DELETE FROM hidden_media WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM hidden_media WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM hidden_media WHERE mediaStoreId = :mediaStoreId")
    suspend fun deleteByMediaStoreId(mediaStoreId: Long)

    @Query("DELETE FROM hidden_media WHERE albumId = :albumId AND mediaStoreId = :mediaStoreId")
    suspend fun deleteByAlbumAndMediaStoreId(albumId: Long, mediaStoreId: Long)
}
