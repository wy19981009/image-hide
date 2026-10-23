package com.whjk.imagehide.ui.albumlist

import android.content.IntentSender
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whjk.imagehide.data.filemove.FileMover
import com.whjk.imagehide.data.filemove.PrivateDirLayout
import com.whjk.imagehide.data.mediastore.MediaStoreDataSource
import com.whjk.imagehide.data.permission.PermissionChecker
import com.whjk.imagehide.data.repository.HiddenRepositoryImpl
import com.whjk.imagehide.data.room.AppDatabase
import com.whjk.imagehide.data.room.entity.HiddenAlbumEntity
import com.whjk.imagehide.data.room.entity.HiddenMediaEntity
import com.whjk.imagehide.domain.error.AppError
import com.whjk.imagehide.domain.model.Album
import com.whjk.imagehide.domain.model.MediaItem
import com.whjk.imagehide.domain.usecase.RestoreAlbumUseCase
import com.whjk.imagehide.domain.usecase.ScanAlbumsUseCase
import com.whjk.imagehide.worker.RestoreAlbumWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class AlbumListState(
    val systemAlbums: List<Album> = emptyList(),
    val hiddenAlbums: List<Album> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val working: Boolean = false,
    val progress: Int = 0,
    val workKind: WorkKind = WorkKind.NONE,
    /** 当前步骤文案，如「隐藏 3/100：IMG_001.jpg」 */
    val statusText: String? = null,

    /** 0=系统相册，1=已隐藏；放 ViewModel 以便进详情再返回时保持 */
    val selectedTab: Int = 0,

    val multiSelect: Boolean = false,
    val selectedAlbums: Set<Long> = emptySet(),

    val message: String? = null,

    /** 还原前选择目标文件夹 */
    val showRestoreFolderPicker: Boolean = false,
    val pendingRestoreAlbumIds: List<Long> = emptyList(),

    /** 隐藏前引导开启所有文件访问 */
    val showManageAllFilesGuide: Boolean = false,
    val pendingHideAlbum: Album? = null,
    val pendingHideCount: Int = 0
)

enum class WorkKind { NONE, HIDE, RESTORE }

data class SafRequest(
    val kind: WorkKind,
    val intentSender: IntentSender,
    val albumDbId: Long = -1L,
    val workId: String = "",
    val batchIndex: Int = 0,
    val batchTotal: Int = 1
)

/**
 * 整本相册隐藏：
 * - 有「所有文件访问」：复制后直接删系统文件，无需弹窗
 * - 否则：每批最多 [SAF_BATCH_SIZE] 张共用一次系统确认
 */
