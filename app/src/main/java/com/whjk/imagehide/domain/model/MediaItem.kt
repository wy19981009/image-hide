package com.whjk.imagehide.domain.model

/**
 * 媒体项。
 *
 * 两种来源：
 * - 系统：path 字段填 MediaStore 内容 URI（content://）
 * - 隐藏：path 填私有目录绝对路径（file://）
 *
 * @param mimeType image/jpeg, video/mp4 等
 * @param durationMs 仅视频有意义
 * @param sizeBytes
 * @param takenAt DATE_TAKEN 毫秒
 * @param relativePath 用于还原时写回原 MediaStore 路径
 */
data class MediaItem(
    val id: Long,
    val displayName: String,
    val path: String,
    val mimeType: String,
    val mediaType: Type,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val takenAt: Long = 0,
    val relativePath: String? = null,
    val width: Int = 0,
    val height: Int = 0
) {
    enum class Type { IMAGE, VIDEO }
}
