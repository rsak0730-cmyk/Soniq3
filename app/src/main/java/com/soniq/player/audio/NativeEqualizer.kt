package com.soniq.player.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer

/** Device-provided Android effects. Band count and ranges are queried, never fabricated. */
class NativeEqualizer {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    fun attach(audioSessionId: Int): Boolean {
        release()

        if (audioSessionId == AudioEffect.ERROR_BAD_VALUE || audioSessionId == 0) {
            return false
        }

        return try {
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = true
            }

            bassBoost = runCatching {
                BassBoost(0, audioSessionId).apply {
                    enabled = true
                }
            }.getOrNull()

            virtualizer = runCatching {
                Virtualizer(0, audioSessionId).apply {
                    enabled = true
                }
            }.getOrNull()

            bandCount() > 0
        } catch (_: Exception) {
            release()
            false
        }
    }

    fun bandCount(): Int =
        runCatching {
            equalizer?.numberOfBands?.toInt() ?: 0
        }.getOrDefault(0)

    // FIX: Convert Android ShortArray values into IntArray.
    fun bandLevelRange(): IntArray? =
        runCatching {
            equalizer?.bandLevelRange?.let { range ->
                intArrayOf(
                    range[0].toInt(),
                    range[1].toInt()
                )
            }
        }.getOrNull()

    fun centerFrequencyHz(index: Int): Int =
        runCatching {
            val eq = equalizer ?: return 0

            if (index !in 0 until eq.numberOfBands.toInt()) {
                return 0
            }

            eq.getCenterFreq(index.toShort()) / 1000
        }.getOrDefault(0)

    fun bandLevel(index: Int): Int =
        runCatching {
            val eq = equalizer ?: return 0

            if (index !in 0 until eq.numberOfBands.toInt()) {
                return 0
            }

            eq.getBandLevel(index.toShort()).toInt()
        }.getOrDefault(0)

    fun setBandLevel(band: Int, level: Int) {
        runCatching {
            val eq = equalizer ?: return

            if (band in 0 until eq.numberOfBands.toInt()) {
                val range = eq.bandLevelRange

                val safeLevel = level.coerceIn(
                    range[0].toInt(),
                    range[1].toInt()
                )

                eq.setBandLevel(
                    band.toShort(),
                    safeLevel.toShort()
                )
            }
        }
    }

    fun setBassStrength(strength: Int) {
        runCatching {
            bassBoost?.let {
                if (it.strengthSupported) {
                    it.setStrength(strength.coerceIn(0, 1000).toShort())
                }
            }
        }
    }

    fun setVirtualizerStrength(strength: Int) {
        runCatching {
            virtualizer?.let {
                if (it.strengthSupported) {
                    it.setStrength(strength.coerceIn(0, 1000).toShort())
                }
            }
        }
    }

    fun bassSupported(): Boolean =
        runCatching {
            bassBoost?.strengthSupported == true
        }.getOrDefault(false)

    fun virtualizerSupported(): Boolean =
        runCatching {
            virtualizer?.strengthSupported == true
        }.getOrDefault(false)

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }

        equalizer = null
        bassBoost = null
        virtualizer = null
    }
}