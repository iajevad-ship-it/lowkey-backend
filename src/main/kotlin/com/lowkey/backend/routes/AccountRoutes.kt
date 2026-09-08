package com.lowkey.backend.routes

import com.lowkey.backend.auth.ChallengeService
import com.lowkey.backend.auth.SIGNED_CHALLENGE_AUTH
import com.lowkey.backend.auth.requireAccount
import com.lowkey.backend.models.ChallengeRequest
import com.lowkey.backend.models.ChallengeResponse
import com.lowkey.backend.models.CreateAccountRequest
import com.lowkey.backend.models.DeviceTokenRequest
import com.lowkey.backend.models.ErrorResponse
import com.lowkey.backend.models.PreKeyBundleRequest
import com.lowkey.backend.models.StatusResponse
import com.lowkey.backend.models.UpdateDisplayNameRequest
import com.lowkey.backend.models.UpdatePreferencesRequest
import com.lowkey.backend.services.AccountService
import com.lowkey.backend.services.PreKeyBundleService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

fun Route.authRoutes(challengeService: ChallengeService) {
    route("/auth") {
        post("/challenge") {
            val body = call.receive<ChallengeRequest>()
            require(body.publicKey.isNotBlank()) { "publicKey is required" }
            try {
                val (nonce, ttl) = challengeService.issue(body.publicKey.trim())
                call.respond(ChallengeResponse(nonce = nonce, expiresInSeconds = ttl))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse(error = "redis_unavailable", hint = e.message)
                )
            }
        }
    }
}

fun Route.chapterRoutes(accountService: AccountService = AccountService()) {
    get("/chapters") {
        call.respond(accountService.listChapters())
    }
}

fun Route.accountRoutes(
    accountService: AccountService = AccountService(),
    preKeyBundleService: PreKeyBundleService = PreKeyBundleService(),
) {
    route("/accounts") {
        post {
            val body = call.receive<CreateAccountRequest>()
            try {
                val created = accountService.createAccount(body)
                call.respond(HttpStatusCode.Created, created)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
            }
        }

        authenticate(SIGNED_CHALLENGE_AUTH) {
            post("/invite-codes") {
                val principal = call.requireAccount()
                val invite = accountService.createInviteCode(principal.accountId)
                call.respond(HttpStatusCode.Created, invite)
            }

            get("/contacts") {
                val principal = call.requireAccount()
                call.respond(accountService.listContacts(principal.accountId))
            }

            get("/me") {
                val principal = call.requireAccount()
                val account = accountService.getById(principal.accountId)
                if (account == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "account_not_found"))
                } else {
                    call.respond(account)
                }
            }

            patch("/me/display-name") {
                val principal = call.requireAccount()
                val body = call.receive<UpdateDisplayNameRequest>()
                try {
                    val updated = accountService.updateDisplayName(principal.accountId, body.displayName)
                    call.respond(HttpStatusCode.OK, updated)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                }
            }

            patch("/me/preferences") {
                val principal = call.requireAccount()
                val body = call.receive<UpdatePreferencesRequest>()
                try {
                    val updated = accountService.updatePreferences(principal.accountId, body)
                    call.respond(HttpStatusCode.OK, updated)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                }
            }

            get("/me/devices") {
                val principal = call.requireAccount()
                // Multi-device Signal sessions are not implemented yet — stub returns this device only.
                call.respond(accountService.listDevices(principal.accountId))
            }

            post("/me/device-token") {
                val principal = call.requireAccount()
                val body = call.receive<DeviceTokenRequest>()
                try {
                    accountService.updateDeviceToken(principal.accountId, body.deviceToken)
                    call.respond(HttpStatusCode.OK, StatusResponse(status = "ok"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                }
            }

            get("/{id}/prekey-bundle") {
                val principal = call.requireAccount()
                val accountId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                if (accountId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid_account_id"))
                    return@get
                }
                // Same chapter only — RLS also enforces this; explicit check for clearer 404 vs empty.
                val bundle = preKeyBundleService.get(accountId, principal.chapterId)
                if (bundle == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "prekey_bundle_not_found"))
                } else {
                    call.respond(bundle)
                }
            }

            post("/{id}/prekey-bundle") {
                val principal = call.requireAccount()
                val accountId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                if (accountId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid_account_id"))
                    return@post
                }
                if (accountId != principal.accountId) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(error = "can_only_upload_own_bundle"))
                    return@post
                }
                val body = call.receive<PreKeyBundleRequest>()
                val saved = preKeyBundleService.upsert(accountId, body.bundle, principal.chapterId)
                call.respond(HttpStatusCode.OK, saved)
            }
        }
    }
}
