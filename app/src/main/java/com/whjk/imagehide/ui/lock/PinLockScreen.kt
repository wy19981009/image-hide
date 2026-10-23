package com.whjk.imagehide.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whjk.imagehide.data.auth.AuthEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * 进应用锁屏：PIN + 可选指纹。
 * 未开启「进入应用需要验证」时自动放行。
 */
@Composable
fun PinLockScreen(
    onUnlocked: () -> Unit,
    viewModel: PinLockViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val biometricHelper = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            AuthEntryPoint::class.java
        ).biometricHelper()
    }
    var pin by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.checkLock() }
    LaunchedEffect(state.unlocked) {
        if (state.unlocked) onUnlocked()
    }

    fun launchBiometric() {
        val act = activity ?: return
        biometricHelper.authenticate(
            activity = act,
            title = "解锁应用",
            subtitle = "使用指纹或面容验证",
            onSuccess = { viewModel.onBiometricSuccess() },
            onError = { viewModel.onBiometricError(it) },
            onFailed = { viewModel.onBiometricError("识别失败，请重试或输入 PIN") }
        )
    }

    LaunchedEffect(state.ready, state.needsLock, state.biometricEnabled) {
        if (state.ready && state.needsLock && state.biometricEnabled && state.biometricAvailable) {
            launchBiometric()
        }
    }

    if (!state.ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (!state.needsLock) return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("需要验证身份", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "输入 PIN 或使用指纹进入应用",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(6) },
            label = { Text("PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            isError = state.error != null
        )
        if (state.error != null) {
            Spacer(Modifier.height(8.dp))
            Text(state.error!!, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { viewModel.verify(pin) },
            enabled = pin.length >= 4
        ) {
            Text("解锁")
        }
        if (state.biometricEnabled && state.biometricAvailable) {
            Spacer(Modifier.height(20.dp))
            IconButton(onClick = { launchBiometric() }) {
                Icon(
                    Icons.Default.Fingerprint,
                    contentDescription = "指纹解锁",
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "使用指纹",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
