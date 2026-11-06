package com.soniq.player.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

enum class OutputKind(val label: String) {
    PHONE_SPEAKER("Phone speaker"),
    WIRED_HEADPHONES("Wired headphones"),
    BLUETOOTH("Bluetooth"),
    USB("USB audio"),
    OTHER("Other output")
}

data class AudioOutput(
    val kind: OutputKind,
    val name: String,
    val type: Int
)

class AudioDeviceMonitor(
    context: Context,
    private val onChanged: (List<AudioOutput>) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = publish()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = publish()
    }

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.registerAudioDeviceCallback(callback, null)
            publish()
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.unregisterAudioDeviceCallback(callback)
        }
    }

    private fun publish() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }
            .map { device ->
                AudioOutput(
                    kind = classify(device.type),
                    name = device.productName?.toString()?.takeIf { it.isNotBlank() }
                        ?: classify(device.type).label,
                    type = device.type
                )
            }
        onChanged(outputs)
    }

    private fun classify(type: Int): OutputKind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputKind.PHONE_SPEAKER

        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> OutputKind.WIRED_HEADPHONES

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> OutputKind.BLUETOOTH

        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKind.USB

        else -> OutputKind.OTHER
    }
}
