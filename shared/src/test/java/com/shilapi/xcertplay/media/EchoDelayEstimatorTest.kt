package com.shilapi.xcertplay.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class EchoDelayEstimatorTest {
    @After
    fun tearDown() = EchoReference.stop()

    private val rate = 8_000

    /** Plays noise into the reference, feeds the estimator a microphone that hears it [delay] samples later. */
    private fun measure(delay: Int, scale: Double = 0.5): EchoDelayEstimator.Estimate? {
        val random = Random(7)
        val total = rate * 4
        val farEnd = ShortArray(total) { (random.nextInt(-8_000, 8_000)).toShort() }
        val bytes = ByteArray(total * 2)
        for (i in 0 until total) { bytes[2 * i] = (farEnd[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = ((farEnd[i].toInt() shr 8) and 0xff).toByte() }
        EchoReference.start(rate)
        EchoReference.push(bytes, 0, bytes.size, 1, rate, queuedFrames = 0)
        // The uplink starts reading at write position minus 2.5 s, i.e. reference position 12 000.
        EchoReference.setLead((rate * 2.5).toLong())
        val estimator = EchoDelayEstimator(rate)
        val frame = 160
        val far = ShortArray(frame)
        val mic = ByteArray(frame * 2)
        var estimate: EchoDelayEstimator.Estimate? = null
        while (estimate == null && EchoReference.lead() > frame) {
            val refPos = EchoReference.pull(far, frame, rate)
            for (i in 0 until frame) {
                val source = (refPos + i - delay).toInt()
                val echo = if (source in 0 until total) (farEnd[source] * scale).toInt() else 0
                val v = (echo + random.nextInt(-200, 200)).coerceIn(-32768, 32767)
                mic[2 * i] = (v and 0xff).toByte(); mic[2 * i + 1] = ((v shr 8) and 0xff).toByte()
            }
            estimator.feed(mic, frame, refPos)
            if (estimator.ready) estimate = estimator.estimate()
        }
        return estimate
    }

    @Test
    fun findsAnEchoThatArrivesLater() {
        val estimate = measure(delay = rate * 300 / 1000)
        assertNotNull(estimate)
        assertTrue("confidence ${estimate!!.confidence}", estimate.confidence > 0.5)
        assertTrue("delay ${estimate.delaySamples}", abs(estimate.delaySamples - rate * 300 / 1000) <= rate * 5 / 1000)
    }

    @Test
    fun findsAnEchoThatIsAlreadyLinedUp() {
        val estimate = measure(delay = 0)
        assertNotNull(estimate)
        assertEquals(0, estimate!!.delaySamples)
    }

    @Test
    fun silenceIsNotMeasured() {
        val estimate = measure(delay = 100, scale = 0.0).also { }
        // With only faint noise in the microphone nothing correlates: either null or low confidence.
        assertTrue(estimate == null || estimate.confidence < 0.25)
    }
}
