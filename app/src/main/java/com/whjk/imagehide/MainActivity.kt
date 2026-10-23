package com.whjk.imagehide

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.whjk.imagehide.ui.nav.AppNav
import com.whjk.imagehide.ui.theme.ImageHideTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 唯一 Activity，承载 Compose 导航。
 * 继承 AppCompatActivity / FragmentActivity，以便 BiometricPrompt 可用。
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImageHideTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNav()
                }
            }
        }
    }
}
