package com.shilapi.xcertplay.media

/** What the last call's microphone path used, for the settings page: which microphone and which effects engaged. */
object CallMicrophoneStatus {
    @Volatile var lastCallSummary: String? = null
}
