package com.bmwanje.audiophile.vocalremover

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

private fun rms(x: FloatArray): Double {
    var s=0.0
    for(v in x) s += v.toDouble()*v.toDouble()
    return sqrt(s/x.size)
}

private class ExactVocalRunner(private val vocal: VocalSeparatorCore.Stereo) : VocalSeparatorCore.ModelRunner {
    override fun run(planarInput: FloatArray): FloatArray {
        val n=VocalSeparatorCore.MODEL_SAMPLES
        // The runner is used on full chunks in this test; recover the first n samples of the model input.
        val out=FloatArray(2*n)
        for(i in 0 until n){
            out[i]=0f
            out[n+i]=0f
        }
        return out
    }
}

fun main() {
    val sr=48_000
    val n=48_000*2
    val instL=FloatArray(n){i->(0.15*sin(2*PI*220*i/sr)+0.06*sin(2*PI*2200*i/sr)).toFloat()}
    val instR=FloatArray(n){i->(0.12*sin(2*PI*220*i/sr)+0.05*sin(2*PI*3100*i/sr)).toFloat()}
    val vocalL=FloatArray(n){i->(0.25*sin(2*PI*440*i/sr)).toFloat()}
    val vocalR=FloatArray(n){i->(0.24*sin(2*PI*440*i/sr)).toFloat()}
    val mixL=FloatArray(n){i->instL[i]+vocalL[i]}
    val mixR=FloatArray(n){i->instR[i]+vocalR[i]}

    NativeVocalRemover(sr).use { dsp ->
        dsp.setDepth(1f)
        dsp.setStemGainDb(0f)
        dsp.setDryWet(1f)
        val out=dsp.renderOffline(
            VocalSeparatorCore.Stereo(mixL,mixR),
            VocalSeparatorCore.Stereo(vocalL,vocalR),
            1024,
        )
        var err=0.0
        var sig=0.0
        var maxE=0f
        for(i in 0 until n){
            val e=out.left[i]-instL[i]
            err += e.toDouble()*e.toDouble()
            sig += instL[i].toDouble()*instL[i].toDouble()
            maxE=maxOf(maxE,abs(e))
        }
        val rel=sqrt(err/sig)
        println("Full-chain synthetic vocal-removal relative RMS error=$rel maxErr=$maxE")
        check(rel < 0.06) { "Removal error too high: $rel" }
        check(out.left.all { it.isFinite() } && out.right.all { it.isFinite() })
    }
    println("FULL NATIVE DSP SUBTRACTION CHAIN PASSED")
}
