package com.whjk.imagehide.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.whjk.imagehide.R
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private enum class DragMode { SEEK, VOLUME, BRIGHTNESS }

/**
 * 视频播放器：进度/倍速/快进快退、横屏、
 * 屏幕手势（横滑快进快退、左侧纵滑亮度、右侧纵滑音量）。
 */
@Composable
fun VideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    onScrubbingChanged: (Boolean) -> Unit = {},
    onLandscapeChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    var errorMsg by remember(uri) { mutableStateOf<String?>(null) }
    var isBuffering by remember(uri) { mutableStateOf(true) }
    var isPlaying by remember(uri) { mutableStateOf(false) }
    var positionMs by remember(uri) { mutableLongStateOf(0L) }
    var durationMs by remember(uri) { mutableLongStateOf(0L) }
    var scrubbing by remember(uri) { mutableStateOf(false) }
    var scrubPos by remember(uri) { mutableFloatStateOf(0f) }
    var speed by remember(uri) { mutableFloatStateOf(1.0f) }
    var speedMenuOpen by remember(uri) { mutableStateOf(false) }
    var controlsVisible by remember(uri) { mutableStateOf(true) }
    var forceLandscape by remember(uri) { mutableStateOf(false) }

    var gestureHint by remember(uri) { mutableStateOf<String?>(null) }
    var gestureProgress by remember(uri) { mutableFloatStateOf(-1f) }

    fun setScrubbing(value: Boolean) {
        if (scrubbing == value) return
        scrubbing = value
        onScrubbingChanged(value)
    }

    LaunchedEffect(forceLandscape, isLandscape) {
        onLandscapeChanged(forceLandscape || isLandscape)
    }

    // 横屏锁定 / 离开恢复
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
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            onLandscapeChanged(false)
        }
    }

    val player = remember(uri) {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setMediaItem(Media3MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "playback error: ${error.errorCodeName}", error)
                errorMsg = error.errorCodeName
                isBuffering = false
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING ||
                    playbackState == Player.STATE_IDLE
                val d = player.duration
                if (d != C.TIME_UNSET && d > 0) durationMs = d
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                speed = playbackParameters.speed
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, scrubbing) {
        while (true) {
            if (!scrubbing) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                val d = player.duration
                if (d != C.TIME_UNSET && d > 0) durationMs = d
            }
            delay(200)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, scrubbing, speedMenuOpen, gestureHint) {
        if (controlsVisible && isPlaying && !scrubbing && !speedMenuOpen && gestureHint == null) {
            delay(3500)
            controlsVisible = false
        }
    }

    val touchSlopPx = with(density) { 24.dp.toPx() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(uri, durationMs, maxVolume) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    val startY = down.position.y
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val height = size.height.toFloat().coerceAtLeast(1f)
                    val leftHalf = startX < width / 2f

                    var mode: DragMode? = null
                    var totalDx = 0f
                    var totalDy = 0f
                    val seekStart = player.currentPosition.coerceAtLeast(0L)
                    val dur = player.duration.let { if (it == C.TIME_UNSET || it <= 0) 0L else it }
                    var startVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    var startBright = activity?.window?.attributes?.screenBrightness
                        ?.takeIf { it in 0f..1f } ?: 0.5f

                    var consumed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            // end
                            if (mode == DragMode.SEEK && dur > 0) {
                                val seekMs = (totalDx / width * dur.toFloat() * 0.35f).toLong()
                                val target = (seekStart + seekMs).coerceIn(0L, dur)
                                player.seekTo(target)
                                setScrubbing(false)
                            }
                            if (mode != null) {
                                gestureHint = null
                                gestureProgress = -1f
                                controlsVisible = true
                            } else if (!consumed) {
                                // tap
                                controlsVisible = !controlsVisible
                            }
                            break
                        }
                        val dx = change.positionChange().x
                        val dy = change.positionChange().y
                        totalDx += dx
                        totalDy += dy
                        if (mode == null) {
                            if (abs(totalDx) > touchSlopPx || abs(totalDy) > touchSlopPx) {
                                mode = if (abs(totalDx) >= abs(totalDy)) {
                                    DragMode.SEEK
                                } else if (leftHalf) {
                                    DragMode.BRIGHTNESS
                                } else {
                                    DragMode.VOLUME
                                }
                                if (mode == DragMode.SEEK) setScrubbing(true)
                                controlsVisible = true
                                consumed = true
                            }
                        }
                        when (mode) {
                            DragMode.SEEK -> if (dur > 0) {
                                val seekMs = (totalDx / width * dur.toFloat() * 0.35f).toLong()
                                val target = (seekStart + seekMs).coerceIn(0L, dur)
                                scrubPos = target.toFloat()
                                val sign = if (seekMs >= 0) "+" else ""
                                gestureHint = "快进 $sign${seekMs / 1000}秒\n${formatTime(target)} / ${formatTime(dur)}"
                                gestureProgress = target.toFloat() / dur.toFloat()
                            }
                            DragMode.VOLUME -> {
                                val delta = (-totalDy / height * maxVolume).roundToInt()
                                val v = (startVol + delta).coerceIn(0, maxVolume)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                gestureHint = "音量 ${(v * 100) / maxVolume}%"
                                gestureProgress = v.toFloat() / maxVolume
                            }
                            DragMode.BRIGHTNESS -> {
                                val delta = -totalDy / height
                                val b = (startBright + delta).coerceIn(0.01f, 1f)
                                activity?.window?.let { win ->
                                    val lp = win.attributes
                                    lp.screenBrightness = b
                                    win.attributes = lp
                                }
                                gestureHint = "亮度 ${(b * 100).roundToInt()}%"
                                gestureProgress = b
                            }
                            null -> Unit
                        }
                        if (mode != null) change.consume()
                    }
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                val v = android.view.LayoutInflater.from(ctx)
                    .inflate(R.layout.view_exo_player, null as ViewGroup?, false) as PlayerView
                v.player = player
                v
            },
            update = { v -> if (v.player !== player) v.player = player },
            modifier = Modifier.fillMaxSize()
        )

        if (isBuffering && errorMsg == null && gestureHint == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        errorMsg?.let { msg ->
            Text(
                "视频播放失败：$msg",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )
        }

        // 手势提示
        AnimatedVisibility(
            visible = gestureHint != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(
                    text = gestureHint.orEmpty(),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                if (gestureProgress in 0f..1f) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.3f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(gestureProgress.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(Color.White)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && errorMsg == null && gestureHint == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            VideoControls(
                isPlaying = isPlaying,
                positionMs = if (scrubbing) scrubPos.toLong() else positionMs,
                durationMs = durationMs,
                speed = speed,
                speedMenuOpen = speedMenuOpen,
                landscape = forceLandscape || isLandscape,
                onSpeedMenuChange = { speedMenuOpen = it },
                onPlayPause = {
                    if (player.isPlaying) player.pause() else player.play()
                    controlsVisible = true
                },
                onSeekBack = {
                    player.seekBack()
                    controlsVisible = true
                },
                onSeekForward = {
                    player.seekForward()
                    controlsVisible = true
                },
                onScrubStart = {
                    setScrubbing(true)
                    scrubPos = positionMs.toFloat()
                },
                onScrub = { scrubPos = it },
                onScrubEnd = {
                    player.seekTo(scrubPos.toLong().coerceIn(0L, durationMs.coerceAtLeast(0L)))
                    setScrubbing(false)
                    controlsVisible = true
                },
                onSpeedSelected = { s ->
                    player.setPlaybackSpeed(s)
                    speed = s
                    speedMenuOpen = false
                    controlsVisible = true
                },
                onToggleLandscape = {
                    forceLandscape = !forceLandscape
                    controlsVisible = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            )
        }

        // 横屏时顶部也放一个退出横屏按钮（方便）
        if ((forceLandscape || isLandscape) && controlsVisible && gestureHint == null) {
            IconButton(
                onClick = { forceLandscape = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Icon(
                    Icons.Default.FullscreenExit,
                    contentDescription = "退出横屏",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun VideoControls(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    speedMenuOpen: Boolean,
    landscape: Boolean,
    onSpeedMenuChange: (Boolean) -> Unit,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onToggleLandscape: () -> Unit,
    modifier: Modifier = Modifier
) {
    val max = durationMs.coerceAtLeast(0L).toFloat().coerceAtLeast(1f)
    val value = positionMs.toFloat().coerceIn(0f, max)
    val progress = (value / max).coerceIn(0f, 1f)
    val accent = Color(0xFFFF6B45)

    Column(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.35f),
                        Color.Black.copy(alpha = 0.82f)
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { }
    ) {
        PrettySeekBar(
            progress = progress,
            accent = accent,
            onScrubFraction = { fraction ->
                onScrubStart()
                onScrub(fraction * max)
            },
            onScrubEnd = onScrubEnd
        )

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTime(positionMs),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = " / ",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 13.sp
            )
            Text(
                text = formatTime(durationMs),
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 13.sp
            )

            Spacer(Modifier.weight(1f))

            Box {
                Text(
                    text = formatSpeed(speed),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                        .clickable { onSpeedMenuChange(true) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                DropdownMenu(
                    expanded = speedMenuOpen,
                    onDismissRequest = { onSpeedMenuChange(false) }
                ) {
                    SPEED_OPTIONS.forEach { s ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    formatSpeed(s),
                                    fontWeight = if (s == speed) FontWeight.Bold else FontWeight.Normal,
                                    color = if (s == speed) accent else Color.Unspecified
                                )
                            },
                            onClick = { onSpeedSelected(s) }
                        )
                    }
                }
            }

            Spacer(Modifier.width(6.dp))

            IconButton(
                onClick = onToggleLandscape,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (landscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = if (landscape) "退出横屏" else "横屏",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            ControlRoundButton(onClick = onSeekBack) {
                Icon(
                    Icons.Default.Replay10,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.width(36.dp))
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .shadow(10.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.12f))
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
            Spacer(Modifier.width(36.dp))
            ControlRoundButton(onClick = onSeekForward) {
                Icon(
                    Icons.Default.Forward10,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PrettySeekBar(
    progress: Float,
    accent: Color,
    onScrubFraction: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(1f) }
    val thumbPx = with(density) { 16.dp.toPx() }
    val p = progress.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(widthPx) {
                detectTapGestures { offset ->
                    onScrubFraction((offset.x / widthPx).coerceIn(0f, 1f))
                    onScrubEnd()
                }
            }
            .pointerInput(widthPx) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onScrubFraction((offset.x / widthPx).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onScrubEnd() },
                    onDragCancel = { onScrubEnd() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onScrubFraction((change.position.x / widthPx).coerceIn(0f, 1f))
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // inactive track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.22f))
        )
        // active track
        Box(
            modifier = Modifier
                .fillMaxWidth(p)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.85f), accent)
                    )
                )
        )
        // thumb
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = ((p * widthPx) - thumbPx / 2f).roundToInt()
                            .coerceIn(0, (widthPx - thumbPx).roundToInt().coerceAtLeast(0)),
                        y = 0
                    )
                }
                .size(16.dp)
                .shadow(6.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(Color.White)
                .border(2.dp, accent.copy(alpha = 0.9f), CircleShape)
        )
    }
}

@Composable
private fun ControlRoundButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
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

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun VideoPlayer(
    file: File,
    modifier: Modifier = Modifier,
    onScrubbingChanged: (Boolean) -> Unit = {},
    onLandscapeChanged: (Boolean) -> Unit = {}
) {
    VideoPlayer(
        uri = Uri.fromFile(file),
        modifier = modifier,
        onScrubbingChanged = onScrubbingChanged,
        onLandscapeChanged = onLandscapeChanged
    )
}

private const val TAG = "VideoPlayer"