@HiltViewModel
class AlbumListViewModel @Inject constructor(
    private val scanAlbums: ScanAlbumsUseCase,
    private val restoreAlbum: RestoreAlbumUseCase,
    private val hiddenRepoImpl: HiddenRepositoryImpl,
    private val fileMover: FileMover,
    private val layout: PrivateDirLayout,
    private val mediaStore: MediaStoreDataSource,
    private val db: AppDatabase,
    private val permissions: PermissionChecker
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumListState())
    val state: StateFlow<AlbumListState> = _state.asStateFlow()

    private val _safRequest = MutableLiveData<SafRequest?>(null)
    val safRequest: LiveData<SafRequest?> = _safRequest

    private var workLiveData: LiveData<androidx.work.WorkInfo>? = null
    private var workObserver: androidx.lifecycle.Observer<androidx.work.WorkInfo>? = null

    private var hideJob: Job? = null
    private var pendingSafResult: CompletableDeferred<Boolean>? = null

    init {
        refresh()
    }

    fun setSelectedTab(index: Int) {
        if (index !in 0..1) return
        _state.update { it.copy(selectedTab = index) }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val system = scanAlbums()
                val hidden = hiddenRepoImpl.listHiddenAlbums()
                _state.update {
                    it.copy(
                        systemAlbums = system,
                        hiddenAlbums = hidden,
                        loading = false,
                        error = null
                    )
                }
            } catch (e: AppError) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: SecurityException) {
                _state.update { it.copy(loading = false, error = "\u6743\u9650\u88ab\u62d2\u7edd") }
            } catch (e: Throwable) {
                _state.update { it.copy(loading = false, error = e.message ?: "\u672a\u77e5\u9519\u8bef") }
            }
        }
    }

    fun toggleHide(album: Album) {
        if (_state.value.working) return
        if (permissions.hasManageAllFiles()) {
            startHide(album)
            return
        }
        // 未开所有文件访问：先引导，避免用户被几百次确认吓到
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                mediaStore.listSystemMediaByBucket(album.bucketId).size
            }
            _state.update {
                it.copy(
                    showManageAllFilesGuide = true,
                    pendingHideAlbum = album,
                    pendingHideCount = count
                )
            }
        }
    }

    fun dismissManageAllFilesGuide() {
        _state.update {
            it.copy(
                showManageAllFilesGuide = false,
                pendingHideAlbum = null,
                pendingHideCount = 0
            )
        }
    }

    /** 用户选择「仍用批量确认」 */
    fun continueHideWithoutAllFiles() {
        val album = _state.value.pendingHideAlbum ?: return
        dismissManageAllFilesGuide()
        startHide(album)
    }

    /** 从设置返回后：若已开启且有待隐藏相册，自动继续 */
    fun onReturnFromAllFilesSettings() {
        val album = _state.value.pendingHideAlbum ?: return
        if (!permissions.hasManageAllFiles()) return
        dismissManageAllFilesGuide()
        startHide(album)
    }

    private fun startHide(album: Album) {
        hideJob?.cancel()
        hideJob = viewModelScope.launch { hideAlbumBatched(album) }
    }

    /** 终止当前隐藏/还原任务 */
    fun cancelWorking() {
        val job = hideJob
        hideJob = null
        pendingSafResult?.complete(false)
        pendingSafResult = null
        _safRequest.postValue(null)
        job?.cancel()
        unregisterWorkObserver()
        _state.update {
            it.copy(
                working = false,
                workKind = WorkKind.NONE,
                statusText = null,
                message = "\u5df2\u7ec8\u6b62"
            )
        }
        refresh()
    }

    private data class Copied(val item: MediaItem, val file: File)

    private suspend fun hideAlbumBatched(album: Album) {
        _state.update {
            it.copy(
                working = true,
                workKind = WorkKind.HIDE,
                progress = 0,
                error = null,
                statusText = "\u6b63\u5728\u626b\u63cf ${album.name}\u2026"
            )
        }
        try {
            val items = withContext(Dispatchers.IO) {
                mediaStore.listSystemMediaByBucket(album.bucketId)
            }
            if (items.isEmpty()) {
                _state.update {
                    it.copy(
                        working = false,
                        workKind = WorkKind.NONE,
                        statusText = null,
                        message = "\u76f8\u518c\u662f\u7a7a\u7684"
                    )
                }
                return
            }

            val direct = permissions.hasManageAllFiles()
            val total = items.size
            val batches = if (direct) 1 else ((total + SAF_BATCH_SIZE - 1) / SAF_BATCH_SIZE)
            Log.i(TAG, "hide batched: ${album.name} count=$total direct=$direct batches~$batches")

            if (!direct) {
                _state.update {
                    it.copy(
                        message = "\u672a\u5f00\u542f\u300c\u6240\u6709\u6587\u4ef6\u8bbf\u95ee\u300d\uff0c\u7ea6\u9700\u786e\u8ba4 $batches \u6b21\uff08\u8bbe\u7f6e\u91cc\u53ef\u5f00\u542f\u4ee5\u514d\u786e\u8ba4\uff09"
                    )
                }
            }

            // 复用已有隐藏相册记录；禁止开删私有目录（会丢掉上次未还原完的文件）
            val albumDbId = withContext(Dispatchers.IO) {
                db.hiddenAlbumDao().findByBucketId(album.bucketId)?.id
                    ?: db.hiddenAlbumDao().insert(
                        HiddenAlbumEntity(
                            originalBucketId = album.bucketId,
                            displayName = album.name,
                            count = 0,
                            hiddenAt = System.currentTimeMillis()
                        )
                    )
            }
            if (albumDbId <= 0L) {
                _state.update {
                    it.copy(
                        working = false,
                        workKind = WorkKind.NONE,
                        statusText = null,
                        error = "\u65e0\u6cd5\u521b\u5efa\u9690\u85cf\u8bb0\u5f55"
                    )
                }
                return
            }

            var done = 0
            var cancelled = false
            var batchIndex = 0
            // 直接删也按批复制，避免一次占满磁盘；无弹窗只是跳过 SAF
            val chunks = items.chunked(SAF_BATCH_SIZE)

            for (chunk in chunks) {
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
                            fileMover.copyToPrivate(item, album.bucketId, encrypt = false)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "copy failed: ${item.displayName}", e)
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
                        val gone = mediaStore.deleteDirect(c.item)
                        if (gone) {
                            withContext(Dispatchers.IO) {
                                insertHiddenMedia(albumDbId, c)
                                done++
                                db.hiddenAlbumDao().updateCount(albumDbId, done)
                            }
                        } else {
                            withContext(Dispatchers.IO) { c.file.delete() }
                            Log.w(TAG, "direct delete failed ${c.item.displayName}")
                        }
                        publishHideProgress(
                            album = album,
                            albumDbId = albumDbId,
                            done = done,
                            total = total,
                            status = "\u5df2\u5904\u7406 $done/$total\uff1a${c.item.displayName}"
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            statusText = "\u8bf7\u786e\u8ba4\u5220\u9664\u7b2c $batchIndex/$batches \u6279\uff08${pending.size} \u5f20\uff09"
                        )
                    }
                    val uris = pending.map { mediaStore.uriForDelete(it.item) }
                    val ids = pending.map { it.item.id }
                    val ok = awaitSystemDeleteBatch(uris, ids)
                    val committed = commitDeletedBatch(albumDbId, pending)
                    done += committed
                    publishHideProgress(
                        album = album,
                        albumDbId = albumDbId,
                        done = done,
                        total = total,
                        status = "\u5df2\u786e\u8ba4\u7b2c $batchIndex/$batches \u6279 \u00b7 \u6210\u529f $done/$total"
                    )
                    if (!ok && committed < pending.size) {
                        cancelled = true
                        break
                    }
                }
            }

            withContext(Dispatchers.IO) {
                if (done == 0) {
                    db.hiddenAlbumDao().deleteById(albumDbId)
                    layout.deleteAlbumDir(album.bucketId)
                } else {
                    db.hiddenAlbumDao().updateCount(albumDbId, done)
                }
            }

            val endMessage = when {
                !cancelled -> "\u5df2\u9690\u85cf $done \u9879"
                lastDeleteFailReason != null && lastDeleteFailReason != "user_cancel" ->
                    "\u5220\u9664\u8bf7\u6c42\u5931\u8d25\uff08${lastDeleteFailReason}\uff09\uff0c\u6210\u529f\u9690\u85cf $done/$total \u9879"
                else -> "\u5df2\u53d6\u6d88\uff0c\u6210\u529f\u9690\u85cf $done/$total \u9879"
            }
            _state.update {
                it.copy(
                    working = false,
                    workKind = WorkKind.NONE,
                    progress = if (cancelled) (done * 100 / total).coerceAtLeast(0) else 100,
                    statusText = null,
                    message = endMessage
                )
            }
            refresh()
        } catch (e: CancellationException) {
            Log.i(TAG, "hideAlbumBatched cancelled")
            // UI 已由 cancelWorking 更新
        } catch (e: Throwable) {
            Log.e(TAG, "hideAlbumBatched failed", e)
            pendingSafResult?.complete(false)
            pendingSafResult = null
            _safRequest.postValue(null)
            _state.update {
                it.copy(
                    working = false,
                    workKind = WorkKind.NONE,
                    statusText = null,
                    error = e.message ?: "\u9690\u85cf\u5931\u8d25"
                )
            }
        }
    }

    private suspend fun insertHiddenMedia(albumDbId: Long, c: Copied) {
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

    /** 进度条 + 下方相册数量实时刷新（系统相册减少、已隐藏增加） */
    private fun publishHideProgress(
        album: Album,
        albumDbId: Long,
        done: Int,
        total: Int,
        status: String
    ) {
        val remaining = (total - done).coerceAtLeast(0)
        _state.update { st ->
            val systemAlbums = st.systemAlbums.map { a ->
                if (a.bucketId == album.bucketId) a.copy(count = remaining) else a
            }.let { list ->
                if (remaining == 0) list.filter { it.bucketId != album.bucketId } else list
            }
            val hiddenEntry = Album(
                bucketId = albumDbId,
                name = album.name,
                count = done,
                coverPath = null,
                source = Album.Source.HIDDEN
            )
            val hiddenAlbums = st.hiddenAlbums.toMutableList()
            val idx = hiddenAlbums.indexOfFirst { it.bucketId == albumDbId }
            if (done <= 0) {
                if (idx >= 0) hiddenAlbums.removeAt(idx)
            } else if (idx >= 0) {
                hiddenAlbums[idx] = hiddenEntry
            } else {
                hiddenAlbums.add(0, hiddenEntry)
            }
            st.copy(
                progress = (done * 100) / total.coerceAtLeast(1),
                statusText = status,
                systemAlbums = systemAlbums,
                hiddenAlbums = hiddenAlbums
            )
        }
    }

    /** 按「系统里是否已消失」提交本批；仍在的删掉私有副本 */
    private suspend fun commitDeletedBatch(albumDbId: Long, pending: List<Copied>): Int {
        var n = 0
        withContext(Dispatchers.IO) {
            for (c in pending) {
                if (!mediaStore.exists(c.item.id)) {
                    insertHiddenMedia(albumDbId, c)
                    n++
                } else {
                    c.file.delete()
                }
            }
            if (n > 0) {
                val count = db.hiddenMediaDao().listByAlbum(albumDbId).size
                db.hiddenAlbumDao().updateCount(albumDbId, count)
            }
        }
        return n
    }

    private var lastDeleteFailReason: String? = null

    private suspend fun awaitSystemDeleteBatch(
        uris: List<android.net.Uri>,
        mediaIds: List<Long>
    ): Boolean {
        if (uris.isEmpty()) return true
        lastDeleteFailReason = null
        val deferred = CompletableDeferred<Boolean>()
        pendingSafResult = deferred
        val confirmed = try {
            val sender = fileMover.createDeleteRequest(uris)
            Log.i(TAG, "createDeleteRequest batch=${uris.size}")
            _safRequest.postValue(SafRequest(kind = WorkKind.HIDE, intentSender = sender))
            deferred.await()
        } catch (e: Throwable) {
            Log.e(TAG, "createDeleteRequest failed size=${uris.size}", e)
            pendingSafResult = null
            lastDeleteFailReason = e.message ?: e.javaClass.simpleName
            false
        }
        if (confirmed) return true
        val anyLeft = withContext(Dispatchers.IO) {
            mediaIds.any { mediaStore.exists(it) }
        }
        if (!anyLeft) {
            Log.w(TAG, "SAF cancel but all ${mediaIds.size} gone -> OK")
            return true
        }
        if (lastDeleteFailReason == null) lastDeleteFailReason = "user_cancel"
        return false
    }

    fun onSafConsumed() {
        _safRequest.value = null
    }

    fun onSafCancelled() {
        Log.i(TAG, "SAF cancelled by UI")
        pendingSafResult?.complete(false)
        pendingSafResult = null
    }

    fun onSafConfirmed(req: SafRequest? = null) {
        Log.i(TAG, "SAF confirmed by UI")
        pendingSafResult?.complete(true)
        pendingSafResult = null
    }

    fun toggleRestore(album: Album) {
        _state.update {
            it.copy(
                showRestoreFolderPicker = true,
                pendingRestoreAlbumIds = listOf(album.bucketId)
            )
        }
    }

    fun dismissRestoreFolderPicker() {
        _state.update {
            it.copy(showRestoreFolderPicker = false, pendingRestoreAlbumIds = emptyList())
        }
    }

    fun confirmRestoreFolder(option: com.whjk.imagehide.ui.common.RestoreFolderOption) {
        val ids = _state.value.pendingRestoreAlbumIds
        _state.update {
            it.copy(showRestoreFolderPicker = false, pendingRestoreAlbumIds = emptyList())
        }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _state.update {
                it.copy(working = true, workKind = WorkKind.RESTORE, progress = 0, error = null)
            }
            try {
                ids.forEachIndexed { index, id ->
                    _state.update {
                        it.copy(statusText = "\u8fd8\u539f\u76f8\u518c ${index + 1}/${ids.size}")
                    }
                    val workId = restoreAlbum.invoke(id, option.relativePath)
                    // 多本时只观察最后一个；单本观察全程进度
                    if (ids.size == 1) {
                        observeRestoreWork(workId)
                        return@launch
                    }
                }
                _state.update {
                    it.copy(
                        message = "\u5df2\u63d0\u4ea4 ${ids.size} \u4e2a\u8fd8\u539f\u4efb\u52a1",
                        working = false,
                        workKind = WorkKind.NONE,
                        statusText = null
                    )
                }
                exitMultiSelect()
                refresh()
            } catch (e: Throwable) {
                _state.update { it.copy(working = false, workKind = WorkKind.NONE, error = e.message) }
            }
        }
    }

    fun onAlbumLongPress(album: Album) {
        if (album.source != Album.Source.HIDDEN) return
        _state.update { it.copy(multiSelect = true) }
        toggleAlbumSelected(album.bucketId)
    }

    fun onAlbumTap(album: Album) {
        if (_state.value.multiSelect && album.source == Album.Source.HIDDEN) {
            toggleAlbumSelected(album.bucketId)
        }
    }

    fun toggleAlbumSelected(id: Long) {
        _state.update {
            val sel = it.selectedAlbums.toMutableSet()
            if (sel.contains(id)) sel.remove(id) else sel.add(id)
            it.copy(selectedAlbums = sel)
        }
    }

    /** 滑动多选相册：返回本次拖动统一目标选中态 */
    fun beginDragSelectAlbum(id: Long): Boolean {
        val state = _state.value
        val target = if (!state.multiSelect) true else id !in state.selectedAlbums
        _state.update {
            val sel = it.selectedAlbums.toMutableSet()
            if (target) sel.add(id) else sel.remove(id)
            it.copy(multiSelect = true, selectedAlbums = sel)
        }
        return target
    }

    fun setAlbumSelected(id: Long, selected: Boolean) {
        _state.update {
            val sel = it.selectedAlbums.toMutableSet()
            if (selected) sel.add(id) else sel.remove(id)
            it.copy(multiSelect = true, selectedAlbums = sel)
        }
    }

    fun selectAllAlbums() {
        _state.update {
            it.copy(selectedAlbums = it.hiddenAlbums.map { a -> a.bucketId }.toSet())
        }
    }

    fun exitMultiSelect() {
        _state.update { it.copy(multiSelect = false, selectedAlbums = emptySet()) }
    }

    fun restoreSelectedAlbums() {
        val ids = _state.value.selectedAlbums.toList()
        if (ids.isEmpty()) return
        exitMultiSelect()
        _state.update {
            it.copy(showRestoreFolderPicker = true, pendingRestoreAlbumIds = ids)
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null, error = null) }
    }

    private fun observeRestoreWork(workId: String) {
        unregisterWorkObserver()
        val live = hiddenRepoImpl.observeWork(workId)
        val observer = androidx.lifecycle.Observer<androidx.work.WorkInfo> { info ->
            if (info == null) return@Observer
            when (info.state) {
                androidx.work.WorkInfo.State.RUNNING -> {
                    val pct = info.progress.getInt(RestoreAlbumWorker.KEY_PROGRESS, 0)
                    _state.update { it.copy(progress = pct) }
                }
                androidx.work.WorkInfo.State.SUCCEEDED -> {
                    val fail = info.outputData.getInt(RestoreAlbumWorker.KEY_FAIL, 0)
                    val ok = info.outputData.getInt(RestoreAlbumWorker.KEY_OK, 0)
                    val message = if (fail > 0) {
                        "\u90e8\u5206\u8fd8\u539f\u6210\u529f $ok\uff0c\u5931\u8d25 $fail \u9879\u5df2\u4fdd\u7559\u5728\u9690\u85cf\u76f8\u518c\uff0c\u53ef\u91cd\u8bd5"
                    } else {
                        "\u8fd8\u539f\u5b8c\u6210 $ok \u9879"
                    }
                    _state.update {
                        it.copy(
                            working = false,
                            workKind = WorkKind.NONE,
                            progress = 100,
                            message = message
                        )
                    }
                    unregisterWorkObserver()
                    refresh()
                }
                androidx.work.WorkInfo.State.FAILED -> {
                    val msg = info.outputData.getString(RestoreAlbumWorker.KEY_ERROR) ?: "\u8fd8\u539f\u5931\u8d25"
                    _state.update { it.copy(working = false, workKind = WorkKind.NONE, error = msg) }
                    unregisterWorkObserver()
                    refresh()
                }
                androidx.work.WorkInfo.State.CANCELLED -> {
                    _state.update { it.copy(working = false, workKind = WorkKind.NONE, error = "\u5df2\u53d6\u6d88") }
                    unregisterWorkObserver()
                }
                else -> Unit
            }
        }
        live.observeForever(observer)
        workLiveData = live
        workObserver = observer
    }

    private fun unregisterWorkObserver() {
        workObserver?.let { obs -> workLiveData?.removeObserver(obs) }
        workLiveData = null
        workObserver = null
    }

    override fun onCleared() {
        hideJob?.cancel()
        pendingSafResult?.complete(false)
        unregisterWorkObserver()
        super.onCleared()
    }

    private companion object {
        const val TAG = "AlbumListVM"
        /** 无「所有文件访问」时，每批共用一次系统删除确认 */
        const val SAF_BATCH_SIZE = 100
    }
}
