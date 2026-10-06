package test

import com.bmwanje.audiophile.vocalremover.VocalSeparatorCore
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private fun check(ok: Boolean, msg: String) { require(ok) { msg } }
private fun rms(x: FloatArray): Double {
    var s = 0.0
    for (v in x) s += v.toDouble() * v.toDouble()
    return sqrt(s / x.size)
}
private fun maxErr(a: FloatArray, b: FloatArray): Float {
    var e = 0f
    for (i in a.indices) e = maxOf(e, abs(a[i] - b[i]))
    return e
}

private class ScaleRunner(private val scale: Float) : VocalSeparatorCore.ModelRunner {
    override fun run(planarInput: FloatArray): FloatArray = FloatArray(planarInput.size) { planarInput[it] * scale }
}

fun main() {
    val n = 500_000
    val left = FloatArray(n) { i -> (0.4 * sin(2.0 * PI * 997.0 * i / 44100.0)).toFloat() }
    val right = FloatArray(n) { i -> (0.3 * cos(2.0 * PI * 631.0 * i / 44100.0)).toFloat() }
    val input = VocalSeparatorCore.Stereo(left, right)

    val runner = ScaleRunner(0.25f)
    val sep = VocalSeparatorCore.separateAtModelRate(input, runner)
    check(maxErr(sep.left, left.map { it * 0.25f }.toFloatArray()) < 2e-5f, "OLA left mismatch")
    val expR = right.map { it * 0.25f }.toFloatArray()
    var mi=0; var me=0f
    for (i in expR.indices){ val e=abs(expR[i]-sep.right[i]); if(e>me){me=e;mi=i}}
    println("right OLA max err=$me at $mi actual=${sep.right[mi]} expected=${expR[mi]}")
    check(me < 2e-4f, "OLA right mismatch")

    val sameRate = VocalSeparatorCore.WindowedSincResampler.resample(input, 44100, 44100)
    check(maxErr(input.left, sameRate.left) == 0f, "same-rate left changed")
    check(maxErr(input.right, sameRate.right) == 0f, "same-rate right changed")

    val shortN = 22050
    val sL = FloatArray(shortN) { i -> (0.5 * sin(2.0 * PI * 1000.0 * i / 44100.0)).toFloat() }
    val sR = FloatArray(shortN) { i -> (0.5 * sin(2.0 * PI * 4000.0 * i / 44100.0)).toFloat() }
    val short = VocalSeparatorCore.Stereo(sL, sR)
    val upDown = VocalSeparatorCore.WindowedSincResampler.resample(
        VocalSeparatorCore.WindowedSincResampler.resample(short, 44100, 48000),
        48000,
        44100,
    )
    val errL = rms(FloatArray(shortN) { i -> upDown.left[i] - sL[i] }) / rms(sL)
    val errR = rms(FloatArray(shortN) { i -> upDown.right[i] - sR[i] }) / rms(sR)
    check(errL < 0.01, "resampler left relative RMS error too high: $errL")
    check(errR < 0.01, "resampler right relative RMS error too high: $errR")

    val rng = Random(12345)
    val randL = FloatArray(50_000) { rng.nextFloat() * 2f - 1f }
    val randR = FloatArray(50_000) { rng.nextFloat() * 2f - 1f }
    val randomIn = VocalSeparatorCore.Stereo(randL, randR)
    val randomOut = VocalSeparatorCore.WindowedSincResampler.resample(randomIn, 48000, 44100)
    check(randomOut.left.all { it.isFinite() } && randomOut.right.all { it.isFinite() }, "nonfinite resampler output")
    check(randomOut.size == kotlin.math.round(50_000.0 * 44100.0 / 48000.0).toInt(), "wrong resampled length")

    // 18 kHz content should be strongly attenuated when downsampling 44.1 -> 16 kHz.
    val hfN = 44_100
    val hf = FloatArray(hfN) { i -> (0.7 * sin(2.0 * PI * 18_000.0 * i / 44_100.0)).toFloat() }
    val hfOut = VocalSeparatorCore.WindowedSincResampler.resample(
        VocalSeparatorCore.Stereo(hf, hf.copyOf()), 44_100, 16_000
    )
    check(rms(hfOut.left) < 0.20, "anti-alias rejection is too weak: ${rms(hfOut.left)}")

    println("ALL VOCAL SEPARATOR CORE TESTS PASSED")
    println("OLA max err = ${maxErr(sep.left, left.map { it * 0.25f }.toFloatArray())}")
    println("Resampler round-trip RMS errors = $errL / $errR")
    println("HF downsample RMS = ${rms(hfOut.left)}")
}
