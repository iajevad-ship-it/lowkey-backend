package com.lowkey.backend.services

import com.lowkey.backend.db.ChapterContext
import com.lowkey.backend.db.CivicAlertCategory
import com.lowkey.backend.db.CivicAlerts
import com.lowkey.backend.db.scopedTransaction
import com.lowkey.backend.models.CivicAlertCategoryDto
import com.lowkey.backend.models.CivicAlertDto
import com.lowkey.backend.models.CreateCivicAlertRequest
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class CivicAlertService {
    fun listActive(chapterId: String): List<CivicAlertDto> = scopedTransaction {
        requireChapterMatchesCaller(chapterId)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        CivicAlerts.selectAll()
            .where {
                (CivicAlerts.chapterId eq ChapterContext.current()) and
                    (CivicAlerts.expiresAt greaterEq now)
            }
            .orderBy(CivicAlerts.createdAt to SortOrder.DESC)
            .map { it.toDto() }
    }

    fun create(postedBy: UUID, request: CreateCivicAlertRequest): CivicAlertDto = scopedTransaction {
        val chapterId = ChapterContext.current()
        require(request.title.isNotBlank()) { "title is required" }
        require(request.description.isNotBlank()) { "description is required" }

        val expiresAt = runCatching { OffsetDateTime.parse(request.expiresAt.trim()) }
            .getOrElse { error("expiresAt must be an ISO-8601 timestamp") }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        require(expiresAt.isAfter(now)) { "expiresAt must be in the future" }

        val id = CivicAlerts.insertAndGetId {
            it[CivicAlerts.chapterId] = chapterId
            it[category] = request.category.toDb()
            it[title] = request.title.trim()
            it[description] = request.description.trim()
            it[postedByAccountId] = postedBy
            it[createdAt] = now
            it[CivicAlerts.expiresAt] = expiresAt
        }
        CivicAlerts.selectAll().where { CivicAlerts.id eq id }.first().toDto()
    }

    private fun requireChapterMatchesCaller(pathChapterId: String) {
        val caller = ChapterContext.current()
        require(pathChapterId.trim().equals(caller, ignoreCase = true)) {
            "chapterId must match your account chapter"
        }
    }
}

private fun CivicAlertCategoryDto.toDb(): CivicAlertCategory = when (this) {
    CivicAlertCategoryDto.water -> CivicAlertCategory.water
    CivicAlertCategoryDto.road -> CivicAlertCategory.road
    CivicAlertCategoryDto.traffic -> CivicAlertCategory.traffic
    CivicAlertCategoryDto.other -> CivicAlertCategory.other
}

private fun CivicAlertCategory.toDto(): CivicAlertCategoryDto = when (this) {
    CivicAlertCategory.water -> CivicAlertCategoryDto.water
    CivicAlertCategory.road -> CivicAlertCategoryDto.road
    CivicAlertCategory.traffic -> CivicAlertCategoryDto.traffic
    CivicAlertCategory.other -> CivicAlertCategoryDto.other
}

private fun org.jetbrains.exposed.sql.ResultRow.toDto() = CivicAlertDto(
    id = this[CivicAlerts.id].value.toString(),
    chapterId = this[CivicAlerts.chapterId],
    category = this[CivicAlerts.category].toDto(),
    title = this[CivicAlerts.title],
    description = this[CivicAlerts.description],
    postedByAccountId = this[CivicAlerts.postedByAccountId].toString(),
    createdAt = this[CivicAlerts.createdAt].toString(),
    expiresAt = this[CivicAlerts.expiresAt].toString(),
)
