package com.soniq.player

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import com.soniq.player.ui.SoniqApp
import com.soniq.player.ui.theme.soniqLightColorScheme

class MainActivity : ComponentActivity() {
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // Required so Android's native Visualizer (RealtimeFftMonitor) doesn't fail/crash
            add(Manifest.permission.RECORD_AUDIO)
        }.toTypedArray()

        permissionLauncher.launch(permissions)

        setContent {
            MaterialTheme(
                colorScheme = soniqLightColorScheme()
            ) {
                SoniqApp()
            }
        }
    }
}
