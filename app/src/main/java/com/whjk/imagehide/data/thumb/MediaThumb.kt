package com.whjk.imagehide.data.thumb

import android.net.Uri
import com.whjk.imagehide.domain.model.MediaItem
import java.io.File

/**
 * Coil 缩略图请求模型：走系统 [android.content.ContentResolver.loadThumbnail] /
 * [android.media.ThumbnailUtils]，避免网格滑动时整图解码或抽视频帧。
 */
data class MediaThumb(
    val model: Any,
    val isVideo: Boolean,
    /** 稳定缓存键（同 id 复用内存缓存） */
    val cacheKey: String
) {
    companion object {
        fun from(item: MediaItem): MediaThumb {
            val model: Any = when {
                item.path.startsWith("content://") && item.mediaType == MediaItem.Type.VIDEO ->
                    android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        item.id
                    )
                item.path.startsWith("content://") || item.path.startsWith("file://") ->
                    Uri.parse(item.path)
                else -> File(item.path)
            }
            return MediaThumb(
                model = model,
                isVideo = item.mediaType == MediaItem.Type.VIDEO,
                cacheKey = "mt:${item.id}:${item.mediaType}"
            )
        }

        fun fromPath(path: String, isVideo: Boolean = guessVideo(path)): MediaThumb {
            val model: Any = when {
                path.startsWith("content://") || path.startsWith("file://") -> Uri.parse(path)
                else -> File(path)
            }
            return MediaThumb(
                model = model,
                isVideo = isVideo,
                cacheKey = "mt:$path"
            )
        }

        private fun guessVideo(path: String): Boolean {
            val n = path.substringAfterLast('.').lowercase()
            return n in VIDEO_EXT
        }

        private val VIDEO_EXT = setOf(
            "mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v", "ts", "mpeg", "mpg"
        )
    }
}
