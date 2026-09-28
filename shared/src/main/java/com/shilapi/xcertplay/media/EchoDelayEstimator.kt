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
) {
    /** A measurement: [delaySamples] is the echo delay relative to the reference position of the frame, at full rate. */
    data class Estimate(val delaySamples: Int, val confidence: Double)

    private val decimatedRate = sampleRate / decimation
    private val windowSamples = decimatedRate * windowMillis / 1000
    private val mic = ShortArray(windowSamples)
    private var filled = 0
    private var micStartRefPos = -1L
    private var carry = 0

    /** Feeds one captured frame; [refPos] is the reference position the frame was aligned with. */
    fun feed(frame: ByteArray, frameSamples: Int, refPos: Long) {
        if (refPos < 0L) return
        if (filled == 0) micStartRefPos = refPos
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
        filled = 0
        carry = 0
        if (micEnergy < MIN_ENERGY * windowSamples) return null
        val maxLag = decimatedRate * maxDelayMillis / 1000
        val step = maxOf(1, decimatedRate * stepMillis / 1000)
        // Reference around the window: from maxLag before it to a little after, decimated the same way.
        val refSpan = windowSamples + maxLag
        val ref = ShortArray(refSpan)
        val full = ShortArray(refSpan * decimation)
        EchoReference.copyBefore(startRef + windowSamples.toLong() * decimation, full.size, full)
        for (i in 0 until refSpan) ref[i] = full[i * decimation]
        // ref[maxLag + t] lines up with mic[t] at lag 0; lag k means the echo arrived k samples later.
        var bestLag = 0
        var best = 0.0
        var lag = 0
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

    private companion object {
        /** Mean square below this is treated as silence (about -50 dBFS). */
        const val MIN_ENERGY = 1_000.0
    }
}
