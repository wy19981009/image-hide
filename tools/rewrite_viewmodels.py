# -*- coding: utf-8 -*-
"""Rewrite AlbumListViewModel and AlbumDetailViewModel with UTF-8 Chinese (\\u escapes in strings)."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def escape_kotlin_string_literals(source: str) -> str:
    result: list[str] = []
    i = 0
    n = len(source)
    while i < n:
        ch = source[i]
        if ch != '"':
            result.append(ch)
            i += 1
            continue
        result.append('"')
        i += 1
        while i < n:
            c = source[i]
            if c == "\\":
                result.append(source[i : i + 2])
                i += 2
            elif c == '"':
                result.append('"')
                i += 1
                break
            elif c == "$" and i + 1 < n and source[i + 1] == "{":
                j = i + 2
                while j < n and source[j] != "}":
                    j += 1
                result.append(source[i : j + 1])
                i = j + 1
            else:
                if ord(c) > 127:
                    result.append(f"\\u{ord(c):04x}")
                else:
                    result.append(c)
                i += 1
    return "".join(result)


ALBUM_LIST_KT = r'''package com.whjk.imagehide.ui.albumlist

import android.content.IntentSender
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whjk.imagehide.data.datastore.UserPrefs
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
import kotlinx.coroutines.flow.first
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
    private val userPrefs: UserPrefs,
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
                _state.update { it.copy(loading = false, error = "权限被拒绝") }
            } catch (e: Throwable) {
                _state.update { it.copy(loading = false, error = e.message ?: "未知错误") }
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
                message = "已终止"
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
                statusText = "正在扫描 ${album.name}…"
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
                        message = "相册是空的"
                    )
                }
                return
            }

            val direct = permissions.hasManageAllFiles()
            val encrypt = userPrefs.encryptEnabled.first()
            val total = items.size
            val batches = if (direct) 1 else ((total + SAF_BATCH_SIZE - 1) / SAF_BATCH_SIZE)
            Log.i(TAG, "hide batched: ${album.name} count=$total direct=$direct batches~$batches")

            if (!direct) {
                _state.update {
                    it.copy(
                        message = "未开启「所有文件访问」，约需确认 $batches 次（设置里可开启以免确认）"
                    )
                }
            }

            val albumDbId = withContext(Dispatchers.IO) {
                db.hiddenAlbumDao().findByBucketId(album.bucketId)?.let { old ->
                    db.hiddenAlbumDao().deleteById(old.id)
                }
                layout.deleteAlbumDir(album.bucketId)
                db.hiddenAlbumDao().insert(
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
                        error = "无法创建隐藏记录"
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
                            statusText = "复制 $n/$total：${item.displayName}"
                        )
                    }
                    val file = try {
                        withContext(Dispatchers.IO) {
                            fileMover.copyToPrivate(item, album.bucketId, encrypt)
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
                        it.copy(statusText = "正在删除 ${pending.size} 项（免确认）…")
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
                            status = "已处理 $done/$total：${c.item.displayName}"
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            statusText = "请确认删除第 $batchIndex/$batches 批（${pending.size} 张）"
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
                        status = "已确认第 $batchIndex/$batches 批 · 成功 $done/$total"
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
                !cancelled -> "已隐藏 $done 项"
                lastDeleteFailReason != null && lastDeleteFailReason != "user_cancel" ->
                    "删除请求失败（${lastDeleteFailReason}），成功隐藏 $done/$total 项"
                else -> "已取消，成功隐藏 $done/$total 项"
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
                    error = e.message ?: "隐藏失败"
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
                        it.copy(statusText = "还原相册 ${index + 1}/${ids.size}")
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
                        message = "已提交 ${ids.size} 个还原任务",
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
                    _state.update { it.copy(working = false, workKind = WorkKind.NONE, progress = 100) }
                    unregisterWorkObserver()
                    refresh()
                }
                androidx.work.WorkInfo.State.FAILED -> {
                    val msg = info.outputData.getString(RestoreAlbumWorker.KEY_ERROR) ?: "还原失败"
                    _state.update { it.copy(working = false, workKind = WorkKind.NONE, error = msg) }
                    unregisterWorkObserver()
                    refresh()
                }
                androidx.work.WorkInfo.State.CANCELLED -> {
                    _state.update { it.copy(working = false, workKind = WorkKind.NONE, error = "已取消") }
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
'''

ALBUM_DETAIL_KT = r'''package com.whjk.imagehide.ui.detail

import android.content.IntentSender
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whjk.imagehide.data.datastore.UserPrefs
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
    val loading: Boolean = false,
    val error: String? = null,
    val statusText: String? = null,

    val multiSelect: Boolean = false,
    val selected: Set<Long> = emptySet(),

    val working: Boolean = false,
    val progress: Int = 0,
    val workKind: WorkKind = WorkKind.NONE,

    /** 还原时弹出的目标文件夹选择 */
    val showRestoreFolderPicker: Boolean = false,
    val pendingRestoreIds: List<Long> = emptyList(),

    /** 隐藏前引导开启所有文件访问 */
    val showManageAllFilesGuide: Boolean = false,
    val pendingHideItems: List<MediaItem> = emptyList()
)

enum class WorkKind { NONE, HIDE, RESTORE }

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val getMedia: GetMediaUseCase,
    private val fileMover: FileMover,
    private val layout: PrivateDirLayout,
    private val mediaStore: MediaStoreDataSource,
    private val db: AppDatabase,
    private val userPrefs: UserPrefs,
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
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                if (hidden) {
                    // albumId = Room hidden_albums.id
                    val album = db.hiddenAlbumDao().findById(albumId)
                    originalBucketId = album?.originalBucketId ?: albumId
                } else {
                    originalBucketId = albumId // system bucketId
                }
                val list = getMedia(albumId, hidden, single = false)
                _state.update { it.copy(media = list, loading = false) }
            } catch (e: Throwable) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    private fun reload() = load(albumId, albumName, hidden)

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
                error = "已终止"
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
            val encrypt = userPrefs.encryptEnabled.first()
            val bucketId = originalBucketId
            val total = items.size
            val direct = permissions.hasManageAllFiles()
            val batches = (total + SAF_BATCH_SIZE - 1) / SAF_BATCH_SIZE

            val albumDbId = withContext(Dispatchers.IO) {
                getOrCreateHiddenAlbum(bucketId, albumName.ifBlank { "相册" })
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
                            statusText = "复制 $n/$total：${item.displayName}"
                        )
                    }
                    val file = try {
                        withContext(Dispatchers.IO) {
                            fileMover.copyToPrivate(item, bucketId, encrypt)
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
                        it.copy(statusText = "正在删除 ${pending.size} 项（免确认）…")
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
                                it.copy(
                                    progress = (done * 100) / total,
                                    statusText = "已处理 $done/$total：${c.item.displayName}",
                                    media = it.media.filter { m -> m.id !in hiddenIds }
                                )
                            }
                        } else {
                            withContext(Dispatchers.IO) { c.file.delete() }
                        }
                    }
                } else {
                    _state.update {
                        it.copy(statusText = "请确认删除第 $batchIndex/$batches 批（${pending.size} 张）")
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
                        it.copy(
                            progress = (done * 100) / total,
                            statusText = "已确认第 $batchIndex/$batches 批 · 成功 $done/$total",
                            media = it.media.filter { m -> m.id !in hiddenIds }
                        )
                    }
                    if (!ok && pending.any { mediaStore.exists(it.item.id) }) {
                        _state.update {
                            it.copy(
                                working = false,
                                workKind = WorkKind.NONE,
                                statusText = null,
                                error = "已取消。成功隐藏 $done/$total 项",
                                media = it.media.filter { m -> m.id !in hiddenIds }
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
            ?: error("无法创建隐藏相册")
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
            it.copy(working = true, workKind = WorkKind.RESTORE, progress = 0, statusText = "正在还原…")
        }
        try {
            val rows = withContext(Dispatchers.IO) { db.hiddenMediaDao().listByIds(ids) }
            if (rows.isEmpty()) {
                _state.update {
                    it.copy(working = false, workKind = WorkKind.NONE, error = "没有可还原的项")
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
                        statusText = "还原 ${idx + 1}/${rows.size}：${row.displayName}"
                    )
                }
                val src = File(albumDir, row.privateFileName)
                val success = if (mediaStore.exists(row.mediaStoreId)) {
                    true // 原图还在，只清隐藏记录
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
                if (success) {
                    withContext(Dispatchers.IO) {
                        src.delete()
                        db.hiddenMediaDao().deleteById(row.id)
                    }
                    ok++
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
                it.copy(
                    working = false,
                    workKind = WorkKind.NONE,
                    progress = 100,
                    statusText = null,
                    media = it.media.filter { m -> m.id !in ids }
                )
            }
            if (ok < rows.size) {
                _state.update { it.copy(error = "部分还原失败：$ok/${rows.size}") }
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
'''


def main() -> None:
    targets = {
        ROOT / "app/src/main/java/com/whjk/imagehide/ui/albumlist/AlbumListViewModel.kt": ALBUM_LIST_KT,
        ROOT / "app/src/main/java/com/whjk/imagehide/ui/detail/AlbumDetailViewModel.kt": ALBUM_DETAIL_KT,
    }
    for path, src in targets.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(escape_kotlin_string_literals(src), encoding="utf-8")
        print(f"Wrote {path}")


if __name__ == "__main__":
    main()
