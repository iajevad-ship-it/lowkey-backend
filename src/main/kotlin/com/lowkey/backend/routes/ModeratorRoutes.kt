package com.lowkey.backend.routes

import com.lowkey.backend.auth.SIGNED_CHALLENGE_AUTH
import com.lowkey.backend.auth.moderatorOnly
import com.lowkey.backend.auth.requireAccount
import com.lowkey.backend.models.ErrorResponse
import com.lowkey.backend.services.AccountService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

fun Route.moderatorRoutes(accountService: AccountService) {
    authenticate(SIGNED_CHALLENGE_AUTH) {
        moderatorOnly {
            route("/moderator") {
                post("/accounts/{id}/verify") {
                    call.requireAccount()
                    val accountId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    if (accountId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid_account_id"))
                        return@post
                    }
                    try {
                        val updated = accountService.verifyOrganizer(accountId)
                        call.respond(HttpStatusCode.OK, updated)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                    } catch (e: IllegalStateException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                    }
                }
            }
        }
    }
}
