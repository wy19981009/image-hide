package com.whjk.imagehide.data.mediastore

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.whjk.imagehide.domain.model.Album
import com.whjk.imagehide.domain.model.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaStore 读写入口。
 *
 * 关键点：
 * - 必须用 ContentResolver.query，不能用 File()（Scoped Storage）
 * - BUCKET_ID 通过 GROUP BY 聚合得到相册
 * - 封面：每个 BUCKET 取 DATE_MODIFIED 最大的那张图
 * - 所有 IO 都跑在 Dispatchers.IO
 *
 * 字段映射：
 *   MediaStore.Files.FileColumns._ID  → mediaId
 *   BUCKET_ID / BUCKET_DISPLAY_NAME  → 相册
 *   DATA                              → 路径（注意 Android 10+ 不可靠，URI 才是真理）
 *   RELATIVE_PATH                     → 还原时用
 *   MIME_TYPE                         → 还原时用
 *   DATE_TAKEN / DATE_MODIFIED        → 排序 + 还原时用
 */
@Singleton
class MediaStoreDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val resolver get() = context.contentResolver

    /**
     * 扫描所有系统相册（图片 + 视频）。
     *
     * 步骤：
     * 1) 按 BUCKET_ID 聚合，统计每相册的媒体数 + 取最大 DATE_MODIFIED 的 _ID 作封面
     * 2) 把所有 BUCKET 走一遍内层循环：拿 _ID → contentUri → 用作封面
     */
    suspend fun scanSystemAlbums(): List<Album> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri("external")

        val projection = arrayOf(
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val orderBy =
            "${MediaStore.Files.FileColumns.BUCKET_ID} ASC, " +
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        val buckets = LinkedHashMap<Long, BucketAccum>()
        // 一次性把全部行拉回来，本地按 BUCKET 聚合（SQL GROUP BY 在 Android 10+ 不一定稳）
        resolver.query(collection, projection, selection, selectionArgs, orderBy)?.use { c ->
            val colBucketId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
            val colName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val colId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val colMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val colDate = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

            while (c.moveToNext()) {
                val bucketId = c.getLong(colBucketId)
                val name = c.getString(colName) ?: DEFAULT_ALBUM
                val id = c.getLong(colId)
                val mime = c.getString(colMime) ?: ""
                val date = c.getLong(colDate)

                val acc = buckets.getOrPut(bucketId) {
                    BucketAccum(bucketId = bucketId, name = name)
                }
                acc.count++
                // 同一 BUCKET 内第一行就是 DATE_MODIFIED 最大（已按 DESC 排序）
                if (acc.coverMediaId == null && mime.startsWith("image/")) {
                    acc.coverMediaId = id
                }
            }
        } ?: return@withContext emptyList()

        // 二次查封面 URI（id → content://media/external/file/{id}）
        buckets.values
            .map { acc ->
                val coverUri: String? = acc.coverMediaId?.let { uriFor(it).toString() }
                Album(
                    bucketId = acc.bucketId,
                    name = acc.name,
                    count = acc.count,
                    coverPath = coverUri,
                    source = Album.Source.SYSTEM
                )
            }
            .sortedByDescending { it.count }
    }

    /**
     * 列出指定 BUCKET 下的所有媒体（图片+视频，按 DATE_MODIFIED 倒序）。
     */
    suspend fun listSystemMediaByBucket(bucketId: Long): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val collection = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_TAKEN,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.DURATION,
                MediaStore.Files.FileColumns.WIDTH,
                MediaStore.Files.FileColumns.HEIGHT,
                MediaStore.Files.FileColumns.RELATIVE_PATH
            )
            val selection =
                "${MediaStore.Files.FileColumns.BUCKET_ID} = ? AND " +
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
            val args = arrayOf(
                bucketId.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            )
            val orderBy = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

            val out = mutableListOf<MediaItem>()
            resolver.query(collection, projection, selection, args, orderBy)?.use { c ->
                val colId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val colName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val colMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val colType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val colSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val colTaken = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
                val colDate = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val colDur = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)
                val colW = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val colH = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val colPath = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)

                while (c.moveToNext()) {
                    val type = c.getInt(colType)
                    val mediaType = if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                        MediaItem.Type.VIDEO
                    } else {
                        MediaItem.Type.IMAGE
                    }
                    // DATE_TAKEN 单位是毫秒；DATE_MODIFIED 单位是秒，需要 *1000
                    val taken = c.getLong(colTaken).takeIf { it > 0 }
                        ?: c.getLong(colDate) * 1000
                    out += MediaItem(
                        id = c.getLong(colId),
                        displayName = c.getString(colName) ?: "unknown",
                        path = uriFor(c.getLong(colId)).toString(),
                        mimeType = c.getString(colMime) ?: "",
                        mediaType = mediaType,
                        durationMs = c.getLong(colDur),
                        sizeBytes = c.getLong(colSize),
                        takenAt = taken,
                        relativePath = c.getString(colPath),
                        width = c.getInt(colW),
                        height = c.getInt(colH)
                    )
                }
            }
            out
        }

    fun uriFor(mediaId: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), mediaId)

    /**
     * createDeleteRequest 必须用 Images / Video 集合 URI；
     * Files 集合在不少机型上会直接取消/失败。
     */
    fun uriForDelete(item: MediaItem): Uri {
        val base = when (item.mediaType) {
            MediaItem.Type.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaItem.Type.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(base, item.id)
    }

    fun uriForDelete(mediaId: Long, mimeType: String): Uri {
        val base = when {
            mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(base, mediaId)
    }

    /** 原 MediaStore 行是否还在（隐藏时若未真正删除，还原会重复插入） */
    suspend fun exists(mediaId: Long): Boolean = withContext(Dispatchers.IO) {
        existsBlocking(mediaId)
    }

    /**
     * 原图是否仍可打开。仅有 MediaStore 行但文件已丢时返回 false，
     * 避免还原时误判「还在」而删掉私有副本。
     */
    suspend fun isReadable(itemId: Long, mimeType: String): Boolean = withContext(Dispatchers.IO) {
        if (!existsBlocking(itemId)) return@withContext false
        val uri = uriForDelete(itemId, mimeType)
        try {
            resolver.openInputStream(uri)?.use { input ->
                // 读 1 字节确认不是空壳/断链
                val buf = ByteArray(1)
                input.read(buf) >= 0
            } == true
        } catch (e: Exception) {
            Log.w(TAG, "isReadable fail id=$itemId", e)
            false
        }
    }

    /**
     * 已授予「所有文件访问」时直接删除，无需 createDeleteRequest。
     *
     * 只走 ContentResolver.delete；**禁止** File.delete 原文件——
     * 否则 MediaStore 行还在、磁盘文件没了，还原又会误判「仍在」并丢掉私有副本。
     */
    suspend fun deleteDirect(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        val uri = uriForDelete(item)
        try {
            resolver.delete(uri, null, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "deleteDirect resolver denied id=${item.id}", e)
        } catch (e: Exception) {
            Log.w(TAG, "deleteDirect resolver failed id=${item.id}", e)
        }
        try {
            resolver.delete(uriFor(item.id), null, null)
        } catch (_: Exception) {
        }
        val gone = !existsBlocking(item.id)
        Log.i(TAG, "deleteDirect final id=${item.id} gone=$gone")
        gone
    }

    private fun existsBlocking(mediaId: Long): Boolean {
        val candidates = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external")
        )
        return candidates.any { base ->
            val uri = ContentUris.withAppendedId(base, mediaId)
            resolver.query(uri, arrayOf("_id"), null, null, null)
                ?.use { it.moveToFirst() } == true
        }
    }

    private data class BucketAccum(
        val bucketId: Long,
        val name: String,
        var count: Int = 0,
        var coverMediaId: Long? = null
    )

    companion object {
        private const val TAG = "MediaStoreDS"
        private const val DEFAULT_ALBUM = "Camera"
    }
}
