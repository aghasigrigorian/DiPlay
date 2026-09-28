package com.shilapi.xcertplay.media

import android.content.SharedPreferences
import android.media.AudioManager
import android.util.Log

/**
 * A separate volume for calls, the way a car's own phone system behaves: the knob moves the media
 * volume, so during a call the media volume is set to the remembered call level, whatever the driver
 * turns it to during the call is remembered for the next call, and when the call ends the music comes
 * back at the level it had before. Only works where the head unit exposes its knob as the Android
 * media volume, which is what the reported behaviour (music loud after a call) suggests.
 */
class CallVolumeMemory(
    private val audioManager: AudioManager?,
    private val store: SharedPreferences?,
    private val enabled: Boolean,
) {
    private var mediaBefore: Int? = null
    private var lastSet: Int? = null
    private var active = false
    private var changedDuringCall = false

    private val remembered: Int?
        get() = store?.getInt(KEY, -1)?.takeIf { it >= 0 }

    fun onCallStarted() {
        val manager = audioManager ?: return
        if (!enabled || active) return
        runCatching {
            val stream = AudioManager.STREAM_MUSIC
            val current = manager.getStreamVolume(stream)
            mediaBefore = current
            val target = remembered?.coerceIn(0, manager.getStreamMaxVolume(stream))
            if (target != null && target != current) {
                manager.setStreamVolume(stream, target, 0)
                lastSet = target
                Log.i(TAG, "call volume: media $current -> remembered $target")
            } else {
                lastSet = current
            }
            active = true
        }.onFailure { Log.w(TAG, "call volume could not be applied", it) }
    }

    /** Called regularly during the call; a knob change since the last look is remembered as the call level. */
    fun poll() {
        val manager = audioManager ?: return
        if (!active) return
        runCatching {
            val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current != lastSet) {
                lastSet = current
                changedDuringCall = true
                store?.edit()?.putInt(KEY, current)?.apply()
                Log.i(TAG, "call volume: driver set $current, remembered")
            }
        }
    }

    fun onCallEnded() {
        val manager = audioManager ?: return
        if (!active) return
        active = false
        val before = mediaBefore ?: return
        runCatching {
            if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) != before) {
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
                Log.i(TAG, "call volume: media restored to $before")
            }
        }.onFailure { Log.w(TAG, "media volume could not be restored", it) }
    }

    fun summary(): String = when {
        !enabled -> "call volume memory off"
        mediaBefore == null -> "call volume memory idle"
        else -> "media volume ${mediaBefore} before, call at ${remembered ?: lastSet}${if (changedDuringCall) " (changed during call)" else ""}, restored after"
    }

    private companion object {
        const val TAG = "xcertplay-volume"
        const val KEY = "call_media_volume"
    }
}
