package com.whjk.imagehide.ui.detail

import android.content.IntentSender
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whjk.imagehide.data.filemove.FileMover
import com.whjk.imagehide.data.filemove.PrivateDirLayout
import com.whjk.imagehide.data.mediastore.MediaStoreDataSource
import com.whjk.imagehide.data.permission.PermissionChecker
import com.whjk.imagehide.data.room.AppDatabase
import com.whjk.imagehide.data.room.entity.HiddenAlbumEntity
import com.whjk.imagehide.data.room.entity.HiddenMediaEntity
import com.whjk.imagehide.domain.model.MediaItem
import com.whjk.imagehide.domain.usecase.GetMediaUseCase
import com.whjk.imagehide.ui.common.DefaultRestoreFolderOptions
import com.whjk.imagehide.ui.common.RestoreFolderOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class AlbumDetailState(
    val media: List<MediaItem> = emptyList(),
    /** 默认 true：进页先转圈，避免闪一下「没有媒体」 */
    val loading: Boolean = true,
    val error: String? = null,
    val statusText: String? = null,

    val multiSelect: Boolean = false,
    val selected: Set<Long> = emptySet(),

    val working: Boolean = false,
    val progress: Int = 0,
    val workKind: WorkKind = WorkKind.NONE,

    /** 列表排序（隐藏相册筛选） */
    val sort: MediaSort = MediaSort.TIME_DESC,

    /** 还原时弹出的目标文件夹选择 */
    val showRestoreFolderPicker: Boolean = false,
    val pendingRestoreIds: List<Long> = emptyList(),

    /** 隐藏前引导开启所有文件访问 */
    val showManageAllFilesGuide: Boolean = false,
    val pendingHideItems: List<MediaItem> = emptyList()
)

enum class WorkKind { NONE, HIDE, RESTORE }

