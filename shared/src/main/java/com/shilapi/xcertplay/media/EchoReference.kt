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

    /** How far capture may run ahead of playback before the read position is re-anchored. */
    private const val REANCHOR_MILLIS = 500
    private val lock = Any()
    private var rate = 0
    private var buffer = ShortArray(0)
    private var writePos = 0L
    private var readPos = 0L
    private var pendingPrefill = false
    private var fixedLead: Int? = null
    private var afterPlaybackLead: Int? = null
    private var targetLead = 0L
    private var resamplePhase = 0.0
    private var resampleLast = 0
    private val sources = linkedMapOf<String, Long>()

    @Volatile var enabled = false
        private set

    /** Silence assumed between writing a sample and hearing it, in samples, at the start of the call. */
    @Volatile var initialLeadSamples = 0
        private set

    /**
     * [fixedLeadSamples] overrides the buffer-based guess with a calibrated echo delay counted from
     * the write; [afterPlaybackLeadSamples] instead adds a calibrated delay counted from the moment
     * sound leaves the player to whatever the player holds at the first write, which survives a
     * different playback buffer between the echo test and a call.
     */
    fun start(sampleRate: Int, fixedLeadSamples: Int? = null, afterPlaybackLeadSamples: Int? = null) {
        synchronized(lock) {
            fixedLead = fixedLeadSamples
            afterPlaybackLead = afterPlaybackLeadSamples
            rate = sampleRate
            buffer = ShortArray(sampleRate * CAPACITY_MILLIS / 1000)
            writePos = 0L
            readPos = 0L
            pendingPrefill = true
            initialLeadSamples = 0
            resamplePhase = 0.0
            resampleLast = 0
            sources.clear()
            enabled = true
        }
    }

    /** Which streams fed the reference this call and how many samples each contributed (after resampling). */
    fun describeSources(): String = synchronized(lock) {
        if (sources.isEmpty()) "no reference audio" else sources.entries.joinToString(", ") { "${it.key}: ${it.value * 1000 / rate.coerceAtLeast(1)} ms" }
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
    fun push(pcm: ByteArray, offset: Int, length: Int, channels: Int, sampleRate: Int, queuedFrames: Int = 0, label: String = "") {
        if (!enabled || channels <= 0 || sampleRate <= 0) return
        synchronized(lock) {
            if (buffer.isEmpty()) return
            if (pendingPrefill) {
                pendingPrefill = false
                val queued = queuedFrames.coerceAtLeast(0) * rate / sampleRate
                val lead = (afterPlaybackLead?.let { queued + it } ?: fixedLead ?: (queued + rate * MARGIN_MILLIS / 1000))
                    .coerceIn(0, buffer.size / 2)
                initialLeadSamples = lead
                targetLead = lead.toLong()
                readPos = writePos - lead
            } else if (writePos - readPos < -(rate.toLong() * REANCHOR_MILLIS / 1000)) {
                // Capture ran far ahead while the far end was quiet or the reference stalled: treat
                // this audio as a fresh start so it is not read as if it were in the past.
                readPos = writePos - targetLead
            }
            val frameBytes = 2 * channels
            val frames = length / frameBytes
            if (frames <= 0) return
            val mono = ShortArray(frames)
            var index = offset
            for (f in 0 until frames) {
                var sum = 0
                repeat(channels) {
                    sum += ((pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8)).toShort().toInt()
                    index += 2
                }
                mono[f] = (sum / channels).toShort()
            }
            val before = writePos
            if (sampleRate == rate) {
                for (sample in mono) append(sample)
            } else {
                // Linear resampling to the reference rate; the phase carries across pushes so the
                // timeline stays continuous. Good enough for a reference the filter adapts to.
                val ratio = sampleRate.toDouble() / rate
                var pos = resamplePhase
                while (pos < frames) {
                    val i = pos.toInt()
                    val frac = pos - i
                    val a = if (i == 0) resampleLast else mono[i - 1].toInt()
                    val b = mono[i].toInt()
                    // pos in (i-1, i]: interpolate between the previous and current source sample
                    append((a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort())
                    pos += ratio
                }
                resamplePhase = pos - frames
                resampleLast = mono[frames - 1].toInt()
            }
            val key = "$label ${sampleRate} Hz ${if (channels >= 2) "stereo" else "mono"}"
            sources[key] = (sources[key] ?: 0L) + (writePos - before)
        }
    }

    private fun append(sample: Short) {
        buffer[(writePos % buffer.size).toInt()] = sample
        writePos++
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

    /** Samples written since [start]; also the index of the next sample in the timeline. */
    fun writePosition(): Long = synchronized(lock) { writePos }

    /** Samples between the read and write positions: positive means the reference trails what was written. */
    fun lead(): Long = synchronized(lock) { writePos - readPos }

    /** Moves the read position so the reference trails the write position by [samples]. */
    fun setLead(samples: Long) {
        synchronized(lock) {
            targetLead = samples.coerceIn(0L, buffer.size.toLong() / 2)
            readPos = writePos - targetLead
        }
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
