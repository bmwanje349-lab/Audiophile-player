package com.example.musicplayer.livekaraoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LivePcmQueueTest {

    @Test
    fun queueNeverExceedsConfiguredFrameBudget() {
        val queue = LivePcmQueue(maxFrames = 16_384)
        val block = ShortArray(8_192 * 2)

        queue.put(block)
        queue.put(block)

        assertEquals(16_384, queue.availableFrames())

        queue.finish()

        assertEquals(block.size, queue.take()!!.size)
        assertEquals(block.size, queue.take()!!.size)
        assertEquals(0, queue.availableFrames())
        assertNull(queue.take())
    }

    @Test
    fun producerBlocksAtBoundAndResumesAfterConsumerDrains() {
        val queue = LivePcmQueue(maxFrames = 8_192)
        val block = ShortArray(8_192 * 2)

        queue.put(block)

        val attempted = CountDownLatch(1)
        val completed = CountDownLatch(1)

        val producer =
            thread(start = true, name = "queue-test-producer") {
                attempted.countDown()
                queue.put(block)
                completed.countDown()
            }

        assertEquals(
            true,
            attempted.await(1, TimeUnit.SECONDS),
        )

        assertEquals(
            false,
            completed.await(100, TimeUnit.MILLISECONDS),
        )

        assertEquals(
            block.size,
            queue.take()!!.size,
        )

        assertEquals(
            true,
            completed.await(1, TimeUnit.SECONDS),
        )

        queue.cancel()
        producer.join(1_000)
    }

    @Test
    fun cancelUnblocksProducerAndDropsBufferedAudio() {
        val queue = LivePcmQueue(maxFrames = 8_192)
        val block = ShortArray(8_192 * 2)

        queue.put(block)

        val producer =
            thread(start = true, name = "queue-test-cancel") {
                runCatching {
                    queue.put(block)
                }
            }

        Thread.sleep(30)
        queue.cancel()
        producer.join(1_000)

        assertEquals(0, queue.availableFrames())
        assertNull(queue.take())
    }
}
