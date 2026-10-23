package com.whjk.imagehide.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM 加密。
 *
 * 关键设计：
 * - 主密钥存 AndroidKeyStore（永不导出），别名 = image_hide_master_key
 * - 每次加密随机 IV（12 字节），与密文拼接：[4B IV长度][12B IV][密文+16B Tag]
 * - 解密时先读前 4 字节得 IV 长度，再读 IV，再读密文
 * - KeyStore 数据随 App 卸载清空，**无法跨设备迁移**
 *
 * 注意：
 * - 加密是流式：把任何 InputStream 加密成 OutputStream
 * - 解密是流式：把加密的 InputStream 还原成 OutputStream
 * - UI 加载时用解密流喂 Coil/ExoPlayer
 */
@Singleton
class CryptoManager @Inject constructor() {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        kg.init(spec)
        return kg.generateKey()
    }

    /**
     * 加密 InputStream → OutputStream（流式）。
     * 输出格式：[4B IV长度][IV][密文+16B Tag]
     */
    fun encryptStream(input: InputStream, output: OutputStream) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv

        // 写 IV 长度 + IV
        output.write(byteArrayOf(
            ((iv.size shr 24) and 0xff).toByte(),
            ((iv.size shr 16) and 0xff).toByte(),
            ((iv.size shr 8) and 0xff).toByte(),
            (iv.size and 0xff).toByte()
        ))
        output.write(iv)

        val cos = javax.crypto.CipherOutputStream(output, cipher)
        val buf = ByteArray(BUFFER_SIZE)
        var n = input.read(buf)
        while (n >= 0) {
            cos.write(buf, 0, n)
            n = input.read(buf)
        }
        cos.close()
    }

    /**
     * 解密 input → output（流式）。
     */
    fun decryptStream(input: InputStream, output: OutputStream) {
        // 读 IV 长度
        val lenBytes = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = input.read(lenBytes, read, 4 - read)
            if (n < 0) error("unexpected EOF while reading IV length")
            read += n
        }
        val ivLen = ((lenBytes[0].toInt() and 0xff) shl 24) or
            ((lenBytes[1].toInt() and 0xff) shl 16) or
            ((lenBytes[2].toInt() and 0xff) shl 8) or
            (lenBytes[3].toInt() and 0xff)

        val iv = ByteArray(ivLen)
        read = 0
        while (read < ivLen) {
            val n = input.read(iv, read, ivLen - read)
            if (n < 0) error("unexpected EOF while reading IV")
            read += n
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val cis = javax.crypto.CipherInputStream(input, cipher)
        val buf = ByteArray(BUFFER_SIZE)
        var n = cis.read(buf)
        while (n >= 0) {
            output.write(buf, 0, n)
            n = cis.read(buf)
        }
        cis.close()
    }

    /**
     * 测试用：删除密钥（清空加密数据后调用）。
     */
    fun deleteKey() {
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "image_hide_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val IV_SIZE = 12
        private const val GCM_TAG_BITS = 128
        private const val BUFFER_SIZE = 64 * 1024

        /** 加密文件后缀 */
        const val ENC_SUFFIX = ".enc"
    }
}
