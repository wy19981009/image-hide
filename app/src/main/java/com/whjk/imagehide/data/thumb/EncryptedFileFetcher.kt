package com.whjk.imagehide.data.thumb

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.whjk.imagehide.data.crypto.CryptoEntryPoint
import com.whjk.imagehide.data.crypto.CryptoManager
import dagger.hilt.android.EntryPointAccessors
import okio.buffer
import okio.source
import java.io.File

/**
 * 加密私有文件（.enc）按需解密后再交给 Coil 解码。
 * 非 .enc 返回 null，走默认 File Fetcher。
 */
class EncryptedFileFetcher(
    private val encrypted: File,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val opener = EntryPointAccessors.fromApplication(
            options.context.applicationContext,
            CryptoEntryPoint::class.java
        ).encryptedFileOpener()
        val plain = opener.openForView(encrypted, options.context.cacheDir)
        if (!plain.exists() || plain.length() <= 0L) {
            throw IllegalStateException("decrypt empty: ${encrypted.name}")
        }
        return SourceResult(
            source = ImageSource(
                source = plain.source().buffer(),
                context = options.context
            ),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<File> {
        override fun create(
            data: File,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            if (!data.name.endsWith(CryptoManager.ENC_SUFFIX)) return null
            return EncryptedFileFetcher(data, options)
        }
    }
}
