package com.soniq.player.audio

import android.media.audiofx.LoudnessEnhancer
import kotlin.math.*

class BiquadFilter(var type: FilterType, var freq: Float, var sampleRate: Float, var q: Float = 1.0f, var gainDb: Float = 0f) {
    enum class FilterType { LOW_SHELF, PEAKING, HIGH_SHELF }

    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f
    private var y1 = 0f; private var y2 = 0f

    init { recalculate() }

    fun setGain(gain: Float) {
        this.gainDb = gain
        recalculate()
    }

    private fun recalculate() {
        val a = 10.0.pow((gainDb / 40.0)).toFloat()
        val w0 = (2.0 * PI * freq / sampleRate).toFloat()
        val cosw0 = cos(w0)
        val sinw0 = sin(w0)
        val alpha = sinw0 / (2.0f * q)

        when (type) {
            FilterType.PEAKING -> {
                val a0 = 1.0f + alpha / a
                b0 = (1.0f + alpha * a) / a0
                b1 = (-2.0f * cosw0) / a0
                b2 = (1.0f - alpha * a) / a0
                a1 = (-2.0f * cosw0) / a0
                a2 = (1.0f - alpha / a) / a0
            }
            FilterType.LOW_SHELF -> {
                val sqrtA = sqrt(a.toDouble()).toFloat()
                val a0 = (a + 1.0f) + (a - 1.0f) * cosw0 + 2.0f * sqrtA * alpha
                b0 = (a * ((a + 1.0f) - (a - 1.0f) * cosw0 + 2.0f * sqrtA * alpha)) / a0
                b1 = (2.0f * a * ((a - 1.0f) - (a + 1.0f) * cosw0)) / a0
                b2 = (a * ((a + 1.0f) - (a - 1.0f) * cosw0 - 2.0f * sqrtA * alpha)) / a0
                a1 = (-2.0f * ((a - 1.0f) + (a + 1.0f) * cosw0)) / a0
                a2 = ((a + 1.0f) + (a - 1.0f) * cosw0 - 2.0f * sqrtA * alpha) / a0
            }
            FilterType.HIGH_SHELF -> {
                val sqrtA = sqrt(a.toDouble()).toFloat()
                val a0 = (a + 1.0f) - (a - 1.0f) * cosw0 + 2.0f * sqrtA * alpha
                b0 = (a * ((a + 1.0f) + (a - 1.0f) * cosw0 + 2.0f * sqrtA * alpha)) / a0
                b1 = (-2.0f * a * ((a - 1.0f) + (a + 1.0f) * cosw0)) / a0
                b2 = (a * ((a + 1.0f) + (a - 1.0f) * cosw0 - 2.0f * sqrtA * alpha)) / a0
                a1 = (2.0f * ((a - 1.0f) - (a + 1.0f) * cosw0)) / a0
                a2 = ((a + 1.0f) - (a - 1.0f) * cosw0 - 2.0f * sqrtA * alpha) / a0
            }
        }
    }

    fun process(sample: Float): Float {
        val y = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = sample
        y2 = y1
        y1 = y
        return y.coerceIn(-1.0f, 1.0f)
    }
}

object SoftwareDspEngine {
    val BAND_FREQUENCIES = floatArrayOf(31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    private val filters = Array(10) { i ->
        val type = when (i) {
            0 -> BiquadFilter.FilterType.LOW_SHELF
            9 -> BiquadFilter.FilterType.HIGH_SHELF
            else -> BiquadFilter.FilterType.PEAKING
        }
        BiquadFilter(type, BAND_FREQUENCIES[i], 44100f, 1.41f, 0f)
    }

    private var loudnessEnhancer: LoudnessEnhancer? = null

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex in filters.indices) {
            filters[bandIndex].setGain(gainDb.coerceIn(-15f, 15f))
        }
    }

    fun attachLoudness(audioSessionId: Int) {
        runCatching {
            loudnessEnhancer?.release()
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(800) // 8 dB normalization gain
                enabled = true
            }
        }
    }

    fun detach() {
        loudnessEnhancer?.release()
        loudnessEnhancer = null
    }
}
