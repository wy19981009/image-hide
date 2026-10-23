package com.whjk.imagehide

import android.app.Application
import android.graphics.Bitmap
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.whjk.imagehide.data.thumb.EncryptedFileFetcher
import com.whjk.imagehide.data.thumb.MediaThumbFetcher
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject

/**
 * 应用入口。
 *
 * - Hilt 容器根
 * - WorkManager 自定义配置：使用 HiltWorkerFactory 让 @AssistedInject 注入 Worker
 * - Coil：系统缩略图优先，减轻网格滑动卡顿
 */
@HiltAndroidApp
class ImageHideApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(false)
            .allowHardware(true)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            // 缩略图 IO 可更高并行；解码仍限流避免挤爆 CPU
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(6))
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(4))
            .components {
                // 网格 MediaThumb → ContentResolver.loadThumbnail / ThumbnailUtils
                add(MediaThumbFetcher.Factory())
                // 隐藏相册 .enc 按需解密
                add(EncryptedFileFetcher.Factory())
                // 非网格回退（极少路径）
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.30)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_image_cache"))
                    .maxSizeBytes(160L * 1024 * 1024)
                    .build()
            }
            .build()
}
