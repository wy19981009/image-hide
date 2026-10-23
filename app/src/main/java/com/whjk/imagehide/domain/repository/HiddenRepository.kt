package com.whjk.imagehide.domain.repository

import com.whjk.imagehide.domain.model.Album
import com.whjk.imagehide.domain.model.MediaItem

/**
 * 隐藏/还原入口。
 *
 * UI 层调这里的几个方法，内部把任务丢给 WorkManager / 走 Room 查。
 */
interface HiddenRepository {
    /** 提交整相册隐藏任务 */
    fun submitHide(bucketId: Long, albumName: String): String

    /**
     * 提交整相册还原任务。
     * @param overrideRelativePath 用户选择的目标目录；null 表示沿用每张图原始路径
     */
    fun submitRestore(albumId: Long, overrideRelativePath: String? = null): String

    /** 列出所有已隐藏相册 */
    suspend fun listHiddenAlbums(): List<Album>

    /** 提交单张/批量隐藏任务（item.id 即 MediaStore._ID） */
    fun submitSingleHide(items: List<MediaItem>): String

    /** 提交单张/批量还原任务（ids = mediaStoreId 列表） */
    fun submitSingleRestore(ids: List<Long>): String
}
