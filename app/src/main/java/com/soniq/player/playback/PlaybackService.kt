package com.soniq.player.playback

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.soniq.player.audio.AudioEffectsBridge
import com.soniq.player.audio.RealtimeFftMonitor
import com.soniq.player.audio.SoftwareDspEngine

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var noisyReceiver: AudioNoisyReceiver? = null
    private var notificationReceiver: NotificationFadeReceiver? = null
    private val crossfadeHandler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var isDucked = false
    private var resumeAfterFocusGain = false

    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            AudioEffectsBridge.attach(audioSessionId)
            RealtimeFftMonitor.attach(audioSessionId)
            SoftwareDspEngine.attachLoudness(audioSessionId)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            session?.player?.let { p ->
                if (!isDucked) p.volume = 1.0f
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // A track that cannot be opened would otherwise leave the player stuck, so the
            // next button stops working. Drop that item from the queue and continue.
            val p = session?.player ?: return
            when {
                p.hasNextMediaItem() -> {
                    p.removeMediaItem(p.currentMediaItemIndex)
                    p.prepare()
                    p.play()
                }
                p.mediaItemCount > 1 -> {
                    // Recover when the failed item is the final queue entry as well.
                    p.seekToDefaultPosition(0)
                    p.prepare()
                    p.play()
                }
                else -> p.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                false
            )
            .build()

        player.addListener(listener)
        session = MediaSession.Builder(this, player).build()
        noisyReceiver = AudioNoisyReceiver(this, player).also { it.register() }

        setupSmartAudioFocus(player)
        setupNotificationFadeReceiver(player)
        setupCrossfadeMonitor(player)
    }

    private fun setupSmartAudioFocus(player: ExoPlayer) {
        val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    fadeVolume(player, player.volume, 0.18f, 350L)
                    isDucked = true
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (isDucked) {
                        fadeVolume(player, player.volume, 1.0f, 600L)
                        isDucked = false
                    }
                    if (resumeAfterFocusGain && player.playbackState != Player.STATE_IDLE) {
                        player.play()
                    }
                    resumeAfterFocusGain = false
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeAfterFocusGain = player.isPlaying
                    if (player.isPlaying) player.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeAfterFocusGain = false
                    if (player.isPlaying) player.pause()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AndroidAudioAttributes.Builder()
                        .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                        .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            focusRequest = req
            audioManager?.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun setupNotificationFadeReceiver(player: ExoPlayer) {
        val receiver = NotificationFadeReceiver { isIncomingAlert ->
            if (isIncomingAlert) {
                fadeVolume(player, player.volume, 0.18f, 300L)
            } else {
                fadeVolume(player, player.volume, 1.0f, 500L)
            }
        }
        notificationReceiver = receiver
        val filter = IntentFilter().apply {
            addAction("android.media.RINGER_MODE_CHANGED")
            addAction("android.intent.action.PHONE_STATE")
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun fadeVolume(player: ExoPlayer, from: Float, to: Float, durationMs: Long) {
        ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            addUpdateListener { anim ->
                runCatching { player.volume = anim.animatedValue as Float }
            }
            start()
        }
    }

    private fun setupCrossfadeMonitor(player: ExoPlayer) {
        val checkInterval = 500L
        val monitorRunnable = object : Runnable {
            override fun run() {
                runCatching {
                    val duration = player.duration
                    val pos = player.currentPosition
                    if (!isDucked && player.isPlaying && duration > 0 && (duration - pos) in 1..4000) {
                        val remainingMs = duration - pos
                        val factor = (remainingMs / 4000f).coerceIn(0.1f, 1f)
                        player.volume = factor
                    } else if (!isDucked && player.volume < 1.0f && player.currentPosition < 3000) {
                        val factor = (player.currentPosition / 3000f).coerceIn(0.1f, 1f)
                        player.volume = factor
                    } else if (!isDucked && player.volume < 1.0f && duration - pos > 4000) {
                        player.volume = 1.0f
                    }
                }
                crossfadeHandler.postDelayed(this, checkInterval)
            }
        }
        crossfadeHandler.post(monitorRunnable)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        crossfadeHandler.removeCallbacksAndMessages(null)
        noisyReceiver?.unregister()
        notificationReceiver?.let { runCatching { unregisterReceiver(it) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        }
        RealtimeFftMonitor.release()
        SoftwareDspEngine.detach()
        session?.let {
            it.player.removeListener(listener)
            it.player.release()
            it.release()
        }
        AudioEffectsBridge.release()
        session = null
        super.onDestroy()
    }
}

class NotificationFadeReceiver(private val onAlertChanged: (Boolean) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == "android.intent.action.PHONE_STATE") {
            val state = intent.getStringExtra("state")
            onAlertChanged(state == "RINGING")
        }
    }
}