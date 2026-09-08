package com.lowkey.backend.services

import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Chapter SOS: store caller's ephemeral location in Redis, fan out FCM to
 * nearby chapter members who also have a fresh Redis location.
 */
class SosAlertService(
    config: ApplicationConfig,
    private val locationPresence: LocationPresenceService,
    private val accountService: AccountService,
    private val fcmService: FcmService,
) {
    private val log = LoggerFactory.getLogger(SosAlertService::class.java)

    private val radiusMeters: Double =
        config.propertyOrNull("alerts.sosRadiusMeters")?.getString()?.toDoubleOrNull()
            ?.coerceIn(50.0, 50_000.0)
            ?: 500.0

    suspend fun triggerSos(
        accountId: UUID,
        chapterId: String,
        lat: Double,
        lng: Double,
    ): SosAlertResult {
        // Ephemeral presence — Redis only, never Postgres.
        locationPresence.put(accountId, chapterId, lat, lng)

        val nearby = locationPresence.findNearby(
            chapterId = chapterId,
            lat = lat,
            lng = lng,
            radiusMeters = radiusMeters,
            exceptAccountId = accountId,
        )

        var notified = 0
        for (peer in nearby) {
            val token = accountService.deviceTokenFor(peer.accountId) ?: continue
            fcmService.notifySos(
                deviceToken = token,
                lat = lat,
                lng = lng,
                distanceMeters = peer.distanceMeters,
            )
            notified++
        }

        log.info(
            "SOS from {} in chapter {}: {} nearby candidate(s), {} push(es)",
            accountId,
            chapterId,
            nearby.size,
            notified,
        )

        return SosAlertResult(
            notifiedCount = notified,
            nearbyCount = nearby.size,
            radiusMeters = radiusMeters,
            locationTtlSeconds = locationPresence.ttlSeconds,
        )
    }
}

data class SosAlertResult(
    val notifiedCount: Int,
    val nearbyCount: Int,
    val radiusMeters: Double,
    val locationTtlSeconds: Long,
)
