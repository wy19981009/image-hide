package com.whjk.imagehide.data.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 隐藏相册。
 *
 * 一个被隐藏的相册对应一行。
 * 关键：原 bucketId 作为唯一索引，防止重复隐藏同一相册。
 */
@Entity(
    tableName = "hidden_albums",
    indices = [Index(value = ["originalBucketId"], unique = true)]
)
data class HiddenAlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** MediaStore 原 BUCKET_ID */
    val originalBucketId: Long,
    /** 相册显示名（隐藏时快照） */
    val displayName: String,
    /** 媒体总数 */
    val count: Int,
    /** 隐藏完成时间戳（毫秒） */
    val hiddenAt: Long
)
