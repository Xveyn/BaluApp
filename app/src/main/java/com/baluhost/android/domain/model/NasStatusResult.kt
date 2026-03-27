package com.baluhost.android.domain.model

sealed class NasStatusResult {
    data class Resolved(val status: NasStatus) : NasStatusResult()
    object FritzBoxUnreachable : NasStatusResult()
    object FritzBoxAuthError : NasStatusResult()
    object FritzBoxNotConfigured : NasStatusResult()
}
