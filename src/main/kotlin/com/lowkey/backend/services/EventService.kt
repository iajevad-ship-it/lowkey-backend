package com.lowkey.backend.services

import com.lowkey.backend.db.ChapterContext
import com.lowkey.backend.db.Events
import com.lowkey.backend.db.scopedTransaction
import com.lowkey.backend.models.CreateEventRequest
import com.lowkey.backend.models.EventDto
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class EventService {
    fun listUpcoming(chapterId: String): List<EventDto> = scopedTransaction {
        requireChapterMatchesCaller(chapterId)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        Events.selectAll()
            .where {
                (Events.chapterId eq ChapterContext.current()) and (Events.eventTime greaterEq now)
            }
            .orderBy(Events.eventTime to SortOrder.ASC)
            .map { it.toEventDto() }
    }

    fun create(creatorId: UUID, request: CreateEventRequest): EventDto = scopedTransaction {
        val chapterId = ChapterContext.current()
        require(request.title.isNotBlank()) { "title is required" }
        require(request.description.isNotBlank()) { "description is required" }
        require(request.location.isNotBlank()) { "location is required" }

        val eventTime = runCatching { OffsetDateTime.parse(request.eventTime.trim()) }
            .getOrElse { error("eventTime must be an ISO-8601 timestamp") }
        require(eventTime.isAfter(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))) {
            "eventTime must be in the future"
        }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val id = Events.insertAndGetId {
            it[Events.chapterId] = chapterId
            it[title] = request.title.trim()
            it[description] = request.description.trim()
            it[location] = request.location.trim()
            it[Events.eventTime] = eventTime
            it[createdByAccountId] = creatorId
            it[createdAt] = now
        }
        Events.selectAll().where { Events.id eq id }.first().toEventDto()
    }

    private fun requireChapterMatchesCaller(pathChapterId: String) {
        val caller = ChapterContext.current()
        require(pathChapterId.trim().equals(caller, ignoreCase = true)) {
            "chapterId must match your account chapter"
        }
    }
}

private fun org.jetbrains.exposed.sql.ResultRow.toEventDto() = EventDto(
    id = this[Events.id].value.toString(),
    chapterId = this[Events.chapterId],
    title = this[Events.title],
    description = this[Events.description],
    location = this[Events.location],
    eventTime = this[Events.eventTime].toString(),
    createdByAccountId = this[Events.createdByAccountId].toString(),
    createdAt = this[Events.createdAt].toString(),
)
