package com.shilapi.xcertplay.media

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun firstWriteSetsTheLeadFromThePlayersBufferPlusMargin() {
        EchoReference.start(1_000) // margin = 60 samples at this rate
        EchoReference.push(pcm(9), 0, 2, 1, 1_000, queuedFrames = 10)
        assertEquals(70, EchoReference.initialLeadSamples)
        assertEquals(71L, EchoReference.lead())
        val out = ShortArray(71)
        assertEquals(-70L, EchoReference.pull(out, 71, 1_000))
        assertEquals(0.toShort(), out[0])
        assertEquals(9.toShort(), out[70])
        assertEquals(0L, EchoReference.lead())
    }

    @Test
    fun samplesComeBackOldestFirstAndUnwrittenPositionsAreSilence() {
        EchoReference.start(16_000)
        EchoReference.push(pcm(1, 2, 3, 4), 0, 8, 1, 16_000)
        EchoReference.setLead(4)
        val out = ShortArray(6) { 99 }
        assertEquals(0L, EchoReference.pull(out, 6, 16_000))
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 0, 0), out)
        assertEquals(-2L, EchoReference.lead()) // capture ran ahead of playback
    }

    @Test
    fun stereoIsDownmixed() {
        EchoReference.start(16_000)
        EchoReference.push(pcm(100, 300, -50, 50), 0, 8, 2, 16_000)
        EchoReference.setLead(2)
        val out = ShortArray(2)
        EchoReference.pull(out, 2, 16_000)
        assertArrayEquals(shortArrayOf(200, 0), out)
    }

    @Test
    fun mismatchedRateOrStoppedReferenceGivesSilence() {
        EchoReference.start(16_000)
        EchoReference.push(pcm(7, 8), 0, 4, 1, 24_000)
        val out = ShortArray(2) { 1 }
        assertEquals(-1L, EchoReference.pull(out, 2, 24_000))
        assertArrayEquals(shortArrayOf(0, 0), out)
        EchoReference.stop()
        assertEquals(-1L, EchoReference.pull(out, 2, 16_000))
    }

    @Test
    fun copyBeforeReadsTheTimelineAndSilenceOutsideIt() {
        EchoReference.start(16_000)
        EchoReference.push(pcm(1, 2, 3, 4, 5), 0, 10, 1, 16_000)
        val out = ShortArray(4)
        EchoReference.copyBefore(5, 4, out)
        assertArrayEquals(shortArrayOf(2, 3, 4, 5), out)
        EchoReference.copyBefore(2, 4, out)
        assertArrayEquals(shortArrayOf(0, 0, 1, 2), out)
    }

    @Test
    fun theLeadCanBeMovedToLineUpWithAMeasuredEcho() {
        EchoReference.start(1_000)
        EchoReference.push(pcm(*IntArray(500) { it }), 0, 1000, 1, 1_000, queuedFrames = 0)
        EchoReference.setLead(200)
        assertEquals(200L, EchoReference.lead())
        val out = ShortArray(1)
        EchoReference.pull(out, 1, 1_000)
        assertEquals(300.toShort(), out[0])
        assertTrue(EchoReference.lead() < 200L)
    }
}
