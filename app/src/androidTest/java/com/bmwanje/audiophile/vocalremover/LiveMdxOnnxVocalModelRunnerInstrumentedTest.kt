package com.bmwanje.audiophile.vocalremover

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class LiveMdxOnnxVocalModelRunnerInstrumentedTest {

    @Test
    fun bundled9482RunsThroughTheAndroidOnnxRunner() {
        val context =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext

        val spec = MdxModelSpec.LIGHT_9482
        val model =
            MdxModelManager.ensureInstalled(
                context,
                spec,
            )

        assertTrue(model.isFile)
        assertEquals(spec.fileSizeBytes, model.length())
        assertEquals(spec.sha256, MdxModelManager.sha256(model))

        val runner =
            LiveMdxOnnxVocalModelRunner(
                modelPath = model.absolutePath,
                modelSpec = spec,
                cpuThreads = 1,
            )

        try {
            val chunk = MdxStft(spec).chunkSizeSamples()
            val sampleRate = MdxStft.SAMPLE_RATE.toDouble()

            val left =
                FloatArray(chunk) { index ->
                    (
                        0.18 * sin(
                            2.0 * Math.PI * 440.0 *
                                index.toDouble() / sampleRate,
                        )
                    ).toFloat()
                }
            val right =
                FloatArray(chunk) { index ->
                    (
                        0.16 * sin(
                            2.0 * Math.PI * 550.0 *
                                index.toDouble() / sampleRate,
                        )
                    ).toFloat()
                }

            val output =
                runner.separateChunk(
                    left,
                    right,
                )

            assertEquals(chunk, output.left.size)
            assertEquals(chunk, output.right.size)
            assertTrue(output.left.all { it.isFinite() })
            assertTrue(output.right.all { it.isFinite() })
            assertEquals(1L, runner.inferenceCount())
            assertTrue(runner.inferenceBackend().isNotBlank())
        } finally {
            runner.close()
        }


    @Test
    fun bundled9482RunsThroughTheFullStreamingSeparator() {
        val context =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext
        val spec = MdxModelSpec.LIGHT_9482
        val model =
            MdxModelManager.ensureInstalled(
                context,
                spec,
            )

        val runner =
            LiveMdxOnnxVocalModelRunner(
                modelPath = model.absolutePath,
                modelSpec = spec,
                cpuThreads = 1,
            )
        val native = NativeVocalRemover(MdxStft.SAMPLE_RATE)
        val emitted = ArrayList<VocalSeparatorCore.Stereo>()
        val streaming =
            LiveStreamingVocalRemover(
                sampleRate = MdxStft.SAMPLE_RATE,
                runner = runner,
                modelSpec = spec,
                native = native,
                emit = { emitted += it },
            )

        try {
            val sourceSamples =
                MdxStft(spec).chunkSizeSamples() + 8_000
            val blockSize = 8_191
            var position = 0

            while (position < sourceSamples) {
                val count =
                    minOf(
                        blockSize,
                        sourceSamples - position,
                    )
                val left =
                    FloatArray(count) { index ->
                        (
                            0.12 * sin(
                                2.0 * Math.PI * 330.0 *
                                    (position + index).toDouble() /
                                    MdxStft.SAMPLE_RATE.toDouble(),
                            )
                        ).toFloat()
                    }
                val right =
                    FloatArray(count) { index ->
                        (
                            0.11 * sin(
                                2.0 * Math.PI * 495.0 *
                                    (position + index).toDouble() /
                                    MdxStft.SAMPLE_RATE.toDouble(),
                            )
                        ).toFloat()
                    }

                streaming.push(
                    VocalSeparatorCore.Stereo(
                        left,
                        right,
                    )
                )
                position += count
            }

            streaming.finish()

            assertEquals(sourceSamples.toLong(), streaming.inputSamples())
            assertEquals(
                sourceSamples.toLong(),
                streaming.emittedSamples(),
            )
            assertTrue(emitted.isNotEmpty())
            assertEquals(
                sourceSamples,
                emitted.sumOf { it.size },
            )
            assertTrue(
                emitted.all { block ->
                    block.left.all(Float::isFinite) &&
                        block.right.all(Float::isFinite)
                },
            )
            assertTrue(streaming.mdxInferenceCount() >= 1L)
        } finally {
            runCatching { streaming.close() }
            runCatching { runner.close() }
        }
    }
    }
}
