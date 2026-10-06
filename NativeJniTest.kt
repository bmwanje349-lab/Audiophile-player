package com.bmwanje.audiophile.vocalremover

import kotlin.math.abs
import kotlin.math.max

fun main() {
    val sr = 48_000
    NativeVocalRemover(sr).use { dsp ->
        val n = 8192
        val mixL = FloatArray(n)
        val mixR = FloatArray(n)
        val vocalL = FloatArray(n)
        val vocalR = FloatArray(n)
        mixL[0] = 0.25f
        mixR[0] = 0.25f
        val outL = FloatArray(n)
        val outR = FloatArray(n)

        dsp.setDepth(0f)
        dsp.setDryWet(1f)
        dsp.setStemGainDb(0f)
        dsp.processBlock(mixL, mixR, vocalL, vocalR, outL, outR)

        println("latency=${dsp.latencySamples()}")
        check(dsp.latencySamples() == 1023)
        check(outL.all { it.isFinite() } && outR.all { it.isFinite() })

        val m = FloatArray(5000) { i -> kotlin.math.sin(i * 0.07).toFloat() * 0.15f }
        val z = FloatArray(5000)
        val rendered = dsp.renderOffline(VocalSeparatorCore.Stereo(m, m.copyOf()), VocalSeparatorCore.Stereo(z, z.copyOf()), 257)
        var maxErr = 0f
        for (i in m.indices) maxErr = maxOf(maxErr, kotlin.math.abs(rendered.left[i] - m[i]))
        println("offline identity maxErr=$maxErr")
        check(maxErr < 0.02f)
    }
    println("JNI DSP CREATE/PROCESS/DESTROY TEST PASSED")
}
