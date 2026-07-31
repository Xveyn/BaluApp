package com.baluhost.android.domain.model

/**
 * The power actions the logged-in user may trigger, as reported by
 * GET system/sleep/my-permissions. Admins get every flag set by the server.
 */
data class PowerPermissions(
    val canSoftSleep: Boolean = false,
    val canWake: Boolean = false,
    val canSuspend: Boolean = false,
    val canWol: Boolean = false,
    val canToggleDesktop: Boolean = false,
    val canUnlockSession: Boolean = false
) {
    /**
     * Whether the power button is worth showing at all.
     *
     * canUnlockSession is deliberately left out: it is an add-on to
     * canToggleDesktop that the server applies while turning the displays back
     * on, not something a user can trigger by itself.
     */
    val hasAnyPermission: Boolean
        get() = canSoftSleep || canWake || canSuspend || canWol || canToggleDesktop
}
