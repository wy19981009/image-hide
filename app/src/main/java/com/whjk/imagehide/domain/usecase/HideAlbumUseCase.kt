package com.whjk.imagehide.domain.usecase

import com.whjk.imagehide.domain.repository.HiddenRepository
import javax.inject.Inject

/**
 * 触发隐藏。
 *
 * 返回 workId 字符串，UI 用它监听 WorkInfo。
 */
class HideAlbumUseCase @Inject constructor(
    private val hidden: HiddenRepository
) {
    operator fun invoke(bucketId: Long, albumName: String): String =
        hidden.submitHide(bucketId, albumName)
}
