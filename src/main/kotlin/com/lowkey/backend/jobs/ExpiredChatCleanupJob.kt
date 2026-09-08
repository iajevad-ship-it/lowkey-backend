package com.lowkey.backend.jobs

import com.lowkey.backend.services.ChatExpiryService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

private val log = LoggerFactory.getLogger("ExpiredChatCleanupJob")

/**
 * Hourly coroutine loop: hard-delete chats past [com.lowkey.backend.db.Chats.expiresAt].
 * Uses a simple delay loop (no Quartz) — enough for one-node Lowkey backends.
 */
fun Application.startExpiredChatCleanupJob(
    chatExpiryService: ChatExpiryService,
    interval: kotlin.time.Duration = 1.hours,
): Job {
    val job = launch {
        log.info("Expired-chat cleanup job started (interval={})", interval)
        while (isActive) {
            try {
                if (com.lowkey.backend.db.DatabaseFactory.isReady()) {
                    chatExpiryService.purgeExpired()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Expired-chat cleanup failed: {}", e.message)
            }
            delay(interval)
        }
    }

    monitor.subscribe(ApplicationStopping) {
        job.cancel()
        log.info("Expired-chat cleanup job stopped")
    }

    return job
}
