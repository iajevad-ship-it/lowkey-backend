package com.lowkey.backend.auth

import org.signal.libsignal.protocol.IdentityKey
import java.util.Base64

/**
 * Challenge-signature verification via official libsignal only.
 * Account [publicKey] values are Base64 of a Signal [IdentityKey.serialize].
 */
object SignalAuth {
    private val decoder = Base64.getDecoder()
    private val urlDecoder = Base64.getUrlDecoder()

    fun verify(publicKeyEncoded: String, nonce: String, signatureEncoded: String): Boolean {
        return try {
            val identity = IdentityKey(decode(publicKeyEncoded))
            val signature = decode(signatureEncoded)
            identity.publicKey.verifySignature(nonce.toByteArray(Charsets.UTF_8), signature)
        } catch (_: Exception) {
            false
        }
    }

    private fun decode(encoded: String): ByteArray {
        val cleaned = encoded.trim().replace("\\s".toRegex(), "")
        return try {
            urlDecoder.decode(cleaned)
        } catch (_: IllegalArgumentException) {
            decoder.decode(cleaned)
        }
    }
}
