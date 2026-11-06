package com.soniq.player.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer

class AudioNoisyReceiver(
    private val context: Context,
    private val player: ExoPlayer
) : BroadcastReceiver() {
    private var registered = false

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
            if (player.isPlaying) {
                player.pause()
            }
        }
    }

    fun register() {
        if (!registered) {
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    this,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                registered = true
            }
        }
    }

    fun unregister() {
        if (registered) {
            runCatching { context.unregisterReceiver(this) }
            registered = false
        }
    }
}
