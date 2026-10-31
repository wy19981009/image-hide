package com.whjk.imagehide.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whjk.imagehide.domain.model.MediaItem
import com.whjk.imagehide.domain.usecase.GetMediaUseCase
import com.whjk.imagehide.ui.detail.MediaSort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class MediaViewerState(
    val media: List<MediaItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class MediaViewerViewModel @Inject constructor(
    private val getMedia: GetMediaUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(MediaViewerState())
    val state: StateFlow<MediaViewerState> = _state.asStateFlow()

    fun load(albumId: Long, hidden: Boolean, sort: MediaSort = MediaSort.TIME_DESC) {
        _state.update { it.copy(loading = true, error = null, media = emptyList()) }
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    getMedia(albumId, hidden, single = false)
                }
                _state.update {
                    it.copy(media = sortMedia(list, sort), loading = false)
                }
            } catch (e: Throwable) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    private fun sortMedia(list: List<MediaItem>, sort: MediaSort): List<MediaItem> =
        when (sort) {
            MediaSort.TIME_DESC -> list.sortedByDescending { it.takenAt }
            MediaSort.TIME_ASC -> list.sortedBy { it.takenAt }
            MediaSort.SIZE_DESC -> list.sortedByDescending { it.sizeBytes }
            MediaSort.SIZE_ASC -> list.sortedBy { it.sizeBytes }
        }
}
