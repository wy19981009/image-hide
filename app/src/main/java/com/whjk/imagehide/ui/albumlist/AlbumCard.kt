package com.whjk.imagehide.ui.albumlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.whjk.imagehide.data.thumb.MediaThumb
import com.whjk.imagehide.domain.model.Album

/**
 * 单个相册卡片。
 *
 * - 封面：Coil 加载 coverPath（content://）
 * - 名称 + 媒体数 + 隐藏/显示按钮
 * - 长按进入多选（隐藏相册 Tab 用）；[onLongClick] 为 null 时交给外层滑动多选手势
 * - 多选模式下显示 √ 角标
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCard(
    album: Album,
    isHidden: Boolean,
    multiSelect: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onToggleHidden: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val clickMod = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .then(clickMod)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面：系统缩略图（MediaThumb），避免抽视频帧卡顿
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2C2C2C)),
                contentAlignment = Alignment.Center
            ) {
                if (album.coverPath != null) {
                    val context = LocalContext.current
                    val coverModel = remember(album.coverPath) {
                        val thumb = MediaThumb.fromPath(album.coverPath!!)
                        ImageRequest.Builder(context)
                            .data(thumb)
                            .size(112)
                            .memoryCacheKey("${thumb.cacheKey}:112")
                            .diskCacheKey("${thumb.cacheKey}:112")
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = coverModel,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                // 多选 √ 角标
                if (multiSelect) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(20.dp),
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primary
                                else Color.Black.copy(alpha = 0.4f)
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "已选",
                                tint = Color.White,
                                modifier = Modifier.padding(2.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(album.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${album.count} 项 · ${if (isHidden) "已隐藏" else "可见"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!multiSelect) {
                IconButton(onClick = onToggleHidden) {
                    Icon(
                        imageVector = if (isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (isHidden) "显示" else "隐藏"
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumListStateView(
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> CircularProgressIndicator()
            error != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("加载失败：$error", color = MaterialTheme.colorScheme.error)
                androidx.compose.material3.TextButton(onClick = onRetry) {
                    Text("重试")
                }
            }
            else -> Text("没有可显示的相册")
        }
    }
}
