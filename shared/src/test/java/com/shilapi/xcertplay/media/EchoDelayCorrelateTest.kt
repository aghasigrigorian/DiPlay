package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class EchoDelayCorrelateTest {
    @Test
    fun findsTheDelayOfAnEchoInARecording() {
        val rate = 48_000
        val random = Random(3)
        val far = ShortArray(rate * 4) { random.nextInt(-6_000, 6_000).toShort() }
        val delay = rate * 320 / 1000
        val mic = ShortArray(far.size) { i -> val src = i - delay; (((if (src >= 0) far[src] * 0.4 else 0.0)) + random.nextInt(-300, 300)).toInt().toShort() }
        val estimate = EchoDelayEstimator.correlate(mic, far, rate, -300, 2_000)
        assertTrue("confidence ${estimate.confidence}", estimate.confidence > 0.6)
        assertTrue("prominence ${estimate.prominence}", estimate.prominence > 4.0 && estimate.clear)
        assertTrue("delay ${estimate.delaySamples}", abs(estimate.delaySamples - delay) <= rate * 2 / 1000)
    }

    @Test
    fun silenceHasNoConfidence() {
        val rate = 48_000
        val mic = ShortArray(rate * 2) { (it % 7 - 3).toShort() }
        val far = ShortArray(rate * 2) { (it % 11 - 5).toShort() }
        val estimate = EchoDelayEstimator.correlate(mic, far, rate, -300, 2_000)
        assertTrue(estimate.confidence < 0.5)
        assertEquals(0, estimate.delaySamples.coerceAtMost(0))
    }
}
