package com.lowkey.backend.services

import com.lowkey.backend.db.ChapterContext
import com.lowkey.backend.db.MarketplaceListings
import com.lowkey.backend.db.scopedTransaction
import com.lowkey.backend.models.CreateMarketplaceListingRequest
import com.lowkey.backend.models.MarketplaceListingDto
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class MarketplaceService {
    fun listActive(chapterId: String): List<MarketplaceListingDto> = scopedTransaction {
        requireChapterMatchesCaller(chapterId)
        MarketplaceListings.selectAll()
            .where { MarketplaceListings.chapterId eq ChapterContext.current() }
            .orderBy(MarketplaceListings.createdAt to SortOrder.DESC)
            .map { it.toListingDto() }
    }

    fun create(sellerId: UUID, request: CreateMarketplaceListingRequest): MarketplaceListingDto =
        scopedTransaction {
            val chapterId = ChapterContext.current()
            require(request.title.isNotBlank()) { "title is required" }
            require(request.description.isNotBlank()) { "description is required" }

            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val id = MarketplaceListings.insertAndGetId {
                it[MarketplaceListings.chapterId] = chapterId
                it[title] = request.title.trim()
                it[description] = request.description.trim()
                it[sellerAccountId] = sellerId
                it[createdAt] = now
            }
            MarketplaceListings.selectAll().where { MarketplaceListings.id eq id }.first().toListingDto()
        }

    private fun requireChapterMatchesCaller(pathChapterId: String) {
        val caller = ChapterContext.current()
        require(pathChapterId.trim().equals(caller, ignoreCase = true)) {
            "chapterId must match your account chapter"
        }
    }
}

private fun org.jetbrains.exposed.sql.ResultRow.toListingDto() = MarketplaceListingDto(
    id = this[MarketplaceListings.id].value.toString(),
    chapterId = this[MarketplaceListings.chapterId],
    title = this[MarketplaceListings.title],
    description = this[MarketplaceListings.description],
    sellerAccountId = this[MarketplaceListings.sellerAccountId].toString(),
    createdAt = this[MarketplaceListings.createdAt].toString(),
)
