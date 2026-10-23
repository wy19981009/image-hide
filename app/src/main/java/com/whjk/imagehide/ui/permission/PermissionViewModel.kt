package com.whjk.imagehide.ui.permission

import android.Manifest
import android.os.Build
import androidx.lifecycle.ViewModel
import com.whjk.imagehide.data.permission.PermissionChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class PermissionState(
    val granted: Boolean = false,
    val needRationale: Boolean = false
)

@HiltViewModel
class PermissionViewModel @Inject constructor(
    private val checker: PermissionChecker
) : ViewModel() {

    private val _state = MutableStateFlow(PermissionState(granted = checker.hasMediaPermission()))
    val state: StateFlow<PermissionState> = _state.asStateFlow()

    fun isAlreadyGranted(): Boolean = checker.hasMediaPermission()

    /**
     * 申请列表：Android 13+ 图片 + 视频；Android 14+ 多一项部分授权
     * READ_MEDIA_VISUAL_USER_SELECTED 在 Android 14 之前不存在，必须用 Build.VERSION 保护
     */
    fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
        // 前台任务进度通知；拒绝也不阻塞隐藏（Worker 已降级）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun onResult(granted: Boolean) {
        _state.update { it.copy(granted = granted, needRationale = !granted) }
    }
}
