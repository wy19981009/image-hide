package com.whjk.imagehide.ui.viewer

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import com.whjk.imagehide.data.crypto.CryptoEntryPoint
import com.whjk.imagehide.data.crypto.CryptoManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import java.io.File

/**
 * 单页图片：铺满可用区域，等比 Fit。
 * 隐藏相册 .enc 先解密再交给 Coil/telephoto，避免黑屏。
 */
@Composable
fun PhotoPage(model: Any, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var error by remember(model) { mutableStateOf<String?>(null) }

    val resolved by produceState<Any?>(initialValue = null, model) {
        value = withContext(Dispatchers.IO) {
            resolveViewModel(model, context.cacheDir, context.applicationContext)
        }
    }

    LaunchedEffect(resolved) {
        val data = resolved ?: return@LaunchedEffect
        error = null
        val request = ImageRequest.Builder(context)
            .data(data)
            .listener(
                onError = { _, result ->
                    error = result.throwable?.message ?: "图片解码失败"
                    Log.e(TAG, "image load failed: $data", result.throwable)
                }
            )
            .build()
        context.imageLoader.enqueue(request)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when {
            resolved == null && error == null -> {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
            resolved != null -> {
                ZoomableAsyncImage(
                    model = resolved,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        error?.let { msg ->
            Text(
                "图片加载失败：$msg",
                color = Color.White,
                modifier = Modifier.padding(24.dp)
            )
        }
    }
}

private fun resolveViewModel(
    model: Any,
    cacheDir: File,
    appContext: android.content.Context
): Any {
    val file = when (model) {
        is File -> model
        is String -> {
            when {
                model.startsWith("content://") || model.startsWith("file://") ->
                    return Uri.parse(model)
                else -> File(model)
            }
        }
        is Uri -> return model
        else -> return model
    }
    if (!file.name.endsWith(CryptoManager.ENC_SUFFIX)) return file
    val opener = EntryPointAccessors.fromApplication(
        appContext,
        CryptoEntryPoint::class.java
    ).encryptedFileOpener()
    return opener.openForView(file, cacheDir)
}

/** 兼容旧调用：本地文件 */
@Composable
fun PhotoPage(file: File, modifier: Modifier = Modifier) {
    PhotoPage(model = file, modifier = modifier)
}

@Composable
fun PhotoPage(uri: Uri, modifier: Modifier = Modifier) {
    PhotoPage(model = uri, modifier = modifier)
}

private const val TAG = "PhotoPage"
