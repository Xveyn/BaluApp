package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * The two always-awake fields of the server's sleep config. The real response
 * carries dozens more — idle thresholds, schedules, core uptime, presence — and
 * this app has no business with any of them. Gson drops what is not declared.
 */
data class SleepConfigDto(
    @SerializedName("always_awake_enabled")
    val alwaysAwakeEnabled: Boolean = false,
    /** ISO-8601. Null means the override is permanent. May arrive without a zone offset. */
    @SerializedName("always_awake_until")
    val alwaysAwakeUntil: String? = null
)
