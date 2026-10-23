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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 还原相册 Worker。
 *
 * 安全规则：
 * - 仅在「可打开的原图仍在」或「写回 MediaStore 成功」后删除该条私有文件与 Room 行
 * - 任一条失败时**保留**其私有文件与记录，绝不整目录清空
 */
@HiltWorker
class RestoreAlbumWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val fileMover: FileMover,
    private val layout: PrivateDirLayout,
    private val mediaStore: MediaStoreDataSource,
    private val db: AppDatabase
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ProgressNotifier.foregroundInfo(NOTIF_ID, applicationContext, "正在还原相册", 0)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val albumId = inputData.getLong(KEY_ALBUM_ID, -1L)
        if (albumId < 0) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "bad albumId"))
        }

        val album = db.hiddenAlbumDao().findById(albumId)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "album not found"))
        val albumName = album.displayName
        val bucketId = album.originalBucketId

        try {
            trySetForeground(
                ProgressNotifier.foregroundInfo(
                    NOTIF_ID,
                    applicationContext,
                    "准备还原 $albumName",
                    0
                ),
                TAG
            )

            val rows = db.hiddenMediaDao().listByAlbum(albumId)
            if (rows.isEmpty()) {
                layout.deleteAlbumDir(bucketId)
                db.hiddenAlbumDao().deleteById(albumId)
                return@withContext Result.success(
                    workDataOf(KEY_STAGE to STAGE_DONE, KEY_PROGRESS to 100)
                )
            }

            val overridePath = inputData.getString(KEY_OVERRIDE_PATH)?.takeIf { it.isNotBlank() }
            val albumDir = layout.albumDir(bucketId)
            var ok = 0
            var fail = 0

            rows.forEachIndexed { idx, row ->
                val src = File(albumDir, row.privateFileName)
                val alreadyReadable = mediaStore.isReadable(row.mediaStoreId, row.mimeType)
                val written = if (alreadyReadable) {
                    Log.i(TAG, "skip insert, original readable id=${row.mediaStoreId}")
                    true
                } else {
                    if (!src.exists()) {
                        Log.e(TAG, "private missing, cannot restore: ${src.absolutePath}")
                        false
                    } else {
                        val entry = FileMover.WriteEntry(
                            displayName = row.displayName,
                            mimeType = row.mimeType,
                            relativePath = row.relativePath,
                            takenAt = row.takenAt
                        )
                        fileMover.restoreToMediaStore(
                            entry,
                            src,
                            overrideRelativePath = overridePath
                        ) != null
                    }
                }

                if (written) {
                    // 仅成功后删本条；失败则保留私有文件与 Room
                    if (src.exists()) {
                        val deleted = src.delete()
                        if (!deleted) Log.w(TAG, "private delete failed: ${src.absolutePath}")
                    }
                    db.hiddenMediaDao().deleteById(row.id)
                    ok++
                } else {
                    fail++
                    Log.e(TAG, "restore failed keep private: ${row.displayName}")
                }

                val pct = ((idx + 1) * 100) / rows.size
                setProgressAsync(
                    workDataOf(
                        KEY_PROGRESS to pct,
                        KEY_STAGE to STAGE_FINALIZE,
                        KEY_ALBUM_ID to albumId,
                        KEY_OK to ok,
                        KEY_FAIL to fail
                    )
                )
                trySetForegroundAsync(
                    ProgressNotifier.foregroundInfo(
                        NOTIF_ID,
                        applicationContext,
                        "正在写回 $albumName ${idx + 1}/${rows.size}",
                        pct
                    ),
                    TAG
                )
            }

            val left = db.hiddenMediaDao().listByAlbum(albumId)
            if (left.isEmpty()) {
                layout.deleteAlbumDir(bucketId)
                db.hiddenAlbumDao().deleteById(albumId)
            } else {
                db.hiddenAlbumDao().updateCount(albumId, left.size)
            }

            if (ok == 0) {
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "还原失败 0/${rows.size}")
                )
            }

            val msg = if (fail == 0) {
                "已还原 $albumName"
            } else {
                "部分还原 $ok/${rows.size}，失败 $fail 已保留"
            }
            trySetForegroundAsync(
                ProgressNotifier.foregroundInfo(
                    NOTIF_ID,
                    applicationContext,
                    msg,
                    100
                ),
                TAG
            )

            // 有失败时仍 success，但带上 fail 计数，便于 UI 提示；数据未丢
            Result.success(
                workDataOf(
                    KEY_STAGE to STAGE_DONE,
                    KEY_PROGRESS to 100,
                    KEY_ALBUM_ID to albumId,
                    KEY_OK to ok,
                    KEY_FAIL to fail
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "restore album failed", t)
            Result.failure(workDataOf(KEY_ERROR to (t.message ?: t::class.java.simpleName)))
        }
    }

    companion object {
        private const val TAG = "RestoreAlbumWorker"
        const val KEY_ALBUM_ID = "album_id"
        /** 用户选择的还原目录，如 DCIM/Camera/；缺省则用各图原始 relativePath */
        const val KEY_OVERRIDE_PATH = "override_path"
        const val KEY_STAGE = "stage"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_OK = "ok"
        const val KEY_FAIL = "fail"

        const val KEY_WRITE_ENTRIES = "write_entries"
        const val KEY_SOURCE_FILES = "source_files"
        const val KEY_TARGET_URIS = "target_uris"

        const val STAGE_PREPARE = "prepare"
        const val STAGE_AWAIT_SAF = "await_saf"
        const val STAGE_FINALIZE = "finalize"
        const val STAGE_DONE = "done"

        private const val NOTIF_ID = 1002
    }
}
