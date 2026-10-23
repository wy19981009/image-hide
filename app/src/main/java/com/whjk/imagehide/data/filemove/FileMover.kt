package com.whjk.imagehide.data.filemove

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.whjk.imagehide.data.crypto.CryptoManager
import com.whjk.imagehide.domain.model.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件移动器。
 *
 * 隐藏：copy → MediaStore.createDeleteRequest（SAF）删系统可见项
 * 还原：本 App insert 的 IS_PENDING 行，App 已有写权限，**不需要** createWriteRequest
 */
@Singleton
class FileMover @Inject constructor(
    @ApplicationContext private val context: Context,
    private val layout: PrivateDirLayout,
    private val crypto: CryptoManager
) {
    private val resolver get() = context.contentResolver

    /**
     * 复制一个媒体项到私有目录。
     *
     * @param encrypt true 时输出 .enc（AES-256-GCM）
     * @return 目标 File（已存在表示成功）
     */
    suspend fun copyToPrivate(
        item: MediaItem,
        bucketId: Long,
        encrypt: Boolean = false
    ): File = withContext(Dispatchers.IO) {
        val base = layout.mediaFile(bucketId, item.displayName)
        val target = if (encrypt) File(base.parentFile, base.name + CryptoManager.ENC_SUFFIX) else base
        val safeTarget = uniqueFile(target)
        val sourceUri = Uri.parse(item.path)

        resolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(safeTarget).use { output ->
                if (encrypt) {
                    crypto.encryptStream(input, output)
                } else {
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
        } ?: throw IllegalStateException("无法打开源流: ${item.path}")

        // 复制后必须有内容，否则禁止进入删除阶段
        if (!safeTarget.exists() || safeTarget.length() <= 0L) {
            safeTarget.delete()
            throw IllegalStateException("复制结果为空: ${item.displayName}")
        }
        // 未加密时尽量核对大小（加密后体积会变）
        if (!encrypt && item.sizeBytes > 0 && safeTarget.length() < item.sizeBytes) {
            val got = safeTarget.length()
            safeTarget.delete()
            throw IllegalStateException(
                "复制不完整 ${item.displayName}: $got < ${item.sizeBytes}"
            )
        }

        safeTarget
    }

    /**
     * 批量复制 + 返回 File 列表。
     * 进度通过 onProgress 回调（done/total）。
     */
    suspend fun copyBatchToPrivate(
        items: List<MediaItem>,
        bucketId: Long,
        encrypt: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<Pair<MediaItem, File>> = withContext(Dispatchers.IO) {
        val out = ArrayList<Pair<MediaItem, File>>(items.size)
        items.forEachIndexed { idx, item ->
            val file = copyToPrivate(item, bucketId, encrypt)
            out += item to file
            onProgress(idx + 1, items.size)
        }
        out
    }

    /**
     * 复制单张媒体到 single/ 子目录，用 uuid 命名。
     * 加密时文件名带 .enc 后缀。
     *
     * @return Pair<真实 File, privateFileName（用于 Room 记录）>
     */
    suspend fun copySingleToPrivate(
        item: MediaItem,
        encrypt: Boolean = false
    ): Pair<File, String> = withContext(Dispatchers.IO) {
        val uuid = java.util.UUID.randomUUID().toString()
        val ext = item.displayName.substringAfterLast('.', "").ifEmpty {
            when {
                item.mimeType.endsWith("jpeg") || item.mimeType.endsWith("jpg") -> "jpg"
                item.mimeType.endsWith("png") -> "png"
                item.mimeType.endsWith("webp") -> "webp"
                item.mimeType.endsWith("mp4") -> "mp4"
                item.mimeType.endsWith("hevc") -> "hevc"
                else -> "bin"
            }
        }
        val base = layout.singleFile(uuid, ext)
        val target = if (encrypt) {
            File(base.parentFile, base.name + CryptoManager.ENC_SUFFIX)
        } else {
            base
        }
        val sourceUri = Uri.parse(item.path)

        resolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(target).use { output ->
                if (encrypt) crypto.encryptStream(input, output)
                else input.copyTo(output, bufferSize = 64 * 1024)
            }
        } ?: throw IllegalStateException("无法打开源流: ${item.path}")

        target to target.name
    }

    /**
     * 批量复制单张。
     */
    suspend fun copySingleBatch(
        items: List<MediaItem>,
        encrypt: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<Pair<MediaItem, Pair<File, String>>> = withContext(Dispatchers.IO) {
        val out = ArrayList<Pair<MediaItem, Pair<File, String>>>(items.size)
        items.forEachIndexed { idx, item ->
            val pair = copySingleToPrivate(item, encrypt)
            out += item to pair
            onProgress(idx + 1, items.size)
        }
        out
    }

    /**
     * 创建"删除 MediaStore 多文件"请求的 IntentSender。
     * 必须在 Activity / Fragment 里用 ActivityResultContracts.StartIntentSenderForResult 启动。
     */
    fun createDeleteRequest(uris: List<Uri>): IntentSender {
        return MediaStore.createDeleteRequest(resolver, uris).intentSender
    }

    /**
     * 还原：本 App 新建 MediaStore 行并写入内容（无需 createWriteRequest）。
     *
     * @return 成功写入的 content Uri；失败返回 null
     */
    suspend fun restoreToMediaStore(
        entry: WriteEntry,
        sourceFile: File,
        /** 用户选择的目标目录；null 则用 entry.relativePath */
        overrideRelativePath: String? = null
    ): Uri? = withContext(Dispatchers.IO) {
        if (!sourceFile.exists()) {
            Log.w(TAG, "source missing: ${sourceFile.absolutePath}")
            return@withContext null
        }

        val collection = collectionForMime(entry.mimeType)
        val relativePath = normalizeRelativePath(
            overrideRelativePath?.takeIf { it.isNotBlank() } ?: entry.relativePath
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (entry.takenAt > 0) {
                put(MediaStore.MediaColumns.DATE_TAKEN, entry.takenAt)
            }
        }

        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            Log.e(TAG, "insert failed for ${entry.displayName}", e)
            null
        } ?: return@withContext null

        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    if (sourceFile.name.endsWith(CryptoManager.ENC_SUFFIX)) {
                        crypto.decryptStream(input, out)
                    } else {
                        input.copyTo(out, bufferSize = 64 * 1024)
                    }
                }
            } ?: throw IllegalStateException("openOutputStream null for $uri")

            commitPending(uri)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "write/commit failed for ${entry.displayName}", e)
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            null
        }
    }

    /**
     * 批量还原。
     * @return 每条是否成功（与 pairs 同序）
     */
    suspend fun restoreBatch(
        pairs: List<Pair<WriteEntry, File>>,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<Boolean> = withContext(Dispatchers.IO) {
        pairs.mapIndexed { idx, (entry, file) ->
            val ok = restoreToMediaStore(entry, file) != null
            onProgress(idx + 1, pairs.size)
            ok
        }
    }

    /**
     * @deprecated 还原已改为 restoreToMediaStore，保留给旧调用兼容
     */
    suspend fun createPendingUris(entries: List<WriteEntry>): List<Uri?> =
        withContext(Dispatchers.IO) {
            entries.map { entry ->
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, entry.displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        normalizeRelativePath(entry.relativePath)
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    put(MediaStore.MediaColumns.DATE_TAKEN, entry.takenAt)
                }
                try {
                    resolver.insert(collectionForMime(entry.mimeType), values)
                } catch (e: Exception) {
                    Log.e(TAG, "createPendingUris failed", e)
                    null
                }
            }
        }

    fun createWriteRequest(uris: List<Uri>): IntentSender {
        return MediaStore.createWriteRequest(resolver, uris).intentSender
    }

    /**
     * 提交 IS_PENDING=0，让系统把文件"确认生效"。
     */
    suspend fun commitPending(uri: Uri) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, values, null, null)
    }

    private fun collectionForMime(mimeType: String): Uri {
        return when {
            mimeType.startsWith("video/") ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            mimeType.startsWith("image/") ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else ->
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
    }

    /** MediaStore 要求 RELATIVE_PATH 以 / 结尾 */
    private fun normalizeRelativePath(path: String?): String {
        val raw = path?.takeIf { it.isNotBlank() } ?: DEFAULT_RELATIVE_PATH
        return if (raw.endsWith("/")) raw else "$raw/"
    }

    private fun uniqueFile(target: File): File {
        if (!target.exists()) return target
        val parent = target.parentFile ?: return target
        val name = target.nameWithoutExtension
        val ext = target.extension.ifEmpty { "" }
        var idx = 1
        while (true) {
            val candidate = File(
                parent,
                if (ext.isEmpty()) "$name-$idx" else "$name-$idx.$ext"
            )
            if (!candidate.exists()) return candidate
            idx++
        }
    }

    data class WriteEntry(
        val displayName: String,
        val mimeType: String,
        val relativePath: String?,
        val takenAt: Long
    )

    private companion object {
        const val TAG = "FileMover"
        const val DEFAULT_RELATIVE_PATH = "Pictures/"
    }
}
