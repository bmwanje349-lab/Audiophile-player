package com.example.musicplayer.livekaraoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for pure timing and safety policy used by LiveKaraokeEngine.
 * These arithmetic tests do not replace Android neural-playback stress tests.
 */
class LiveKaraokePolicyTest {

    @Test
    fun startupBufferUsesLargerOfTimeMinimumAndModelWindow() {
        assertEquals(
            44_100 * 8,
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
        assertEquals(
            44_100L * 6L,
            calculateLiveKaraokeRequiredBufferFrames(
                sourceSampleRate = 44_100,
                generatedPerWindow = 44_100,
                startupBufferSeconds = 6,
                startupBufferWindows = 1,
                remainingSeconds = 180.0,
                measuredProducerRate = 1.0,
                safetyMarginSeconds = 6.0,
            ),
        )
    }

    @Test
    fun slowProducerRequiresDeficitBufferForRemainingTrack() {
        assertEquals(
            44_100L * 26L,
            calculateLiveKaraokeRequiredBufferFrames(
                sourceSampleRate = 44_100,
                generatedPerWindow = 44_100,
                startupBufferSeconds = 6,
                startupBufferWindows = 1,
                remainingSeconds = 100.0,
                measuredProducerRate = 0.8,
                safetyMarginSeconds = 6.0,
            ),
        )
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
            // 180 * 0.2 + 6 = 42 seconds, exceeding the 32-second cap.
        }
    }

    @Test
    fun startupViabilityRejectsSlowOrExcessivelyLongStartup() {
        assertTrue(isNeuralLiveStartupViable(1.0, 44_100L * 6L, 44_100))
        assertFalse(isNeuralLiveStartupViable(0.89, 44_100L * 6L, 44_100))
        assertFalse(isNeuralLiveStartupViable(1.0, 44_100L * 25L, 44_100))
    }

    @Test
    fun fallbackRequiresCriticalThermalStateOrLowRateAndLowBuffer() {
        assertFalse(shouldSwitchLiveKaraokeToFastFallback(0.7, 8.0, false, false))
        assertTrue(shouldSwitchLiveKaraokeToFastFallback(0.7, 5.0, false, false))
        assertTrue(shouldSwitchLiveKaraokeToFastFallback(1.0, 20.0, true, false))
    }

    @Test
    fun playbackHeadUnsignedWrapIsHandled() {
        assertEquals(0xFFFF_FFFFL, audioTrackPlaybackHeadFrames(-1))
        assertEquals(0L, audioTrackPlaybackHeadFrames(0))
    }

    @Test
    fun positionTracksFramesAndClampsAtDuration() {
        assertEquals(
            111_000L,
            liveKaraokePositionMs(110_000L, 44_100L, 44_100, 120_000L),
        )
        assertEquals(
            120_000L,
            liveKaraokePositionMs(119_500L, 44_100L, 44_100, 120_000L),
        )
    }

    @Test
    fun prerollRoundsUpToWholeSourceFrame() {
        assertEquals(45, calculateLiveKaraokePrerollFrames(1L, 0L, 44_100))
    }
}
