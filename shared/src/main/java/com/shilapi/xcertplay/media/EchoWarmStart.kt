package com.shilapi.xcertplay.media

import android.util.Log
import java.io.File

/**
 * Lets a call start with an echo canceller that has already learned this car: the echo test's
 * recording (microphone and lined-up far end) is replayed through a fresh canceller in the
 * background when CarPlay connects, and the microphone uplink takes that instance instead of a
 * blank one. The other party talks in short bursts during a real call, too short for a blank
 * filter to settle, which is why calls cancelled far less than the test.
 */
object EchoWarmStart {
    const val MIC_FILE = "echo-test-mic.pcm"
    const val FAR_FILE = "echo-test-far.pcm"

    /** What the echo test learned; [afterPlaybackMillis] is the delay counted from the moment sound leaves the player. */
    data class Calibration(val delayMillis: Int, val afterPlaybackMillis: Int, val directory: File?)

    @Volatile private var pending: SpeexEchoCanceller? = null
    @Volatile private var pendingRate = 0
    @Volatile private var pendingFrame = 0
    @Volatile var lastReport: String = "no warm start yet"
        private set

    /** Prepares a warmed canceller for [sampleRate]/[frameSamples] in the background; safe to call repeatedly. */
    fun prepareAsync(calibration: Calibration?, sampleRate: Int, frameSamples: Int) {
        val directory = calibration?.directory ?: return
        val mic = File(directory, MIC_FILE)
        val far = File(directory, FAR_FILE)
        if (!mic.isFile || !far.isFile) { lastReport = "no echo-test recording stored"; return }
        if (pending != null && pendingRate == sampleRate && pendingFrame == frameSamples) return
        Thread({
            try {
                val started = System.currentTimeMillis()
                val canceller = SpeexEchoCanceller.create(sampleRate, frameSamples, SpeexEchoCanceller.CALIBRATED_TAIL_MILLIS)
                    ?: run { lastReport = "warm start: canceller unavailable"; return@Thread }
                val micBytes = mic.readBytes()
                val farBytes = far.readBytes()
                val frameBytes = frameSamples * 2
                val leadSamples = ((calibration.delayMillis - MicrophoneUplink.CALIBRATED_SETTLE_MILLIS).coerceAtLeast(0) * sampleRate / 1000)
                val frames = minOf(micBytes.size, farBytes.size) / frameBytes
                val work = ByteArray(frameBytes)
                val farFrame = ShortArray(frameSamples)
                for (f in 0 until frames) {
                    val base = f * frameSamples
                    System.arraycopy(micBytes, base * 2, work, 0, frameBytes)
                    for (i in 0 until frameSamples) {
                        val index = base + i - leadSamples
                        farFrame[i] = if (index >= 0 && (index * 2 + 1) < farBytes.size)
                            ((farBytes[index * 2].toInt() and 0xff) or (farBytes[index * 2 + 1].toInt() shl 8)).toShort() else 0
                    }
                    canceller.processWithReference(work, farFrame)
                }
                val learned = canceller.removalDb()
                canceller.resetStats()
                pending?.close()
                pending = canceller
                pendingRate = sampleRate
                pendingFrame = frameSamples
                lastReport = "warm start ready: ${frames * frameSamples / sampleRate} s replayed in ${System.currentTimeMillis() - started} ms, learned ${learned?.let { "%.1f dB".format(it) } ?: "n/a"}"
                Log.i(TAG, lastReport)
            } catch (error: Exception) {
                lastReport = "warm start failed: ${error.message}"
                Log.w(TAG, lastReport, error)
            }
        }, "echo-warm-start").apply { isDaemon = true; start() }
    }

    /** Hands over the warmed canceller if it matches; the caller now owns it. */
    internal fun take(sampleRate: Int, frameSamples: Int): SpeexEchoCanceller? {
        val ready = pending ?: return null
        if (pendingRate != sampleRate || pendingFrame != frameSamples) return null
        pending = null
        return ready
    }

    private const val TAG = "xcertplay-aec"
}
