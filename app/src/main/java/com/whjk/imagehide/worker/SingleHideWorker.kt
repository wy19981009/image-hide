package com.whjk.imagehide.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.whjk.imagehide.data.filemove.FileMover
import com.whjk.imagehide.data.mediastore.MediaStoreDataSource
import com.whjk.imagehide.data.room.AppDatabase
import com.whjk.imagehide.data.room.entity.SingleHiddenEntity
import com.whjk.imagehide.domain.model.MediaItem
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单张 / 批量 隐藏 Worker（2 阶段）。
 *
 * ★ 关键：SAF 所需的 uri 列表必须放在 [Result.success] 的 outputData 里。
 *   WorkManager 在 SUCCEEDED 后会清空 progress，UI 只能从 outputData 读到 AWAIT_SAF。
 *
 * STAGE_COPY → 复制 + 落库 → success(outputData=AWAIT_SAF+uris)
 * UI 弹 SAF 删除确认后入队 STAGE_FINALIZE
 * STAGE_FINALIZE → 收尾（删除已在系统弹窗里完成）
 */
@HiltWorker
class SingleHideWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val mediaStore: MediaStoreDataSource,
    private val fileMover: FileMover,
    private val db: AppDatabase
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ProgressNotifier.foregroundInfo(NOTIF_ID, applicationContext, "正在隐藏", 0)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val stage = inputData.getString(KEY_STAGE) ?: STAGE_COPY
        try {
            when (stage) {
                STAGE_COPY -> copyStage()
                STAGE_FINALIZE -> finalizeStage()
                else -> Result.failure(workDataOf(KEY_ERROR to "unknown stage: $stage"))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "hide failed stage=$stage", t)
            Result.failure(workDataOf(KEY_ERROR to (t.message ?: t::class.java.simpleName)))
        }
    }

    private suspend fun copyStage(): Result {
        val itemsJson = inputData.getString(KEY_ITEMS_JSON) ?: return Result.failure(
            workDataOf(KEY_ERROR to "missing items")
        )
        val items = decodeItems(itemsJson)
        if (items.isEmpty()) return Result.failure(workDataOf(KEY_ERROR to "empty items"))

        trySetForeground(
            ProgressNotifier.foregroundInfo(
                NOTIF_ID,
                applicationContext,
                "正在隐藏 ${items.size} 项",
                0
            ),
            TAG
        )

        val copied = fileMover.copySingleBatch(items, encrypt = false) { done, total ->
            val pct = done * 90 / total
            setProgressAsync(
                workDataOf(
                    KEY_PROGRESS to pct,
                    KEY_TOTAL to total,
                    KEY_DONE to done,
                    KEY_STAGE to STAGE_COPY
                )
            )
            trySetForegroundAsync(
                ProgressNotifier.foregroundInfo(
                    NOTIF_ID,
                    applicationContext,
                    "隐藏 $done/$total",
                    pct
                ),
                TAG
            )
        }

        val entities = copied.map { (item, pair) ->
            SingleHiddenEntity(
                mediaStoreId = item.id,
                displayName = item.displayName,
                mimeType = item.mimeType,
                relativePath = item.relativePath,
                takenAt = item.takenAt,
                sizeBytes = item.sizeBytes,
                durationMs = item.durationMs,
                width = item.width,
                height = item.height,
                privateFileName = pair.second,
                hiddenAt = System.currentTimeMillis()
            )
        }
        db.singleHiddenDao().insertAll(entities)

        // ★ 不把 URI 列表塞进 WorkData（10KB 上限）；UI 用已知 mediaStoreId 现拼
        val idArray = items.map { it.id }.toLongArray()
        setProgress(
            workDataOf(
                KEY_PROGRESS to 95,
                KEY_STAGE to STAGE_AWAIT_SAF,
                KEY_MEDIA_IDS to idArray
            )
        )
        trySetForegroundAsync(
            ProgressNotifier.foregroundInfo(NOTIF_ID, applicationContext, "等待系统授权删除", 95),
            TAG
        )

        return Result.success(
            workDataOf(
                KEY_PROGRESS to 95,
                KEY_STAGE to STAGE_AWAIT_SAF,
                KEY_MEDIA_IDS to idArray,
                KEY_TOTAL to items.size
            )
        )
    }

    /** SAF 确认后：按 mediaStoreId 再 delete 兜底。 */
    private suspend fun finalizeStage(): Result {
        val ids = inputData.getLongArray(KEY_MEDIA_IDS)
            ?: inputData.getLongArray(KEY_IDS)
            ?: longArrayOf()
        val resolver = applicationContext.contentResolver
        var deleted = 0
        ids.forEach { id ->
            try {
                if (resolver.delete(mediaStore.uriFor(id), null, null) > 0) deleted++
            } catch (_: Exception) {
            }
        }

        trySetForegroundAsync(
            ProgressNotifier.foregroundInfo(
                NOTIF_ID,
                applicationContext,
                "已隐藏",
                100
            ),
            TAG
        )
        return Result.success(
            workDataOf(
                KEY_PROGRESS to 100,
                KEY_STAGE to STAGE_DONE,
                KEY_DONE to deleted
            )
        )
    }

    private fun decodeItems(json: String): List<MediaItem> {
        val arr = org.json.JSONArray(json)
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val mime = o.getString("mimeType")
            out += MediaItem(
                id = o.getLong("id"),
                displayName = o.getString("displayName"),
                path = o.getString("path"),
                mimeType = mime,
                mediaType = if (mime.startsWith("video/")) {
                    MediaItem.Type.VIDEO
                } else {
                    MediaItem.Type.IMAGE
                },
                durationMs = o.optLong("durationMs", 0L),
                sizeBytes = o.optLong("sizeBytes", 0L),
                takenAt = o.optLong("takenAt", 0L),
                relativePath = o.optString("relativePath", "").ifEmpty { null },
                width = o.optInt("width", 0),
                height = o.optInt("height", 0)
            )
        }
        return out
    }

    companion object {
        private const val TAG = "SingleHideWorker"
        const val KEY_ITEMS_JSON = "items_json"
        const val KEY_DELETE_URIS = "delete_uris" // 兼容旧调用
        const val KEY_MEDIA_IDS = "media_ids"
        const val KEY_IDS = "ids"
        const val KEY_ALBUM_NAME = "album_name"
        const val KEY_STAGE = "stage"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"
        const val KEY_DONE = "done"
        const val KEY_ERROR = "error"

        const val STAGE_COPY = "copy"
        const val STAGE_AWAIT_SAF = "await_saf"
        const val STAGE_FINALIZE = "finalize"
        const val STAGE_DONE = "done"

        private const val NOTIF_ID = 1003
    }
}
