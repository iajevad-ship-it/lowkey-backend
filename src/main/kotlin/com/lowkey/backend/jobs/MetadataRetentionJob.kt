package com.lowkey.backend.jobs

import com.lowkey.backend.db.DatabaseFactory
import com.lowkey.backend.services.MetadataRetentionService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.days

private val log = LoggerFactory.getLogger("MetadataRetentionJob")

/**
 * Daily coroutine loop: purge Account metadata older than config retention.days
 * and append [com.lowkey.backend.db.AuditLog] rows.
 */
fun Application.startMetadataRetentionJob(
    metadataRetentionService: MetadataRetentionService,
    interval: kotlin.time.Duration = 1.days,
): Job {
    val job = launch {
        log.info(
            "Metadata retention job started (interval={}, retentionDays={})",
            interval,
            metadataRetentionService.retentionDays,
        )
        while (isActive) {
            try {
                if (DatabaseFactory.isReady()) {
                    metadataRetentionService.purgeExpiredMetadata()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Metadata retention purge failed: {}", e.message)
            }
            delay(interval)
        }
    }

    monitor.subscribe(ApplicationStopping) {
        job.cancel()
        log.info("Metadata retention job stopped")
    }

    return job
}
