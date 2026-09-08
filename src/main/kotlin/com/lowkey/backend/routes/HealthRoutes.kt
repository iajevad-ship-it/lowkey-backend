package com.lowkey.backend.routes

import com.lowkey.backend.services.HealthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String)

fun Route.healthRoutes(healthService: HealthService = HealthService()) {
    get("/health") {
        call.respond(HttpStatusCode.OK, healthService.check())
    }
}
