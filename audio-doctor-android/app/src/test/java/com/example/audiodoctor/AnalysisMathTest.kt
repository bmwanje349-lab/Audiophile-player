package com.example.audiodoctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.hypot

class AnalysisMathTest {
    @Test fun halfScaleIsAboutMinusSixDbfs() {
        assertEquals(-6.0206, AnalysisMath.dbfs(0.5), 0.01)
    }

    @Test fun correlationIdentifiesSameAndOppositeSignals() {
        assertEquals(1.0, AnalysisMath.correlation(3.0, 3.0, 5.0, 5.0, 5.0, 3L)!!, 0.0001)
        assertEquals(-1.0, AnalysisMath.correlation(0.0, 0.0, 2.0, 2.0, -2.0, 2L)!!, 0.0001)
    }

    @Test fun tempoEstimatorFindsRegular120BpmPulseTrain() {
        val env = List(20 * 20) { index -> if (index % 10 == 0) 1.0 else 0.05 }
        val bpm = AnalysisMath.estimateTempo(env)
        assertNotNull(bpm)
        assertEquals(120.0, bpm!!, 0.1)
    }

    @Test fun fftFindsMagnitudeAtBin() {
        val n = 1024
        val real = DoubleArray(n) { index -> sin(2.0 * PI * 8 * index / n) }
        val imaginary = DoubleArray(n)
        AnalysisMath.fft(real, imaginary)
        val magnitude = hypot(real[8], imaginary[8])
        assertEquals(512.0, magnitude, 0.01)
    }
}
