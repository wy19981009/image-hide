package com.whjk.imagehide.data.repository

import android.content.Context
import android.util.Log
import com.whjk.imagehide.data.crypto.EncryptedFileOpener
import com.whjk.imagehide.data.filemove.PrivateDirLayout
import com.whjk.imagehide.data.mediastore.MediaStoreDataSource
import com.whjk.imagehide.data.room.AppDatabase
import com.whjk.imagehide.domain.model.MediaItem
import com.whjk.imagehide.domain.repository.MediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepositoryImpl @Inject constructor(
    private val mediaStore: MediaStoreDataSource,
    private val db: AppDatabase,
    private val layout: PrivateDirLayout,
    private val encryptedOpener: EncryptedFileOpener,
    @ApplicationContext private val context: Context
) : MediaRepository {

    override suspend fun listSystemMediaByBucket(bucketId: Long): List<MediaItem> =
        mediaStore.listSystemMediaByBucket(bucketId)

    /**
     * 隐藏相册媒体：读 Room，拼私有路径；加密文件在此解密到 cache（与之前行为一致）。
     * 整段跑在 IO，避免卡住导航；进页靠 loading 转圈等待。
     */
    override suspend fun listHiddenMedia(albumId: Long): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val album = db.hiddenAlbumDao().findById(albumId) ?: return@withContext emptyList()
            val rows = db.hiddenMediaDao().listByAlbum(albumId)
            if (rows.isEmpty()) return@withContext emptyList()

            val albumDir = layout.albumDir(album.originalBucketId)
            val out = ArrayList<MediaItem>(rows.size)
            val orphanIds = ArrayList<Long>()

            for (row in rows) {
                val source = File(albumDir, row.privateFileName)
                if (!source.exists()) {
                    orphanIds += row.id
                    continue
                }
                val file = try {
                    encryptedOpener.openForView(source, context.cacheDir)
                } catch (e: Exception) {
                    Log.e(TAG, "openForView failed ${source.name}", e)
                    continue
                }
                if (!file.exists() || file.length() <= 0L) {
                    Log.w(TAG, "view file empty: ${file.absolutePath}")
                    continue
                }
                out += MediaItem(
                    id = row.id,
                    displayName = row.displayName,
                    path = file.absolutePath,
                    mimeType = row.mimeType,
                    mediaType = if (row.mimeType.startsWith("video/")) {
                        MediaItem.Type.VIDEO
                    } else {
                        MediaItem.Type.IMAGE
                    },
                    durationMs = row.durationMs,
                    sizeBytes = row.sizeBytes,
                    takenAt = row.takenAt,
                    relativePath = row.relativePath,
                    width = row.width,
                    height = row.height
                )
            }

            if (orphanIds.isNotEmpty()) {
                Log.w(TAG, "purge ${orphanIds.size} orphan hidden media in album $albumId")
                db.hiddenMediaDao().deleteByIds(orphanIds)
                val left = db.hiddenMediaDao().listByAlbum(albumId)
                if (left.isEmpty()) {
                    db.hiddenAlbumDao().deleteById(albumId)
                    layout.deleteAlbumDir(album.originalBucketId)
                } else {
                    db.hiddenAlbumDao().updateCount(albumId, left.size)
                }
            }
            out
        }

    override suspend fun listSingleHidden(): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val rows = db.singleHiddenDao().observeAll().first()
            val singleDir = layout.singleDir()
            rows.mapNotNull { row ->
                val source = File(singleDir, row.privateFileName)
                if (!source.exists()) {
                    db.singleHiddenDao().deleteById(row.mediaStoreId)
                    return@mapNotNull null
                }
                val file = try {
                    encryptedOpener.openForView(source, context.cacheDir)
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                if (!file.exists()) return@mapNotNull null
                MediaItem(
                    id = row.mediaStoreId,
                    displayName = row.displayName,
                    path = file.absolutePath,
                    mimeType = row.mimeType,
                    mediaType = if (row.mimeType.startsWith("video/")) {
                        MediaItem.Type.VIDEO
                    } else {
                        MediaItem.Type.IMAGE
                    },
                    durationMs = row.durationMs,
                    sizeBytes = row.sizeBytes,
                    takenAt = row.takenAt,
                    relativePath = row.relativePath,
                    width = row.width,
                    height = row.height
                )
            }
        }

    private companion object {
        const val TAG = "MediaRepo"
    }
}
