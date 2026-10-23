package com.whjk.imagehide.domain.repository

import com.whjk.imagehide.domain.model.MediaItem

interface MediaRepository {
    /** 列出指定 BUCKET 下所有媒体 */
    suspend fun listSystemMediaByBucket(bucketId: Long): List<MediaItem>

    /** 列出隐藏相册下的媒体（从私有目录或 Room 索引） */
    suspend fun listHiddenMedia(albumId: Long): List<MediaItem>

    /** 列出所有单张隐藏的媒体 */
    suspend fun listSingleHidden(): List<MediaItem>
}
