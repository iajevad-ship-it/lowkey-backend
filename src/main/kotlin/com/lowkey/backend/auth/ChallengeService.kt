package com.lowkey.backend.auth

import com.lowkey.backend.db.RedisFactory
import java.security.SecureRandom
import java.util.Base64

/**
 * Issues short-lived nonces and stores them in Redis keyed by public key.
 * Nonces are reusable until expiry so a client can call several routes
 * after a single challenge without minting session cookies.
 */
class ChallengeService(
    private val ttlSeconds: Long = 300,
) {
    private val random = SecureRandom()

    fun issue(publicKey: String): Pair<String, Long> {
        val nonceBytes = ByteArray(32)
        random.nextBytes(nonceBytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        val key = redisKey(publicKey, nonce)
        RedisFactory.sync().setex(key, ttlSeconds, "1")
        return nonce to ttlSeconds
    }

    fun isValid(publicKey: String, nonce: String): Boolean {
        return try {
            RedisFactory.sync().exists(redisKey(publicKey, nonce)) > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun redisKey(publicKey: String, nonce: String): String =
        "auth:nonce:${publicKey.trim()}:$nonce"
}
