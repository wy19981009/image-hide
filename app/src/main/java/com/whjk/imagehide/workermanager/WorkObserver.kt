package com.whjk.imagehide.workermanager

import androidx.lifecycle.LiveData
import androidx.work.Data
import androidx.work.WorkInfo
import com.whjk.imagehide.data.repository.HiddenRepositoryImpl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一的 Worker 进度监听器。
 *
 * SUCCEEDED 时优先读 [WorkInfo.outputData]（AWAIT_SAF 载荷放这里，progress 会被清空）。
 */
@Singleton
class WorkObserver @Inject constructor(
    private val hiddenRepoImpl: HiddenRepositoryImpl
) {
    private val registry = mutableMapOf<String, Registered>()

    private data class Registered(
        val liveData: LiveData<WorkInfo>,
        val observer: androidx.lifecycle.Observer<WorkInfo>
    )

    /**
     * @param onProgress 收到 setProgress / 终态时的回调 (pct, stage, data)
     * @param onFailed Worker 失败
     * @param onSucceeded Worker 成功；[data] 为 outputData（含 AWAIT_SAF）
     */
    fun observe(
        workId: String,
        onProgress: (pct: Int, stage: String, data: Data) -> Unit = { _, _, _ -> },
        onFailed: (msg: String?) -> Unit = {},
        onSucceeded: (data: Data) -> Unit = {}
    ) {
        unregister(workId)

        val live = hiddenRepoImpl.observeWork(workId)
        val obs = androidx.lifecycle.Observer<WorkInfo> { info ->
            if (info == null) return@Observer

            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val data = info.outputData
                    val stage = data.getString("stage") ?: ""
                    val pct = data.getInt("progress", 100)
                    onProgress(pct, stage, data)
                    onSucceeded(data)
                }
                WorkInfo.State.FAILED -> {
                    onFailed(info.outputData.getString("error"))
                }
                WorkInfo.State.CANCELLED -> {
                    onFailed("cancelled")
                }
                else -> {
                    val data = info.progress
                    val pct = data.getInt("progress", 0)
                    val stage = data.getString("stage") ?: ""
                    onProgress(pct, stage, data)
                }
            }
        }
        live.observeForever(obs)
        registry[workId] = Registered(live, obs)
    }

    fun unregister(workId: String) {
        registry.remove(workId)?.let { r ->
            r.liveData.removeObserver(r.observer)
        }
    }

    fun peekInfo(workId: String): WorkInfo? {
        val live = registry[workId]?.liveData ?: return null
        return live.value
    }

    fun unregisterAll() {
        registry.values.forEach { r ->
            r.liveData.removeObserver(r.observer)
        }
        registry.clear()
    }
}
