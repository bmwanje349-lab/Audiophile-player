package com.bmwanje.audiophile.vocalremover

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.sqrt

private fun rms(x: FloatArray): Double {
    var s=0.0
    for (v in x) s += v.toDouble()*v.toDouble()
    return sqrt(s/x.size)
}
private fun maxErr(a: FloatArray,b:FloatArray):Float {
    var e=0f
    for(i in a.indices)e=maxOf(e,abs(a[i]-b[i]))
    return e
}

fun main(){
    for(sr in intArrayOf(44_100,48_000,96_000)){
        val n=sr/2
        val l=FloatArray(n){i->(0.12*sin(2*PI*311*i/sr)+0.04*sin(2*PI*5300*i/sr)).toFloat()}
        val r=FloatArray(n){i->(0.10*sin(2*PI*311*i/sr)+0.05*sin(2*PI*4200*i/sr)).toFloat()}
        val z=FloatArray(n)
        NativeVocalRemover(sr).use { dsp ->
            dsp.setDepth(0f)
            dsp.setDryWet(1f)
            val a=dsp.renderOffline(VocalSeparatorCore.Stereo(l,r),VocalSeparatorCore.Stereo(z,z.copyOf()),257)
            val b=dsp.renderOffline(VocalSeparatorCore.Stereo(l,r),VocalSeparatorCore.Stereo(z,z.copyOf()),4096)
            val e=maxErr(a.left,b.left).coerceAtLeast(maxErr(a.right,b.right))
            println("sr=$sr block invariance max error=$e")
            check(e < 1e-6f)
            check(maxErr(a.left,l) < 2e-4f)
            check(maxErr(a.right,r) < 2e-4f)
            check(a.left.all{it.isFinite()} && a.right.all{it.isFinite()})
        }
    }
    println("NATIVE MULTI-SR / BLOCK / RESET REGRESSION PASSED")
}
