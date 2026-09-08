package com.lowkey.backend.services

import com.lowkey.backend.db.RedisFactory
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ephemeral approximate locations — Redis only, short TTL, never Postgres.
 * Keyed by accountId; chapter-scoped index for SOS neighbor lookup.
 */
class LocationPresenceService(
    config: ApplicationConfig,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val ttlSeconds: Long =
        config.propertyOrNull("alerts.locationTtlSeconds")?.getString()?.toLongOrNull()
            ?.coerceIn(60, 3_600)
            ?: 300L

    fun put(accountId: UUID, chapterId: String, lat: Double, lng: Double) {
        require(RedisFactory.isReady()) { "redis_unavailable" }
        require(lat in -90.0..90.0) { "lat out of range" }
        require(lng in -180.0..180.0) { "lng out of range" }

        val redis = RedisFactory.sync()
        val payload = json.encodeToString(
            StoredLocation(lat = lat, lng = lng, chapterId = chapterId)
        )
        val accountKey = accountKey(accountId)
        redis.setex(accountKey, ttlSeconds, payload)
        redis.sadd(chapterKey(chapterId), accountId.toString())
        // Keep the chapter index from growing forever; key itself gets a soft TTL refresh.
        redis.expire(chapterKey(chapterId), ttlSeconds * 2)
    }

    /**
     * Returns other accounts in [chapterId] whose stored location is within [radiusMeters]
     * of ([lat], [lng]), excluding [exceptAccountId]. Drops stale index members whose
     * location key has already expired.
     */
    fun findNearby(
        chapterId: String,
        lat: Double,
        lng: Double,
        radiusMeters: Double,
        exceptAccountId: UUID,
    ): List<NearbyAccount> {
        if (!RedisFactory.isReady()) return emptyList()
        val redis = RedisFactory.sync()
        val members = redis.smembers(chapterKey(chapterId)).orEmpty()
        if (members.isEmpty()) return emptyList()

        val results = mutableListOf<NearbyAccount>()
        for (member in members) {
            val id = runCatching { UUID.fromString(member) }.getOrNull() ?: continue
            if (id == exceptAccountId) continue

            val raw = redis.get(accountKey(id))
            if (raw.isNullOrBlank()) {
                redis.srem(chapterKey(chapterId), member)
                continue
            }
            val stored = runCatching { json.decodeFromString<StoredLocation>(raw) }.getOrNull()
                ?: continue
            if (stored.chapterId != chapterId) continue

            val distance = haversineMeters(lat, lng, stored.lat, stored.lng)
            if (distance <= radiusMeters) {
                results += NearbyAccount(accountId = id, distanceMeters = distance)
            }
        }
        return results.sortedBy { it.distanceMeters }
    }

    private fun accountKey(accountId: UUID) = "loc:account:$accountId"
    private fun chapterKey(chapterId: String) = "loc:chapter:$chapterId"

    companion object {
        /** Great-circle distance in meters. */
        fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return r * c
        }
    }
}

@Serializable
private data class StoredLocation(
    val lat: Double,
    val lng: Double,
    val chapterId: String,
)

data class NearbyAccount(
    val accountId: UUID,
    val distanceMeters: Double,
)
