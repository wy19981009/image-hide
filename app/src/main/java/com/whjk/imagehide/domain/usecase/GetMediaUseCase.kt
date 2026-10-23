package com.whjk.imagehide.domain.usecase

import com.whjk.imagehide.domain.model.MediaItem
import com.whjk.imagehide.domain.repository.MediaRepository
import javax.inject.Inject

/**
 * 通用：根据 albumId + 来源（系统/隐藏/单张）拉媒体列表
 */
class GetMediaUseCase @Inject constructor(
    private val media: MediaRepository
) {
    /**
     * @param albumId bucketId（系统相册）或 hidden_albums.id（隐藏相册）
     * @param hidden true=从私有目录读，false=从 MediaStore 读
     * @param single 强制从单张隐藏读（与 hidden 互斥；single 优先）
     */
    suspend operator fun invoke(albumId: Long, hidden: Boolean, single: Boolean = false): List<MediaItem> {
        if (single) return media.listSingleHidden()
        return if (hidden) media.listHiddenMedia(albumId)
        else media.listSystemMediaByBucket(albumId)
    }

    suspend fun listSingleHidden(): List<MediaItem> = media.listSingleHidden()
}
