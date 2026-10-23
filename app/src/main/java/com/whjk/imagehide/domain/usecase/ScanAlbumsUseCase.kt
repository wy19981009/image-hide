package com.whjk.imagehide.domain.usecase

import com.whjk.imagehide.domain.model.Album
import com.whjk.imagehide.domain.repository.AlbumRepository
import javax.inject.Inject

class ScanAlbumsUseCase @Inject constructor(
    private val repo: AlbumRepository
) {
    suspend operator fun invoke(): List<Album> = repo.scanSystemAlbums()
}
