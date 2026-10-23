package com.whjk.imagehide.domain.repository

import com.whjk.imagehide.domain.model.Album

interface AlbumRepository {
    /** 扫描 MediaStore，按 BUCKET 分组得到所有系统相册 */
    suspend fun scanSystemAlbums(): List<Album>
}
