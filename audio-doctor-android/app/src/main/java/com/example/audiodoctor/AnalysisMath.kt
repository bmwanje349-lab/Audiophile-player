package com.example.audiodoctor

import kotlin.math.*

data class KeyGuess(val name: String, val confidence: String)

object AnalysisMath {
    fun dbfs(value: Double): Double = 20.0 * log10(max(abs(value), 1.0e-15))

    fun correlation(sumL: Double, sumR: Double, sumLL: Double, sumRR: Double, sumLR: Double, count: Long): Double? {
        if (count < 2L) return null
        val n = count.toDouble()
        val cov = sumLR - sumL * sumR / n
        val varL = sumLL - sumL * sumL / n
        val varR = sumRR - sumR * sumR / n
        if (varL <= 1.0e-18 || varR <= 1.0e-18) return null
        return (cov / sqrt(varL * varR)).coerceIn(-1.0, 1.0)
    }

    fun estimateTempo(envelope: List<Double>, framesPerSecond: Double = 20.0): Double? {
        if (envelope.size < (framesPerSecond * 8).toInt()) return null
        val novelty = DoubleArray(envelope.size)
        var mean = 0.0
        for (i in envelope.indices) {
            val difference = max(0.0, envelope[i] - if (i == 0) envelope[i] else envelope[i - 1])
            novelty[i] = difference
            mean += difference
        }
        mean /= novelty.size
        for (i in novelty.indices) novelty[i] -= mean
        var variance = 0.0
        for (v in novelty) variance += v * v
        if (variance < 1.0e-12) return null
        val minLag = max(1, floor(framesPerSecond * 60.0 / 180.0).toInt())
        val maxLag = min(novelty.size / 2, ceil(framesPerSecond * 60.0 / 60.0).toInt())
        var bestLag = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var score = 0.0
            var i = lag
            while (i < novelty.size) {
                score += novelty[i] * novelty[i - lag]
                i++
            }
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0 || bestScore <= 0.0) return null
        var bpm = framesPerSecond * 60.0 / bestLag
        while (bpm < 70.0) bpm *= 2.0
        while (bpm > 175.0) bpm /= 2.0
        return if (bpm in 55.0..200.0) round(bpm * 10.0) / 10.0 else null
    }

    fun estimateKey(magnitudes: DoubleArray, sampleRate: Int, fftSize: Int): KeyGuess? {
        if (magnitudes.size < 32 || sampleRate <= 0 || fftSize <= 0) return null
        val names = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
        val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        val chroma = DoubleArray(12)
        for (bin in 1 until magnitudes.size) {
            val frequency = bin.toDouble() * sampleRate / fftSize
            if (frequency !in 55.0..4000.0) continue
            val midi = round(69.0 + 12.0 * log2(frequency / 440.0)).toInt()
            val pitchClass = ((midi % 12) + 12) % 12
            chroma[pitchClass] += magnitudes[bin]
        }
        val mean = chroma.average()
        var variance = 0.0
        for (v in chroma) variance += (v - mean) * (v - mean)
        val standardDeviation = sqrt(variance / chroma.size)
        if (standardDeviation < 1.0e-12) return null
        for (i in chroma.indices) chroma[i] = (chroma[i] - mean) / standardDeviation

        var bestScore = Double.NEGATIVE_INFINITY
        var secondScore = Double.NEGATIVE_INFINITY
        var bestRoot = 0
        var bestMinor = false
        for (root in 0 until 12) {
            for (mode in 0..1) {
                val profile = if (mode == 0) major else minor
                val profileMean = profile.average()
                var dot = 0.0
                var x2 = 0.0
                var y2 = 0.0
                for (pc in 0 until 12) {
                    val x = chroma[pc]
                    val y = profile[(pc - root + 12) % 12] - profileMean
                    dot += x * y
                    x2 += x * x
                    y2 += y * y
                }
                val score = dot / sqrt(max(1.0e-12, x2 * y2))
                if (score > bestScore) {
                    secondScore = bestScore
                    bestScore = score
                    bestRoot = root
                    bestMinor = mode == 1
                } else if (score > secondScore) {
                    secondScore = score
                }
            }
        }
        val confidence = if (bestScore - secondScore > 0.08) "medium" else "low"
        return KeyGuess(names[bestRoot] + if (bestMinor) " minor" else " major", confidence)
    }

    fun estimateSpectralCutoff(magnitudes: DoubleArray, sampleRate: Int, fftSize: Int): Int? {
        if (sampleRate < 20000 || magnitudes.size < 64) return null
        val reference = ArrayList<Double>()
        for (bin in magnitudes.indices) {
            val frequency = bin.toDouble() * sampleRate / fftSize
            if (frequency in 2000.0..8000.0) reference.add(magnitudes[bin])
        }
        if (reference.isEmpty()) return null
        reference.sort()
        val midReference = reference[reference.size / 2]
        if (midReference <= 1.0e-12) return null
        val maxFrequency = min(sampleRate / 2, 20000)
        var start = 7000
        while (start + 3000 <= maxFrequency) {
            var quietBands = 0
            var bandStart = start
            while (bandStart < start + 3000) {
                val band = ArrayList<Double>()
                for (bin in magnitudes.indices) {
                    val frequency = bin.toDouble() * sampleRate / fftSize
                    if (frequency >= bandStart && frequency < bandStart + 1000) band.add(magnitudes[bin])
                }
                if (band.isEmpty()) break
                band.sort()
                val median = band[band.size / 2]
                if (dbfs(median / midReference) < -34.0) quietBands++
                bandStart += 1000
            }
            if (quietBands == 3) return start
            start += 1000
        }
        return null
    }

    fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val n = real.size
        require(n == imaginary.size && n > 0 && (n and (n - 1)) == 0) { "FFT size must be a positive power of two." }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * Math.PI / length
            val wrStep = cos(angle)
            val wiStep = sin(angle)
            var start = 0
            while (start < n) {
                var wr = 1.0
                var wi = 0.0
                val half = length / 2
                for (k in 0 until half) {
                    val u = start + k
                    val v = u + half
                    val vr = real[v] * wr - imaginary[v] * wi
                    val vi = real[v] * wi + imaginary[v] * wr
                    real[v] = real[u] - vr
                    imaginary[v] = imaginary[u] - vi
                    real[u] += vr
                    imaginary[u] += vi
                    val nextWr = wr * wrStep - wi * wiStep
                    wi = wr * wiStep + wi * wrStep
                    wr = nextWr
                }
                start += length
            }
            length = length shl 1
        }
    }
}
