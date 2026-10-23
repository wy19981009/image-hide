package com.whjk.imagehide.data.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 权限检查。
 *
 * Android 13+ 三件套：
 * - READ_MEDIA_IMAGES
 * - READ_MEDIA_VIDEO
 * - READ_MEDIA_VISUAL_USER_SELECTED（Android 14+，部分授权也算过）
 *
 * 另：MANAGE_EXTERNAL_STORAGE（所有文件访问）用于隐藏时直接删系统文件、免多次确认。
 */
@Singleton
class PermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun hasMediaPermission(): Boolean {
        val images = isGranted(Manifest.permission.READ_MEDIA_IMAGES)
        val videos = isGranted(Manifest.permission.READ_MEDIA_VIDEO)
        // 任一允许就算过（适配部分授权）
        return images || videos
    }

    fun hasFullPermission(): Boolean {
        return isGranted(Manifest.permission.READ_MEDIA_IMAGES) &&
            isGranted(Manifest.permission.READ_MEDIA_VIDEO)
    }

    fun isPartialSelectionOnly(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        if (!isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) return false
        // 部分授权意味着 SELECTED 授予但 IMAGES/VIDEO 未授予
        return !isGranted(Manifest.permission.READ_MEDIA_IMAGES)
    }

    /** 是否已开启「所有文件访问」——隐藏时可跳过系统删除确认弹窗 */
    fun hasManageAllFiles(): Boolean =
        Environment.isExternalStorageManager()

    private fun isGranted(p: String): Boolean =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
}
