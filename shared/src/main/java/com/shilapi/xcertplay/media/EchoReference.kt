package com.shilapi.xcertplay.media

/**
 * The far end's voice as it is written to the car speakers, kept briefly so the app's echo canceller
 * knows what to subtract from the microphone. A short FIFO of 16-bit mono samples: the renderer pushes
 * what it writes, the microphone uplink pulls one frame per captured frame. When a call starts the FIFO
 * is cleared and, on the first write, padded with as much silence as the player still holds in its
 * buffer plus a small margin, so the reference lines up with what actually leaves the speakers and the
 * canceller's tail only has to cover the cabin.
 */
internal object EchoReference {
    private const val CAPACITY_MILLIS = 2_000

    /** Microphone capture latency plus the cabin: added on top of the player's buffered audio. */
    private const val MARGIN_MILLIS = 60
    private val lock = Any()
    private var rate = 0
    private var buffer = ShortArray(0)
    private var head = 0
    private var size = 0
    private var pendingPrefill = false

    @Volatile var enabled = false
        private set

    /** Silence inserted at the start of the current call to line up with playback, in samples. */
    @Volatile var prefillSamples = 0
        private set

    fun start(sampleRate: Int) {
        synchronized(lock) {
            rate = sampleRate
            buffer = ShortArray(sampleRate * CAPACITY_MILLIS / 1000)
            head = 0
            size = 0
            pendingPrefill = true
            prefillSamples = 0
            enabled = true
        }
    }

    fun stop() {
        synchronized(lock) {
            enabled = false
            size = 0
            head = 0
        }
    }

    /**
     * Called by the renderer with the PCM it just wrote; interleaved 16-bit little-endian.
     * [queuedFrames] is how much audio the player still holds ahead of the speakers at this moment.
     */
    fun push(pcm: ByteArray, offset: Int, length: Int, channels: Int, sampleRate: Int, queuedFrames: Int = 0) {
        if (!enabled || channels <= 0) return
        synchronized(lock) {
            if (rate != sampleRate || buffer.isEmpty()) return
            if (pendingPrefill) {
                pendingPrefill = false
                val silence = (queuedFrames.coerceAtLeast(0) + rate * MARGIN_MILLIS / 1000)
                    .coerceAtMost(buffer.size * 3 / 4)
                repeat(silence) { write(0) }
                prefillSamples = silence
            }
            val frameBytes = 2 * channels
            var index = offset
            val end = offset + length - (length % frameBytes)
            while (index < end) {
                var sum = 0
                repeat(channels) {
                    sum += ((pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8)).toShort().toInt()
                    index += 2
                }
                write((sum / channels).toShort())
            }
        }
    }

    /** Fills [out] with the oldest [count] samples; missing samples are silence. Returns how many were real. */
    fun pull(out: ShortArray, count: Int, sampleRate: Int): Int {
        synchronized(lock) {
            if (!enabled || rate != sampleRate || buffer.isEmpty()) {
                out.fill(0, 0, count)
                return 0
            }
            val available = minOf(count, size)
            val capacity = buffer.size
            var read = (head - size + capacity) % capacity
            for (i in 0 until available) {
                out[i] = buffer[read]
                read = (read + 1) % capacity
            }
            size -= available
            if (available < count) out.fill(0, available, count)
            return available
        }
    }

    /** Samples currently queued ahead of capture, for diagnostics. */
    fun queued(): Int = synchronized(lock) { size }

    private fun write(sample: Short) {
        val capacity = buffer.size
        buffer[head] = sample
        head = (head + 1) % capacity
        if (size < capacity) size++ // full: the oldest sample is overwritten
    }
}
