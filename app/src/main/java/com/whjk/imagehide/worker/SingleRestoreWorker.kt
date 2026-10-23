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
 * 单张 / 批量 还原 Worker。
 *
 * 若原 MediaStore 行仍在（隐藏时未真正删掉），只清私有副本 + Room，**不再 insert**，
 * 避免相册里多出一张重复。
 */
@HiltWorker
class SingleRestoreWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val fileMover: FileMover,
    private val layout: PrivateDirLayout,
    private val mediaStore: MediaStoreDataSource,
    private val db: AppDatabase
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ProgressNotifier.foregroundInfo(NOTIF_ID, applicationContext, "正在还原", 0)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ids = inputData.getLongArray(KEY_IDS) ?: return@withContext Result.failure(
            workDataOf(KEY_ERROR to "missing ids")
        )

        try {
            trySetForeground(
                ProgressNotifier.foregroundInfo(NOTIF_ID, applicationContext, "准备还原", 0),
                TAG
            )

            val rows = db.singleHiddenDao().listByIds(ids.toList())
            if (rows.isEmpty()) {
                return@withContext Result.success(
                    workDataOf(KEY_STAGE to STAGE_DONE, KEY_PROGRESS to 100, KEY_DONE to 0)
                )
            }

            var restored = 0
            rows.forEachIndexed { idx, row ->
                val src = File(layout.singleDir(), row.privateFileName)
                val ok = if (mediaStore.exists(row.mediaStoreId)) {
                    // 原图还在系统相册 → 隐藏时没删成功，只清理隐藏记录，不写回
                    Log.i(TAG, "skip insert, original still exists id=${row.mediaStoreId}")
                    true
                } else {
                    val entry = FileMover.WriteEntry(
                        displayName = row.displayName,
                        mimeType = row.mimeType,
                        relativePath = row.relativePath,
                        takenAt = row.takenAt
                    )
                    fileMover.restoreToMediaStore(entry, src) != null
                }

                if (ok) {
                    src.delete()
                    db.singleHiddenDao().deleteById(row.mediaStoreId)
                    restored++
                }

                val pct = ((idx + 1) * 100) / rows.size
                setProgressAsync(
                    workDataOf(
                        KEY_PROGRESS to pct,
                        KEY_STAGE to STAGE_FINALIZE,
                        KEY_DONE to restored,
                        KEY_TOTAL to rows.size
                    )
                )
                trySetForegroundAsync(
                    ProgressNotifier.foregroundInfo(
                        NOTIF_ID,
                        applicationContext,
                        "还原 ${idx + 1}/${rows.size}",
                        pct
                    ),
                    TAG
                )
            }

            trySetForegroundAsync(
                ProgressNotifier.foregroundInfo(
                    NOTIF_ID,
                    applicationContext,
                    "已还原 $restored 项",
                    100
                ),
                TAG
            )
            if (restored == 0 && rows.isNotEmpty()) {
                Result.failure(workDataOf(KEY_ERROR to "还原失败，请重试"))
            } else {
                Result.success(
                    workDataOf(
                        KEY_STAGE to STAGE_DONE,
                        KEY_PROGRESS to 100,
                        KEY_DONE to restored
                    )
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "restore failed", t)
            Result.failure(workDataOf(KEY_ERROR to (t.message ?: t::class.java.simpleName)))
        }
    }

    companion object {
        private const val TAG = "SingleRestoreWorker"
        const val KEY_IDS = "ids"
        const val KEY_STAGE = "stage"
        const val KEY_PROGRESS = "progress"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        const val KEY_ENTRIES = "entries"
        const val KEY_SOURCE_FILES = "source_files"
        const val KEY_TARGET_URIS = "target_uris"

        const val STAGE_PREPARE = "prepare"
        const val STAGE_AWAIT_SAF = "await_saf"
        const val STAGE_FINALIZE = "finalize"
        const val STAGE_DONE = "done"

        private const val NOTIF_ID = 1004
    }
}
