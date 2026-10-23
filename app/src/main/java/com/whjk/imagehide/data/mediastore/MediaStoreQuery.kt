package com.whjk.imagehide.data.mediastore

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具：在 MediaStore 里按 displayName + relativePath 查 _ID，拼出 content URI。
 *
 * 用途：SAF createWriteRequest 后，用户授权，系统会创建 IS_PENDING=1 的占位行，
 * 但 ActivityResult 不直接给 URI，需要回查 MediaStore。
 */
@Singleton
class MediaStoreQuery @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val resolver get() = context.contentResolver

    /**
     * 按 displayName + relativePath 查找 IS_PENDING=1 的 _ID，返回 content URI。
     */
    suspend fun findPendingByName(
        displayName: String,
        relativePath: String?
    ): Uri? = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.RELATIVE_PATH
        )
        val selection = buildString {
            append("${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?")
            append(" AND ${MediaStore.Files.FileColumns.IS_PENDING} = 1")
            if (!relativePath.isNullOrBlank()) {
                append(" AND ${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?")
            }
        }
        val args = buildList {
            add(displayName)
            if (!relativePath.isNullOrBlank()) add(relativePath)
        }.toTypedArray()

        resolver.query(collection, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                return@withContext Uri.withAppendedPath(collection, id.toString())
            }
        }
        null
    }
}
