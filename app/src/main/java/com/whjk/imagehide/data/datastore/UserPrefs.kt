package com.whjk.imagehide.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户偏好（DataStore Preferences）。
 *
 * - PIN：SHA-256(salt + 明文)，不存明文
 * - biometricEnabled：允许用指纹/面容代替 PIN
 * - lockHiddenAlbums：打开隐藏相册前再验一次
 * - appLockEnabled：进入应用需要验证（需先设置 PIN）
 */
private val Context.userDataStore by preferencesDataStore(name = "user_prefs")

@Singleton
class UserPrefs @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val store = context.userDataStore

    private val KEY_PIN_HASH = stringPreferencesKey("pin_hash")
    private val KEY_PIN_SALT = stringPreferencesKey("pin_salt")
    private val KEY_BIOMETRIC = booleanPreferencesKey("biometric_enabled")
    private val KEY_LOCK_HIDDEN = booleanPreferencesKey("lock_hidden_albums")
    private val KEY_APP_LOCK = booleanPreferencesKey("app_lock_enabled")

    val pinEnabled: Flow<Boolean> = store.data.map { it[KEY_PIN_HASH]?.isNotEmpty() == true }
    val biometricEnabled: Flow<Boolean> = store.data.map { it[KEY_BIOMETRIC] ?: false }
    val lockHiddenAlbums: Flow<Boolean> = store.data.map { it[KEY_LOCK_HIDDEN] ?: false }
    /** 有 PIN 时默认开启进应用锁；可在设置里关 */
    val appLockEnabled: Flow<Boolean> = store.data.map { prefs ->
        val hasPin = prefs[KEY_PIN_HASH]?.isNotEmpty() == true
        if (!hasPin) false else (prefs[KEY_APP_LOCK] ?: true)
    }

    suspend fun isPinEnabled(): Boolean = pinEnabled.first()
    suspend fun isBiometricEnabled(): Boolean = biometricEnabled.first()
    suspend fun isLockHiddenAlbums(): Boolean = lockHiddenAlbums.first()
    suspend fun isAppLockEnabled(): Boolean = appLockEnabled.first()

    suspend fun setPin(plainPin: String?) {
        store.edit { prefs ->
            if (plainPin.isNullOrEmpty()) {
                prefs.remove(KEY_PIN_HASH)
                prefs.remove(KEY_PIN_SALT)
                prefs[KEY_APP_LOCK] = false
                prefs[KEY_LOCK_HIDDEN] = false
                prefs[KEY_BIOMETRIC] = false
            } else {
                val salt = prefs[KEY_PIN_SALT] ?: generateSalt().also { prefs[KEY_PIN_SALT] = it }
                prefs[KEY_PIN_HASH] = hashPin(plainPin, salt)
                // 设 PIN 后默认开启进应用锁
                if (prefs[KEY_APP_LOCK] == null) prefs[KEY_APP_LOCK] = true
            }
        }
    }

    suspend fun verifyPin(plainPin: String): Boolean {
        val prefs = store.data.first()
        val hash = prefs[KEY_PIN_HASH] ?: return false
        val salt = prefs[KEY_PIN_SALT] ?: return false
        return hashPin(plainPin, salt) == hash
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        store.edit { it[KEY_BIOMETRIC] = enabled }
    }

    suspend fun setLockHiddenAlbums(enabled: Boolean) {
        store.edit { it[KEY_LOCK_HIDDEN] = enabled }
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        store.edit { it[KEY_APP_LOCK] = enabled }
    }

    private fun hashPin(pin: String, salt: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((salt + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateSalt(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
