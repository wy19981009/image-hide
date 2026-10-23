package com.whjk.imagehide.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 还原目标目录；relativePath 为 null 表示沿用每张图原始路径 */
data class RestoreFolderOption(
    val label: String,
    val relativePath: String?
)

val DefaultRestoreFolderOptions: List<RestoreFolderOption> = listOf(
    RestoreFolderOption("原路径（每张各自原来的位置）", null),
    RestoreFolderOption("相机 DCIM/Camera", "DCIM/Camera/"),
    RestoreFolderOption("图片 Pictures", "Pictures/"),
    RestoreFolderOption("下载 Download", "Download/"),
    RestoreFolderOption("截图 Pictures/Screenshots", "Pictures/Screenshots/")
)

@Composable
fun RestoreFolderDialog(
    options: List<RestoreFolderOption> = DefaultRestoreFolderOptions,
    onDismiss: () -> Unit,
    onConfirm: (RestoreFolderOption) -> Unit
) {
    var selected by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择还原到哪个文件夹") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = index }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == index,
                            onClick = { selected = index }
                        )
                        Text(option.label, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(options[selected]) }) {
                Text("还原")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
