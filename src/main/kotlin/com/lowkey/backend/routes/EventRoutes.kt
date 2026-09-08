package com.lowkey.backend.routes

import com.lowkey.backend.auth.SIGNED_CHALLENGE_AUTH
import com.lowkey.backend.auth.requireAccount
import com.lowkey.backend.models.CreateEventRequest
import com.lowkey.backend.services.EventService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.eventRoutes(eventService: EventService) {
    authenticate(SIGNED_CHALLENGE_AUTH) {
        get("/chapters/{id}/events") {
            call.requireAccount()
            val chapterId = call.parameters["id"]?.trim().orEmpty()
            require(chapterId.isNotBlank()) { "chapter id is required" }
            // ChapterIsolation binds ChapterContext; service rejects mismatched path ids.
            call.respond(eventService.listUpcoming(chapterId))
        }

        post("/events") {
            val principal = call.requireAccount()
            val body = call.receive<CreateEventRequest>()
            val created = eventService.create(principal.accountId, body)
            call.respond(HttpStatusCode.Created, created)
        }
    }
}
