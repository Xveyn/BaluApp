package com.baluhost.android.domain.model

import java.time.Instant

/**
 * The always-awake override, which overrules every automatic sleep path.
 *
 * [until] is null while the override is permanent — the same encoding the server
 * uses, where a missing expiry means "no expiry" rather than "not set".
 */
data class AlwaysAwake(
    val enabled: Boolean,
    val until: Instant?
)
