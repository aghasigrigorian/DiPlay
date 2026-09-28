package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 *
 * For calls the head unit's microphone hears the far end coming out of the car speakers, and a
 * certified accessory is expected to cancel that itself. The platform echo canceller and noise
 * suppressor are attached to the recording session where the firmware offers them, and the audio
 * mode is switched to in-communication for the duration of the call so the firmware's own voice
 * processing engages. [audioManager] may be null on callers without a context; the effects still apply.
 */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val audioManager: AudioManager? = null,
    private val echoCancellation: EchoCancellationMode = EchoCancellationMode.AUTO,
    private val callVolumeMemory: CallVolumeMemory? = null,
) : Closeable {
    @Volatile private var softwareCanceller: SpeexEchoCanceller? = null
    private val running = AtomicBoolean(false)
    private val firstPacketLogged = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var effects: List<AudioEffect> = emptyList()
    @Volatile private var previousAudioMode: Int? = null
    @Volatile private var previousCallVolume: Int? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusEncoder? = null
    private var thread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "microphone unavailable rate=${config.sampleRate} channels=${config.channels}")
            running.set(false)
            return false
        }

        val source = when (config.audioType) {
            // The app's own canceller wants the raw microphone: the firmware's voice chain adds gain
            // control and its own half-working echo processing that the adaptive filter cannot follow.
            "telephony" -> if (echoCancellation == EchoCancellationMode.APP) MediaRecorder.AudioSource.VOICE_RECOGNITION
                else MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            OpusEncoder(config.bitrate ?: 48_000).takeIf { it.available }
        } else {
            null
        }
        if (config.codec == AudioCodecKind.OPUS && nextEncoder == null) {
            Log.w(TAG, "microphone Opus encoder is unavailable")
            running.set(false)
            return false
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        val nextRecorder = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (error: Exception) {
            Log.e(TAG, "microphone recorder creation failed", error)
            nextEncoder?.close()
            running.set(false)
            return false
        }
        if (nextRecorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "microphone recorder failed to initialize")
            nextRecorder.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        val nextSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("::"), 0))
            }
        } catch (error: Exception) {
            Log.e(TAG, "microphone socket creation failed", error)
            nextRecorder.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        recorder = nextRecorder
        socket = nextSocket
        opusEncoder = nextEncoder
        if (config.audioType == "telephony") {
            if (echoCancellation == EchoCancellationMode.AUTO) {
                effects = attachVoiceEffects(nextRecorder.audioSessionId)
                enterCommunicationMode()
            } else if (echoCancellation == EchoCancellationMode.APP) {
                enterCommunicationMode()
            }
            callVolumeMemory?.onCallStarted()
            val platformOn = effects.any { it is AcousticEchoCanceler }
            val wantSoftware = when (echoCancellation) {
                EchoCancellationMode.APP -> true
                EchoCancellationMode.AUTO -> !platformOn
                EchoCancellationMode.OFF -> false
            }
            val canceller = if (wantSoftware && config.channels == 1) {
                EchoReference.start(config.sampleRate)
                SpeexEchoCanceller.create(config.sampleRate, config.frameBytes / 2)
            } else {
                null
            }
            softwareCanceller = canceller
            val summary = "car microphone (${if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) "raw" else "voice-processed"})" +
                " · head-unit echo canceller ${if (platformOn) "on" else if (echoCancellation == EchoCancellationMode.AUTO) "unavailable" else "off"}" +
                " · app echo canceller ${if (canceller != null) "on" else if (wantSoftware) "unavailable" else "off"}" +
                " · noise suppressor ${if (effects.any { it is NoiseSuppressor }) "head unit" else if (canceller != null) "app" else "off"}"
            CallMicrophoneStatus.lastCallSummary = summary
            Log.i(TAG, "microphone voice processing: $summary")
        }
        return try {
            nextRecorder.startRecording()
            thread = Thread({ capture(nextRecorder, nextSocket) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            Log.i(
                TAG,
                "microphone uplink started type=${config.audioType} " +
                    "rate=${config.sampleRate} channels=${config.channels} " +
                    "frameMs=${config.frameMillis} port=${config.port}",
            )
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            release()
            false
        }
    }

    /** Platform echo cancellation and noise suppression on the capture session, where the firmware has them. */
    private fun attachVoiceEffects(sessionId: Int): List<AudioEffect> {
        val attached = mutableListOf<AudioEffect>()
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(sessionId) }.getOrNull() else null
        val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(sessionId) }.getOrNull() else null
        for (effect in listOfNotNull(aec, ns)) {
            val status = runCatching { effect.setEnabled(true) }.getOrDefault(AudioEffect.ERROR)
            if (status == AudioEffect.SUCCESS) attached += effect else runCatching { effect.release() }
        }
        Log.i(TAG, "microphone voice effects echoCanceller=${describe(aec, attached)} noiseSuppressor=${describe(ns, attached)}")
        return attached
    }

    private fun describe(effect: AudioEffect?, attached: List<AudioEffect>): String = when {
        effect == null -> "unavailable"
        effect in attached -> "on"
        else -> "failed"
    }

    private fun enterCommunicationMode() {
        val manager = audioManager ?: return
        runCatching {
            val current = manager.mode
            if (current != AudioManager.MODE_IN_COMMUNICATION) {
                previousAudioMode = current
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                Log.i(TAG, "audio mode in-communication for the call (was $current)")
            }
        }.onFailure { Log.w(TAG, "audio mode could not be changed", it) }
        // The car's volume knob usually moves the media stream only; the call stream sits wherever the
        // firmware left it. Run it at its maximum for the call and put it back afterwards.
        runCatching {
            val stream = AudioManager.STREAM_VOICE_CALL
            val current = manager.getStreamVolume(stream)
            val max = manager.getStreamMaxVolume(stream)
            if (current < max) {
                previousCallVolume = current
                manager.setStreamVolume(stream, max, 0)
                Log.i(TAG, "call stream volume $current -> $max for the call")
            }
        }.onFailure { Log.w(TAG, "call stream volume could not be changed", it) }
    }

    private fun leaveCommunicationMode() {
        val manager = audioManager ?: return
        previousCallVolume?.let { volume ->
            previousCallVolume = null
            runCatching { manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, volume, 0) }
                .onFailure { Log.w(TAG, "call stream volume could not be restored", it) }
        }
        val previous = previousAudioMode ?: return
        previousAudioMode = null
        runCatching {
            if (manager.mode == AudioManager.MODE_IN_COMMUNICATION) manager.mode = previous
        }.onFailure { Log.w(TAG, "audio mode could not be restored", it) }
    }

    private fun capture(recorder: AudioRecord, socket: DatagramSocket) {
        val frame = ByteArray(config.frameBytes)
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES))
        val counters = MicrophoneCounters()
        var filled = 0
        var reads = 0
        try {
            while (running.get()) {
                if (++reads % VOLUME_POLL_EVERY_READS == 0) callVolumeMemory?.poll()
                val count = recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                if (count < 0) {
                    if (running.get()) Log.e(TAG, "microphone read failed code=$count")
                    return
                }
                if (count == 0) {
                    continue
                }
                var offset = 0
                while (offset < count && running.get()) {
                    val copied = minOf(frame.size - filled, count - offset)
                    readBuffer.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        sendFrame(socket, counters, frame)
                        filled = 0
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) Log.e(TAG, "microphone capture failed", error)
        } finally {
            running.set(false)
            release()
        }
    }

    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        softwareCanceller?.process(frame)
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(frame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(frame))
        }
        bodies.forEach { body ->
            sendPacket(
                socket = socket,
                counters = counters,
                body = body,
                samples = config.samplesPerPacket,
            )
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ) {
        val packet = MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = samples,
        )
        try {
            socket.send(DatagramPacket(packet, packet.size, config.host, config.port))
            if (firstPacketLogged.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "microphone first packet bytes=${packet.size} body=${body.size} " +
                        "head=${packet.copyOf(minOf(packet.size, 16)).toHexString()} " +
                        "port=${config.port}",
                )
            }
        } catch (error: Exception) {
            if (running.get()) throw error
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        val currentEffects = effects
        effects = emptyList()
        currentEffects.forEach { runCatching { it.release() } }
        callVolumeMemory?.let { memory ->
            memory.onCallEnded()
            if (config.audioType == "telephony" && CallMicrophoneStatus.lastCallSummary?.contains("media volume") == false) {
                CallMicrophoneStatus.lastCallSummary = (CallMicrophoneStatus.lastCallSummary ?: "") + " · ${memory.summary()}"
            }
        }
        val currentCanceller = softwareCanceller
        softwareCanceller = null
        if (currentCanceller != null) {
            val stats = currentCanceller.stats()
            CallMicrophoneStatus.lastCallSummary = (CallMicrophoneStatus.lastCallSummary ?: "") +
                " · start lead ${EchoReference.initialLeadSamples * 1000 / config.sampleRate.coerceAtLeast(1)} ms · $stats"
            currentCanceller.close()
        }
        EchoReference.stop()
        leaveCommunicationMode()
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentEncoder = opusEncoder
        opusEncoder = null
        currentEncoder?.close()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MIN_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
        const val VOLUME_POLL_EVERY_READS = 8
    }
}
