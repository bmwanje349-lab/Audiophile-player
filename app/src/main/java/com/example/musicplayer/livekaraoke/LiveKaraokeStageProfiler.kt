package com.example.musicplayer.livekaraoke

import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Low-overhead stage profiler for the live pipeline. */
internal class LiveKaraokeStageProfiler {
    enum class Stage {
        DECODE_LOOP,
        NEURAL_PIPELINE,
        MDX_STFT,
        ONNX_INFERENCE,
        MDX_ISTFT,
        NATIVE_DSP,
        QUEUE_ENQUEUE,
        AUDIOTRACK_WRITE,
    }

    private class Stats {
        private var count = 0L
        private var totalNs = 0L
        private var maxNs = 0L
        private val samples = ArrayDeque<Long>(64)

        @Synchronized
        fun add(elapsedNs: Long) {
            val value = elapsedNs.coerceAtLeast(0L)
            count += 1L
            totalNs += value
            maxNs = maxOf(maxNs, value)
            if (samples.size == 64) samples.removeFirst()
            samples.addLast(value)
        }

        @Synchronized
        fun summary(): String {
            if (samples.isEmpty()) return "n/a"
            val sorted = samples.toLongArray().sortedArray()
            fun pct(p: Double): Long {
                val index = ((sorted.size - 1).toDouble() * p).toInt()
                    .coerceIn(0, sorted.lastIndex)
                return sorted[index]
            }
            return "n=$count, p50=${micros(pct(0.50))}us, " +
                "p95=${micros(pct(0.95))}us, max=${micros(maxNs)}us"
        }

        private fun micros(ns: Long): Long = (ns / 1_000L).coerceAtLeast(0L)
    }

    private val stats = Stage.values().associateWith { Stats() }
    private val sourceFrames = AtomicLong(0L)
    private val neuralWallNs = AtomicLong(0L)

    fun markSourceFrames(frames: Int) {
        if (frames > 0) sourceFrames.addAndGet(frames.toLong())
    }

    fun record(stage: Stage, elapsedNs: Long) {
        stats[stage]?.add(elapsedNs)
        if (stage == Stage.NEURAL_PIPELINE) {
            neuralWallNs.addAndGet(elapsedNs.coerceAtLeast(0L))
        }
    }

    inline fun <T> measure(stage: Stage, block: () -> T): T {
        val start = System.nanoTime()
        return try { block() } finally { record(stage, System.nanoTime() - start) }
    }

    fun sourceFrames(): Long = sourceFrames.get()

    fun producerRate(sourceSampleRate: Int): Double {
        require(sourceSampleRate > 0)
        val wallSeconds = neuralWallNs.get().toDouble() / 1_000_000_000.0
        if (wallSeconds <= 0.0) return Double.NaN
        return (sourceFrames.get().toDouble() / sourceSampleRate.toDouble()) / wallSeconds
    }

    fun report(sourceSampleRate: Int): String {
        val rate = producerRate(sourceSampleRate)
        val b = StringBuilder("Live profile: producerRTF=")
        if (rate.isFinite()) b.append(String.format(Locale.US, "%.3fx", rate)) else b.append("n/a")
        b.append(", sourceFrames=").append(sourceFrames())
        for (stage in Stage.values()) {
            b.append("; ").append(stage.name).append(": ").append(stats[stage]?.summary() ?: "n/a")
        }
        return b.toString()
    }
}
