package com.whjk.imagehide.ui.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whjk.imagehide.data.crypto.CryptoEntryPoint
import com.whjk.imagehide.data.crypto.CryptoManager
import com.whjk.imagehide.domain.model.MediaItem
import com.whjk.imagehide.ui.detail.MediaSort
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 沉浸式全屏看图 / 看视频。
 *
 * 视频播放器故意放在 HorizontalPager 外面：
 * Pager 内嵌播放器时，拖进度条的横向手势会带动翻页/重组，MediaPlayer 被释放后从 0 重播。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    albumId: Long,
    startId: Long,
    hidden: Boolean,
    sortName: String = MediaSort.TIME_DESC.name,
    onBack: () -> Unit,
    viewModel: MediaViewerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sort = remember(sortName) {
        runCatching { MediaSort.valueOf(sortName) }.getOrDefault(MediaSort.TIME_DESC)
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(albumId, hidden, sort) {
        viewModel.load(albumId, hidden, sort)
    }

    val pageCount = state.media.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(initialPage = 0) { pageCount }

    var jumped by remember(albumId, startId, hidden, sort) { mutableStateOf(false) }
    LaunchedEffect(state.media.size, startId) {
        if (!jumped && state.media.isNotEmpty()) {
            val target = state.media.indexOfFirst { it.id == startId }
                .takeIf { it >= 0 }
                ?: 0
            pagerState.scrollToPage(target.coerceIn(0, state.media.lastIndex))
            jumped = true
        }
    }

    var videoLandscape by remember { mutableStateOf(false) }
    var videoControlsVisible by remember { mutableStateOf(true) }

    val currentItem = state.media.getOrNull(pagerState.currentPage)
    val currentIsVideo = currentItem?.mediaType == MediaItem.Type.VIDEO
    // 视频 + 控制栏可见：禁止翻页，进度条独占横向手势
    val pagerScrollEnabled =
        !videoLandscape && !(currentIsVideo && videoControlsVisible)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (state.loading || state.media.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (state.loading) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    Text(state.error ?: "没有媒体", color = Color.White)
                }
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                userScrollEnabled = pagerScrollEnabled,
                key = { page -> state.media[page].id }
            ) { page ->
                val item = state.media[page]
                when (item.mediaType) {
                    MediaItem.Type.IMAGE -> PhotoPage(model = mediaModel(item))
                    // 视频页只占位；真正的播放器在 Pager 外层，避免被翻页销毁
                    MediaItem.Type.VIDEO -> Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    )
                }
            }

            // 当前页是视频时，在 Pager 之上挂唯一播放器
            if (currentItem?.mediaType == MediaItem.Type.VIDEO) {
                val context = LocalContext.current
                val path = currentItem.path
                val uri by produceState<Uri?>(initialValue = null, path) {
                    value = withContext(Dispatchers.IO) {
                        resolvePlayableUri(path, context.cacheDir, context.applicationContext)
                    }
                }
                key(currentItem.id) {
                    if (uri != null) {
                        VideoPlayer(
                            uri = uri!!,
                            durationHintMs = currentItem.durationMs,
                            isActive = true,
                            onControlsVisibleChanged = { videoControlsVisible = it },
                            onLandscapeChanged = { videoLandscape = it }
                        )
                    } else {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            } else {
                // 离开视频页时复位，避免下一张视频误用旧控制栏状态锁翻页
                LaunchedEffect(currentItem?.id) {
                    videoControlsVisible = true
                    videoLandscape = false
                }
            }
        }

        if (videoLandscape) {
            // 横屏隐藏顶栏，左上角保留返回
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(4.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
        } else {
            TopAppBar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding(),
                title = {
                    Text(
                        text = if (state.media.isEmpty()) "" else
                            "${pagerState.currentPage + 1} / ${state.media.size}",
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.4f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                ),
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    }
}

private fun mediaModel(item: MediaItem): Any {
    val path = item.path
    return when {
        path.startsWith("content://") || path.startsWith("file://") -> Uri.parse(path)
        else -> File(path)
    }
}

/** 视频播放：.enc 在 IO 线程按需解密，不在进相册时整批解密。 */
private fun resolvePlayableUri(
    path: String,
    cacheDir: File,
    appContext: android.content.Context
): Uri {
    when {
        path.startsWith("content://") || path.startsWith("file://") -> return Uri.parse(path)
    }
    val file = File(path)
    if (file.name.endsWith(CryptoManager.ENC_SUFFIX)) {
        val opener = EntryPointAccessors.fromApplication(
            appContext,
            CryptoEntryPoint::class.java
        ).encryptedFileOpener()
        return Uri.fromFile(opener.openForView(file, cacheDir))
    }
    return Uri.fromFile(file)
}
