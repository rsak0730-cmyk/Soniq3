package com.soniq.player.audio

object AudioEffectsBridge {
    @Volatile private var effects: NativeEqualizer? = null
    fun attach(audioSessionId: Int): Boolean {
        val instance = NativeEqualizer()
        val ok = instance.attach(audioSessionId)
        if (ok) {
            effects?.release()
            effects = instance
        } else instance.release()
        return ok
    }
    fun isAvailable() = bandCount() > 0
    fun bandCount() = effects?.bandCount() ?: 0
    fun bandLevelRange(): IntRange {
        val r = effects?.bandLevelRange() ?: return -1500..1500
        return if (r.size >= 2) r[0].toInt()..r[1].toInt() else -1500..1500
    }
    fun centerFrequencyHz(i: Int) = effects?.centerFrequencyHz(i) ?: 0
    fun bandLevel(i: Int) = effects?.bandLevel(i) ?: 0
    fun setBandLevel(i: Int, v: Int) { effects?.setBandLevel(i, v) }
    fun setBassStrength(v: Int) { effects?.setBassStrength(v) }
    fun setVirtualizerStrength(v: Int) { effects?.setVirtualizerStrength(v) }
    fun bassSupported() = effects?.bassSupported() == true
    fun virtualizerSupported() = effects?.virtualizerSupported() == true
    fun reset() { repeat(bandCount()) { setBandLevel(it, 0) }; setBassStrength(0); setVirtualizerStrength(0) }
    fun release() { effects?.release(); effects = null }
}
