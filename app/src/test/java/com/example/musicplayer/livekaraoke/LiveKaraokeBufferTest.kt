package com.example.musicplayer.livekaraoke

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveKaraokeBufferTest {

    @Test
    fun startupBudgetRemainsThirtySecondsWhenWindowIsSmaller() {
        val frames =
            calculateLiveKaraokeStartupBufferFrames(
                sourceSampleRate = 44_100,
                generatedPerWindow = 200_000,
                startupBufferSeconds = 30,
                startupBufferWindows = 5,
                maxLookaheadFrames = 5_292_000,
            )

        assertEquals(1_323_000, frames)
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
