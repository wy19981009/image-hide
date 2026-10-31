package com.whjk.imagehide.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.shuyu.gsyvideoplayer.builder.GSYVideoOptionBuilder
import com.shuyu.gsyvideoplayer.listener.GSYSampleCallBack
import com.shuyu.gsyvideoplayer.model.VideoOptionModel
import com.shuyu.gsyvideoplayer.player.IjkPlayerManager
import com.shuyu.gsyvideoplayer.player.PlayerFactory
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.danmaku.ijk.media.player.IjkMediaPlayer

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * 与主流视频 App 相同路径：完整 GSYVideoPlayer（IJK 内核），
 * 由 GSY 管理 TextureView / MediaCodec / 进度条；倍速与横屏用 Compose 补齐。
 */
@Composable
fun VideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    durationHintMs: Long = 0L,
    isActive: Boolean = true,
    onControlsVisibleChanged: (Boolean) -> Unit = {},
    onLandscapeChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val isLandscape =
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val lifecycleOwner = LocalLifecycleOwner.current
    val uriKey = remember(uri) { uri.toString() }

    var errorMsg by remember(uriKey) { mutableStateOf<String?>(null) }
    var preparing by remember(uriKey) { mutableStateOf(true) }
    var playUrl by remember(uriKey) { mutableStateOf<String?>(null) }
    var controlsVisible by remember(uriKey) { mutableStateOf(true) }
    var forceLandscape by remember(uriKey) { mutableStateOf(false) }
    var speed by remember(uriKey) { mutableFloatStateOf(1.0f) }
    var speedMenuOpen by remember(uriKey) { mutableStateOf(false) }
    var playerRef by remember(uriKey) { mutableStateOf<GalleryGsyPlayer?>(null) }

    val controlsVisibleCallback by rememberUpdatedState(onControlsVisibleChanged)
    val landscapeCallback by rememberUpdatedState(onLandscapeChanged)
    val isActiveState by rememberUpdatedState(isActive)

    fun setControlsVisible(value: Boolean) {
        if (controlsVisible == value) return
        controlsVisible = value
        if (!value) speedMenuOpen = false
        if (isActive) controlsVisibleCallback(value)
    }

    fun applySpeed(value: Float) {
        speed = value
        speedMenuOpen = false
        runCatching { playerRef?.setSpeedPlaying(value, true) }
            .onFailure { Log.e(TAG, "setSpeed failed", it) }
    }

    LaunchedEffect(uriKey, isActive) {
        if (isActive) controlsVisibleCallback(controlsVisible)
    }
    LaunchedEffect(forceLandscape, isLandscape, isActive) {
        if (isActive) landscapeCallback(forceLandscape || isLandscape)
    }

    DisposableEffect(forceLandscape) {
        val act = activity
        if (act != null) {
            act.requestedOrientation = if (forceLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        onDispose {
            if (forceLandscape) {
                act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    LaunchedEffect(uriKey) {
        ensureGsyIjkConfigured()
        preparing = true
        errorMsg = null
        playUrl = null
        val url = withContext(Dispatchers.IO) {
            runCatching {
                val file = resolveLocalSourceFile(context, uri)
                Log.i(
                    TAG,
                    "playFile=${file.absolutePath} len=${file.length()} durHint=$durationHintMs"
                )
                file.absolutePath
            }.onFailure {
                Log.e(TAG, "resolve failed", it)
            }.getOrNull()
        }
        playUrl = url
        preparing = false
        if (url == null) errorMsg = "无法打开视频文件"
    }

    DisposableEffect(lifecycleOwner, uriKey) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> GSYVideoManager.onPause()
                Lifecycle.Event.ON_RESUME -> {
                    if (isActiveState) GSYVideoManager.onResume()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            GSYVideoManager.releaseAllVideos()
            playerRef = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val url = playUrl
        if (url != null && errorMsg == null) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        val player = GalleryGsyPlayer(ctx).also { p ->
                            p.onUiVisible = { visible -> setControlsVisible(visible) }
                            p.backButton?.visibility = View.GONE
                            p.titleTextView?.visibility = View.GONE
                            p.fullscreenButton?.visibility = View.GONE
                        }
                        addView(
                            player,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        playerRef = player
                        bindGsyPlayer(player, url, speed) { err ->
                            errorMsg = err
                        }
                    }
                },
                update = { root ->
                    val player = root.getChildAt(0) as? GalleryGsyPlayer ?: return@AndroidView
                    player.onUiVisible = { visible -> setControlsVisible(visible) }
                    playerRef = player
                    if (player.currentUrlOrEmpty() != url) {
                        bindGsyPlayer(player, url, speed) { err ->
                            errorMsg = err
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (preparing && errorMsg == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        errorMsg?.let { msg ->
            Text(
                text = "播放失败\n$msg",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )
        }

        // 放在底部，避开外层 TopAppBar 遮挡；与 GSY 控制条同步显隐
        if (controlsVisible && errorMsg == null && !preparing) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 8.dp, bottom = 56.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    TextButton(
                        onClick = { speedMenuOpen = true },
                        modifier = Modifier.background(
                            Color.Black.copy(alpha = 0.45f),
                            RoundedCornerShape(8.dp)
                        )
                    ) {
                        Text(
                            text = formatSpeed(speed),
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                    DropdownMenu(
                        expanded = speedMenuOpen,
                        onDismissRequest = { speedMenuOpen = false }
                    ) {
                        SPEED_OPTIONS.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(formatSpeed(option)) },
                                onClick = { applySpeed(option) }
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { forceLandscape = !forceLandscape },
                    modifier = Modifier.background(
                        Color.Black.copy(alpha = 0.45f),
                        RoundedCornerShape(8.dp)
                    )
                ) {
                    Icon(
                        imageVector = if (forceLandscape || isLandscape) {
                            Icons.Default.FullscreenExit
                        } else {
                            Icons.Default.Fullscreen
                        },
                        contentDescription = if (forceLandscape || isLandscape) {
                            "退出横屏"
                        } else {
                            "横屏"
                        },
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun VideoPlayer(
    file: File,
    modifier: Modifier = Modifier,
    durationHintMs: Long = 0L,
    isActive: Boolean = true,
    onControlsVisibleChanged: (Boolean) -> Unit = {},
    onLandscapeChanged: (Boolean) -> Unit = {}
) {
    VideoPlayer(
        uri = Uri.fromFile(file),
        modifier = modifier,
        durationHintMs = durationHintMs,
        isActive = isActive,
        onControlsVisibleChanged = onControlsVisibleChanged,
        onLandscapeChanged = onLandscapeChanged
    )
}

/** 把控件显隐回调出来，供外层锁住 HorizontalPager 手势 */
private class GalleryGsyPlayer(context: Context) : StandardGSYVideoPlayer(context) {
    var onUiVisible: ((Boolean) -> Unit)? = null
    private var boundUrl: String? = null

    fun currentUrlOrEmpty(): String = boundUrl.orEmpty()

    fun markBoundUrl(url: String) {
        boundUrl = url
    }

    override fun hideAllWidget() {
        super.hideAllWidget()
        onUiVisible?.invoke(false)
    }

    override fun changeUiToPlayingShow() {
        super.changeUiToPlayingShow()
        onUiVisible?.invoke(true)
    }

    override fun changeUiToPauseShow() {
        super.changeUiToPauseShow()
        onUiVisible?.invoke(true)
    }

    override fun changeUiToPreparingShow() {
        super.changeUiToPreparingShow()
        onUiVisible?.invoke(true)
    }

    override fun changeUiToCompleteShow() {
        super.changeUiToCompleteShow()
        onUiVisible?.invoke(true)
    }

    override fun changeUiToError() {
        super.changeUiToError()
        onUiVisible?.invoke(true)
    }
}

private fun bindGsyPlayer(
    player: GalleryGsyPlayer,
    url: String,
    speed: Float,
    onError: (String) -> Unit
) {
    player.markBoundUrl(url)
    // 不要把 durationHint 当成 seekOnStart，否则进度条会直接跳到片尾
    GSYVideoOptionBuilder()
        .setUrl(url)
        .setCacheWithPlay(false)
        .setIsTouchWiget(true)
        .setRotateViewAuto(false)
        .setLockLand(false)
        .setAutoFullWithSize(false)
        .setShowFullAnimation(false)
        .setNeedLockFull(false)
        .setSeekOnStart(0)
        .setSpeed(speed)
        .setVideoAllCallBack(object : GSYSampleCallBack() {
            override fun onPlayError(url: String?, vararg objects: Any?) {
                Log.e(TAG, "gsy play error url=$url")
                onError("解码失败")
            }

            override fun onPrepared(url: String?, vararg objects: Any?) {
                Log.i(TAG, "gsy prepared url=$url")
                runCatching { player.setSpeedPlaying(speed, true) }
            }
        })
        .build(player)
    player.startPlayLogic()
}

private val gsyConfigured = AtomicBoolean(false)

/**
 * 与 GSY Demo / 多数播放器一致的 IJK 配置：
 * TextureView + MediaCodec（含 Texture 通路）+ 精确 seek。
 */
private fun ensureGsyIjkConfigured() {
    if (!gsyConfigured.compareAndSet(false, true)) return
    PlayerFactory.setPlayManager(IjkPlayerManager::class.java)
    GSYVideoType.setRenderType(GSYVideoType.TEXTURE)
    GSYVideoType.enableMediaCodec()
    GSYVideoType.enableMediaCodecTexture()
    GSYVideoType.setShowType(GSYVideoType.SCREEN_TYPE_DEFAULT)

    val options = ArrayList<VideoOptionModel>()
    options.add(
        VideoOptionModel(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1)
    )
    options.add(
        VideoOptionModel(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1)
    )
    options.add(
        VideoOptionModel(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1)
    )
    options.add(
        VideoOptionModel(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1)
    )
    GSYVideoManager.instance().setOptionModelList(options)
    Log.i(TAG, "GSY IJK configured: texture + mediacodec + accurate-seek")
}

private fun formatSpeed(speed: Float): String = when (speed) {
    0.5f -> "0.5x"
    0.75f -> "0.75x"
    1.0f -> "1.0x"
    1.25f -> "1.25x"
    1.5f -> "1.5x"
    2.0f -> "2.0x"
    else -> String.format(Locale.US, "%.2fx", speed)
}

private fun resolveLocalSourceFile(context: Context, uri: Uri): File {
    val scheme = uri.scheme?.lowercase(Locale.US)
    when (scheme) {
        "file" -> {
            val path = uri.path ?: throw IllegalArgumentException("empty path")
            val f = File(path)
            if (!f.exists()) throw IllegalStateException("missing $path")
            return f
        }
        "content" -> {
            val tmp = File(context.cacheDir, "src_${uri.lastPathSegment ?: "video"}.bin")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("cannot open $uri")
            return tmp
        }
        null -> {
            val f = File(uri.toString())
            if (f.exists()) return f
        }
    }
    throw IllegalArgumentException("unsupported uri $uri")
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private const val TAG = "VideoPlayer"
