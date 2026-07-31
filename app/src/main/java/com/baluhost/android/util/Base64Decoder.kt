package com.baluhost.android.util

import android.util.Base64
import javax.inject.Inject

/**
 * Base64 decoding behind an interface.
 *
 * android.util.Base64 is an Android framework class. Plain JVM unit tests run
 * against a stubbed framework where it returns null, so anything calling it
 * directly cannot be unit tested at all — which is exactly what happened to
 * ImportVpnConfigUseCase.
 */
interface Base64Decoder {
    /** @throws IllegalArgumentException if [input] is not valid Base64. */
    fun decode(input: String): ByteArray
}

class AndroidBase64Decoder @Inject constructor() : Base64Decoder {
    override fun decode(input: String): ByteArray = Base64.decode(input, Base64.DEFAULT)
}
