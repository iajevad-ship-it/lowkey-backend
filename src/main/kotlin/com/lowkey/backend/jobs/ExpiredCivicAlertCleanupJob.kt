package com.lowkey.backend.jobs

import com.lowkey.backend.db.DatabaseFactory
import com.lowkey.backend.services.CivicAlertExpiryService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

private val log = LoggerFactory.getLogger("ExpiredCivicAlertCleanupJob")

/**
 * Hourly coroutine loop: hard-delete civic alerts past [com.lowkey.backend.db.CivicAlerts.expiresAt].
 * Mirrors [startExpiredChatCleanupJob] (Prompt 8 event-room cleanup).
 */
fun Application.startExpiredCivicAlertCleanupJob(
    civicAlertExpiryService: CivicAlertExpiryService,
    interval: kotlin.time.Duration = 1.hours,
): Job {
    val job = launch {
        log.info("Expired civic-alert cleanup job started (interval={})", interval)
        while (isActive) {
            try {
                if (DatabaseFactory.isReady()) {
                    civicAlertExpiryService.purgeExpired()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Expired civic-alert cleanup failed: {}", e.message)
            }
            delay(interval)
        }
    }

    monitor.subscribe(ApplicationStopping) {
        job.cancel()
        log.info("Expired civic-alert cleanup job stopped")
    }

    return job
}
