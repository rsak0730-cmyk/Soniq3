package com.soniq.player.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * Real-time Biquad Stems Isolator that adjusts parametric equalizer bands
 * to isolate or suppress Vocals, Sub/Mid Bass, and Instrumentals.
 */
object AudioStemsEngine {

    // Stems values range from 0.0f (Muted) to 1.0f (Normal) to 2.0f (Boosted)
    var vocalStem by mutableFloatStateOf(1.0f)
        private set
    var bassStem by mutableFloatStateOf(1.0f)
        private set
    var instrumentalStem by mutableFloatStateOf(1.0f)
        private set

    fun updateVocalStem(gain: Float) {
        vocalStem = gain
        applyStems()
    }

    fun updateBassStem(gain: Float) {
        bassStem = gain
        applyStems()
    }

    fun updateInstrumentalStem(gain: Float) {
        instrumentalStem = gain
        applyStems()
    }

    fun resetStems() {
        vocalStem = 1.0f
        bassStem = 1.0f
        instrumentalStem = 1.0f
        applyStems()
    }

    private fun applyStems() {
        if (!AudioEffectsBridge.isAvailable()) return

        val bandCount = AudioEffectsBridge.bandCount()
        if (bandCount == 0) return

        val range = AudioEffectsBridge.bandLevelRange()
        val maxGain = range.last.toFloat()
        val minGain = range.first.toFloat()

        for (i in 0 until bandCount) {
            val freq = AudioEffectsBridge.centerFrequencyHz(i)
            var multiplier = 1.0f

            when {
                // Bass frequencies (< 250 Hz)
                freq < 250 -> {
                    multiplier = bassStem
                }
                // Vocal core frequencies (300 Hz - 3500 Hz)
                freq in 250..3500 -> {
                    multiplier = vocalStem
                }
                // Upper harmonic / Treble / Instrumentals (> 3500 Hz)
                else -> {
                    multiplier = instrumentalStem
                }
            }

            // Convert stem factor (0..2) to millibels dB adjustment
            val targetDb = when {
                multiplier < 1.0f -> minGain * (1.0f - multiplier)
                multiplier > 1.0f -> maxGain * (multiplier - 1.0f)
                else -> 0f
            }

            AudioEffectsBridge.setBandLevel(i, targetDb.toInt())
        }

        // Also scale native BassBoost with bassStem
        if (AudioEffectsBridge.bassSupported()) {
            val bassStrength = ((bassStem - 0.5f).coerceAtLeast(0f) * 600f).toInt().coerceIn(0, 1000)
            AudioEffectsBridge.setBassStrength(bassStrength)
        }
    }
}
