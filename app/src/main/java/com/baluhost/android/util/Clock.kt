package com.baluhost.android.util

import java.time.Instant

/**
 * Injectable time source.
 *
 * Preset expiries and the five-minute / seven-day bounds are all computed
 * against "now". With Instant.now() wired in directly, the boundary cases could
 * only be tested approximately, and would race the wall clock.
 */
fun interface Clock {
    fun now(): Instant
}
