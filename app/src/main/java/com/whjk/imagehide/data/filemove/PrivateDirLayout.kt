package com.whjk.imagehide.data.filemove

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 私有目录布局规则。
 *
 * 路径：
 *   filesDir/
 *     hidden/
 *       {bucketId}/          # 一个被隐藏的相册 = 一个子目录
 *         {displayName}      # 原始文件名
 *         meta.json          # 原始相对路径 / mime / taken_at（用于还原）
 *
 * filesDir 不需要权限；其他 APP 拿不到。
 */
@Singleton
class PrivateDirLayout @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val root: File = File(context.filesDir, "hidden")
    private val singleDir: File = File(root, "single").apply { mkdirs() }

    fun albumDir(bucketId: Long): File =
        File(root, bucketId.toString()).apply { mkdirs() }

    fun mediaFile(bucketId: Long, displayName: String): File =
        File(albumDir(bucketId), displayName)

    fun metaFile(bucketId: Long): File =
        File(albumDir(bucketId), "meta.json")

    /** 单张隐藏的文件目录：filesDir/hidden/single/ */
    fun singleDir(): File = singleDir

    /** 单张文件路径（uuid 命名） */
    fun singleFile(uuid: String, ext: String): File {
        val name = if (ext.isEmpty()) uuid else "$uuid.$ext"
        return File(singleDir, name)
    }

    fun rootDir(): File = root

    fun deleteAlbumDir(bucketId: Long): Boolean =
        albumDir(bucketId).deleteRecursively()
}
