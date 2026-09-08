package com.lowkey.backend.services

import com.lowkey.backend.db.Accounts
import com.lowkey.backend.db.ChatMembers
import com.lowkey.backend.db.Chats
import com.lowkey.backend.db.Reports
import com.lowkey.backend.db.VouchLinks
import com.lowkey.backend.db.scopedTransaction
import com.lowkey.backend.models.CreateReportRequest
import com.lowkey.backend.models.ReportDto
import com.lowkey.backend.models.ResolveReportRequest
import com.lowkey.backend.models.VouchLinkDto
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ReportService {
    fun create(reporterId: UUID, request: CreateReportRequest): ReportDto = scopedTransaction {
        val reportedId = request.reportedAccountId?.let { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull()
                ?: error("invalid reportedAccountId")
        }
        val chatId = runCatching { UUID.fromString(request.chatId) }.getOrNull()
            ?: error("invalid chatId")
        require(request.reason.isNotBlank()) { "reason is required" }

        if (reportedId != null) {
            require(reportedId != reporterId) { "cannot report yourself" }
            require(Accounts.selectAll().where { Accounts.id eq reportedId }.count() > 0) {
                "reported account not found"
            }
        }

        require(Chats.selectAll().where { Chats.id eq chatId }.count() > 0) {
            "chat not found"
        }
        // Reporter must belong to the chat being reported about.
        require(
            ChatMembers.selectAll()
                .where { (ChatMembers.chatId eq chatId) and (ChatMembers.accountId eq reporterId) }
                .count() > 0
        ) { "not a member of this chat" }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val id = Reports.insertAndGetId {
            it[reportedAccountId] = reportedId
            it[reportedByAccountId] = reporterId
            it[Reports.chatId] = chatId
            it[reason] = request.reason.trim()
            it[createdAt] = now
        }

        toReportDto(id.value)
    }

    fun listOpenAndRecent(limit: Int = 100): List<ReportDto> = scopedTransaction {
        Reports.selectAll()
            .orderBy(Reports.createdAt to SortOrder.DESC)
            .take(limit.coerceIn(1, 500))
            .map { toReportDto(it[Reports.id].value) }
    }

    fun resolve(reportId: UUID, moderatorId: UUID, request: ResolveReportRequest): ReportDto =
        scopedTransaction {
            val row = Reports.selectAll().where { Reports.id eq reportId }.firstOrNull()
                ?: error("report not found")
            require(row[Reports.resolvedAt] == null) { "report already resolved" }

            val now = OffsetDateTime.now(ZoneOffset.UTC)
            Reports.update({ Reports.id eq reportId }) {
                it[resolvedAt] = now
            }

            if (request.ban) {
                val reportedId = row[Reports.reportedAccountId]
                    ?: error("cannot ban without a reported account")
                require(reportedId != moderatorId) { "cannot ban yourself" }
                val updated = Accounts.update({ Accounts.id eq reportedId }) {
                    it[banned] = true
                }
                require(updated == 1) { "reported account not found" }
            }

            toReportDto(reportId)
        }

    /**
     * Builds the vouch graph around [accountId]: upward invite chain plus
     * direct outbound vouches. IDs and timestamps only.
     */
    private fun vouchChainFor(accountId: UUID): List<VouchLinkDto> {
        val edges = linkedMapOf<String, VouchLinkDto>()

        fun add(row: org.jetbrains.exposed.sql.ResultRow) {
            val dto = VouchLinkDto(
                voucherAccountId = row[VouchLinks.voucherAccountId].toString(),
                voucheeAccountId = row[VouchLinks.voucheeAccountId].toString(),
                createdAt = row[VouchLinks.createdAt].toString(),
            )
            edges["${dto.voucherAccountId}->${dto.voucheeAccountId}"] = dto
        }

        // Walk voucher ancestry (who invited whom up to genesis).
        var current: UUID? = accountId
        val seen = mutableSetOf<UUID>()
        while (current != null && seen.add(current)) {
            val link = VouchLinks.selectAll()
                .where { VouchLinks.voucheeAccountId eq current!! }
                .firstOrNull()
            if (link == null) break
            add(link)
            current = link[VouchLinks.voucherAccountId]
        }

        // People this account directly vouched for.
        VouchLinks.selectAll()
            .where { VouchLinks.voucherAccountId eq accountId }
            .forEach { add(it) }

        // Any other edge touching this account (e.g. co-vouches).
        VouchLinks.selectAll()
            .where {
                (VouchLinks.voucherAccountId eq accountId) or (VouchLinks.voucheeAccountId eq accountId)
            }
            .forEach { add(it) }

        return edges.values.toList()
    }

    private fun toReportDto(reportId: UUID): ReportDto {
        val row = Reports.selectAll().where { Reports.id eq reportId }.first()
        val reportedId = row[Reports.reportedAccountId]
        val bannedFlag = if (reportedId != null) {
            Accounts.selectAll()
                .where { Accounts.id eq reportedId }
                .firstOrNull()
                ?.get(Accounts.banned)
                ?: false
        } else {
            false
        }

        return ReportDto(
            id = reportId.toString(),
            reportedAccountId = reportedId?.toString(),
            reportedByAccountId = row[Reports.reportedByAccountId].toString(),
            chatId = row[Reports.chatId].toString(),
            reason = row[Reports.reason],
            createdAt = row[Reports.createdAt].toString(),
            resolvedAt = row[Reports.resolvedAt]?.toString(),
            vouchChain = reportedId?.let { vouchChainFor(it) } ?: emptyList(),
            reportedAccountBanned = bannedFlag,
        )
    }
}
