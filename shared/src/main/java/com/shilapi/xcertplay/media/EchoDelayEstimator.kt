package com.shilapi.xcertplay.media

import kotlin.math.sqrt

/**
 * Measures how long the far end's voice takes to travel from DiPlay's write into the player, through
 * the car's amplifier and cabin, back into the microphone. Pure arithmetic on decimated audio: for a
 * window of captured frames it correlates the microphone against the reference at many offsets and
 * reports the offset with the strongest match. The canceller's adaptive filter only sees a limited
 * window behind the reference position, so the reference must be placed just ahead of the echo.
 */
internal class EchoDelayEstimator(
    private val sampleRate: Int,
    private val decimation: Int = maxOf(1, sampleRate / 8_000),
    private val windowMillis: Int = 1_000,
    private val maxDelayMillis: Int = 1_500,
    private val stepMillis: Int = 5,
    private val maxEarlyMillis: Int = 300,
) {
    /** A measurement: [delaySamples] is the echo delay relative to the reference position of the frame, at full rate. */
    data class Estimate(val delaySamples: Int, val confidence: Double)

    private val decimatedRate = sampleRate / decimation
    private val windowSamples = decimatedRate * windowMillis / 1000
    private val mic = ShortArray(windowSamples)
    private var filled = 0
    private var micStartRefPos = -1L
    private var carry = 0
    private var activeSamples = 0

    /** Feeds one captured frame; [refPos] is the reference position the frame was aligned with. */
    fun feed(frame: ByteArray, frameSamples: Int, refPos: Long, farActive: Boolean = true) {
        if (refPos < 0L) return
        if (filled == 0) { micStartRefPos = refPos; activeSamples = 0 }
        if (farActive) activeSamples += frameSamples / decimation
        var i = carry
        while (i < frameSamples && filled < windowSamples) {
            mic[filled++] = ((frame[2 * i].toInt() and 0xff) or (frame[2 * i + 1].toInt() shl 8)).toShort()
            i += decimation
        }
        carry = (i - frameSamples).coerceAtLeast(0)
    }

    val ready: Boolean get() = filled >= windowSamples

    /** Runs the correlation over the collected window, then clears it. Null when the window is too quiet. */
    fun estimate(): Estimate? {
        if (!ready) return null
        val micEnergy = energy(mic, 0, windowSamples)
        val startRef = micStartRefPos
        val active = activeSamples
        filled = 0
        carry = 0
        activeSamples = 0
        // Only windows where the far end actually talked for most of the time say anything about the echo.
        if (active < windowSamples / 2) return null
        if (micEnergy < MIN_ENERGY * windowSamples) return null
        val maxLag = decimatedRate * maxDelayMillis / 1000
        val maxEarly = decimatedRate * maxEarlyMillis / 1000
        val step = maxOf(1, decimatedRate * stepMillis / 1000)
        // Reference around the window: from maxLag before it to maxEarly after, decimated the same way.
        val refSpan = windowSamples + maxLag + maxEarly
        val ref = ShortArray(refSpan)
        val full = ShortArray(refSpan * decimation)
        EchoReference.copyBefore(startRef + (windowSamples + maxEarly).toLong() * decimation, full.size, full)
        for (i in 0 until refSpan) ref[i] = full[i * decimation]
        // ref[maxLag + t] lines up with mic[t] at lag 0; lag k means the echo arrived k samples later,
        // a negative lag means the reference is already behind the echo.
        var bestLag = 0
        var best = 0.0
        var lag = -maxEarly
        while (lag <= maxLag) {
            var dot = 0.0
            var refEnergy = 0.0
            val base = maxLag - lag
            for (t in 0 until windowSamples) {
                val r = ref[base + t].toDouble()
                dot += mic[t] * r
                refEnergy += r * r
            }
            if (refEnergy > MIN_ENERGY * windowSamples) {
                val score = dot / sqrt(micEnergy * refEnergy)
                if (score > best) { best = score; bestLag = lag }
            }
            lag += step
        }
        return Estimate(bestLag * decimation, best)
    }

    private fun energy(data: ShortArray, offset: Int, count: Int): Double {
        var sum = 0.0
        for (i in offset until offset + count) { val v = data[i].toDouble(); sum += v * v }
        return sum
    }

    companion object {
        /** Mean square below this is treated as silence (about -50 dBFS). */
        private const val MIN_ENERGY = 1_000.0

        /**
         * Offline: how much later than [aligned] the echo appears in [mic]. Both are 16-bit mono at
         * [sampleRate] and describe the same stretch of time; [aligned] is the far end as it was
         * handed to the player at each moment. Coarse search in 5 ms steps, then refined sample by
         * sample around the peak. Returns the delay in samples at [sampleRate] and a 0..1 confidence.
         */
        fun correlate(mic: ShortArray, aligned: ShortArray, sampleRate: Int, minLagMillis: Int, maxLagMillis: Int): Estimate {
            val decimation = maxOf(1, sampleRate / 8_000)
            val rate = sampleRate / decimation
            val n = minOf(mic.size, aligned.size) / decimation
            val m = ShortArray(n) { mic[it * decimation] }
            val r = ShortArray(n) { aligned[it * decimation] }
            val micEnergy = m.sumOf { it.toDouble() * it }
            if (n < rate || micEnergy <= 0.0) return Estimate(0, 0.0)
            fun score(lag: Int): Double {
                var dot = 0.0
                var refEnergy = 0.0
                val from = maxOf(0, lag)
                val to = minOf(n, n + lag)
                if (to - from < rate / 2) return 0.0
                for (t in from until to) {
                    val v = r[t - lag].toDouble()
                    dot += m[t] * v
                    refEnergy += v * v
                }
                return if (refEnergy <= 0.0) 0.0 else dot / sqrt(micEnergy * refEnergy)
            }
            val minLag = rate * minLagMillis / 1000
            val maxLag = rate * maxLagMillis / 1000
            val step = maxOf(1, rate * 5 / 1000)
            var bestLag = 0
            var best = 0.0
            var lag = minLag
            while (lag <= maxLag) {
                val s = score(lag)
                if (s > best) { best = s; bestLag = lag }
                lag += step
            }
            for (fine in (bestLag - step + 1) until (bestLag + step)) {
                if (fine < minLag || fine > maxLag) continue
                val s = score(fine)
                if (s > best) { best = s; bestLag = fine }
            }
            return Estimate(bestLag * decimation, best)
        }
    }
}
