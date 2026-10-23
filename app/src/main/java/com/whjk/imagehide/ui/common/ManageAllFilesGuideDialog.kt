package com.whjk.imagehide.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 隐藏前引导开启「所有文件访问」，避免反复点系统删除确认。
 */
@Composable
fun ManageAllFilesGuideDialog(
    itemCount: Int,
    onOpenSettings: () -> Unit,
    onContinueWithBatch: () -> Unit,
    onDismiss: () -> Unit
) {
    val batches = (itemCount + 99) / 100
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("建议开启「所有文件访问」") },
        text = {
            Text(
                "开启后隐藏时可直接删除，无需反复点「允许」。\n" +
                    "当前约 $itemCount 项，不开则大约需确认 $batches 次。"
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text("去开启") }
        },
        dismissButton = {
            TextButton(onClick = onContinueWithBatch) { Text("仍用批量确认") }
        }
    )
}
