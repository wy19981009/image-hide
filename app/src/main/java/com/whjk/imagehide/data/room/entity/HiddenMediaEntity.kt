package com.whjk.imagehide.data.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 隐藏媒体。
 *
 * 记录每张被移走媒体的关键元数据，用于还原时写回 MediaStore。
 * 真实文件在 filesDir/hidden/{albumId}/{displayName}。
 */
@Entity(
    tableName = "hidden_media",
    foreignKeys = [
        ForeignKey(
            entity = HiddenAlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("albumId")]
)
data class HiddenMediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val albumId: Long,
    val mediaStoreId: Long,
    val displayName: String,
    val mimeType: String,
    val relativePath: String?,
    val takenAt: Long,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val privateFileName: String
)
