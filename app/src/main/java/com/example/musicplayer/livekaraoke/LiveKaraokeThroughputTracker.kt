package com.example.musicplayer.livekaraoke

/**
 * Measures neural throughput at complete MDX-window cadence rather than on
 * each small PCM block. A model window is emitted in a burst of small blocks;
 * measuring the first block would incorrectly report near-zero throughput and
 * measuring every burst block would incorrectly report unbounded throughput.
 */
internal class LiveKaraokeThroughputTracker(
    private val sampleRate: Int,
    private val calibrationFrames: Long,
    private val rollingWindowFrames: Long,
) {
    private var startedAtNs = 0L
    private var started = false
    private var totalFrames = 0L
    private var lastRateSampleNs = 0L
    private var lastRateSampleFrames = 0L

    var calibratedRate: Double = Double.NaN
        private set

    var rollingRate: Double = Double.NaN
        private set

    val framesEmitted: Long
        get() = totalFrames

    init {
        require(sampleRate > 0)
        require(calibrationFrames > 0L)
        require(rollingWindowFrames > 0L)
    }

    @Synchronized
    fun start(nowNs: Long) {
        if (started) return
        started = true
        startedAtNs = nowNs
    }

    @Synchronized
    fun addOutputFrames(frames: Int, nowNs: Long) {
        require(frames > 0)
        if (!started) start(nowNs)
        totalFrames += frames.toLong()

        // Do not calibrate from only the first model window. Requiring two
        // full windows makes startup representative of sustained inference,
        // including model warm-up and window-to-window overhead.
        if (
            !calibratedRate.isFinite() &&
            totalFrames >= calibrationFrames &&
            nowNs > startedAtNs
        ) {
            val elapsedSeconds =
                (nowNs - startedAtNs).toDouble() / 1_000_000_000.0
            calibratedRate =
                (totalFrames.toDouble() / sampleRate.toDouble()) /
                    elapsedSeconds
            lastRateSampleNs = nowNs
            lastRateSampleFrames = totalFrames
            return
        }

        // Check rolling throughput only after another full model-window worth
        // of output has arrived. This avoids measuring a tiny PCM block at the
        // start of an output burst against several seconds of inference time.
        if (
            calibratedRate.isFinite() &&
            totalFrames - lastRateSampleFrames >= rollingWindowFrames &&
            nowNs > lastRateSampleNs
        ) {
            val deltaFrames = totalFrames - lastRateSampleFrames
            val elapsedSeconds =
                (nowNs - lastRateSampleNs).toDouble() / 1_000_000_000.0
            rollingRate =
                (deltaFrames.toDouble() / sampleRate.toDouble()) /
                    elapsedSeconds
            lastRateSampleFrames = totalFrames
            lastRateSampleNs = nowNs
        }
    }
}
