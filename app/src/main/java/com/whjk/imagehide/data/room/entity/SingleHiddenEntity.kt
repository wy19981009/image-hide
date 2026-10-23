package com.whjk.imagehide.data.room.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单张隐藏的媒体（不含整相册隐藏的）。
 *
 * - 主键 = MediaStoreId，保证幂等：同一张图反复隐藏不会重复写入
 * - 真实文件在 filesDir/hidden/single/{privateFileName}
 */
@Entity(
    tableName = "single_hidden_media",
    indices = [Index("hiddenAt")]
)
data class SingleHiddenEntity(
    @PrimaryKey val mediaStoreId: Long,
    val displayName: String,
    val mimeType: String,
    val relativePath: String?,
    val takenAt: Long,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val privateFileName: String,
    val hiddenAt: Long
)
