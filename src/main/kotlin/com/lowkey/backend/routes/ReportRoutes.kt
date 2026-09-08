package com.lowkey.backend.routes

import com.lowkey.backend.auth.SIGNED_CHALLENGE_AUTH
import com.lowkey.backend.auth.moderatorOnly
import com.lowkey.backend.auth.requireAccount
import com.lowkey.backend.models.CreateReportRequest
import com.lowkey.backend.models.ErrorResponse
import com.lowkey.backend.models.ResolveReportRequest
import com.lowkey.backend.services.ReportService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

fun Route.reportRoutes(reportService: ReportService) {
    authenticate(SIGNED_CHALLENGE_AUTH) {
        route("/reports") {
            post {
                val principal = call.requireAccount()
                val body = call.receive<CreateReportRequest>()
                val created = reportService.create(principal.accountId, body)
                call.respond(HttpStatusCode.Created, created)
            }

            moderatorOnly {
                get {
                    call.requireAccount()
                    call.respond(reportService.listOpenAndRecent())
                }

                post("/{id}/resolve") {
                    val principal = call.requireAccount()
                    val reportId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    if (reportId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid_report_id"))
                        return@post
                    }
                    val body = runCatching { call.receive<ResolveReportRequest>() }
                        .getOrElse { ResolveReportRequest() }
                    val resolved = reportService.resolve(reportId, principal.accountId, body)
                    call.respond(HttpStatusCode.OK, resolved)
                }
            }
        }
    }
}
