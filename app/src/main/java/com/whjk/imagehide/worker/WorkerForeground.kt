package com.whjk.imagehide.worker

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo

/**
 * 安全进入前台服务。
 *
 * 小米 / 通知被关 / FGS 受限时，setForeground 会抛异常并导致整次 Worker FAILURE。
 * 隐藏逻辑本身不依赖通知，失败时降级为后台继续跑。
 */
suspend fun CoroutineWorker.trySetForeground(info: ForegroundInfo, tag: String = "WorkerFG") {
    try {
        setForeground(info)
    } catch (t: Throwable) {
        Log.w(tag, "setForeground skipped: ${t.message}")
    }
}

fun CoroutineWorker.trySetForegroundAsync(info: ForegroundInfo, tag: String = "WorkerFG") {
    try {
        setForegroundAsync(info)
    } catch (t: Throwable) {
        Log.w(tag, "setForegroundAsync skipped: ${t.message}")
    }
}
