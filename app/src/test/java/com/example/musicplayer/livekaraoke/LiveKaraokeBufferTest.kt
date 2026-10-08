package com.example.musicplayer.livekaraoke

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveKaraokeBufferTest {

    @Test
    fun liveKaraokeStartupBudgetUsesShortFixedMinimum() {
        val frames =
            calculateLiveKaraokeStartupBufferFrames(
                sourceSampleRate = 44_100,
                generatedPerWindow = 254_976,
                startupBufferSeconds = 6,
                startupBufferWindows = 1,
                maxLookaheadFrames = 5_292_000,
            )

        assertEquals(264_600, frames)
    }

    @Test
    fun lowerThanRealtimeThroughputProducesTheMathematicallyRequiredTarget() {
        /*
         * 212 s remaining at 0.508x real-time needs:
         * 212 * (1 - 0.508) + 6 = 110.304 s.
         */
        val frames =
            calculateLiveKaraokeSafeBufferFrames(
                sourceSampleRate = 44_100,
                generatedPerWindow = 254_976,
                startupBufferSeconds = 30,
                startupBufferWindows = 5,
                remainingSeconds = 212.0,
                measuredProducerRate = 0.508,
                safetyMarginSeconds = 6.0,
                maxLookaheadFrames = 5_292_000,
            )

        assertEquals(
            4_864_407,
            frames,
        )
    }

    @Test(expected = NeuralLiveNotViableException::class)
    fun impossibleThroughputIsRejectedForFastFallbackInsteadOfStartingUnsafely() {
        calculateLiveKaraokeSafeBufferFrames(
            sourceSampleRate = 44_100,
            generatedPerWindow = 254_976,
            startupBufferSeconds = 6,
            startupBufferWindows = 1,
            remainingSeconds = 212.0,
            measuredProducerRate = 0.40,
            safetyMarginSeconds = 6.0,
            maxLookaheadFrames = 5_292_000,
        )
    }

    @Test
    fun lookaheadCapacityScalesWithSourceRate() {
        assertEquals(
            32 * 44_100,
            maxLiveKaraokeLookaheadFrames(44_100),
        )
        assertEquals(
            32 * 48_000,
            maxLiveKaraokeLookaheadFrames(48_000),
        )
    }

    @Test
    fun realtimeOrFasterThroughputKeepsShortMinimum() {
        val frames =
            calculateLiveKaraokeSafeBufferFrames(
                sourceSampleRate = 44_100,
                generatedPerWindow = 254_976,
                startupBufferSeconds = 6,
                startupBufferWindows = 1,
                remainingSeconds = 212.0,
                measuredProducerRate = 1.01,
                safetyMarginSeconds = 6.0,
                maxLookaheadFrames = 5_292_000,
            )

        assertEquals(
            6 * 44_100,
            frames,
        )
    }

    @Test
    fun startupBudgetUsesSingleWindowWhenItIsLarger() {
        val frames =
            calculateLiveKaraokeStartupBufferFrames(
                sourceSampleRate = 44_100,
                generatedPerWindow = 300_000,
                startupBufferSeconds = 6,
                startupBufferWindows = 1,
                maxLookaheadFrames = 5_292_000,
            )

        assertEquals(300_000, frames)
    }

    @Test
    fun startupBudgetNeverExceedsLookaheadCap() {
        val frames =
            calculateLiveKaraokeStartupBufferFrames(
                sourceSampleRate = 44_100,
                generatedPerWindow = 2_000_000,
                startupBufferSeconds = 30,
                startupBufferWindows = 5,
                maxLookaheadFrames = 1_000_000,
            )

        assertEquals(1_000_000, frames)
    }
}
