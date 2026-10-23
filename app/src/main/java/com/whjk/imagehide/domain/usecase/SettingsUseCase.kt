package com.whjk.imagehide.domain.usecase

import com.whjk.imagehide.data.datastore.UserPrefs
import com.whjk.imagehide.data.repository.HiddenRepositoryImpl
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * 设置相关用例。
 */
class SettingsUseCase @Inject constructor(
    private val prefs: UserPrefs
) {
    val pinEnabled: Flow<Boolean> = prefs.pinEnabled
    val biometricEnabled: Flow<Boolean> = prefs.biometricEnabled
    val lockHiddenAlbums: Flow<Boolean> = prefs.lockHiddenAlbums
    val appLockEnabled: Flow<Boolean> = prefs.appLockEnabled

    suspend fun setPin(pin: String?) = prefs.setPin(pin)
    suspend fun verifyPin(pin: String): Boolean = prefs.verifyPin(pin)
    suspend fun isPinEnabled(): Boolean = prefs.isPinEnabled()
    suspend fun isBiometricEnabled(): Boolean = prefs.isBiometricEnabled()
    suspend fun isLockHiddenAlbums(): Boolean = prefs.isLockHiddenAlbums()
    suspend fun isAppLockEnabled(): Boolean = prefs.isAppLockEnabled()

    suspend fun setBiometricEnabled(enabled: Boolean) = prefs.setBiometricEnabled(enabled)
    suspend fun setLockHiddenAlbums(enabled: Boolean) = prefs.setLockHiddenAlbums(enabled)
    suspend fun setAppLockEnabled(enabled: Boolean) = prefs.setAppLockEnabled(enabled)
}

/**
 * 一键还原全部：把所有隐藏相册逐个 enqueue restore worker。
 */
class RestoreAllUseCase @Inject constructor(
    private val hidden: HiddenRepositoryImpl
) {
    suspend operator fun invoke(): Int {
        val albums = hidden.listHiddenAlbums()
        albums.forEach { album ->
            hidden.submitRestore(album.bucketId)
        }
        return albums.size
    }
}
