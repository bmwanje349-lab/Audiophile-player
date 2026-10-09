package com.example.musicplayer.livekaraoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class LiveKaraokeThroughputTrackerTest {
    private val sampleRate = 44_100
    private val framesPerWindow = 254_976L

    private fun newTracker() =
        LiveKaraokeThroughputTracker(
            sampleRate = sampleRate,
            calibrationFrames = framesPerWindow * 2L,
            rollingWindowFrames = framesPerWindow,
        )

    @Test
    fun firstWindowRateIsAvailableBeforeTwoWindowCalibration() {
        val tracker = newTracker()
        tracker.start(1_000_000_000L)
        emitWindow(tracker, 7_000_000_000L)

        assertTrue(tracker.firstWindowRate.isFinite())
        assertEquals(
            (framesPerWindow.toDouble() / sampleRate) / 6.0,
            tracker.firstWindowRate,
            0.002,
        )
        assertFalse(tracker.calibratedRate.isFinite())
    }

    @Test
    fun firstModelWindowDoesNotPretendToBeSustainedThroughput() {
        val tracker = newTracker()
        tracker.start(1_000_000_000L)
        emitWindow(tracker, 7_000_000_000L)

        assertFalse(tracker.calibratedRate.isFinite())
        assertFalse(tracker.rollingRate.isFinite())
    }

    @Test
    fun calibrationUsesTwoWindowAverageAndRollingRateUsesNextFullWindow() {
        val tracker = newTracker()
        tracker.start(1_000_000_000L)
        emitWindow(tracker, 7_000_000_000L)
        emitWindow(tracker, 13_000_000_000L)

        assertTrue(tracker.calibratedRate.isFinite())
        assertEquals(
            (framesPerWindow * 2.0 / sampleRate) / 12.0,
            tracker.calibratedRate,
            0.002,
        )
        // Must not compute a rate from individual blocks in the same burst
        // that completed the second model window.
        assertFalse(tracker.rollingRate.isFinite())

        emitWindow(tracker, 19_000_000_000L)

        assertTrue(tracker.rollingRate.isFinite())
        assertEquals(
            (framesPerWindow.toDouble() / sampleRate) / 6.0,
            tracker.rollingRate,
            0.002,
        )
    }

    @Test
    fun slowSustainedRateIsMeasuredAcrossWindowsInsteadOfSingleOutputBurst() {
        val tracker = newTracker()
        tracker.start(1_000_000_000L)
        emitWindow(tracker, 8_000_000_000L)
        emitWindow(tracker, 15_000_000_000L)

        assertEquals(
            (framesPerWindow * 2.0 / sampleRate) / 14.0,
            tracker.calibratedRate,
            0.002,
        )
    }

    private fun emitWindow(
        target: LiveKaraokeThroughputTracker,
        nowNs: Long,
    ) {
        var remaining = framesPerWindow.toInt()
        while (remaining > 0) {
            val block = min(8_192, remaining)
            target.addOutputFrames(block, nowNs)
            remaining -= block
        }
    }
}
