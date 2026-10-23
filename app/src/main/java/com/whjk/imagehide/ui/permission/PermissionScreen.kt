package com.whjk.imagehide.ui.permission

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 权限引导页。
 *
 * 关键点：
 * - 申请 READ_MEDIA_IMAGES + READ_MEDIA_VIDEO
 * - Android 14+ 同时申请 READ_MEDIA_VISUAL_USER_SELECTED（部分授权）
 * - 拒绝时给出"为什么需要"说明 + "去设置"入口
 * - 用户同意后调 onGranted() 跳相册列表
 */
@Composable
fun PermissionScreen(
    onGranted: () -> Unit,
    viewModel: PermissionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 系统授权回调
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it }
        viewModel.onResult(granted)
        if (granted) onGranted()
    }

    // 首次进入时若已授权，直接跳过
    LaunchedEffect(Unit) {
        if (viewModel.isAlreadyGranted()) onGranted()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("需要访问你的相册", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            "用来扫描所有相册、隐藏/还原。\n隐藏后系统相册和其他 APP 都看不到。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.needRationale) {
            Spacer(Modifier.height(16.dp))
            Text(
                "未授权：仅显示授权的媒体。\n可以在 设置 → 应用 → 隐藏相册 → 权限 里随时改。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                if (viewModel.isAlreadyGranted()) {
                    onGranted()
                } else {
                    launcher.launch(viewModel.requiredPermissions())
                }
            }
        ) {
            Text(if (state.granted) "进入" else "授权并继续")
        }
        if (state.needRationale) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                val pkg = context.packageName
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", pkg, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }) {
                Text("去系统设置")
            }
        }
    }
}
