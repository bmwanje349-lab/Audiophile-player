package com.bmwanje.audiophile.vocalremover

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

private class IdentityRunner : VocalSeparatorCore.ModelRunner {
    var calls = 0
    override fun run(planarInput: FloatArray): FloatArray {
        calls++
        return planarInput.copyOf()
    }
}

private fun makeInput(n: Int): VocalSeparatorCore.Stereo {
    val l = FloatArray(n) { i -> (0.37 * sin(i * 0.013) + 0.11 * sin(i * 0.071)).toFloat() }
    val r = FloatArray(n) { i -> (0.29 * sin(i * 0.017 + 0.4) - 0.08 * sin(i * 0.053)).toFloat() }
    return VocalSeparatorCore.Stereo(l, r)
}

private fun collectStreaming(input: VocalSeparatorCore.Stereo, splits: IntArray): Pair<VocalSeparatorCore.Stereo, Int> {
    val outL = ArrayList<Float>()
    val outR = ArrayList<Float>()
    val runner = IdentityRunner()
    val stream = VocalSeparatorCore.StreamingSeparator(runner) { block ->
        for (x in block.left) outL.add(x)
        for (x in block.right) outR.add(x)
    }
    var pos = 0
    var splitIndex = 0
    while (pos < input.size) {
        val requested = splits[splitIndex % splits.size]
        val count = minOf(requested, input.size - pos)
        stream.push(
            VocalSeparatorCore.Stereo(
                input.left.copyOfRange(pos, pos + count),
                input.right.copyOfRange(pos, pos + count),
            )
        )
        pos += count
        splitIndex++
    }
    stream.finish()
    check(outL.size == input.size) { "Streaming left length ${outL.size}, expected ${input.size}" }
    check(outR.size == input.size) { "Streaming right length ${outR.size}, expected ${input.size}" }
    val left = FloatArray(outL.size) { outL[it] }
    val right = FloatArray(outR.size) { outR[it] }
    return VocalSeparatorCore.Stereo(left, right) to runner.calls
}

fun main() {
    val lengths = intArrayOf(
        1, 100, 10000, VocalSeparatorCore.OVERLAP_SAMPLES,
        VocalSeparatorCore.MODEL_SAMPLES - 1,
        VocalSeparatorCore.MODEL_SAMPLES,
        VocalSeparatorCore.MODEL_SAMPLES + 1,
        VocalSeparatorCore.STRIDE_SAMPLES * 2,
        VocalSeparatorCore.STRIDE_SAMPLES * 2 + 12345,
        VocalSeparatorCore.MODEL_SAMPLES * 3 + 777,
    )

    for (n in lengths) {
        val input = makeInput(n)
        val (actual, calls) = collectStreaming(input, intArrayOf(1, 257, 4096, 7777, 16384))
        var maxErr = 0.0f
        for (i in 0 until n) {
            maxErr = max(maxErr, abs(actual.left[i] - input.left[i]))
            maxErr = max(maxErr, abs(actual.right[i] - input.right[i]))
        }
        check(maxErr < 3e-6f) { "n=$n streaming identity maxErr=$maxErr calls=$calls" }
        println("n=$n streaming maxErr=$maxErr modelCalls=$calls")
    }
    println("STREAMING SEPARATOR TEST PASSED")
}
