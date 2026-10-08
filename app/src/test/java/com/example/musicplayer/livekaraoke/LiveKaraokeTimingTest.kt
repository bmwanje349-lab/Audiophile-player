package com.example.musicplayer.livekaraoke

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveKaraokeTimingTest {

    @Test
    fun playbackHeadIsHandledAsUnsigned32BitFrameCounter() {
        assertEquals(
            0L,
            audioTrackPlaybackHeadFrames(0),
        )
        assertEquals(
            2_147_483_647L,
            audioTrackPlaybackHeadFrames(Int.MAX_VALUE),
        )
        assertEquals(
            2_147_483_648L,
            audioTrackPlaybackHeadFrames(Int.MIN_VALUE),
        )
        assertEquals(
            4_294_967_295L,
            audioTrackPlaybackHeadFrames(-1),
        )
    }

    @Test
    fun positionUsesAudibleFramesNotSubmittedFrames() {
        assertEquals(
            0L,
            liveKaraokePositionMs(
                playbackStartMs = 0L,
                playbackFrames = 0L,
                sampleRate = 44_100,
                durationMs = 212_000L,
            ),
        )

        assertEquals(
            10_000L,
            liveKaraokePositionMs(
                playbackStartMs = 0L,
                playbackFrames = 441_000L,
                sampleRate = 44_100,
                durationMs = 212_000L,
            ),
        )

        assertEquals(
            25_000L,
            liveKaraokePositionMs(
                playbackStartMs = 1_000L,
                playbackFrames = 1_058_400L,
                sampleRate = 44_100,
                durationMs = 212_000L,
            ),
        )
    }

    @Test
    fun positionNeverExceedsKnownDuration() {
        assertEquals(
            212_000L,
            liveKaraokePositionMs(
                playbackStartMs = 0L,
                playbackFrames = 20_000_000L,
                sampleRate = 44_100,
                durationMs = 212_000L,
            ),
        )
    }
}
