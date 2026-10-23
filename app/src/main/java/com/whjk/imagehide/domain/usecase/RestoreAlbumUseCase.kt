package com.whjk.imagehide.domain.usecase

import com.whjk.imagehide.domain.repository.HiddenRepository
import javax.inject.Inject

/**
 * 触发还原。
 *
 * 返回 workId 字符串，UI 用它监听 WorkInfo。
 */
class RestoreAlbumUseCase @Inject constructor(
    private val hidden: HiddenRepository
) {
    operator fun invoke(albumId: Long, overrideRelativePath: String? = null): String =
        hidden.submitRestore(albumId, overrideRelativePath)
}
