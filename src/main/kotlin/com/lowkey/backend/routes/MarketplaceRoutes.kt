package com.lowkey.backend.routes

import com.lowkey.backend.auth.SIGNED_CHALLENGE_AUTH
import com.lowkey.backend.auth.requireAccount
import com.lowkey.backend.models.CreateMarketplaceListingRequest
import com.lowkey.backend.services.MarketplaceService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.marketplaceRoutes(marketplaceService: MarketplaceService) {
    authenticate(SIGNED_CHALLENGE_AUTH) {
        get("/chapters/{id}/marketplace") {
            call.requireAccount()
            val chapterId = call.parameters["id"]?.trim().orEmpty()
            require(chapterId.isNotBlank()) { "chapter id is required" }
            call.respond(marketplaceService.listActive(chapterId))
        }

        post("/marketplace") {
            val principal = call.requireAccount()
            val body = call.receive<CreateMarketplaceListingRequest>()
            val created = marketplaceService.create(principal.accountId, body)
            call.respond(HttpStatusCode.Created, created)
        }
    }
}
