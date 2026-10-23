package com.whjk.imagehide.data.thumb

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.util.Size
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.Dimension
import coil.size.isOriginal
import com.whjk.imagehide.data.crypto.CryptoEntryPoint
import com.whjk.imagehide.data.crypto.CryptoManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 系统缩略图 Fetcher：MediaStore / ThumbnailUtils，适合相册网格高速滑动。
 * 本地 .enc 在缩略图阶段按需解密（不在进相册时整相册解密）。
 */
class MediaThumbFetcher(
    private val data: MediaThumb,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val (reqW, reqH) = requestPixels(options)
        val bitmap = withContext(Dispatchers.IO) {
            loadBitmap(reqW, reqH)
        }
        val config = options.config
        val out = if (
            config == Bitmap.Config.RGB_565 &&
            bitmap.config != Bitmap.Config.RGB_565 &&
            bitmap.config != Bitmap.Config.ALPHA_8
        ) {
            bitmap.copy(Bitmap.Config.RGB_565, false)?.also { copied ->
                if (copied !== bitmap) bitmap.recycle()
            } ?: bitmap
        } else {
            bitmap
        }
        return DrawableResult(
            drawable = out.toDrawable(options.context.resources),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    private fun loadBitmap(reqW: Int, reqH: Int): Bitmap {
        val size = Size(reqW.coerceAtLeast(64), reqH.coerceAtLeast(64))
        return when (val model = data.model) {
            is Uri -> options.context.contentResolver.loadThumbnail(model, size, null)
            is File -> {
                val file = resolveLocalFile(model)
                if (!file.exists()) throw IOException("missing file: ${file.path}")
                if (data.isVideo) {
                    ThumbnailUtils.createVideoThumbnail(file, size, null)
                        ?: throw IOException("video thumb null: ${file.path}")
                } else {
                    ThumbnailUtils.createImageThumbnail(file, size, null)
                        ?: throw IOException("image thumb null: ${file.path}")
                }
            }
            else -> throw IOException("unsupported thumb model: ${model.javaClass}")
        }
    }

    private fun resolveLocalFile(source: File): File {
        if (!source.name.endsWith(CryptoManager.ENC_SUFFIX)) return source
        val opener = EntryPointAccessors.fromApplication(
            options.context.applicationContext,
            CryptoEntryPoint::class.java
        ).encryptedFileOpener()
        return opener.openForView(source, options.context.cacheDir)
    }

    class Factory : Fetcher.Factory<MediaThumb> {
        override fun create(
            data: MediaThumb,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher = MediaThumbFetcher(data, options)
    }
}

private fun requestPixels(options: Options): Pair<Int, Int> {
    val size = options.size
    if (size.isOriginal) return DEFAULT_PX to DEFAULT_PX
    val pxW = when (val w = size.width) {
        is Dimension.Pixels -> w.px
        else -> DEFAULT_PX
    }
    val pxH = when (val h = size.height) {
        is Dimension.Pixels -> h.px
        else -> DEFAULT_PX
    }
    // 略放大一点再交给系统采样，Crop 时更清晰；上限避免过大
    val max = maxOf(pxW, pxH).coerceIn(96, 512)
    return max to max
}

private const val DEFAULT_PX = 256
