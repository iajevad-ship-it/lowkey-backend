package com.lowkey.backend.routes

import com.lowkey.backend.auth.SIGNED_CHALLENGE_AUTH
import com.lowkey.backend.auth.requireAccount
import com.lowkey.backend.models.CreateCivicAlertRequest
import com.lowkey.backend.models.ErrorResponse
import com.lowkey.backend.models.SosAlertRequest
import com.lowkey.backend.models.SosAlertResponse
import com.lowkey.backend.models.StatusResponse
import com.lowkey.backend.services.CivicAlertService
import com.lowkey.backend.services.LocationPresenceService
import com.lowkey.backend.services.SosAlertService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.alertRoutes(
    sosAlertService: SosAlertService,
    locationPresenceService: LocationPresenceService,
    civicAlertService: CivicAlertService,
) {
    authenticate(SIGNED_CHALLENGE_AUTH) {
        get("/chapters/{id}/alerts") {
            call.requireAccount()
            val chapterId = call.parameters["id"]?.trim().orEmpty()
            require(chapterId.isNotBlank()) { "chapter id is required" }
            call.respond(civicAlertService.listActive(chapterId))
        }

        route("/alerts") {
            post {
                val principal = call.requireAccount()
                val body = call.receive<CreateCivicAlertRequest>()
                val created = civicAlertService.create(principal.accountId, body)
                call.respond(HttpStatusCode.Created, created)
            }

            // Quiet Redis presence refresh (TTL) so SOS can find this account later.
            post("/location") {
                val principal = call.requireAccount()
                val body = call.receive<SosAlertRequest>()
                try {
                    locationPresenceService.put(
                        accountId = principal.accountId,
                        chapterId = principal.chapterId,
                        lat = body.lat,
                        lng = body.lng,
                    )
                    call.respond(HttpStatusCode.OK, StatusResponse(status = "ok"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                } catch (e: IllegalStateException) {
                    val status = if (e.message == "redis_unavailable") {
                        HttpStatusCode.ServiceUnavailable
                    } else {
                        HttpStatusCode.BadRequest
                    }
                    call.respond(status, ErrorResponse(error = e.message ?: "bad_request"))
                }
            }

            post("/sos") {
                val principal = call.requireAccount()
                val body = call.receive<SosAlertRequest>()
                try {
                    val result = sosAlertService.triggerSos(
                        accountId = principal.accountId,
                        chapterId = principal.chapterId,
                        lat = body.lat,
                        lng = body.lng,
                    )
                    call.respond(
                        HttpStatusCode.OK,
                        SosAlertResponse(
                            notifiedCount = result.notifiedCount,
                            nearbyCount = result.nearbyCount,
                            radiusMeters = result.radiusMeters,
                            locationTtlSeconds = result.locationTtlSeconds,
                        ),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                } catch (e: IllegalStateException) {
                    val status = if (e.message == "redis_unavailable") {
                        HttpStatusCode.ServiceUnavailable
                    } else {
                        HttpStatusCode.BadRequest
                    }
                    call.respond(status, ErrorResponse(error = e.message ?: "bad_request"))
                }
            }
        }
    }
}
