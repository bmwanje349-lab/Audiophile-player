package com.example.peq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class WidenerHaasIntegrationTest {
    @Test
    fun haasControlsCreateStereoCueFromMonoInputThroughJni() {
        val sampleRate = 44_100
        val frames = sampleRate * 2
        val engine = WidenerEngine()
        val processor = WidenerAudioProcessor(engine)

        try {
            // Set values before configure: onConfigure prepares the native DSP
            // and then reapplies all Kotlin controls to the new native state.
            engine.enabled = true
            engine.width = 1.0f
            engine.dryWet = 1.0f
            engine.autoLevel = false
            engine.outputGainDb = 0.0f
            engine.outputCeilingDb = 0.0f
            engine.limiterSafetyMarginDb = 0.0f
            engine.haasDelayMs = 8.0f
            engine.haasMix = 1.0f

            val outputFormat = processor.configure(
                AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_FLOAT),
            )
            assertEquals(C.ENCODING_PCM_FLOAT, outputFormat.encoding)
            processor.flush()

            val input = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
            repeat(frames) { frame ->
                val t = frame.toDouble() / sampleRate
                val mono = (
                    0.16 * sin(2.0 * PI * 220.0 * t) +
                    0.10 * sin(2.0 * PI * 437.0 * t + 0.23) +
                    0.06 * sin(2.0 * PI * 1331.0 * t + 0.71)
                ).toFloat()
                input.putFloat(mono)
                input.putFloat(mono)
            }
            input.flip()
            processor.queueInput(input)

            val output = processor.output.duplicate().order(ByteOrder.nativeOrder())
            assertEquals("Unexpected output byte count", frames * 8, output.remaining())

            var sideEnergy = 0.0
            var inputEnergy = 0.0
            var peak = 0.0f
            var count = 0
            for (frame in 0 until frames) {
                val left = output.float
                val right = output.float
                assertTrue("Non-finite left sample at frame $frame", left.isFinite())
                assertTrue("Non-finite right sample at frame $frame", right.isFinite())
                peak = maxOf(peak, abs(left), abs(right))
                if (frame >= sampleRate / 2) {
                    val side = 0.5 * (left.toDouble() - right.toDouble())
                    sideEnergy += side * side
                    val t = frame.toDouble() / sampleRate
                    val mono =
                        0.16 * sin(2.0 * PI * 220.0 * t) +
                        0.10 * sin(2.0 * PI * 437.0 * t + 0.23) +
                        0.06 * sin(2.0 * PI * 1331.0 * t + 0.71)
                    inputEnergy += mono * mono
                    count++
                }
            }

            val sideRms = sqrt(sideEnergy / count)
            val inputRms = sqrt(inputEnergy / count)
            assertTrue(
                "Haas delay/mix reached JNI but created no useful side cue " +
                    "(sideRms=$sideRms inputRms=$inputRms)",
                sideRms > inputRms * 0.02,
            )
            assertTrue("Haas side feed exceeded the intended headroom (peak=$peak)", peak < 0.75f)
        } finally {
            runCatching { processor.reset() }
            engine.close()
        }
    }
}
