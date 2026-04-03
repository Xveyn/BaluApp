package com.baluhost.android.domain.model

data class PowerPermissions(
    val canSoftSleep: Boolean = false,
    val canWake: Boolean = false,
    val canSuspend: Boolean = false,
    val canWol: Boolean = false
) {
    val hasAnyPermission: Boolean
        get() = canSoftSleep || canWake || canSuspend || canWol
}
