package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exercises the call microphone path without a call: plays a recorded voice through the car
 * speakers on the call route, captures the microphone, runs the app's echo canceller over it
 * exactly as during a call, and keeps both the raw and the cleaned recording so the driver can
 * listen to what the other party would have heard.
 */
class EchoSelfTest(private val audioManager: AudioManager?) {
    class Result(val summary: String, val raw: ByteArray, val cleaned: ByteArray, val sampleRate: Int)

    /** [clip] is 16-bit little-endian mono PCM at [SAMPLE_RATE]. Blocks for about 25 s; call off the main thread. */
    fun run(clip: ByteArray, onStatus: (String) -> Unit): Result {
        check(running.compareAndSet(false, true)) { "Echo test already running" }
        var previousMode: Int? = null
        var previousCallVolume: Int? = null
        val channelIn = AudioFormat.CHANNEL_IN_MONO
        val recordBuffer = maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, channelIn, AudioFormat.ENCODING_PCM_16BIT) * 2, FRAME_BYTES * 8)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(channelIn).build())
            .setBufferSizeInBytes(recordBuffer)
            .build()
        val trackBuffer = maxOf(AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME_BYTES * 5)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(trackBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        EchoReference.start(SAMPLE_RATE)
        val canceller = SpeexEchoCanceller.create(SAMPLE_RATE, FRAME_SAMPLES)
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be opened" }
            checkNotNull(canceller) { "The app echo canceller is unavailable on this head unit" }
            audioManager?.let { manager ->
                runCatching {
                    previousMode = manager.mode
                    manager.mode = AudioManager.MODE_IN_COMMUNICATION
                    val stream = AudioManager.STREAM_VOICE_CALL
                    previousCallVolume = manager.getStreamVolume(stream)
                    manager.setStreamVolume(stream, manager.getStreamMaxVolume(stream), 0)
                }
            }
            record.startRecording()
            track.play()
            val raw = ByteArrayOutputStream()
            val cleaned = ByteArrayOutputStream()
            val playing = AtomicBoolean(true)
            val player = Thread({
                try {
                    var framesWritten = 0L
                    fun feed(data: ByteArray, offset: Int, length: Int) {
                        var done = 0
                        while (done < length && running.get()) {
                            val n = track.write(data, offset + done, length - done, AudioTrack.WRITE_BLOCKING)
                            if (n <= 0) break
                            framesWritten += n / 2
                            val queued = (framesWritten - runCatching { track.playbackHeadPosition.toLong() }.getOrDefault(0L)).coerceAtLeast(0L)
                            EchoReference.push(data, offset + done, n, 1, SAMPLE_RATE, queued.toInt(), "echo test")
                            done += n
                        }
                    }
                    val silence = ByteArray(FRAME_BYTES)
                    repeat(PASSES) {
                        var offset = 0
                        while (offset < clip.size && running.get()) {
                            val n = minOf(FRAME_BYTES, clip.size - offset)
                            feed(clip, offset, n)
                            offset += n
                        }
                        repeat(PAUSE_FRAMES) { if (running.get()) feed(silence, 0, FRAME_BYTES) }
                    }
                } finally {
                    playing.set(false)
                }
            }, "echo-test-player").apply { isDaemon = true; start() }

            val frame = ByteArray(FRAME_BYTES)
            val started = System.currentTimeMillis()
            var tailDeadline = -1L
            while (running.get()) {
                val now = System.currentTimeMillis()
                if (!playing.get()) {
                    if (tailDeadline < 0) tailDeadline = now + TAIL_MILLIS
                    else if (now > tailDeadline) break
                }
                var filled = 0
                while (filled < frame.size) {
                    val n = record.read(frame, filled, frame.size - filled, AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    filled += n
                }
                if (filled < frame.size) break
                raw.write(frame, 0, frame.size)
                canceller.process(frame)
                cleaned.write(frame, 0, frame.size)
                if (((now - started) / 1000) % 2 == 0L) onStatus("Echo test running… ${(now - started) / 1000} s of about ${(clip.size / FRAME_BYTES + PAUSE_FRAMES) * PASSES / 50}")
            }
            running.set(false)
            player.join(3000)
            val result = Result(canceller.stats(), raw.toByteArray(), cleaned.toByteArray(), SAMPLE_RATE)
            lastResult = result
            Log.i(TAG, "echo test: ${result.summary}")
            return result
        } finally {
            running.set(false)
            runCatching { record.stop() }
            record.release()
            runCatching { track.stop() }
            track.release()
            canceller?.close()
            EchoReference.stop()
            audioManager?.let { manager ->
                previousCallVolume?.let { runCatching { manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, it, 0) } }
                previousMode?.let { runCatching { manager.mode = it } }
            }
        }
    }

    companion object {
        private const val TAG = "xcertplay-echo-test"
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 960
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val PASSES = 2
        private const val PAUSE_FRAMES = 50
        private const val TAIL_MILLIS = 1_000L

        private val running = AtomicBoolean(false)
        val isRunning: Boolean get() = running.get()

        @Volatile var lastResult: Result? = null

        /** Plays a recording through the media route; blocks until it has been heard. */
        fun play(pcm: ByteArray, sampleRate: Int) {
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuffer, 8_192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                track.play()
                var offset = 0
                while (offset < pcm.size) {
                    val n = track.write(pcm, offset, minOf(8_192, pcm.size - offset), AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) break
                    offset += n
                }
                Thread.sleep(300)
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }

        /** Extracts 16-bit mono PCM from a RIFF/WAVE file, walking its chunks (some encoders add padding chunks). */
        fun pcmFromWav(bytes: ByteArray, expectedRate: Int): ByteArray {
            require(bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not a WAVE file" }
            var offset = 12
            var rate = 0
            var channels = 0
            var bits = 0
            while (offset + 8 <= bytes.size) {
                val id = String(bytes, offset, 4, Charsets.US_ASCII)
                val size = (bytes[offset + 4].toInt() and 0xff) or ((bytes[offset + 5].toInt() and 0xff) shl 8) or
                    ((bytes[offset + 6].toInt() and 0xff) shl 16) or ((bytes[offset + 7].toInt() and 0xff) shl 24)
                val body = offset + 8
                when (id) {
                    "fmt " -> {
                        channels = (bytes[body + 2].toInt() and 0xff) or ((bytes[body + 3].toInt() and 0xff) shl 8)
                        rate = (bytes[body + 4].toInt() and 0xff) or ((bytes[body + 5].toInt() and 0xff) shl 8) or
                            ((bytes[body + 6].toInt() and 0xff) shl 16) or ((bytes[body + 7].toInt() and 0xff) shl 24)
                        bits = (bytes[body + 14].toInt() and 0xff) or ((bytes[body + 15].toInt() and 0xff) shl 8)
                    }
                    "data" -> {
                        require(channels == 1 && bits == 16 && rate == expectedRate) { "Expected 16-bit mono at $expectedRate Hz, got $bits-bit ${channels}ch $rate Hz" }
                        return bytes.copyOfRange(body, minOf(bytes.size, body + size))
                    }
                }
                offset = body + size + (size and 1)
            }
            throw IllegalArgumentException("No audio data in the WAVE file")
        }
    }
}
