package com.whjk.imagehide.ui.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
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
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 沉浸式全屏看图 / 看视频。
 * 媒体铺满整个屏幕；顶栏浮在上层，不挤压内容高度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    albumId: Long,
    startIndex: Int,
    hidden: Boolean,
    onBack: () -> Unit,
    viewModel: MediaViewerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    LaunchedEffect(albumId, hidden) {
        viewModel.load(albumId, hidden)
    }

    val pageCount = state.media.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(initialPage = 0) { pageCount }

    var jumped by remember(albumId, startIndex, hidden) { mutableStateOf(false) }
    LaunchedEffect(state.media.size, startIndex) {
        if (!jumped && state.media.isNotEmpty()) {
            val target = startIndex.coerceIn(0, state.media.lastIndex)
            pagerState.scrollToPage(target)
            jumped = true
        }
    }

    var videoScrubbing by remember { mutableStateOf(false) }
    var videoLandscape by remember { mutableStateOf(false) }

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
                // 拖进度/手势或横屏时禁用左右翻页
                userScrollEnabled = !videoScrubbing && !videoLandscape,
                key = { page -> state.media[page].id }
            ) { page ->
                val item = state.media[page]
                when (item.mediaType) {
                    MediaItem.Type.IMAGE -> PhotoPage(model = mediaModel(item))
                    MediaItem.Type.VIDEO -> {
                        val context = LocalContext.current
                        val uri by produceState<Uri?>(initialValue = null, item.path) {
                            value = withContext(Dispatchers.IO) {
                                resolvePlayableUri(item.path, context.cacheDir, context.applicationContext)
                            }
                        }
                        if (uri != null) {
                            VideoPlayer(
                                uri = uri!!,
                                onScrubbingChanged = { videoScrubbing = it },
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
                }
            }
        }

        if (!videoLandscape) {
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
