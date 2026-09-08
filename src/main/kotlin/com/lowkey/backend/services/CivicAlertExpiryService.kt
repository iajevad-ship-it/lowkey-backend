package com.lowkey.backend.services

import com.lowkey.backend.db.CivicAlerts
import com.lowkey.backend.db.bootstrapTransaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Hard-deletes civic alerts whose [CivicAlerts.expiresAt] is in the past.
 * Runs under RLS bypass so every chapter is covered (same pattern as [ChatExpiryService]).
 */
class CivicAlertExpiryService {
    private val log = LoggerFactory.getLogger(CivicAlertExpiryService::class.java)

    fun purgeExpired(): Int = bootstrapTransaction {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val deleted = CivicAlerts.deleteWhere { CivicAlerts.expiresAt less now }
        if (deleted > 0) {
            log.info("Purged {} expired civic alert(s)", deleted)
        }
        deleted
    }
}
