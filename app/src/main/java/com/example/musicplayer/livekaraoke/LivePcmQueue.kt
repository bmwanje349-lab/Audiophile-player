package com.example.musicplayer.livekaraoke

import java.util.ArrayDeque

/**
 * Bounded stereo PCM queue between MDX inference and AudioTrack.
 *
 * Capacity is measured in audio frames, so inference can run ahead only by a
 * fixed amount and cannot accumulate an entire song in RAM.
 */
internal class LivePcmQueue(
    private val maxFrames: Int,
) {
    init {
        require(maxFrames >= 8_192)
    }

    private val lock = Object()
    private val blocks = ArrayDeque<ShortArray>()
    private var queuedFrames = 0
    private var finished = false
    private var cancelled = false

    fun availableFrames(): Int = synchronized(lock) { queuedFrames }

    fun put(block: ShortArray) {
        require(block.isNotEmpty() && block.size % 2 == 0)
        val frames = block.size / 2
        require(frames <= maxFrames)

        synchronized(lock) {
            while (!cancelled && !finished && queuedFrames + frames > maxFrames) {
                lock.wait()
            }
            check(!cancelled) { "Live PCM queue was cancelled" }
            check(!finished) { "Live PCM queue is already finished" }
            blocks.addLast(block)
            queuedFrames += frames
            lock.notifyAll()
        }
    }

    fun take(): ShortArray? =
        synchronized(lock) {
            while (!cancelled && blocks.isEmpty() && !finished) {
                lock.wait()
            }
            if (cancelled) return null
            if (blocks.isEmpty() && finished) return null

            val block = blocks.removeFirst()
            queuedFrames -= block.size / 2
            lock.notifyAll()
            block
        }

    fun finish() {
        synchronized(lock) {
            if (cancelled) return
            finished = true
            lock.notifyAll()
        }
    }

    fun cancel() {
        synchronized(lock) {
            cancelled = true
            finished = true
            blocks.clear()
            queuedFrames = 0
            lock.notifyAll()
        }
    }
}
