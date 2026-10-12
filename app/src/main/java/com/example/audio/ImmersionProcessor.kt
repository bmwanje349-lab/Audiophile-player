package com.example.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

object ImmersionSettings {
    @Volatile var enabled = true
    @Volatile var space = 0.35f
    @Volatile var ambience = 0.30f
    @Volatile var air = 0.30f
    @Volatile var bass = 0.30f
    // Retained preference key for compatibility; now controls dry-to-measured-HRTF blend.
    @Volatile var profile = 0.50f

    private const val PREFS = "immersion_settings"

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean("enabled", true)
        space = prefs.getFloat("space", 0.35f)
        ambience = prefs.getFloat("ambience", 0.30f)
        air = prefs.getFloat("air", 0.30f)
        bass = prefs.getFloat("bass", 0.30f)
        profile = prefs.getFloat("profile", 0.50f)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled)
            .putFloat("space", space)
            .putFloat("ambience", ambience)
            .putFloat("air", air)
            .putFloat("bass", bass)
            .putFloat("profile", profile)
            .apply()
    }
}

@UnstableApi
class ImmersionProcessor : BaseAudioProcessor() {
    private var handle = 0L

    override fun onConfigure(format: AudioFormat): AudioFormat {
        if (format.encoding != C.ENCODING_PCM_FLOAT || format.channelCount != 2) {
            throw UnhandledAudioFormatException(format)
        }
        if (handle == 0L) handle = ImmersionNative.create()
        ImmersionNative.prepare(handle, format.sampleRate)
        return format
    }

    override fun queueInput(input: ByteBuffer) {
        val bytes = input.remaining()
        val frames = bytes / (2 * Float.SIZE_BYTES)
        if (frames <= 0) {
            input.position(input.limit())
            return
        }
        val output = replaceOutputBuffer(bytes)
        ImmersionNative.setEnabled(handle, ImmersionSettings.enabled)
        ImmersionNative.setSpace(handle, ImmersionSettings.space)
        ImmersionNative.setAmbience(handle, ImmersionSettings.ambience)
        ImmersionNative.setAir(handle, ImmersionSettings.air)
        ImmersionNative.setBass(handle, ImmersionSettings.bass)
        ImmersionNative.setProfile(handle, ImmersionSettings.profile)
        ImmersionNative.process(handle, input, output, frames)
        output.position(0)
        output.limit(bytes)
        input.position(input.limit())
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
