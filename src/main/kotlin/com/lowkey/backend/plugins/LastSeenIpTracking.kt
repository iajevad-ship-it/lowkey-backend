package com.lowkey.backend.plugins

import com.lowkey.backend.models.AccountPrincipal
import com.lowkey.backend.services.LastSeenIpService
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("LastSeenIpTracking")

/**
 * After successful auth, upsert the caller's approximate last-seen IP
 * (for retention + lawful-access provenance). Failures are swallowed.
 */
fun lastSeenIpTrackingPlugin(lastSeenIpService: LastSeenIpService) =
    createApplicationPlugin(name = "LastSeenIpTracking") {
        on(AuthenticationChecked) { call ->
            val principal = call.principal<AccountPrincipal>() ?: return@on
            val ip = clientIp(call)
            try {
                // Pass chapter explicitly — this hook may run before Call-phase coroutine scope binds.
                lastSeenIpService.record(principal.accountId, ip, principal.chapterId)
            } catch (e: Exception) {
                log.trace("last-seen IP record skipped: {}", e.message)
            }
        }
    }

private fun clientIp(call: io.ktor.server.application.ApplicationCall): String {
    val forwarded = call.request.header("X-Forwarded-For")
        ?.split(',')
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    return forwarded
        ?: call.request.origin.remoteAddress
        ?: call.request.origin.remoteHost
}
