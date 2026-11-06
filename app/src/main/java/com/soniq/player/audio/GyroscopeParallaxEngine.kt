package com.soniq.player.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * Reads Accelerometer & Gyroscope data to calculate smooth pitch and roll
 * for dynamic 3D liquid parallax and tilt effects.
 */
class GyroscopeParallaxEngine(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    var pitch by mutableFloatStateOf(0f)
        private set

    var roll by mutableFloatStateOf(0f)
        private set

    private var filterFactor = 0.15f

    fun start() {
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)

                val targetPitch = (orientation[1] * 2.0f).coerceIn(-1.5f, 1.5f)
                val targetRoll = (orientation[2] * 2.0f).coerceIn(-1.5f, 1.5f)

                pitch += (targetPitch - pitch) * filterFactor
                roll += (targetRoll - roll) * filterFactor
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = (event.values[0] / 9.81f).coerceIn(-1f, 1f)
                val ay = (event.values[1] / 9.81f).coerceIn(-1f, 1f)

                pitch += (-ay - pitch) * filterFactor
                roll += (ax - roll) * filterFactor
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
