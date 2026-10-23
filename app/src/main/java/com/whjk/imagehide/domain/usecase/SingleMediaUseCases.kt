package com.whjk.imagehide.domain.usecase

import com.whjk.imagehide.domain.model.MediaItem
import com.whjk.imagehide.domain.repository.HiddenRepository
import javax.inject.Inject

/**
 * 单张/批量隐藏。
 */
class SingleHideUseCase @Inject constructor(
    private val hidden: HiddenRepository
) {
    operator fun invoke(items: List<MediaItem>): String =
        hidden.submitSingleHide(items)
}

/**
 * 单张/批量还原。
 */
class SingleRestoreUseCase @Inject constructor(
    private val hidden: HiddenRepository
) {
    operator fun invoke(ids: List<Long>): String =
        hidden.submitSingleRestore(ids)
}
