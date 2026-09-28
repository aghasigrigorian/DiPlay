package com.shilapi.xcertplay.media

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EchoReferenceTest {
    @After
    fun tearDown() = EchoReference.stop()

    private fun pcm(vararg samples: Int): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s -> bytes[2 * i] = (s and 0xff).toByte(); bytes[2 * i + 1] = ((s shr 8) and 0xff).toByte() }
        return bytes
    }

    @Test
    fun samplesComeBackOldestFirst() {
        EchoReference.start(16_000)
        EchoReference.push(pcm(1, 2, 3, 4), 0, 8, 1, 16_000)
        val out = ShortArray(3)
        assertEquals(3, EchoReference.pull(out, 3, 16_000))
        assertArrayEquals(shortArrayOf(1, 2, 3), out)
        assertEquals(1, EchoReference.queued())
    }

    @Test
    fun missingSamplesAreSilence() {
        EchoReference.start(16_000)
        EchoReference.push(pcm(5), 0, 2, 1, 16_000)
        val out = ShortArray(4) { 99 }
        assertEquals(1, EchoReference.pull(out, 4, 16_000))
        assertArrayEquals(shortArrayOf(5, 0, 0, 0), out)
    }

    @Test
    fun stereoIsDownmixed() {
        EchoReference.start(16_000)
        EchoReference.push(pcm(100, 300, -50, 50), 0, 8, 2, 16_000)
        val out = ShortArray(2)
        assertEquals(2, EchoReference.pull(out, 2, 16_000))
        assertArrayEquals(shortArrayOf(200, 0), out)
    }

    @Test
    fun mismatchedRateOrStoppedReferenceGivesSilence() {
        EchoReference.start(16_000)
        EchoReference.push(pcm(7, 8), 0, 4, 1, 24_000)
        val out = ShortArray(2) { 1 }
        assertEquals(0, EchoReference.pull(out, 2, 16_000))
        assertArrayEquals(shortArrayOf(0, 0), out)
        EchoReference.push(pcm(7, 8), 0, 4, 1, 16_000)
        EchoReference.stop()
        assertEquals(0, EchoReference.pull(out, 2, 16_000))
    }

    @Test
    fun firstWriteOfACallIsPaddedWithWhatThePlayerStillHolds() {
        EchoReference.start(1_000) // margin = 60 samples at this rate
        EchoReference.push(pcm(9), 0, 2, 1, 1_000, queuedFrames = 10)
        assertEquals(70, EchoReference.prefillSamples)
        assertEquals(71, EchoReference.queued())
        val out = ShortArray(71)
        assertEquals(71, EchoReference.pull(out, 71, 1_000))
        assertEquals(0.toShort(), out[0])
        assertEquals(9.toShort(), out[70])
        EchoReference.push(pcm(3), 0, 2, 1, 1_000, queuedFrames = 500) // no second padding
        assertEquals(1, EchoReference.queued())
    }

    @Test
    fun fullBufferKeepsTheNewestSamples() {
        EchoReference.start(1_000) // 2 000 samples of capacity
        val many = IntArray(2_100) { it }
        EchoReference.push(pcm(*many), 0, many.size * 2, 1, 1_000)
        val out = ShortArray(1)
        assertEquals(1, EchoReference.pull(out, 1, 1_000))
        assertEquals(160.toShort(), out[0]) // 2 100 samples + 60 of margin padding, capacity 2 000
    }
}
