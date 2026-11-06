package com.soniq.player.audio

import android.media.audiofx.Visualizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.hypot

object RealtimeFftMonitor {
    private var visualizer: Visualizer? = null
    var bassEnergy by mutableFloatStateOf(0f)
        private set
    var midEnergy by mutableFloatStateOf(0f)
        private set
    var amplitudes by mutableStateOf(FloatArray(16))
        private set

    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0) return
        runCatching {
            visualizer?.release()
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null) return
                        val n = fft.size / 2
                        val magnitudes = FloatArray(n)
                        for (i in 0 until n) {
                            val r = fft[i * 2].toFloat()
                            val im = fft[i * 2 + 1].toFloat()
                            magnitudes[i] = hypot(r, im)
                        }
                        val bass = (1..4.coerceAtMost(n - 1)).map { magnitudes[it] }.average().toFloat() / 128f
                        bassEnergy = bass.coerceIn(0f, 2.5f)

                        val mids = (5..12.coerceAtMost(n - 1)).map { magnitudes[it] }.average().toFloat() / 128f
                        midEnergy = mids.coerceIn(0f, 2f)

                        val chunk = (n / 16).coerceAtLeast(1)
                        val bands = FloatArray(16) { idx ->
                            val start = idx * chunk
                            var sum = 0f
                            for (j in 0 until chunk) {
                                if (start + j < n) sum += magnitudes[start + j]
                            }
                            (sum / chunk / 100f).coerceIn(0.05f, 1.0f)
                        }
                        amplitudes = bands
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        }.onFailure {
            visualizer = null
            bassEnergy = 0f
            midEnergy = 0f
        }
    }

    fun release() {
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
        bassEnergy = 0f
        midEnergy = 0f
    }
}
