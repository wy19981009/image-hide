package com.whjk.imagehide.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.whjk.imagehide.data.filemove.FileMover
import com.whjk.imagehide.data.filemove.PrivateDirLayout
import com.whjk.imagehide.data.mediastore.MediaStoreDataSource
import com.whjk.imagehide.data.room.AppDatabase
import com.whjk.imagehide.data.room.entity.HiddenAlbumEntity
import com.whjk.imagehide.data.room.entity.HiddenMediaEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 隐藏整本相册。
 *
 * 失败常见原因（已处理）：
 * 1) setForeground 在通知关闭时抛异常 → 降级继续
 * 2) 上次半截隐藏留下 Room 唯一约束 → 先清再写
 * 3) WorkData 塞 URI 超 10KB → 只传 albumDbId
 */
@HiltWorker
class HideAlbumWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val mediaStore: MediaStoreDataSource,
    private val fileMover: FileMover,
    private val layout: PrivateDirLayout,
    private val db: AppDatabase
) : CoroutineWorker(appContext, params) {

    /** WorkManager 在重启 FGS 时会调这个；不实现会直接崩 */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ProgressNotifier.foregroundInfo(
            NOTIF_ID,
            applicationContext,
            "正在隐藏相册",
            0
        )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val bucketId = inputData.getLong(KEY_BUCKET_ID, -1L)
        val albumName = inputData.getString(KEY_ALBUM_NAME) ?: ""
        val stage = inputData.getString(KEY_STAGE) ?: STAGE_COPY

        Log.i(TAG, "doWork start stage=$stage bucketId=$bucketId name=$albumName")

        // ★ MediaStore BUCKET_ID 是路径 hash，完全可以是负数（如 -1033692156）
        //   不能用 bucketId < 0 判断非法；用 hasKey 区分「没传」和「真负数」
        if (stage == STAGE_COPY) {
            val hasBucket = inputData.hasKeyWithValueOfType(KEY_BUCKET_ID, Long::class.javaObjectType)
            if (!hasBucket || albumName.isBlank()) {
                Log.e(TAG, "bad input hasBucket=$hasBucket bucketId=$bucketId name=$albumName")
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "bad input: hasBucket=$hasBucket name=$albumName")
                )
            }
        }

        try {
            trySetForeground(
                ProgressNotifier.foregroundInfo(
                    NOTIF_ID,
                    applicationContext,
                    "正在处理 ${albumName.ifBlank { "相册" }}",
                    0
                ),
                TAG
            )

            when (stage) {
                STAGE_COPY -> copyStage(bucketId, albumName)
                STAGE_FINALIZE -> finalizeStage(albumName.ifBlank { "相册" })
                else -> Result.failure(workDataOf(KEY_ERROR to "unknown stage: $stage"))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "hide failed: bucketId=$bucketId stage=$stage", t)
            Result.failure(
                workDataOf(KEY_ERROR to (t.message ?: t::class.java.simpleName))
            )
        }
    }

    private suspend fun copyStage(bucketId: Long, albumName: String): Result {
        val items = mediaStore.listSystemMediaByBucket(bucketId)
        if (items.isEmpty()) {
            Log.w(TAG, "empty album bucketId=$bucketId")
            return Result.success(
                workDataOf(KEY_STAGE to STAGE_DONE, KEY_PROGRESS to 100)
            )
        }

        Log.i(TAG, "copyStage: count=${items.size}")

        // ★ 上次失败可能已写入 Room（unique originalBucketId），先清干净再隐藏
        clearPreviousHide(bucketId)

        val copied = fileMover.copyBatchToPrivate(items, bucketId, encrypt = false) { done, total ->
            val pct = done * 90 / total
            setProgressAsync(
                workDataOf(
                    KEY_PROGRESS to pct,
                    KEY_STAGE to STAGE_COPY,
                    KEY_DONE to done,
                    KEY_TOTAL to total
                )
            )
            trySetForegroundAsync(
                ProgressNotifier.foregroundInfo(
                    NOTIF_ID,
                    applicationContext,
                    "正在复制 $albumName $done/$total",
                    pct
                ),
                TAG
            )
        }

        var albumId = db.hiddenAlbumDao().insert(
            HiddenAlbumEntity(
                originalBucketId = bucketId,
                displayName = albumName,
                count = items.size,
                hiddenAt = System.currentTimeMillis()
            )
        )
        // IGNORE 冲突时 Room 返回 -1
        if (albumId <= 0L) {
            clearPreviousHide(bucketId)
            albumId = db.hiddenAlbumDao().insert(
                HiddenAlbumEntity(
                    originalBucketId = bucketId,
                    displayName = albumName,
                    count = items.size,
                    hiddenAt = System.currentTimeMillis()
                )
            )
        }
        if (albumId <= 0L) {
            return Result.failure(workDataOf(KEY_ERROR to "无法写入隐藏相册记录"))
        }

        val mediaRows = copied.map { (item, file) ->
            HiddenMediaEntity(
                albumId = albumId,
                mediaStoreId = item.id,
                displayName = item.displayName,
                mimeType = item.mimeType,
                relativePath = item.relativePath,
                takenAt = item.takenAt,
                sizeBytes = item.sizeBytes,
                durationMs = item.durationMs,
                width = item.width,
                height = item.height,
                privateFileName = file.name
            )
        }
        db.hiddenMediaDao().insertAll(mediaRows)

        Log.i(TAG, "copy done albumDbId=$albumId, await SAF delete")

        setProgress(
            workDataOf(
                KEY_PROGRESS to 95,
                KEY_STAGE to STAGE_AWAIT_SAF,
                KEY_ALBUM_DB_ID to albumId
            )
        )
        trySetForegroundAsync(
            ProgressNotifier.foregroundInfo(
                NOTIF_ID,
                applicationContext,
                "等待系统授权删除",
                95
            ),
            TAG
        )

        return Result.success(
            workDataOf(
                KEY_PROGRESS to 95,
                KEY_STAGE to STAGE_AWAIT_SAF,
                KEY_ALBUM_DB_ID to albumId,
                KEY_TOTAL to items.size
            )
        )
    }

    private suspend fun clearPreviousHide(bucketId: Long) {
        val existing = db.hiddenAlbumDao().findByBucketId(bucketId)
        if (existing != null) {
            Log.w(TAG, "clear previous hide albumDbId=${existing.id} bucketId=$bucketId")
            db.hiddenAlbumDao().deleteById(existing.id) // CASCADE media
        }
        layout.deleteAlbumDir(bucketId)
    }

    private suspend fun finalizeStage(albumName: String): Result {
        val albumId = inputData.getLong(KEY_ALBUM_DB_ID, -1L)
        if (albumId < 0L) {
            return Result.failure(workDataOf(KEY_ERROR to "finalize missing albumDbId"))
        }

        val rows = db.hiddenMediaDao().listByAlbum(albumId)
        val resolver = applicationContext.contentResolver
        var deleted = 0
        rows.forEach { row ->
            try {
                val uri = mediaStore.uriFor(row.mediaStoreId)
                if (resolver.delete(uri, null, null) > 0) deleted++
            } catch (e: Exception) {
                Log.w(TAG, "delete failed id=${row.mediaStoreId}", e)
            }
        }
        Log.i(TAG, "finalize: albumId=$albumId rows=${rows.size} deleted=$deleted")

        trySetForegroundAsync(
            ProgressNotifier.foregroundInfo(
                NOTIF_ID,
                applicationContext,
                "已隐藏 $albumName",
                100
            ),
            TAG
        )
        return Result.success(
            workDataOf(
                KEY_PROGRESS to 100,
                KEY_STAGE to STAGE_DONE,
                KEY_ALBUM_DB_ID to albumId,
                KEY_DONE to deleted
            )
        )
    }

    companion object {
        const val KEY_ERROR = "error"
        private const val TAG = "HideAlbumWorker"
        const val KEY_BUCKET_ID = "bucket_id"
        const val KEY_ALBUM_NAME = "album_name"
        const val KEY_STAGE = "stage"
        const val KEY_PROGRESS = "progress"
        const val KEY_DELETE_URIS = "delete_uris"
        const val KEY_ALBUM_DB_ID = "album_db_id"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"

        const val STAGE_COPY = "copy"
        const val STAGE_AWAIT_SAF = "await_saf"
        const val STAGE_FINALIZE = "finalize"
        const val STAGE_DONE = "done"

        private const val NOTIF_ID = 1001
    }
}
