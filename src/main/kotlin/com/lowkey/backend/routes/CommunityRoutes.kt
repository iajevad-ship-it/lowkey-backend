package com.lowkey.backend.routes

import com.lowkey.backend.auth.SIGNED_CHALLENGE_AUTH
import com.lowkey.backend.auth.requireAccount
import com.lowkey.backend.services.ChatService
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.communityRoutes(chatService: ChatService) {
    authenticate(SIGNED_CHALLENGE_AUTH) {
        route("/community") {
            get("/event-rooms") {
                call.requireAccount()
                call.respond(chatService.listEventRooms())
            }
        }
    }
}
