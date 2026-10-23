package com.whjk.imagehide.data.crypto

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CryptoEntryPoint {
    fun encryptedFileOpener(): EncryptedFileOpener
}
