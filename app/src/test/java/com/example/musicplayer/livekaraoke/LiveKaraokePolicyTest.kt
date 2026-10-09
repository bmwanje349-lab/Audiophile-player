package com.example.musicplayer.livekaraoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the pure timing and safety policy used by LiveKaraokeEngine.
 *
 * These tests deliberately exercise arithmetic without Android audio hardware.
 * They do not replace an on-device neural playback/stress test.
 */
class LiveKaraokePolicyTest {

    @Test
    fun startupBufferUsesLargerOfTimeMinimumAndModelWindow() {
        assertEquals(
            44_100 * 6,
            calculateLiveKaraokeStartupBufferFrames(
                sourceSampleRate = 44_100,
                generatedPerWindow = 44_100 * 8,
                startupBufferSeconds = 6,
                startupBufferWindows = 1,
                maxLookaheadFrames = 44_100 * 20,
            ),
        )
    }

    @Test
    fun startupBufferIsBoundedByMaximumLookahead() {
        assertEquals(
            44_100 * 10,
            calculateLiveKaraokeStartupBufferFrames(
                sourceSampleRate = 44_100,
                generatedPerWindow = 44_100 * 15,
                startupBufferSeconds = 6,
                startupBufferWindows = 1,
                maxLookaheadFrames = 44_100 * 10,
            ),
        )
    }

    @Test
    fun realTimeOrFasterProducerNeedsOnlyFixedMinimum() {
        val frames = calculateLiveKaraokeRequiredBufferFrames(
            sourceSampleRate = 44_100,
            generatedPerWindow = 44_100,
            startupBufferSeconds = 6,
            startupBufferWindows = 1,
            remainingSeconds = 180.0,
            measuredProducerRate = 1.0,
            safetyMarginSeconds = 6.0,
        )
        assertEquals(44_100L * 6L, frames)
    }

    @Test
    fun slowProducerRequiresDeficitBufferForRemainingTrack() {
        val frames = calculateLiveKaraokeRequiredBufferFrames(
            sourceSampleRate = 44_100,
            generatedPerWindow = 44_100,
            startupBufferSeconds = 6,
            startupBufferWindows = 1,
            remainingSeconds = 100.0,
            measuredProducerRate = 0.8,
            safetyMarginSeconds = 6.0,
        )
        // 100 s * (1 - 0.8) + 6 s = 26 s.
        assertEquals(44_100L * 26L, frames)
    }

    @Test
    fun unsafeBufferIsRejectedInsteadOfGrowingWithoutBound() {
        try {
            calculateLiveKaraokeSafeBufferFrames(
                sourceSampleRate = 44_100,
                generatedPerWindow = 44_100,
                startupBufferSeconds = 6,
                startupBufferWindows = 1,
                remainingSeconds = 180.0,
                measuredProducerRate = 0.8,
                safetyMarginSeconds = 6.0,
                maxLookaheadFrames = 44_100 * 32,
            )
            throw AssertionError("Expected bounded-buffer rejection")
        } catch (_: NeuralLiveNotViableException) {
            // Expected: 180 * 0.2 + 6 = 42 seconds, above the 32-second cap.
        }
    }

    @Test
    fun startupViabilityRejectsSlowOrExcessivelyLongStartup() {
        assertTrue(
            isNeuralLiveStartupViable(
                measuredProducerRate = 1.0,
                requiredFrames = 44_100L * 6L,
                sourceSampleRate = 44_100,
            ),
        )
        assertFalse(
            isNeuralLiveStartupViable(
                measuredProducerRate = 0.89,
                requiredFrames = 44_100L * 6L,
                sourceSampleRate = 44_100,
            ),
        )
        assertFalse(
            isNeuralLiveStartupViable(
                measuredProducerRate = 1.0,
                requiredFrames = 44_100L * 25L,
                sourceSampleRate = 44_100,
            ),
        )
    }

    @Test
    fun fallbackRequiresCriticalThermalStateOrLowRateAndLowBuffer() {
        assertFalse(
            shouldSwitchLiveKaraokeToFastFallback(
                rollingProducerRate = 0.7,
                bufferedSeconds = 8.0,
                thermalCritical = false,
                thermalSevere = false,
            ),
        )
        assertTrue(
            shouldSwitchLiveKaraokeToFastFallback(
                rollingProducerRate = 0.7,
                bufferedSeconds = 5.0,
                thermalCritical = false,
                thermalSevere = false,
            ),
        )
        assertTrue(
            shouldSwitchLiveKaraokeToFastFallback(
                rollingProducerRate = 1.0,
                bufferedSeconds = 20.0,
                thermalCritical = true,
                thermalSevere = false,
            ),
        )
    }

    @Test
    fun playbackHeadUnsignedWrapIsHandled() {
        assertEquals(0xFFFF_FFFFL, audioTrackPlaybackHeadFrames(-1))
        assertEquals(0L, audioTrackPlaybackHeadFrames(0))
    }

    @Test
    fun positionNeverExceedsKnownTrackDuration() {
        assertEquals(
            120_000L,
            liveKaraokePositionMs(
                playbackStartMs = 110_000L,
                playbackFrames = 44_100L,
                sampleRate = 44_100,
                durationMs = 120_000L,
            ),
        )
    }

    @Test
    fun prerollRoundsUpToWholeSourceFrame() {
        assertEquals(
            45,
            calculateLiveKaraokePrerollFrames(
                playbackStartMs = 1L,
                actualDecodeStartUs = 0L,
                sampleRate = 44_100,
            ),
        )
    }
}
