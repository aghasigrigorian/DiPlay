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
    class Result(
        val summary: String,
        val raw: ByteArray,
        val cleaned: ByteArray,
        val sampleRate: Int,
        val delayMillis: Int,
        val confidence: Double,
        val removalDb: Double?,
        val calibrated: Boolean,
    )

    /** [clip] is 16-bit little-endian mono PCM at [SAMPLE_RATE]. Blocks for about 30 s; call off the main thread. */
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
        // The full far-end sequence in the order it is handed to the player: the timeline index of a
        // pushed sample is its index in this array.
        val silence = ByteArray(FRAME_BYTES)
        val sequence = ByteArrayOutputStream()
        repeat(PASSES) { sequence.write(clip); repeat(PAUSE_FRAMES) { sequence.write(silence) } }
        val farEnd = toShorts(sequence.toByteArray())
        EchoReference.start(SAMPLE_RATE, 0)
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be opened" }
            check(SpeexEchoCanceller.available) { "The app echo canceller is unavailable on this head unit" }
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
            val writePositions = ArrayList<Long>()
            val playing = AtomicBoolean(true)
            val player = Thread({
                try {
                    var framesWritten = 0L
                    val data = sequence.toByteArray()
                    var offset = 0
                    while (offset < data.size && running.get()) {
                        val n = track.write(data, offset, minOf(FRAME_BYTES, data.size - offset), AudioTrack.WRITE_BLOCKING)
                        if (n <= 0) break
                        framesWritten += n / 2
                        val queued = (framesWritten - runCatching { track.playbackHeadPosition.toLong() }.getOrDefault(0L)).coerceAtLeast(0L)
                        EchoReference.push(data, offset, n, 1, SAMPLE_RATE, queued.toInt(), "echo test")
                        offset += n
                    }
                } finally {
                    playing.set(false)
                }
            }, "echo-test-player").apply { isDaemon = true; start() }

            val frame = ByteArray(FRAME_BYTES)
            val started = System.currentTimeMillis()
            val expectedSeconds = farEnd.size / SAMPLE_RATE + 1
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
                writePositions.add(EchoReference.writePosition())
                onStatus("Echo test: listening… ${(now - started) / 1000} s of about $expectedSeconds")
            }
            running.set(false)
            player.join(3000)
            runCatching { record.stop() }
            runCatching { track.stop() }
            onStatus("Echo test: measuring the echo delay…")
            return analyse(raw.toByteArray(), writePositions, farEnd, onStatus).also { lastResult = it }
        } finally {
            running.set(false)
            record.release()
            track.release()
            EchoReference.stop()
            audioManager?.let { manager ->
                previousCallVolume?.let { runCatching { manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, it, 0) } }
                previousMode?.let { runCatching { manager.mode = it } }
            }
        }
    }

    /**
     * Offline: lines the far end up with the recording (what was handed to the player during each
     * captured frame), measures how much later it came back through the microphone, then runs the
     * canceller over the recording with the reference placed just ahead of that echo — one warm-up
     * pass so the filter has settled, one measured pass that produces the cleaned recording.
     */
    private fun analyse(raw: ByteArray, writePositions: List<Long>, farEnd: ShortArray, onStatus: (String) -> Unit): Result {
        val mic = toShorts(raw)
        // Both streams run on the same audio clock, so the far end is lined up once, from the write
        // position at the first captured frame, and then advances sample for sample with the capture.
        // Using the write position of every frame would add up to a frame of jitter and smear the peak.
        val base = (writePositions.firstOrNull() ?: 0L) - FRAME_SAMPLES
        val aligned = ShortArray(mic.size) { k ->
            val index = base + k
            if (index in 0 until farEnd.size) farEnd[index.toInt()] else 0
        }
        val estimate = EchoDelayEstimator.correlate(mic, aligned, SAMPLE_RATE, MIN_DELAY_MILLIS, MAX_DELAY_MILLIS)
        val delayMillis = estimate.delaySamples * 1000 / SAMPLE_RATE
        val confident = estimate.clear
        onStatus("Echo test: cancelling with a ${delayMillis} ms delay…")
        val leadSamples = if (confident) (estimate.delaySamples - SAMPLE_RATE * SETTLE_MILLIS / 1000).coerceAtLeast(0) else 0
        val tail = if (confident) SpeexEchoCanceller.CALIBRATED_TAIL_MILLIS else SpeexEchoCanceller.TAIL_MILLIS
        val canceller = SpeexEchoCanceller.create(SAMPLE_RATE, FRAME_SAMPLES, tail) ?: throw IllegalStateException("The app echo canceller is unavailable")
        val cleaned = ByteArray(raw.size)
        try {
            val frames = mic.size / FRAME_SAMPLES
            val farFrame = ShortArray(FRAME_SAMPLES)
            val work = ByteArray(FRAME_BYTES)
            repeat(2) { pass ->
                if (pass == 1) canceller.resetStats()
                for (f in 0 until frames) {
                    val base = f * FRAME_SAMPLES
                    System.arraycopy(raw, base * 2, work, 0, FRAME_BYTES)
                    for (i in 0 until FRAME_SAMPLES) {
                        val index = base + i - leadSamples
                        farFrame[i] = if (index >= 0 && index < aligned.size) aligned[index] else 0
                    }
                    canceller.processWithReference(work, farFrame)
                    if (pass == 1) System.arraycopy(work, 0, cleaned, base * 2, FRAME_BYTES)
                }
            }
            val removal = canceller.removalDb()
            val summary = buildString {
                append(if (confident) "echo delay ≈ $delayMillis ms (correlation ${"%.2f".format(estimate.confidence)}, peak ${"%.1f".format(estimate.prominence)}× the noise, calibrated)" else "no clear echo path found (best guess $delayMillis ms, correlation ${"%.2f".format(estimate.confidence)}, peak ${"%.1f".format(estimate.prominence)}×)")
                append(" · canceller removes ")
                append(removal?.let { "${"%.1f".format(it)} dB" } ?: "nothing (no far-end activity)")
                append(" · tail $tail ms · ${mic.size / SAMPLE_RATE} s recorded")
            }
            Log.i(TAG, "echo test: $summary")
            return Result(summary, raw, cleaned, SAMPLE_RATE, delayMillis, estimate.confidence, removal, confident)
        } finally {
            canceller.close()
        }
    }

    private fun toShorts(pcm: ByteArray): ShortArray =
        ShortArray(pcm.size / 2) { i -> ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort() }

    companion object {
        private const val TAG = "xcertplay-echo-test"
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 960
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val PASSES = 2
        private const val PAUSE_FRAMES = 50
        private const val TAIL_MILLIS = 1_000L
        private const val MIN_DELAY_MILLIS = -300
        private const val MAX_DELAY_MILLIS = 2_000
        private const val SETTLE_MILLIS = 60

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
