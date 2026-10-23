package com.whjk.imagehide.domain.model

/**
 * 隐藏/还原进度上报。
 *
 * Worker 通过 setProgress() 推进，UI 通过 WorkManager.getWorkInfoByIdLiveData 监听。
 */
data class HideProgress(
    val total: Int,
    val done: Int
) {
    val ratio: Float get() = if (total == 0) 0f else done.toFloat() / total
}
