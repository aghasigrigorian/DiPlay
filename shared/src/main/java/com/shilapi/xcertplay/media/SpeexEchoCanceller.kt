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
    private val tailMillis: Int,
    private val liveRealign: Boolean,
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
    private val history = ArrayDeque<String>()
    private var nearEnergy = 0.0
    private var outEnergy = 0.0
    private var activeFrames = 0

    /** [frame] holds exactly one frame of 16-bit little-endian mono PCM and is rewritten in place. */
    fun process(frame: ByteArray) {
        val current = handle
        if (current == 0L || frame.size < frameSamples * 2) return
        for (i in 0 until frameSamples) {
            near[i] = ((frame[2 * i].toInt() and 0xff) or (frame[2 * i + 1].toInt() shl 8)).toShort()
        }
        val refPos = EchoReference.pull(far, frameSamples, sampleRate)
        val farActive = refPos >= 0L && energy(far) > ACTIVE_ENERGY * frameSamples
        if (!farActive) framesWithoutReference++
        estimator.feed(frame, frameSamples, refPos, farActive)
        run(current, farActive)
        for (i in 0 until frameSamples) {
            val sample = out[i].toInt()
            frame[2 * i] = (sample and 0xff).toByte()
            frame[2 * i + 1] = ((sample shr 8) and 0xff).toByte()
        }
        if (estimator.ready && framesProcessed - lastEstimateFrame >= ESTIMATE_EVERY_FRAMES) {
            lastEstimateFrame = framesProcessed
            estimator.estimate()?.let { apply(it) }
        }
    }

    /** Offline variant: the far-end frame is supplied by the caller instead of read from [EchoReference]. */
    fun processWithReference(frame: ByteArray, farFrame: ShortArray) {
        val current = handle
        if (current == 0L || frame.size < frameSamples * 2) return
        for (i in 0 until frameSamples) {
            near[i] = ((frame[2 * i].toInt() and 0xff) or (frame[2 * i + 1].toInt() shl 8)).toShort()
            far[i] = farFrame[i]
        }
        val farActive = energy(far) > ACTIVE_ENERGY * frameSamples
        if (!farActive) framesWithoutReference++
        run(current, farActive)
        for (i in 0 until frameSamples) {
            val sample = out[i].toInt()
            frame[2 * i] = (sample and 0xff).toByte()
            frame[2 * i + 1] = ((sample shr 8) and 0xff).toByte()
        }
    }

    private fun run(current: Long, farActive: Boolean) {
        nativeProcess(current, near, far, out)
        if (farActive) {
            nearEnergy += energy(near)
            outEnergy += energy(out)
            activeFrames++
        }
        framesProcessed++
    }

    /** Forgets the removal statistics (used between a warm-up pass and a measured pass). */
    fun resetStats() {
        nearEnergy = 0.0; outEnergy = 0.0; activeFrames = 0; framesProcessed = 0; framesWithoutReference = 0
    }

    /** dB the canceller took off the microphone while the far end talked; null when nothing was active. */
    fun removalDb(): Double? = if (activeFrames == 0 || outEnergy <= 0.0) null else 10 * kotlin.math.log10(nearEnergy / outEnergy)

    /** Places the reference [SETTLE_MILLIS] ahead of the measured echo so the filter's window covers it. */
    private fun apply(estimate: EchoDelayEstimator.Estimate) {
        val currentLead = EchoReference.lead()
        // Delay from writing the far end into the player to hearing it in the microphone.
        val totalDelayMs = ((currentLead + estimate.delaySamples) * 1000 / sampleRate).toInt()
        if (history.size >= 6) history.removeFirst()
        history.addLast("${totalDelayMs}ms/${"%.2f".format(estimate.confidence)}")
        if (estimate.confidence < MIN_CONFIDENCE) return
        measuredConfidence = estimate.confidence
        measuredDelayMs = totalDelayMs
        if (!liveRealign) return
        val extra = estimate.delaySamples - sampleRate * SETTLE_MILLIS / 1000
        if (kotlin.math.abs(extra) > sampleRate * REALIGN_THRESHOLD_MILLIS / 1000) {
            EchoReference.setLead(currentLead + extra)
            realignments++
            Log.i(TAG, "echo delay ${totalDelayMs} ms (confidence ${"%.2f".format(estimate.confidence)}), reference lead moved to ${EchoReference.lead() * 1000 / sampleRate} ms")
        }
    }

    private fun energy(samples: ShortArray): Double {
        var sum = 0.0
        for (s in samples) { val v = s.toDouble(); sum += v * v }
        return sum
    }

    /** One line for the settings page. */
    fun stats(): String {
        val percent = if (framesProcessed == 0) 0 else framesWithoutReference * 100 / framesProcessed
        val delay = if (measuredConfidence < MIN_CONFIDENCE) "live echo delay not confirmed" else "live echo delay ≈ $measuredDelayMs ms (${"%.2f".format(measuredConfidence)})"
        val removed = removalDb()?.let { "removes ${"%.1f".format(it)} dB while the far end talks ($activeFrames frames)" } ?: "nothing to cancel"
        return "$delay · estimates [${history.joinToString(" ")}] · lead ${EchoReference.lead() * 1000 / sampleRate.coerceAtLeast(1)} ms · tail $tailMillis ms · realigned $realignments× · $removed · frames $framesProcessed, $percent% silent reference · mic $sampleRate Hz · reference from ${EchoReference.describeSources()}"
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) nativeDestroy(current)
    }

    companion object {
        private const val TAG = "xcertplay-aec"

        /** Echo path the filter can absorb behind the reference position when nothing is known about it. */
        const val TAIL_MILLIS = 1_000

        /** Tail once the echo delay is calibrated and the reference sits just ahead of the echo. */
        const val CALIBRATED_TAIL_MILLIS = 400

        /** How far ahead of the measured echo the reference is placed. */
        private const val SETTLE_MILLIS = 40
        private const val REALIGN_THRESHOLD_MILLIS = 30
        private const val MIN_CONFIDENCE = 0.5
        private const val ESTIMATE_EVERY_FRAMES = 100

        /** Mean square above which a reference frame counts as the far end talking (about -50 dBFS). */
        private const val ACTIVE_ENERGY = 1_000.0

        val available: Boolean by lazy {
            runCatching { System.loadLibrary("xcertplay_aec") }
                .onFailure { Log.w(TAG, "app echo canceller library unavailable", it) }
                .isSuccess
        }

        fun create(sampleRate: Int, frameSamples: Int, tailMillis: Int = TAIL_MILLIS, liveRealign: Boolean = false): SpeexEchoCanceller? {
            if (!available || sampleRate <= 0 || frameSamples <= 0) return null
            val filterLength = sampleRate * tailMillis / 1000
            val handle = runCatching { nativeCreate(frameSamples, filterLength, sampleRate) }.getOrDefault(0L)
            if (handle == 0L) {
                Log.w(TAG, "app echo canceller could not start rate=$sampleRate frame=$frameSamples")
                return null
            }
            return SpeexEchoCanceller(handle, sampleRate, frameSamples, tailMillis, liveRealign)
        }

        @JvmStatic external fun nativeCreate(frameSize: Int, filterLength: Int, sampleRate: Int): Long
        @JvmStatic external fun nativeProcess(handle: Long, near: ShortArray, far: ShortArray, out: ShortArray)
        @JvmStatic external fun nativeDestroy(handle: Long)
    }
}
