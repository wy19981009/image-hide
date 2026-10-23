package com.whjk.imagehide.ui.detail

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.whjk.imagehide.data.thumb.MediaThumb
import com.whjk.imagehide.domain.model.MediaItem
import com.whjk.imagehide.ui.common.ManageAllFilesGuideDialog
import com.whjk.imagehide.ui.common.RestoreFolderDialog
import com.whjk.imagehide.ui.common.WorkProgressDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    bucketId: Long,
    albumName: String,
    hidden: Boolean,
    onMediaClick: (startIndex: Int) -> Unit,
    onBack: () -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var sortMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(bucketId, albumName, hidden) {
        viewModel.load(bucketId, albumName, hidden)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onReturnFromAllFilesSettings()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onHideSafConfirmed()
        } else {
            viewModel.onHideSafCancelled()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.safRequest.collect { sender ->
            safLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    LaunchedEffect(state.error) {
        val msg = state.error ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.consumeError()
    }

    if (state.showRestoreFolderPicker) {
        RestoreFolderDialog(
            onDismiss = { viewModel.dismissRestoreFolderPicker() },
            onConfirm = { viewModel.confirmRestoreFolder(it) }
        )
    }

    if (state.showManageAllFilesGuide) {
        ManageAllFilesGuideDialog(
            itemCount = state.pendingHideItems.size,
            onOpenSettings = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            },
            onContinueWithBatch = { viewModel.continueHideWithoutAllFiles() },
            onDismiss = { viewModel.dismissManageAllFilesGuide() }
        )
    }

    if (state.working) {
        val title = when (state.workKind) {
            WorkKind.HIDE -> "正在隐藏"
            WorkKind.RESTORE -> "正在还原"
            else -> "请稍候"
        }
        WorkProgressDialog(
            title = title,
            statusText = state.statusText,
            progress = state.progress,
            onCancel = { viewModel.cancelWorking() }
        )
    }

    Scaffold(
        topBar = {
            if (state.multiSelect) {
                TopAppBar(
                    title = { Text("已选 ${state.selected.size}") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.exitMultiSelect() }, enabled = !state.working) {
                            Icon(Icons.Default.Close, contentDescription = "取消")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll() }, enabled = !state.working) {
                            Icon(Icons.Default.DoneAll, contentDescription = "全选")
                        }
                        if (hidden) {
                            IconButton(
                                onClick = { viewModel.restoreSelected() },
                                enabled = state.selected.isNotEmpty() && !state.working
                            ) {
                                Icon(Icons.Default.Visibility, contentDescription = "取消隐藏")
                            }
                        } else {
                            IconButton(
                                onClick = { viewModel.hideSelected() },
                                enabled = state.selected.isNotEmpty() && !state.working
                            ) {
                                Icon(Icons.Default.VisibilityOff, contentDescription = "隐藏选中")
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(albumName.ifBlank { "相册" }) },
                    navigationIcon = {
                        IconButton(onClick = onBack, enabled = !state.working) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        Box {
                            IconButton(
                                onClick = { sortMenuOpen = true },
                                enabled = !state.working
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                            }
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("时间：从近到远") },
                                    onClick = {
                                        viewModel.setSort(MediaSort.TIME_DESC)
                                        sortMenuOpen = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("时间：从远到近") },
                                    onClick = {
                                        viewModel.setSort(MediaSort.TIME_ASC)
                                        sortMenuOpen = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("大小：从大到小") },
                                    onClick = {
                                        viewModel.setSort(MediaSort.SIZE_DESC)
                                        sortMenuOpen = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("大小：从小到大") },
                                    onClick = {
                                        viewModel.setSort(MediaSort.SIZE_ASC)
                                        sortMenuOpen = false
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.loading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            "加载中…",
                            modifier = Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                state.media.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "没有媒体",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(1.dp),
                    userScrollEnabled = !state.working
                ) {
                    itemsIndexed(
                        items = state.media,
                        key = { _, item -> item.id },
                        contentType = { _, item ->
                            if (item.mediaType == MediaItem.Type.VIDEO) "v" else "i"
                        }
                    ) { index, item ->
                        val selected = state.selected.contains(item.id)
                        MediaThumbnail(
                            item = item,
                            selected = selected,
                            multiSelect = state.multiSelect,
                            onClick = {
                                if (state.working) return@MediaThumbnail
                                if (state.multiSelect) {
                                    viewModel.onMediaTap(item)
                                } else {
                                    onMediaClick(index)
                                }
                            },
                            onLongClick = {
                                if (state.working) return@MediaThumbnail
                                if (!state.multiSelect) {
                                    viewModel.onMediaLongPress(item)
                                } else {
                                    viewModel.onMediaTap(item)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaThumbnail(
    item: MediaItem,
    selected: Boolean = false,
    multiSelect: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isVideo = item.mediaType == MediaItem.Type.VIDEO
    val thumbPx = remember(density) {
        with(density) { 120.dp.roundToPx() }.coerceIn(96, 360)
    }
    val model = remember(item.id, item.path, item.mediaType, thumbPx) {
        val thumb = MediaThumb.from(item)
        ImageRequest.Builder(context)
            .data(thumb)
            .size(thumbPx)
            .precision(Precision.INEXACT)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(true)
            .memoryCacheKey("${thumb.cacheKey}:$thumbPx")
            .diskCacheKey("${thumb.cacheKey}:$thumbPx")
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(1.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(3.dp)),
            contentScale = ContentScale.Crop
        )

        if (selected && multiSelect) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        }

        if (isVideo) {
            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = "视频",
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
            )
        }

        if (multiSelect) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp),
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f)
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
}