/** 媒体列表排序 */
enum class MediaSort {
    /** 时间：从近到远（新→旧） */
    TIME_DESC,
    /** 时间：从远到近（旧→新） */
    TIME_ASC,
    /** 大小：从大到小 */
    SIZE_DESC,
    /** 大小：从小到大 */
    SIZE_ASC
}

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val getMedia: GetMediaUseCase,
    private val fileMover: FileMover,
    private val layout: PrivateDirLayout,
    private val mediaStore: MediaStoreDataSource,
    private val db: AppDatabase,
    private val permissions: PermissionChecker
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumDetailState())
    val state: StateFlow<AlbumDetailState> = _state.asStateFlow()

    private val _safRequest = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)
    val safRequest: SharedFlow<IntentSender> = _safRequest.asSharedFlow()

    private var albumId: Long = 0L
    private var albumName: String = ""
    private var hidden: Boolean = false
    private var originalBucketId: Long = 0L

    private var hideJob: Job? = null
    private var pendingSafResult: CompletableDeferred<Boolean>? = null

    val restoreFolderOptions: List<RestoreFolderOption> = DefaultRestoreFolderOptions

    fun load(albumId: Long, albumName: String, hidden: Boolean) {
        this.albumId = albumId
        this.albumName = albumName
        this.hidden = hidden
        // 同步置 loading，进页立刻有反馈，不堵主线程
        _state.update {
            it.copy(loading = true, error = null, media = emptyList(), multiSelect = false, selected = emptySet())
        }
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    if (hidden) {
                        val album = db.hiddenAlbumDao().findById(albumId)
                        originalBucketId = album?.originalBucketId ?: albumId
                    } else {
                        originalBucketId = albumId
                    }
                    getMedia(albumId, hidden, single = false)
                }
                val sort = _state.value.sort
                _state.update {
                    it.copy(media = sortMedia(list, sort), loading = false)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    private fun reload() = load(albumId, albumName, hidden)

    fun setSort(sort: MediaSort) {
        _state.update {
            it.copy(sort = sort, media = sortMedia(it.media, sort))
        }
    }

    private fun sortMedia(list: List<MediaItem>, sort: MediaSort): List<MediaItem> =
        when (sort) {
            MediaSort.TIME_DESC -> list.sortedByDescending { it.takenAt }
            MediaSort.TIME_ASC -> list.sortedBy { it.takenAt }
            MediaSort.SIZE_DESC -> list.sortedByDescending { it.sizeBytes }
            MediaSort.SIZE_ASC -> list.sortedBy { it.sizeBytes }
        }

    /** 更新媒体列表时保持当前排序 */
    private fun withSortedMedia(
        current: AlbumDetailState,
        media: List<MediaItem>
    ): AlbumDetailState = current.copy(media = sortMedia(media, current.sort))

    fun onMediaTap(item: MediaItem) {
        if (_state.value.multiSelect) toggleSelected(item.id)
    }

    fun onMediaLongPress(item: MediaItem) {
        _state.update { it.copy(multiSelect = true) }
        toggleSelected(item.id)
    }

    fun toggleSelected(id: Long) {
        _state.update {
            val sel = it.selected.toMutableSet()
            if (sel.contains(id)) sel.remove(id) else sel.add(id)
            it.copy(selected = sel)
        }
    }

    /**
     * 滑动多选：手指落点开始一次拖选。
     * @return 本次拖动手势要统一成的目标选中状态（落在已选项上则一路取消，否则一路选中）
     */
    fun beginDragSelect(id: Long): Boolean {
        val state = _state.value
        val target = if (!state.multiSelect) {
            true
        } else {
            id !in state.selected
        }
        _state.update {
            val sel = it.selected.toMutableSet()
            if (target) sel.add(id) else sel.remove(id)
            it.copy(multiSelect = true, selected = sel)
        }
        return target
    }

    fun setItemSelected(id: Long, selected: Boolean) {
        _state.update {
            val sel = it.selected.toMutableSet()
            if (selected) sel.add(id) else sel.remove(id)
            it.copy(multiSelect = true, selected = sel)
        }
    }

    /** 滑动多选：整表替换选中集合（官方区间选中算法用） */
    fun setSelectedIds(ids: Set<Long>) {
        _state.update { it.copy(multiSelect = true, selected = ids) }
    }

    fun selectAll() {
        _state.update { it.copy(selected = it.media.map { m -> m.id }.toSet()) }
    }

    fun exitMultiSelect() {
        _state.update { it.copy(multiSelect = false, selected = emptySet()) }
    }

    // ---- 隐藏：选中的图进「当前系统相册」对应的隐藏目录 ----

    fun hideSelected() {
        if (hidden) return
        val items = _state.value.media.filter { it.id in _state.value.selected }
        if (items.isEmpty()) return
        exitMultiSelect()
        if (permissions.hasManageAllFiles()) {
            startHide(items)
            return
        }
        _state.update {
            it.copy(showManageAllFilesGuide = true, pendingHideItems = items)
        }
    }

    fun dismissManageAllFilesGuide() {
        _state.update {
            it.copy(showManageAllFilesGuide = false, pendingHideItems = emptyList())
        }
    }

    fun continueHideWithoutAllFiles() {
        val items = _state.value.pendingHideItems
        dismissManageAllFilesGuide()
        if (items.isNotEmpty()) startHide(items)
    }

    fun onReturnFromAllFilesSettings() {
        val items = _state.value.pendingHideItems
        if (items.isEmpty() || !permissions.hasManageAllFiles()) return
        dismissManageAllFilesGuide()
        startHide(items)
    }

    private fun startHide(items: List<MediaItem>) {
        hideJob?.cancel()
        hideJob = viewModelScope.launch { hideItemsBatched(items) }
    }

    fun cancelWorking() {
        val job = hideJob
        hideJob = null
        pendingSafResult?.complete(false)
        pendingSafResult = null
        job?.cancel()
        _state.update {
            it.copy(
                working = false,
                workKind = WorkKind.NONE,
                statusText = null,
                error = "\u5df2\u7ec8\u6b62"
            )
        }
        reload()
    }

    private data class Copied(val item: MediaItem, val file: File)

    private suspend fun hideItemsBatched(items: List<MediaItem>) {
        _state.update {
            it.copy(working = true, workKind = WorkKind.HIDE, progress = 0, error = null)
        }
        try {
            val bucketId = originalBucketId
            val total = items.size
            val direct = permissions.hasManageAllFiles()
            val batches = (total + SAF_BATCH_SIZE - 1) / SAF_BATCH_SIZE

            val albumDbId = withContext(Dispatchers.IO) {
                getOrCreateHiddenAlbum(bucketId, albumName.ifBlank { "\u76f8\u518c" })
            }

            var done = 0
            var batchIndex = 0
            val hiddenIds = mutableSetOf<Long>()

            for (chunk in items.chunked(SAF_BATCH_SIZE)) {
                coroutineContext.ensureActive()
                batchIndex++
                val pending = ArrayList<Copied>(chunk.size)
                for ((i, item) in chunk.withIndex()) {
                    coroutineContext.ensureActive()
                    val n = done + pending.size + 1
                    _state.update {
                        it.copy(
                            progress = ((done + i) * 100) / total,
                            statusText = "\u590d\u5236 $n/$total\uff1a${item.displayName}"
                        )
                    }
                    val file = try {
                        withContext(Dispatchers.IO) {
                            fileMover.copyToPrivate(item, bucketId, encrypt = false)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "copy failed ${item.displayName}", e)
                        continue
                    }
                    pending += Copied(item, file)
                }
                if (pending.isEmpty()) continue

                if (direct) {
                    _state.update {
                        it.copy(statusText = "\u6b63\u5728\u5220\u9664 ${pending.size} \u9879\uff08\u514d\u786e\u8ba4\uff09\u2026")
                    }
                    for (c in pending) {
                        coroutineContext.ensureActive()
                        if (mediaStore.deleteDirect(c.item)) {
                            withContext(Dispatchers.IO) {
                                insertHidden(albumDbId, c)
                                done++
                                db.hiddenAlbumDao().updateCount(
                                    albumDbId,
                                    db.hiddenMediaDao().listByAlbum(albumDbId).size
                                )
                            }
                            hiddenIds += c.item.id
                            // 每删一张就从网格里拿走，下面列表实时变少
                            _state.update {
                                withSortedMedia(
                                    it.copy(
                                        progress = (done * 100) / total,
                                        statusText = "\u5df2\u5904\u7406 $done/$total\uff1a${c.item.displayName}"
                                    ),
                                    it.media.filter { m -> m.id !in hiddenIds }
                                )
                            }
                        } else {
                            withContext(Dispatchers.IO) { c.file.delete() }
                        }
                    }
                } else {
                    _state.update {
                        it.copy(statusText = "\u8bf7\u786e\u8ba4\u5220\u9664\u7b2c $batchIndex/$batches \u6279\uff08${pending.size} \u5f20\uff09")
                    }
                    val uris = pending.map { mediaStore.uriForDelete(it.item) }
                    val ids = pending.map { it.item.id }
                    val ok = awaitSystemDeleteBatch(uris, ids)
                    for (c in pending) {
                        if (!mediaStore.exists(c.item.id)) {
                            withContext(Dispatchers.IO) {
                                insertHidden(albumDbId, c)
                                done++
                            }
                            hiddenIds += c.item.id
                        } else {
                            withContext(Dispatchers.IO) { c.file.delete() }
                        }
                    }
                    withContext(Dispatchers.IO) {
                        db.hiddenAlbumDao().updateCount(
                            albumDbId,
                            db.hiddenMediaDao().listByAlbum(albumDbId).size
                        )
                    }
                    _state.update {
                        withSortedMedia(
                            it.copy(
                                progress = (done * 100) / total,
                                statusText = "\u5df2\u786e\u8ba4\u7b2c $batchIndex/$batches \u6279 \u00b7 \u6210\u529f $done/$total"
                            ),
                            it.media.filter { m -> m.id !in hiddenIds }
                        )
                    }
                    if (!ok && pending.any { mediaStore.exists(it.item.id) }) {
                        _state.update {
                            withSortedMedia(
                                it.copy(
                                    working = false,
                                    workKind = WorkKind.NONE,
                                    statusText = null,
                                    error = "\u5df2\u53d6\u6d88\u3002\u6210\u529f\u9690\u85cf $done/$total \u9879"
                                ),
                                it.media.filter { m -> m.id !in hiddenIds }
                            )
                        }
                        return
                    }
                }
            }

            _state.update {
                it.copy(
                    working = false,
                    workKind = WorkKind.NONE,
                    progress = 100,
                    statusText = null,
                    error = null
                )
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "hideItemsBatched cancelled")
        } catch (e: Throwable) {
            Log.e(TAG, "hideItemsBatched failed", e)
            pendingSafResult?.complete(false)
            _state.update {
                it.copy(
                    working = false,
                    workKind = WorkKind.NONE,
                    statusText = null,
                    error = e.message
                )
            }
        }
    }

    private suspend fun insertHidden(albumDbId: Long, c: Copied) {
        db.hiddenMediaDao().insertAll(
            listOf(
                HiddenMediaEntity(
                    albumId = albumDbId,
                    mediaStoreId = c.item.id,
                    displayName = c.item.displayName,
                    mimeType = c.item.mimeType,
                    relativePath = c.item.relativePath,
                    takenAt = c.item.takenAt,
                    sizeBytes = c.item.sizeBytes,
                    durationMs = c.item.durationMs,
                    width = c.item.width,
                    height = c.item.height,
                    privateFileName = c.file.name
                )
            )
        )
    }

    private suspend fun getOrCreateHiddenAlbum(bucketId: Long, name: String): Long {
        db.hiddenAlbumDao().findByBucketId(bucketId)?.let { return it.id }
        val id = db.hiddenAlbumDao().insert(
            HiddenAlbumEntity(
                originalBucketId = bucketId,
                displayName = name,
                count = 0,
                hiddenAt = System.currentTimeMillis()
            )
        )
        if (id > 0) return id
        return db.hiddenAlbumDao().findByBucketId(bucketId)?.id
            ?: error("\u65e0\u6cd5\u521b\u5efa\u9690\u85cf\u76f8\u518c")
    }

    private suspend fun awaitSystemDeleteBatch(
        uris: List<Uri>,
        mediaIds: List<Long>
    ): Boolean {
        if (uris.isEmpty()) return true
        val deferred = CompletableDeferred<Boolean>()
        pendingSafResult = deferred
        val confirmed = try {
            Log.i(TAG, "createDeleteRequest batch=${uris.size}")
            _safRequest.emit(fileMover.createDeleteRequest(uris))
            deferred.await()
        } catch (e: Throwable) {
            Log.e(TAG, "SAF failed size=${uris.size}", e)
            pendingSafResult = null
            false
        }
        if (confirmed) return true
        val anyLeft = withContext(Dispatchers.IO) {
            mediaIds.any { mediaStore.exists(it) }
        }
        if (!anyLeft) {
            Log.w(TAG, "SAF cancel but all gone -> OK")
            return true
        }
        return false
    }

    fun onHideSafConfirmed() {
        Log.i(TAG, "SAF confirmed")
        pendingSafResult?.complete(true)
        pendingSafResult = null
    }

    fun onHideSafCancelled() {
        Log.i(TAG, "SAF cancelled")
        pendingSafResult?.complete(false)
        pendingSafResult = null
    }

    fun clearSafState() { /* no-op：状态由 pendingSafResult 管理 */ }

    // ---- 还原：先选文件夹 ----

    fun restoreOne(mediaId: Long) {
        _state.update {
            it.copy(showRestoreFolderPicker = true, pendingRestoreIds = listOf(mediaId))
        }
    }

    fun restoreSelected() {
        val ids = _state.value.selected.toList()
        if (ids.isEmpty()) return
        exitMultiSelect()
        _state.update {
            it.copy(showRestoreFolderPicker = true, pendingRestoreIds = ids)
        }
    }

    fun dismissRestoreFolderPicker() {
        _state.update {
            it.copy(showRestoreFolderPicker = false, pendingRestoreIds = emptyList())
        }
    }

    fun consumeError() {
        _state.update { it.copy(error = null) }
    }

    fun confirmRestoreFolder(option: RestoreFolderOption) {
        val ids = _state.value.pendingRestoreIds
        _state.update {
            it.copy(showRestoreFolderPicker = false, pendingRestoreIds = emptyList())
        }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            restoreItems(ids, option.relativePath)
        }
    }

    private suspend fun restoreItems(ids: List<Long>, overridePath: String?) {
        _state.update {
            it.copy(working = true, workKind = WorkKind.RESTORE, progress = 0, statusText = "\u6b63\u5728\u8fd8\u539f\u2026")
        }
        try {
            val rows = withContext(Dispatchers.IO) { db.hiddenMediaDao().listByIds(ids) }
            if (rows.isEmpty()) {
                _state.update {
                    it.copy(working = false, workKind = WorkKind.NONE, error = "\u6ca1\u6709\u53ef\u8fd8\u539f\u7684\u9879")
                }
                return
            }
            val album = withContext(Dispatchers.IO) { db.hiddenAlbumDao().findById(albumId) }
            val bucketId = album?.originalBucketId ?: originalBucketId
            val albumDir = layout.albumDir(bucketId)

            var ok = 0
            rows.forEachIndexed { idx, row ->
                _state.update {
                    it.copy(
                        progress = (idx * 100) / rows.size,
                        statusText = "\u8fd8\u539f ${idx + 1}/${rows.size}\uff1a${row.displayName}"
                    )
                }
                val src = File(albumDir, row.privateFileName)
                val success = withContext(Dispatchers.IO) {
                    val readable = mediaStore.isReadable(row.mediaStoreId, row.mimeType)
                    if (readable) {
                        true // 原图可打开，只清隐藏记录
                    } else if (!src.exists()) {
                        Log.e(TAG, "restore skip, private missing ${src.absolutePath}")
                        false
                    } else {
                        fileMover.restoreToMediaStore(
                            FileMover.WriteEntry(
                                displayName = row.displayName,
                                mimeType = row.mimeType,
                                relativePath = row.relativePath,
                                takenAt = row.takenAt
                            ),
                            src,
                            overrideRelativePath = overridePath
                        ) != null
                    }
                }
                if (success) {
                    withContext(Dispatchers.IO) {
                        if (src.exists()) src.delete()
                        db.hiddenMediaDao().deleteById(row.id)
                    }
                    ok++
                } else {
                    Log.e(TAG, "restore failed keep private: ${row.displayName}")
                }
            }

            withContext(Dispatchers.IO) {
                val left = db.hiddenMediaDao().listByAlbum(albumId)
                if (left.isEmpty()) {
                    db.hiddenAlbumDao().deleteById(albumId)
                    layout.deleteAlbumDir(bucketId)
                } else {
                    db.hiddenAlbumDao().updateCount(albumId, left.size)
                }
            }

            _state.update {
                withSortedMedia(
                    it.copy(
                        working = false,
                        workKind = WorkKind.NONE,
                        progress = 100,
                        statusText = null
                    ),
                    it.media.filter { m -> m.id !in ids }
                )
            }
            if (ok < rows.size) {
                _state.update { it.copy(error = "\u90e8\u5206\u8fd8\u539f\u5931\u8d25\uff1a$ok/${rows.size}") }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "restore failed", e)
            _state.update {
                it.copy(working = false, workKind = WorkKind.NONE, statusText = null, error = e.message)
            }
        }
    }

    override fun onCleared() {
        hideJob?.cancel()
        pendingSafResult?.complete(false)
        super.onCleared()
    }

    private companion object {
        const val TAG = "AlbumDetailVM"
        const val SAF_BATCH_SIZE = 100
    }
}
