package com.lowkey.backend.services

import com.lowkey.backend.db.AccountLastSeenIps
import com.lowkey.backend.db.Accounts
import com.lowkey.backend.db.AuditLog
import com.lowkey.backend.db.DeviceTokenHistory
import com.lowkey.backend.db.bootstrapTransaction
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Daily retention: hard-delete Account-level metadata older than [retentionDays]
 * and write [AuditLog] rows describing what was purged (no token/IP values retained).
 */
class MetadataRetentionService(
    config: ApplicationConfig,
) {
    private val log = LoggerFactory.getLogger(MetadataRetentionService::class.java)

    val retentionDays: Long =
        config.propertyOrNull("retention.days")?.getString()?.toLongOrNull()
            ?.coerceIn(1, 3650)
            ?: 90L

    fun purgeExpiredMetadata(): RetentionPurgeResult = bootstrapTransaction {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val cutoff = now.minusDays(retentionDays)

        val historyByAccount = purgeDeviceTokenHistory(cutoff)
        val clearedActiveTokens = purgeStaleActiveDeviceTokens(cutoff)
        val ipsByAccount = purgeLastSeenIps(cutoff)
        val days = retentionDays.toInt()

        val totalDeleted =
            historyByAccount.values.sum() + clearedActiveTokens.size + ipsByAccount.values.sum()

        AuditLog.insert {
            it[action] = "metadata_purge"
            it[category] = "purge_run"
            it[accountId] = null
            it[AuditLog.retentionDays] = days
            it[cutoffAt] = cutoff
            it[deletedCount] = totalDeleted
            it[detail] = buildJsonObject {
                put("deviceTokenHistoryAccounts", historyByAccount.size)
                put("deviceTokenHistoryRows", historyByAccount.values.sum())
                put("activeDeviceTokensCleared", clearedActiveTokens.size)
                put("lastSeenIpAccounts", ipsByAccount.size)
                put("lastSeenIpRows", ipsByAccount.values.sum())
            }.toString()
            it[createdAt] = now
        }

        for ((accountId, count) in historyByAccount) {
            writeAccountAudit(now, cutoff, days, "device_token_history", accountId, count)
        }
        for (accountId in clearedActiveTokens) {
            writeAccountAudit(now, cutoff, days, "active_device_token", accountId, 1)
        }
        for ((accountId, count) in ipsByAccount) {
            writeAccountAudit(now, cutoff, days, "last_seen_ip", accountId, count)
        }

        log.info(
            "Retention purge ({}d, cutoff={}): historyRows={}, activeTokens={}, ipRows={}",
            retentionDays,
            cutoff,
            historyByAccount.values.sum(),
            clearedActiveTokens.size,
            ipsByAccount.values.sum(),
        )

        RetentionPurgeResult(
            retentionDays = retentionDays,
            cutoffAt = cutoff,
            deviceTokenHistoryDeleted = historyByAccount.values.sum(),
            activeDeviceTokensCleared = clearedActiveTokens.size,
            lastSeenIpsDeleted = ipsByAccount.values.sum(),
        )
    }

    private fun org.jetbrains.exposed.sql.Transaction.purgeDeviceTokenHistory(
        cutoff: OffsetDateTime,
    ): Map<UUID, Int> {
        val rows = DeviceTokenHistory.selectAll()
            .where { DeviceTokenHistory.recordedAt less cutoff }
            .toList()
        if (rows.isEmpty()) return emptyMap()

        val byAccount = rows.groupingBy { it[DeviceTokenHistory.accountId] }.eachCount()
        DeviceTokenHistory.deleteWhere { DeviceTokenHistory.recordedAt less cutoff }
        return byAccount
    }

    private fun org.jetbrains.exposed.sql.Transaction.purgeStaleActiveDeviceTokens(
        cutoff: OffsetDateTime,
    ): List<UUID> {
        val stale = Accounts.selectAll()
            .where {
                Accounts.deviceToken.isNotNull() and
                    Accounts.deviceTokenUpdatedAt.isNotNull() and
                    (Accounts.deviceTokenUpdatedAt less cutoff)
            }
            .map { it[Accounts.id].value }
        if (stale.isEmpty()) return emptyList()

        for (accountId in stale) {
            Accounts.update({ Accounts.id eq accountId }) {
                it[deviceToken] = null
                it[deviceTokenUpdatedAt] = null
            }
        }
        return stale
    }

    private fun org.jetbrains.exposed.sql.Transaction.purgeLastSeenIps(
        cutoff: OffsetDateTime,
    ): Map<UUID, Int> {
        val rows = AccountLastSeenIps.selectAll()
            .where { AccountLastSeenIps.seenAt less cutoff }
            .toList()
        if (rows.isEmpty()) return emptyMap()

        val byAccount = rows.groupingBy { it[AccountLastSeenIps.accountId] }.eachCount()
        AccountLastSeenIps.deleteWhere { AccountLastSeenIps.seenAt less cutoff }
        return byAccount
    }

    private fun org.jetbrains.exposed.sql.Transaction.writeAccountAudit(
        now: OffsetDateTime,
        cutoff: OffsetDateTime,
        days: Int,
        category: String,
        accountId: UUID,
        deletedCount: Int,
    ) {
        AuditLog.insert {
            it[action] = "metadata_purge"
            it[AuditLog.category] = category
            it[AuditLog.accountId] = accountId
            it[AuditLog.retentionDays] = days
            it[cutoffAt] = cutoff
            it[AuditLog.deletedCount] = deletedCount
            it[detail] = buildJsonObject {
                put("accountId", accountId.toString())
                put("category", category)
                put("deletedCount", deletedCount)
                put("note", "Values (token/IP) not retained in audit")
            }.toString()
            it[createdAt] = now
        }
    }
}

data class RetentionPurgeResult(
    val retentionDays: Long,
    val cutoffAt: OffsetDateTime,
    val deviceTokenHistoryDeleted: Int,
    val activeDeviceTokensCleared: Int,
    val lastSeenIpsDeleted: Int,
)
