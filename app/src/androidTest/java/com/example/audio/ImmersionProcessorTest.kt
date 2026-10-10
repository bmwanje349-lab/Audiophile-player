package com.example.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ImmersionProcessorTest {
    @Test
    fun ambienceCreatesDelayedStereoEnergyAndFiniteOutput() {
        val oldEnabled = ImmersionSettings.enabled
        val oldSpace = ImmersionSettings.space
        val oldAmbience = ImmersionSettings.ambience
        val oldAir = ImmersionSettings.air
        val oldBass = ImmersionSettings.bass
        val processor = ImmersionProcessor()
        try {
            ImmersionSettings.enabled = true
            ImmersionSettings.space = 0f
            ImmersionSettings.ambience = 1f
            ImmersionSettings.air = 0f
            ImmersionSettings.bass = 0f

            val sampleRate = 44_100
            val frames = sampleRate / 2
            val format = processor.configure(
                AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_FLOAT),
            )
            assertEquals(C.ENCODING_PCM_FLOAT, format.encoding)
            processor.flush()

            val input = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
            repeat(frames) { frame ->
                val sample = if (frame == 0) 0.5f else 0f
                input.putFloat(sample)
                input.putFloat(sample)
            }
            input.flip()
            processor.queueInput(input)

            val output = processor.output.duplicate().order(ByteOrder.nativeOrder())
            assertEquals(frames * 8, output.remaining())
            var delayedEnergy = 0.0
            var stereoDifferenceEnergy = 0.0
            var peak = 0f
            for (frame in 0 until frames) {
                val left = output.float
                val right = output.float
                assertTrue("Non-finite left sample at frame $frame", left.isFinite())
                assertTrue("Non-finite right sample at frame $frame", right.isFinite())
                peak = maxOf(peak, abs(left), abs(right))
                if (frame > sampleRate / 200) {
                    delayedEnergy += left.toDouble() * left + right.toDouble() * right
                    val difference = left.toDouble() - right.toDouble()
                    stereoDifferenceEnergy += difference * difference
                }
            }
            assertTrue("Room reflections should return delayed energy", delayedEnergy > 1e-5)
            assertTrue(
                "Room reflections should create a stereo difference from a mono impulse",
                stereoDifferenceEnergy > 1e-7,
            )
            assertTrue("Wet reflection gain should retain headroom (peak=$peak)", peak < 0.75f)
        } finally {
            runCatching { processor.reset() }
            ImmersionSettings.enabled = oldEnabled
            ImmersionSettings.space = oldSpace
            ImmersionSettings.ambience = oldAmbience
            ImmersionSettings.air = oldAir
            ImmersionSettings.bass = oldBass
        }
    }

    @Test
    fun disabledImmersionIsBitExactDryPassThrough() {
        val oldEnabled = ImmersionSettings.enabled
        val oldSpace = ImmersionSettings.space
        val oldAmbience = ImmersionSettings.ambience
        val oldAir = ImmersionSettings.air
        val oldBass = ImmersionSettings.bass
        val processor = ImmersionProcessor()
        try {
            ImmersionSettings.enabled = false
            ImmersionSettings.space = 1f
            ImmersionSettings.ambience = 1f
            ImmersionSettings.air = 1f
            ImmersionSettings.bass = 1f

            val sampleRate = 44_100
            val frames = 1024
            processor.configure(
                AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_FLOAT),
            )
            processor.flush()
            val expectedLeft = FloatArray(frames)
            val expectedRight = FloatArray(frames)
            val input = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
            repeat(frames) { frame ->
                val left = ((frame % 31) - 15) / 100f
                val right = ((frame % 23) - 11) / 100f
                expectedLeft[frame] = left
                expectedRight[frame] = right
                input.putFloat(left)
                input.putFloat(right)
            }
            input.flip()
            processor.queueInput(input)

            val output = processor.output.duplicate().order(ByteOrder.nativeOrder())
            assertEquals(frames * 8, output.remaining())
            repeat(frames) { frame ->
                assertEquals(
                    "Left bypass sample differs at frame $frame",
                    expectedLeft[frame].toRawBits(),
                    output.float.toRawBits(),
                )
                assertEquals(
                    "Right bypass sample differs at frame $frame",
                    expectedRight[frame].toRawBits(),
                    output.float.toRawBits(),
                )
            }
        } finally {
            runCatching { processor.reset() }
            ImmersionSettings.enabled = oldEnabled
            ImmersionSettings.space = oldSpace
            ImmersionSettings.ambience = oldAmbience
            ImmersionSettings.air = oldAir
            ImmersionSettings.bass = oldBass
        }
    }
}
