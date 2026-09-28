package com.shilapi.xcertplay.media

import android.util.Log
import java.io.Closeable

/**
 * The app's own acoustic echo canceller (speexdsp, BSD) for calls on head units whose firmware has
 * none. Works on one 16-bit mono frame at a time: the captured frame and the matching far-end frame
 * from [EchoReference] go in, the cleaned frame comes out in place.
 */
internal class SpeexEchoCanceller private constructor(
    private var handle: Long,
    private val sampleRate: Int,
    private val frameSamples: Int,
) : Closeable {
    private val near = ShortArray(frameSamples)
    private val far = ShortArray(frameSamples)
    private val out = ShortArray(frameSamples)
    private var framesWithoutReference = 0
    private var framesProcessed = 0

    /** [frame] holds exactly one frame of 16-bit little-endian mono PCM and is rewritten in place. */
    fun process(frame: ByteArray) {
        val current = handle
        if (current == 0L || frame.size < frameSamples * 2) return
        for (i in 0 until frameSamples) {
            near[i] = ((frame[2 * i].toInt() and 0xff) or (frame[2 * i + 1].toInt() shl 8)).toShort()
        }
        val real = EchoReference.pull(far, frameSamples, sampleRate)
        if (real == 0) framesWithoutReference++
        nativeProcess(current, near, far, out)
        for (i in 0 until frameSamples) {
            val sample = out[i].toInt()
            frame[2 * i] = (sample and 0xff).toByte()
            frame[2 * i + 1] = ((sample shr 8) and 0xff).toByte()
        }
        if (++framesProcessed % 500 == 0) {
            Log.i(TAG, "app echo canceller frames=$framesProcessed withoutReference=$framesWithoutReference queued=${EchoReference.queued()}")
        }
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) nativeDestroy(current)
    }

    companion object {
        private const val TAG = "xcertplay-aec"

        /** Echo path the canceller can absorb: playback buffering, the car's amplifier and the cabin. */
        const val TAIL_MILLIS = 600

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
