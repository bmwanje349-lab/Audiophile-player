package com.example.musicplayer.livekaraoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveKaraokeRealtimePolicyTest {

    @Test
    fun cpuThreadRecommendationScalesWithoutOversubscribingSmallDevices() {
        assertEquals(1, recommendedLiveKaraokeCpuThreads(1))
        assertEquals(1, recommendedLiveKaraokeCpuThreads(2))
        assertEquals(2, recommendedLiveKaraokeCpuThreads(4))
        assertEquals(3, recommendedLiveKaraokeCpuThreads(6))
        assertEquals(4, recommendedLiveKaraokeCpuThreads(8))
    }

    @Test
    fun slow9482DeviceIsRejectedInsteadOfRequestingHugeStartupBuffer() {
        val sampleRate = 48_000
        val remainingSeconds = 212.0
        val measuredRate = 0.117
        val required =
            calculateLiveKaraokeRequiredBufferFrames(
                sourceSampleRate = sampleRate,
                generatedPerWindow = 254_976,
                startupBufferSeconds = 6,
                startupBufferWindows = 1,
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
                startupBufferSeconds = 6,
                startupBufferWindows = 1,
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
    fun startupPolicyRejectsRateBelowNinetyPercent() {
        assertFalse(
            isNeuralLiveStartupViable(
                measuredProducerRate = 0.899,
                requiredFrames = 100_000L,
                sourceSampleRate = 48_000,
            )
        )
    }

    @Test
    fun slowerNearRealtimeDeviceIsAcceptedWhenSafeBufferFitsTheBound() {
        val sampleRate = 48_000
        val required =
            calculateLiveKaraokeRequiredBufferFrames(
                sourceSampleRate = sampleRate,
                generatedPerWindow = 254_976,
                startupBufferSeconds = 6,
                startupBufferWindows = 1,
                remainingSeconds = 212.0,
                measuredProducerRate = 0.92,
                safetyMarginSeconds = 6.0,
            )

        assertTrue(
            isNeuralLiveStartupViable(
                measuredProducerRate = 0.92,
                requiredFrames = required,
                sourceSampleRate = sampleRate,
            )
        )
        assertTrue(required.toDouble() / sampleRate <= 24.0)
    }

    @Test
    fun playbackFallsBackOnlyWhenRateHeadroomOrThermalSafetyRequiresIt() {
        assertFalse(
            shouldSwitchLiveKaraokeToFastFallback(
                rollingProducerRate = Double.NaN,
                bufferedSeconds = 4.0,
                thermalCritical = false,
                thermalSevere = false,
            )
        )
        assertFalse(
            shouldSwitchLiveKaraokeToFastFallback(
                rollingProducerRate = 0.80,
                bufferedSeconds = 7.0,
                thermalCritical = false,
                thermalSevere = false,
            )
        )
        assertTrue(
            shouldSwitchLiveKaraokeToFastFallback(
                rollingProducerRate = 0.80,
                bufferedSeconds = 5.0,
                thermalCritical = false,
                thermalSevere = false,
            )
        )
        assertTrue(
            shouldSwitchLiveKaraokeToFastFallback(
                rollingProducerRate = Double.NaN,
                bufferedSeconds = 15.0,
                thermalCritical = true,
                thermalSevere = false,
            )
        )
        assertFalse(
            shouldSwitchLiveKaraokeToFastFallback(
                rollingProducerRate = Double.NaN,
                bufferedSeconds = 15.0,
                thermalCritical = false,
                thermalSevere = true,
            )
        )
        assertTrue(
            shouldSwitchLiveKaraokeToFastFallback(
                rollingProducerRate = Double.NaN,
                bufferedSeconds = 8.0,
                thermalCritical = false,
                thermalSevere = true,
            )
        )
    }

    @Test
    fun startupPolicyRejectsLongStartupEvenWhenRateIsOtherwiseHealthy() {
        val requiredFrames = 24L * 48_000L + 1L

        assertFalse(
            isNeuralLiveStartupViable(
                measuredProducerRate = 1.0,
                requiredFrames = requiredFrames,
                sourceSampleRate = 48_000,
            )
        )
    }

    @Test
    fun memoryPolicyKeepsLiveLookaheadInTensOfSeconds() {
        assertEquals(20, LiveKaraokePerformancePolicy.maxLookaheadSeconds(192))
        assertEquals(24, LiveKaraokePerformancePolicy.maxLookaheadSeconds(256))
        assertEquals(32, LiveKaraokePerformancePolicy.maxLookaheadSeconds(384))
        assertEquals(48, LiveKaraokePerformancePolicy.maxLookaheadSeconds(512))
    }

    @Test
    fun defaultLookaheadIsNowBoundedWithoutMinuteScaleQueue() {
        assertEquals(
            32 * 48_000,
            maxLiveKaraokeLookaheadFrames(48_000),
        )
    }
}
