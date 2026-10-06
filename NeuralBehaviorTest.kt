package com.bmwanje.audiophile.vocalremover

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

private fun rms(x: FloatArray): Double {
    var s = 0.0
    for (v in x) s += v.toDouble() * v.toDouble()
    return sqrt(s / x.size)
}

private data class CaseResult(val relativeError: Double, val outputRms: Double)

private fun runCase(depth: Float, correlated: Boolean): CaseResult {
    val sr = 48_000
    val n = sr * 3
    val vocal = FloatArray(n) { i ->
        (0.25 * sin(2.0 * PI * 440.0 * i / sr) +
            0.08 * sin(2.0 * PI * 1500.0 * i / sr)).toFloat()
    }
    val instrumental = FloatArray(n) { i ->
        val base = (0.14 * sin(2.0 * PI * 220.0 * i / sr) +
            0.05 * sin(2.0 * PI * 3100.0 * i / sr)).toFloat()
        if (correlated) base + (0.08 * sin(2.0 * PI * 440.0 * i / sr)).toFloat() else base
    }
    val mix = FloatArray(n) { instrumental[it] + vocal[it] }

    NativeVocalRemover(sr).use { dsp ->
        dsp.setDepth(depth)
        dsp.setStemGainDb(0f)
        dsp.setDryWet(1f)
        val out = dsp.renderOffline(
            VocalSeparatorCore.Stereo(mix, mix.copyOf()),
            VocalSeparatorCore.Stereo(vocal, vocal.copyOf()),
            2048,
        )
        var err2 = 0.0
        var signal2 = 0.0
        val warmup = n / 10
        for (i in warmup until n) {
            val e = out.left[i] - instrumental[i]
            err2 += e * e
            signal2 += instrumental[i].toDouble() * instrumental[i]
        }
        return CaseResult(sqrt(err2 / signal2), rms(out.left.copyOfRange(warmup, n)))
    }
}

fun main() {
    val full = runCase(depth = 1f, correlated = false)
    val correlated = runCase(depth = 1f, correlated = true)
    val none = runCase(depth = 0f, correlated = false)

    println("neural depth=1 uncorrelated relative error=${full.relativeError}")
    println("neural depth=1 correlated relative error=${correlated.relativeError}")
    println("neural depth=0 output RMS=${none.outputRms}")

    check(full.relativeError < 1e-3) { "Exact stem subtraction regressed: ${full.relativeError}" }
    check(correlated.relativeError < 1e-3) { "Correlated accompaniment causes over-subtraction: ${correlated.relativeError}" }

    val depthZeroMixRms = run {
        val sr = 48_000
        val n = sr * 3
        val vocal = FloatArray(n) { i ->
            (0.25 * sin(2.0 * PI * 440.0 * i / sr) +
                0.08 * sin(2.0 * PI * 1500.0 * i / sr)).toFloat()
        }
        val inst = FloatArray(n) { i ->
            (0.14 * sin(2.0 * PI * 220.0 * i / sr) +
                0.05 * sin(2.0 * PI * 3100.0 * i / sr)).toFloat()
        }
        val mix = FloatArray(n) { inst[it] + vocal[it] }
        rms(mix.copyOfRange(n / 10, n))
    }
    check(abs(none.outputRms - depthZeroMixRms) < 1e-4) {
        "Depth=0 should bypass neural subtraction"
    }
    println("NEURAL PARAMETER/GAIN REGRESSION PASSED")
}
