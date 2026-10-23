package com.whjk.imagehide.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whjk.imagehide.data.auth.BiometricHelper
import com.whjk.imagehide.data.permission.PermissionChecker
import com.whjk.imagehide.data.repository.HiddenRepositoryImpl
import com.whjk.imagehide.domain.usecase.RestoreAllUseCase
import com.whjk.imagehide.domain.usecase.SettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    val pinEnabled: Boolean = false,
    val appLockEnabled: Boolean = false,
    val lockHiddenAlbums: Boolean = false,
    val biometricEnabled: Boolean = false,
    val biometricAvailable: Boolean = false,
    val hiddenCount: Int = 0,
    val manageAllFiles: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsUseCase,
    private val restoreAll: RestoreAllUseCase,
    private val hiddenRepo: HiddenRepositoryImpl,
    private val permissions: PermissionChecker,
    private val biometricHelper: BiometricHelper
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val pin = settings.isPinEnabled()
            _state.update {
                it.copy(
                    pinEnabled = pin,
                    appLockEnabled = settings.isAppLockEnabled(),
                    lockHiddenAlbums = settings.isLockHiddenAlbums(),
                    biometricEnabled = settings.isBiometricEnabled(),
                    biometricAvailable = biometricHelper.canAuthenticate(),
                    hiddenCount = hiddenRepo.listHiddenAlbums().size,
                    manageAllFiles = permissions.hasManageAllFiles()
                )
            }
        }
    }

    fun setPin(pin: String?) {
        viewModelScope.launch {
            settings.setPin(pin)
            refresh()
        }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        if (enabled && !_state.value.pinEnabled) return
        _state.update { it.copy(appLockEnabled = enabled) }
        viewModelScope.launch {
            settings.setAppLockEnabled(enabled)
            refresh()
        }
    }

    fun setLockHiddenAlbums(enabled: Boolean) {
        if (enabled && !_state.value.pinEnabled) return
        _state.update { it.copy(lockHiddenAlbums = enabled) }
        viewModelScope.launch {
            settings.setLockHiddenAlbums(enabled)
            refresh()
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        if (enabled && (!_state.value.pinEnabled || !biometricHelper.canAuthenticate())) return
        _state.update { it.copy(biometricEnabled = enabled) }
        viewModelScope.launch {
            settings.setBiometricEnabled(enabled)
            refresh()
        }
    }

    fun restoreAll() {
        viewModelScope.launch {
            restoreAll.invoke()
            refresh()
        }
    }
}
