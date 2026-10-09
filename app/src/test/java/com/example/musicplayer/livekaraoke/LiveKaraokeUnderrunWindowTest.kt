package com.example.musicplayer.livekaraoke

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveKaraokeUnderrunWindowTest {

    private val second = 1_000_000_000L

    @Test
    fun singleUnderrunDoesNotTriggerFallback() {
        val window = LiveKaraokeUnderrunWindow(30 * second, 3)

        assertFalse(window.record(10 * second, 1))
    }

    @Test
    fun repeatedUnderrunsInsideWindowTriggerFallback() {
        val window = LiveKaraokeUnderrunWindow(30 * second, 3)

        assertFalse(window.record(1 * second, 1))
        assertFalse(window.record(10 * second, 1))
        assertTrue(window.record(20 * second, 1))
    }

    @Test
    fun oldUnderrunsExpireOutOfTheWindow() {
        val window = LiveKaraokeUnderrunWindow(30 * second, 3)

        assertFalse(window.record(1 * second, 1))
        assertFalse(window.record(10 * second, 1))
        // The first two are now older than the 30 s window, so this is the
        // only recent underrun.
        assertFalse(window.record(45 * second, 1))
    }

    @Test
    fun burstInOnePollCountsEveryUnderrun() {
        val window = LiveKaraokeUnderrunWindow(30 * second, 3)

        assertTrue(window.record(5 * second, 3))
    }

    @Test
    fun hugeDriverReportedBurstIsHandledWithoutLooping() {
        val window = LiveKaraokeUnderrunWindow(30 * second, 3)

        assertTrue(window.record(5 * second, Int.MAX_VALUE))
    }
}
