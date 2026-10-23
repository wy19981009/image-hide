package com.whjk.imagehide.data.repository

import androidx.lifecycle.LiveData
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.whjk.imagehide.data.room.AppDatabase
import com.whjk.imagehide.data.room.entity.HiddenAlbumEntity
import com.whjk.imagehide.domain.model.Album
import com.whjk.imagehide.domain.model.MediaItem
import com.whjk.imagehide.domain.repository.HiddenRepository
import com.whjk.imagehide.worker.HideAlbumWorker
import com.whjk.imagehide.worker.RestoreAlbumWorker
import com.whjk.imagehide.worker.SingleHideWorker
import com.whjk.imagehide.worker.SingleRestoreWorker
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HiddenRepositoryImpl @Inject constructor(
    private val workManager: WorkManager,
    private val db: AppDatabase
) : HiddenRepository {

    override fun submitHide(bucketId: Long, albumName: String): String {
        val req = OneTimeWorkRequestBuilder<HideAlbumWorker>()
            .setInputData(
                workDataOf(
                    HideAlbumWorker.KEY_BUCKET_ID to bucketId,
                    HideAlbumWorker.KEY_ALBUM_NAME to albumName,
                    HideAlbumWorker.KEY_STAGE to HideAlbumWorker.STAGE_COPY
                )
            )
            .build()
        workManager.enqueue(req)
        return req.id.toString()
    }

    override fun submitRestore(albumId: Long, overrideRelativePath: String?): String {
        val data = if (overrideRelativePath != null) {
            workDataOf(
                RestoreAlbumWorker.KEY_ALBUM_ID to albumId,
                RestoreAlbumWorker.KEY_OVERRIDE_PATH to overrideRelativePath
            )
        } else {
            workDataOf(RestoreAlbumWorker.KEY_ALBUM_ID to albumId)
        }
        val req = OneTimeWorkRequestBuilder<RestoreAlbumWorker>()
            .setInputData(data)
            .build()
        workManager.enqueue(req)
        return req.id.toString()
    }

    override suspend fun listHiddenAlbums(): List<Album> {
        val rows: List<HiddenAlbumEntity> = db.hiddenAlbumDao().observeAll().first()
        return rows.map {
            Album(
                bucketId = it.id,
                name = it.displayName,
                count = it.count,
                coverPath = null,
                source = Album.Source.HIDDEN
            )
        }
    }

    override fun submitSingleHide(items: List<MediaItem>): String {
        val req = OneTimeWorkRequestBuilder<SingleHideWorker>()
            .setInputData(
                workDataOf(
                    SingleHideWorker.KEY_ITEMS_JSON to encodeItems(items),
                    SingleHideWorker.KEY_STAGE to SingleHideWorker.STAGE_COPY
                )
            )
            .build()
        workManager.enqueue(req)
        return req.id.toString()
    }

    override fun submitSingleRestore(ids: List<Long>): String {
        val req = OneTimeWorkRequestBuilder<SingleRestoreWorker>()
            .setInputData(
                workDataOf(
                    SingleRestoreWorker.KEY_IDS to ids.toLongArray()
                )
            )
            .build()
        workManager.enqueue(req)
        return req.id.toString()
    }

    fun observeWork(workId: String): LiveData<WorkInfo> =
        workManager.getWorkInfoByIdLiveData(UUID.fromString(workId))

    /** 编码 MediaItem 列表为 JSON（顶层工具，跟 Worker 里那个对应） */
    private fun encodeItems(items: List<MediaItem>): String {
        val arr = org.json.JSONArray()
        items.forEach { item ->
            val o = org.json.JSONObject().apply {
                put("id", item.id)
                put("displayName", item.displayName)
                put("path", item.path)
                put("mimeType", item.mimeType)
                put("relativePath", item.relativePath ?: "")
                put("takenAt", item.takenAt)
                put("sizeBytes", item.sizeBytes)
                put("durationMs", item.durationMs)
                put("width", item.width)
                put("height", item.height)
            }
            arr.put(o)
        }
        return arr.toString()
    }
}
