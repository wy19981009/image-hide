package com.whjk.imagehide.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whjk.imagehide.data.auth.BiometricHelper
import com.whjk.imagehide.domain.usecase.SettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PinLockState(
    val needsLock: Boolean = false,
    val unlocked: Boolean = false,
    val biometricEnabled: Boolean = false,
    val biometricAvailable: Boolean = false,
    val error: String? = null,
    val ready: Boolean = false
)

@HiltViewModel
class PinLockViewModel @Inject constructor(
    private val settings: SettingsUseCase,
    private val biometricHelper: BiometricHelper
) : ViewModel() {

    private val _state = MutableStateFlow(PinLockState())
    val state: StateFlow<PinLockState> = _state.asStateFlow()

    fun checkLock() {
        viewModelScope.launch {
            val appLock = settings.isAppLockEnabled()
            val bio = settings.isBiometricEnabled()
            if (!appLock) {
                _state.update {
                    it.copy(
                        needsLock = false,
                        unlocked = true,
                        ready = true,
                        biometricEnabled = bio,
                        biometricAvailable = biometricHelper.canAuthenticate()
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        needsLock = true,
                        unlocked = false,
                        ready = true,
                        biometricEnabled = bio,
                        biometricAvailable = biometricHelper.canAuthenticate()
                    )
                }
            }
        }
    }

    fun verify(pin: String) {
        viewModelScope.launch {
            val ok = settings.verifyPin(pin)
            if (ok) {
                _state.update { it.copy(unlocked = true, error = null) }
            } else {
                _state.update { it.copy(error = "PIN 不对") }
            }
        }
    }

    fun onBiometricSuccess() {
        _state.update { it.copy(unlocked = true, error = null) }
    }

    fun onBiometricError(msg: String) {
        _state.update { it.copy(error = msg) }
    }
}
