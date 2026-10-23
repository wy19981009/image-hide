package com.whjk.imagehide.domain.model

/**
 * 相册。
 *
 * - 系统相册：来自 MediaStore.BUCKET
 * - 隐藏相册：来自 Room hidden_albums 表
 *
 * @param bucketId MediaStore BUCKET_ID；隐藏相册用自己的 hash
 * @param name 相册显示名
 * @param count 媒体数量
 * @param coverPath 封面路径（系统相册 = uri string；隐藏相册 = file path）
 * @param source 标记来源
 */
data class Album(
    val bucketId: Long,
    val name: String,
    val count: Int,
    val coverPath: String?,
    val source: Source
) {
    enum class Source { SYSTEM, HIDDEN }
}
