package com.lowkey.backend.services

import com.lowkey.backend.db.AccountLastSeenIps
import com.lowkey.backend.db.scopedTransaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Records last-seen client IPs for retention (and lawful-access provenance).
 * Does not log request paths or payloads.
 */
class LastSeenIpService(
    private val accountService: AccountService = AccountService(),
) {
    fun record(accountId: UUID, ipAddress: String, chapterId: String) {
        val ip = ipAddress.trim().take(64)
        if (ip.isBlank() || ip == "unknown") return

        scopedTransaction(chapterId) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val existing = AccountLastSeenIps.selectAll()
                .where {
                    (AccountLastSeenIps.accountId eq accountId) and
                        (AccountLastSeenIps.ipAddress eq ip)
                }
                .firstOrNull()

            if (existing == null) {
                AccountLastSeenIps.insert {
                    it[AccountLastSeenIps.accountId] = accountId
                    it[AccountLastSeenIps.ipAddress] = ip
                    it[seenAt] = now
                }
            } else {
                // Throttle writes: only bump if last mark was > 1 hour ago.
                val previous = existing[AccountLastSeenIps.seenAt]
                if (previous.isBefore(now.minusHours(1))) {
                    AccountLastSeenIps.update({ AccountLastSeenIps.id eq existing[AccountLastSeenIps.id] }) {
                        it[seenAt] = now
                    }
                }
            }
        }
        // Separate throttle for presence timestamp used by last-seen UI.
        runCatching { accountService.touchLastActive(accountId, chapterId) }
    }
}
