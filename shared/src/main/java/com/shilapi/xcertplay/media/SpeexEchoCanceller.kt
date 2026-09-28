package com.shilapi.xcertplay.media

import android.util.Log
import java.io.Closeable

/**
 * The app's own acoustic echo canceller (speexdsp, BSD) for calls on head units whose firmware has
 * none that works. One 16-bit mono frame at a time: the captured frame and the matching far-end
 * frame from [EchoReference] go in, the cleaned frame comes out in place. While the call runs,
 * [EchoDelayEstimator] measures the real echo delay and the reference position is moved so the
 * echo falls inside the filter's window.
 */
internal class SpeexEchoCanceller private constructor(
    private var handle: Long,
    private val sampleRate: Int,
    private val frameSamples: Int,
) : Closeable {
    private val near = ShortArray(frameSamples)
    private val far = ShortArray(frameSamples)
    private val out = ShortArray(frameSamples)
    private val estimator = EchoDelayEstimator(sampleRate)
    private var framesProcessed = 0
    private var framesWithoutReference = 0
    private var measuredDelayMs = -1
    private var measuredConfidence = 0.0
    private var realignments = 0
    private var lastEstimateFrame = 0

    /** [frame] holds exactly one frame of 16-bit little-endian mono PCM and is rewritten in place. */
    fun process(frame: ByteArray) {
        val current = handle
        if (current == 0L || frame.size < frameSamples * 2) return
        for (i in 0 until frameSamples) {
            near[i] = ((frame[2 * i].toInt() and 0xff) or (frame[2 * i + 1].toInt() shl 8)).toShort()
        }
        val refPos = EchoReference.pull(far, frameSamples, sampleRate)
        if (refPos < 0L || far.all { it.toInt() == 0 }) framesWithoutReference++
        estimator.feed(frame, frameSamples, refPos)
        nativeProcess(current, near, far, out)
        for (i in 0 until frameSamples) {
            val sample = out[i].toInt()
            frame[2 * i] = (sample and 0xff).toByte()
            frame[2 * i + 1] = ((sample shr 8) and 0xff).toByte()
        }
        framesProcessed++
        if (estimator.ready && framesProcessed - lastEstimateFrame >= ESTIMATE_EVERY_FRAMES) {
            lastEstimateFrame = framesProcessed
            estimator.estimate()?.let { apply(it) }
        }
    }

    /** Places the reference [SETTLE_MILLIS] ahead of the measured echo so the filter's window covers it. */
    private fun apply(estimate: EchoDelayEstimator.Estimate) {
        if (estimate.confidence < MIN_CONFIDENCE) return
        measuredConfidence = estimate.confidence
        val extra = estimate.delaySamples - sampleRate * SETTLE_MILLIS / 1000
        val currentLead = EchoReference.lead()
        val totalDelayMs = ((currentLead + estimate.delaySamples) * 1000 / sampleRate).toInt()
        measuredDelayMs = totalDelayMs
        if (kotlin.math.abs(extra) > sampleRate * REALIGN_THRESHOLD_MILLIS / 1000) {
            EchoReference.setLead(currentLead + extra)
            realignments++
            Log.i(TAG, "echo delay ${totalDelayMs} ms (confidence ${"%.2f".format(estimate.confidence)}), reference lead moved to ${EchoReference.lead() * 1000 / sampleRate} ms")
        }
    }

    /** One line for the settings page. */
    fun stats(): String {
        val percent = if (framesProcessed == 0) 0 else framesWithoutReference * 100 / framesProcessed
        val delay = if (measuredDelayMs < 0) "echo delay not measured" else "echo delay ≈ $measuredDelayMs ms (${"%.2f".format(measuredConfidence)})"
        return "$delay · lead ${EchoReference.lead() * 1000 / sampleRate.coerceAtLeast(1)} ms · realigned $realignments× · frames $framesProcessed, $percent% silent reference"
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) nativeDestroy(current)
    }

    companion object {
        private const val TAG = "xcertplay-aec"

        /** Echo path the filter can absorb behind the reference position. */
        const val TAIL_MILLIS = 1_000

        /** How far ahead of the measured echo the reference is placed. */
        private const val SETTLE_MILLIS = 40
        private const val REALIGN_THRESHOLD_MILLIS = 30
        private const val MIN_CONFIDENCE = 0.25
        private const val ESTIMATE_EVERY_FRAMES = 100

        val available: Boolean by lazy {
            runCatching { System.loadLibrary("xcertplay_aec") }
                .onFailure { Log.w(TAG, "app echo canceller library unavailable", it) }
                .isSuccess
        }

        fun create(sampleRate: Int, frameSamples: Int): SpeexEchoCanceller? {
            if (!available || sampleRate <= 0 || frameSamples <= 0) return null
            val filterLength = sampleRate * TAIL_MILLIS / 1000
            val handle = runCatching { nativeCreate(frameSamples, filterLength, sampleRate) }.getOrDefault(0L)
            if (handle == 0L) {
                Log.w(TAG, "app echo canceller could not start rate=$sampleRate frame=$frameSamples")
                return null
            }
            return SpeexEchoCanceller(handle, sampleRate, frameSamples)
        }

        @JvmStatic external fun nativeCreate(frameSize: Int, filterLength: Int, sampleRate: Int): Long
        @JvmStatic external fun nativeProcess(handle: Long, near: ShortArray, far: ShortArray, out: ShortArray)
        @JvmStatic external fun nativeDestroy(handle: Long)
    }
}
