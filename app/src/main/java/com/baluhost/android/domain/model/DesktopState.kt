package com.baluhost.android.domain.model

/**
 * Whether the server's displays are on. Reported by
 * GET system/sleep/desktop/status; UNKNOWN also stands in for a failed lookup,
 * in which case the UI shows no desktop entry at all.
 */
enum class DesktopState {
    RUNNING,
    STOPPED,
    UNKNOWN;

    companion object {
        /** Maps the server's state string; anything unrecognised becomes UNKNOWN. */
        fun fromApi(state: String?): DesktopState = when (state) {
            "running" -> RUNNING
            "stopped" -> STOPPED
            else -> UNKNOWN
        }
    }
}
