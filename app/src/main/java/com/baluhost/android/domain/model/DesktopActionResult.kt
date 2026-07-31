package com.baluhost.android.domain.model

/**
 * Outcome of turning the server's displays back on.
 *
 * [sessionUnlocked] is null when the server said nothing about the lock screen,
 * and false when it refused to unlock it. A refusal is not a failure — the
 * displays are on either way, the session just stays locked.
 */
data class DesktopActionResult(
    val message: String,
    val sessionUnlocked: Boolean?
)
