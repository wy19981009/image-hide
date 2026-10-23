package com.whjk.imagehide.data.crypto

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 给 UI 用的解密流工具。
 *
 * 用法：
 * ```
 * val s = EncryptedFileOpener.open(file) // 返回原始 InputStream
 * coil.load(s) ...
 * ```
 *
 * 但 Coil/ExoPlayer 优先用 File/Uri；本工具改返回"解密后的临时 File"或"FileProvider Uri"。
 *
 * 简化方案：解密到缓存目录，返回缓存 File。
 */
@Singleton
class EncryptedFileOpener @Inject constructor(
    private val crypto: CryptoManager
) {

    /**
     * 如果是 .enc 文件，解密到 cacheDir；否则原样返回。
     * 缓存文件有 TTL 限制，建议加 cleanup。
     */
    fun openForView(source: File, cacheDir: File): File {
        if (!source.exists()) return source
        if (!source.name.endsWith(CryptoManager.ENC_SUFFIX)) return source
        val cacheFile = File(cacheDir, "dec_${source.nameWithoutExtension}")
        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile

        cacheFile.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            cacheFile.outputStream().use { output ->
                crypto.decryptStream(input, output)
            }
        }
        return cacheFile
    }

    fun isEncrypted(file: File): Boolean =
        file.name.endsWith(CryptoManager.ENC_SUFFIX)
}
