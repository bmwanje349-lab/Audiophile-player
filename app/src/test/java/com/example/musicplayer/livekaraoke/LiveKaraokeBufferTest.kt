package com.example.musicplayer.livekaraoke

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveKaraokeBufferTest {

    @Test
    fun actualMdx9482StartupBudgetIsThirtySeconds() {
        val frames =
            calculateLiveKaraokeStartupBufferFrames(
                sourceSampleRate = 44_100,
                generatedPerWindow = 254_976,
                startupBufferSeconds = 30,
                startupBufferWindows = 5,
                maxLookaheadFrames = 5_292_000,
            )

        assertEquals(1_323_000, frames)
    }

    @Test
    fun lowNeuralThroughputCannotMoveTheStartupTarget() {
        /*
         * For a 212-second track, even a sustained 0.51x producer rate would
         * mathematically require much more than 30 seconds to guarantee no
         * underrun. That is a separate throughput/sustainability problem;
         * it must not mutate the fixed startup-buffer target.
         */
        val frames =
            calculateLiveKaraokeStartupBufferFrames(
                sourceSampleRate = 44_100,
                generatedPerWindow = 254_976,
                startupBufferSeconds = 30,
                startupBufferWindows = 5,
                maxLookaheadFrames = 5_292_000,
            )

        assertEquals(30 * 44_100, frames)
    }

    @Test
    fun startupBudgetUsesWindowRequirementWhenItIsLarger() {
        val frames =
            calculateLiveKaraokeStartupBufferFrames(
                sourceSampleRate = 44_100,
                generatedPerWindow = 300_000,
                startupBufferSeconds = 30,
                startupBufferWindows = 5,
                maxLookaheadFrames = 5_292_000,
            )

        assertEquals(1_500_000, frames)
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
