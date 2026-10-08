package com.example.musicplayer.livekaraoke

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveKaraokeRealtimePolicyTest {

    @Test
    fun slow9482DeviceIsRejectedInsteadOfRequestingHugeStartupBuffer() {
        val sampleRate = 48_000
        val remainingSeconds = 212.0
        val measuredRate = 0.117
        val required =
            calculateLiveKaraokeRequiredBufferFrames(
                sourceSampleRate = sampleRate,
                generatedPerWindow = 254_976,
                startupBufferSeconds = 30,
                startupBufferWindows = 5,
                remainingSeconds = remainingSeconds,
                measuredProducerRate = measuredRate,
                safetyMarginSeconds = 6.0,
            )

        assertFalse(
            isNeuralLiveStartupViable(
                measuredProducerRate = measuredRate,
                requiredFrames = required,
                sourceSampleRate = sampleRate,
            )
        )

        assertTrue(
            required.toDouble() / sampleRate.toDouble() > 18.0
        )
    }

    @Test
    fun nearRealtimeDeviceCanUseNeuralProgressivePlayback() {
        val sampleRate = 48_000
        val remainingSeconds = 212.0
        val measuredRate = 0.95
        val required =
            calculateLiveKaraokeRequiredBufferFrames(
                sourceSampleRate = sampleRate,
                generatedPerWindow = 254_976,
                startupBufferSeconds = 30,
                startupBufferWindows = 5,
                remainingSeconds = remainingSeconds,
                measuredProducerRate = measuredRate,
                safetyMarginSeconds = 6.0,
            )

        assertTrue(
            isNeuralLiveStartupViable(
                measuredProducerRate = measuredRate,
                requiredFrames = required,
                sourceSampleRate = sampleRate,
            )
        )

        assertTrue(
            required.toDouble() / sampleRate.toDouble() <= 18.0
        )
    }

    @Test
    fun startupPolicyRejectsBelowMinimumSustainedRate() {
        assertFalse(
            isNeuralLiveStartupViable(
                measuredProducerRate = 0.949,
                requiredFrames = 100_000L,
                sourceSampleRate = 48_000,
            )
        )
    }

    @Test
    fun startupPolicyRejectsLongStartupEvenWhenRateIsOtherwiseHealthy() {
        val requiredFrames = 18L * 48_000L + 1L

        assertFalse(
            isNeuralLiveStartupViable(
                measuredProducerRate = 1.0,
                requiredFrames = requiredFrames,
                sourceSampleRate = 48_000,
            )
        )
    }
}
