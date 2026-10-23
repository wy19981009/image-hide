package com.whjk.imagehide.data.repository

import com.whjk.imagehide.data.mediastore.MediaStoreDataSource
import com.whjk.imagehide.domain.model.Album
import com.whjk.imagehide.domain.repository.AlbumRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlbumRepositoryImpl @Inject constructor(
    private val mediaStore: MediaStoreDataSource
) : AlbumRepository {
    override suspend fun scanSystemAlbums(): List<Album> =
        mediaStore.scanSystemAlbums()
}
