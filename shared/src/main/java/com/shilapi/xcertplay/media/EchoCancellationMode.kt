package com.shilapi.xcertplay.media

/** How the call microphone removes the far end's voice coming out of the car speakers. */
enum class EchoCancellationMode {
    /** The head unit's own canceller when it offers one, otherwise the app's. */
    AUTO,

    /** Always the app's canceller, on top of whatever the head unit does. */
    APP,

    /** No cancellation at all. */
    OFF,
    ;

    companion object {
        fun parse(value: String?): EchoCancellationMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}
