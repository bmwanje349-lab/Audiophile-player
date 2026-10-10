package com.example.musicplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.audio.FloatToPcm16Processor
import com.example.audio.LoudnessProcessor
import com.example.audio.ToFloatProcessor
import com.example.peq.PeqAudioProcessor
import com.example.peq.PeqEngine
import com.example.peq.WidenerAudioProcessor
import com.example.peq.WidenerEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AudioPipelineSmokeTest {
    @Test
    fun floatDspChainProducesNonSilentPcm16Output() {
        val sampleRate = 44_100
        val peq = PeqEngine(16)
        val widener = WidenerEngine()
        val processors: List<AudioProcessor> = listOf(
            ToFloatProcessor(),
            PeqAudioProcessor(peq),
            WidenerAudioProcessor(widener),
            LoudnessProcessor(),
            FloatToPcm16Processor(),
        )

        try {
            var format = AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT)
            processors.forEach { processor -> format = processor.configure(format) }
            assertEquals(C.ENCODING_PCM_16BIT, format.encoding)
            processors.forEach { it.flush() }

            val frames = sampleRate * 2
            val input = ByteBuffer.allocateDirect(frames * 2 * 2).order(ByteOrder.nativeOrder())
            repeat(frames) { frame ->
                val sample = (sin(2.0 * Math.PI * 440.0 * frame / sampleRate) * 7000.0).toInt().toShort()
                input.putShort(sample)
                input.putShort(sample)
            }
            input.flip()

            var current: ByteBuffer = input
            processors.forEach { processor ->
                processor.queueInput(current)
                current = processor.output
            }

            val output = current.duplicate().order(ByteOrder.nativeOrder())
            assertTrue("DSP chain returned too few bytes", output.remaining() > 4_000)
            var peak = 0
            var count = 0
            var sumSquares = 0.0
            while (output.remaining() >= 2) {
                val sample = output.short.toInt()
                peak = max(peak, abs(sample))
                sumSquares += sample.toDouble() * sample.toDouble()
                count++
            }
            val rms = if (count == 0) 0.0 else sqrt(sumSquares / count)
            assertTrue("Custom DSP chain output is silent (peak=$peak)", peak > 500)
            assertTrue("Custom DSP chain output level is implausibly low (RMS=$rms)", rms > 100.0)
        } finally {
            processors.forEach { processor -> runCatching { processor.reset() } }
            peq.close()
            widener.close()
        }
    }
}