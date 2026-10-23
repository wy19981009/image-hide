package com.whjk.imagehide.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

/**
 * 通知构建器 + ForegroundInfo 工厂。
 *
 * 关键点：
 * - Android 8.0+ 必须有 NotificationChannel
 * - setOnlyAlertOnce(true) 避免进度更新时反复响
 * - setOngoing(true) 防止被用户滑掉
 * - ★ Android 14 (API 34) 起 targetSdk>=34 强制要求 ForegroundInfo 带 service type，
 *   否则 setForeground() 抛 IllegalStateException。这里统一走 foregroundInfo()。
 */
object ProgressNotifier {
    const val CHANNEL_ID = "image_hide_progress"
    private const val CHANNEL_NAME = "隐藏相册进度"

    fun build(context: Context, text: String, progress: Int): android.app.Notification {
        // Android 8.0+ 没有 channel 的通知根本发不出去（startForeground 会被系统判定为异常通知）
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("隐藏相册")
            .setContentText(text)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    /**
     * 构造带 service type 的 ForegroundInfo（Android 14 必须）。
     * 全部 Worker 都应该用这个，不要直接 new ForegroundInfo(id, notification)。
     */
    fun foregroundInfo(notificationId: Int, context: Context, text: String, pct: Int): ForegroundInfo {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return ForegroundInfo(notificationId, build(context, text, pct), type)
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "隐藏/还原相册时的进度通知"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }
}