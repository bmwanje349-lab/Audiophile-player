package com.example.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImmersionSettings {
    @Volatile var enabled = true
    @Volatile var space = 0.35f
    @Volatile var ambience = 0.30f
    @Volatile var air = 0.30f
    @Volatile var bass = 0.30f
    @Volatile var profile = 0.50f

    private const val PREFS = "immersion_settings"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = p.getBoolean("enabled", true)
        space = p.getFloat("space", 0.35f).coerceIn(0f, 1f)
        ambience = p.getFloat("ambience", 0.30f).coerceIn(0f, 1f)
        air = p.getFloat("air", 0.30f).coerceIn(0f, 1f)
        bass = p.getFloat("bass", 0.30f).coerceIn(0f, 1f)
        profile = p.getFloat("profile", 0.50f).coerceIn(0f, 1f)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled)
            .putFloat("space", space.coerceIn(0f, 1f))
            .putFloat("ambience", ambience.coerceIn(0f, 1f))
            .putFloat("air", air.coerceIn(0f, 1f))
            .putFloat("bass", bass.coerceIn(0f, 1f))
            .putFloat("profile", profile.coerceIn(0f, 1f))
            .apply()
    }
}

@UnstableApi
class ImmersionProcessor : BaseAudioProcessor() {
    private var handle = 0L
    private var configuredRate = 48_000

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount != 2) {
            return AudioFormat.NOT_SET
        }

        configuredRate = inputAudioFormat.sampleRate
        if (handle == 0L) handle = ImmersionNative.create()
        ImmersionNative.prepare(handle, configuredRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val byteCount = inputBuffer.remaining()
        if (byteCount <= 0) return

        // Keep the original input position out of JNI's address calculations:
        // GetDirectBufferAddress points to the slice's first byte, not the
        // original ByteBuffer's current position.
        val source = inputBuffer.slice().order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())

        if (handle == 0L || byteCount % (2 * Float.SIZE_BYTES) != 0) {
            output.put(source)
            inputBuffer.position(inputBuffer.limit())
            output.flip()
            return
        }

        val frames = byteCount / (2 * Float.SIZE_BYTES)
        ImmersionNative.setEnabled(handle, ImmersionSettings.enabled)
        ImmersionNative.setSpace(handle, ImmersionSettings.space.coerceIn(0f, 1f))
        ImmersionNative.setAmbience(handle, ImmersionSettings.ambience.coerceIn(0f, 1f))
        ImmersionNative.setAir(handle, ImmersionSettings.air.coerceIn(0f, 1f))
        ImmersionNative.setBass(handle, ImmersionSettings.bass.coerceIn(0f, 1f))
        ImmersionNative.setProfile(handle, ImmersionSettings.profile.coerceIn(0f, 1f))
        ImmersionNative.process(handle, source, output, frames)

        inputBuffer.position(inputBuffer.limit())
        output.position(0)
        output.limit(byteCount)
    }

    override fun onFlush() {
        if (handle != 0L) ImmersionNative.reset(handle)
    }

    override fun onReset() {
        if (handle != 0L) {
            ImmersionNative.destroy(handle)
            handle = 0L
        }
    }
}
