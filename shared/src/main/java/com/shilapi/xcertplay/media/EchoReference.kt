package com.shilapi.xcertplay.media

/**
 * The far end's voice as DiPlay writes it to the car speakers, kept on a timeline so the app's echo
 * canceller can read it at any offset. The renderer appends what it writes; the microphone uplink
 * reads one frame per captured frame from a read position that trails the write position by the
 * current *lead*. The lead starts at whatever the player still held in its buffer plus a margin and
 * is corrected during the call by [EchoDelayEstimator], which measures how long the speakers' sound
 * takes to come back through the microphone.
 */
internal object EchoReference {
    private const val CAPACITY_MILLIS = 4_000

    /** Microphone capture latency plus the cabin, added on top of the player's buffered audio. */
    private const val MARGIN_MILLIS = 60
    private val lock = Any()
    private var rate = 0
    private var buffer = ShortArray(0)
    private var writePos = 0L
    private var readPos = 0L
    private var pendingPrefill = false

    @Volatile var enabled = false
        private set

    /** Silence assumed between writing a sample and hearing it, in samples, at the start of the call. */
    @Volatile var initialLeadSamples = 0
        private set

    fun start(sampleRate: Int) {
        synchronized(lock) {
            rate = sampleRate
            buffer = ShortArray(sampleRate * CAPACITY_MILLIS / 1000)
            writePos = 0L
            readPos = 0L
            pendingPrefill = true
            initialLeadSamples = 0
            enabled = true
        }
    }

    fun stop() {
        synchronized(lock) {
            enabled = false
            writePos = 0L
            readPos = 0L
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
                val lead = (queuedFrames.coerceAtLeast(0) + rate * MARGIN_MILLIS / 1000).coerceAtMost(buffer.size / 2)
                initialLeadSamples = lead
                readPos = writePos - lead
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
                buffer[(writePos % buffer.size).toInt()] = (sum / channels).toShort()
                writePos++
            }
        }
    }

    /**
     * Fills [out] with the next [count] samples at the read position and advances it. Positions that
     * were never written, or that fell out of the window, read as silence. Returns the read position
     * the frame started at, or -1 when the reference is off.
     */
    fun pull(out: ShortArray, count: Int, sampleRate: Int): Long {
        synchronized(lock) {
            if (!enabled || rate != sampleRate || buffer.isEmpty()) {
                out.fill(0, 0, count)
                return -1L
            }
            val start = readPos
            for (i in 0 until count) {
                val pos = readPos + i
                out[i] = sampleAt(pos)
            }
            readPos += count
            return start
        }
    }

    /** Samples between the read and write positions: positive means the reference trails what was written. */
    fun lead(): Long = synchronized(lock) { writePos - readPos }

    /** Moves the read position so the reference trails the write position by [samples]. */
    fun setLead(samples: Long) {
        synchronized(lock) { readPos = writePos - samples.coerceIn(0L, buffer.size.toLong() / 2) }
    }

    /** Copies [count] samples ending just before [endPos] into [out]; unwritten positions are silence. */
    fun copyBefore(endPos: Long, count: Int, out: ShortArray) {
        synchronized(lock) {
            for (i in 0 until count) out[i] = sampleAt(endPos - count + i)
        }
    }

    private fun sampleAt(pos: Long): Short {
        if (pos < 0L || pos >= writePos || writePos - pos > buffer.size) return 0
        return buffer[(pos % buffer.size).toInt()]
    }
}
