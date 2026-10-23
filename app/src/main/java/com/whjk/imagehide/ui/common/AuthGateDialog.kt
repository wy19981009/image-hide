package com.whjk.imagehide.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.whjk.imagehide.data.auth.BiometricHelper
import kotlinx.coroutines.launch

/**
 * 打开隐藏相册前的验证弹窗：PIN + 可选指纹。
 */
@Composable
fun AuthGateDialog(
    title: String = "验证身份",
    message: String = "打开隐藏相册需要验证",
    biometricEnabled: Boolean,
    biometricHelper: BiometricHelper,
    onVerifyPin: suspend (String) -> Boolean,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }

    fun launchBiometric() {
        val act = activity ?: run {
            error = "无法调起生物识别"
            return
        }
        if (!biometricHelper.canAuthenticate()) {
            error = "设备不支持或未录入生物识别"
            return
        }
        biometricHelper.authenticate(
            activity = act,
            title = title,
            subtitle = message,
            onSuccess = onSuccess,
            onError = { error = it },
            onFailed = { error = "识别失败，请重试或输入 PIN" }
        )
    }

    LaunchedEffect(biometricEnabled) {
        if (biometricEnabled && biometricHelper.canAuthenticate()) {
            launchBiometric()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter(Char::isDigit).take(6)
                        error = null
                    },
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
                if (biometricEnabled && biometricHelper.canAuthenticate()) {
                    Spacer(Modifier.height(12.dp))
                    IconButton(onClick = { launchBiometric() }) {
                        Icon(
                            Icons.Default.Fingerprint,
                            contentDescription = "指纹解锁",
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
        },
        confirmButton = {
            TextButton(
                enabled = pin.length >= 4 && !verifying,
                onClick = {
                    scope.launch {
                        verifying = true
                        val ok = onVerifyPin(pin)
                        verifying = false
                        if (ok) onSuccess() else error = "PIN 不对"
                    }
                }
            ) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
