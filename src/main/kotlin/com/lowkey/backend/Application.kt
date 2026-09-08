package com.lowkey.backend

import com.lowkey.backend.auth.AuthorizationException
import com.lowkey.backend.auth.ChallengeService
import com.lowkey.backend.auth.signedChallenge
import com.lowkey.backend.db.DatabaseFactory
import com.lowkey.backend.db.RedisFactory
import com.lowkey.backend.jobs.startExpiredChatCleanupJob
import com.lowkey.backend.jobs.startExpiredCivicAlertCleanupJob
import com.lowkey.backend.jobs.startMetadataRetentionJob
import com.lowkey.backend.models.ErrorResponse
import com.lowkey.backend.plugins.ChapterIsolation
import com.lowkey.backend.plugins.lastSeenIpTrackingPlugin
import com.lowkey.backend.routes.accountRoutes
import com.lowkey.backend.routes.alertRoutes
import com.lowkey.backend.routes.authRoutes
import com.lowkey.backend.routes.chapterRoutes
import com.lowkey.backend.routes.chatRoutes
import com.lowkey.backend.routes.communityRoutes
import com.lowkey.backend.routes.eventRoutes
import com.lowkey.backend.routes.healthRoutes
import com.lowkey.backend.routes.marketplaceRoutes
import com.lowkey.backend.routes.moderatorRoutes
import com.lowkey.backend.routes.reportRoutes
import com.lowkey.backend.services.AccountService
import com.lowkey.backend.services.ChatExpiryService
import com.lowkey.backend.services.ChatService
import com.lowkey.backend.services.CivicAlertExpiryService
import com.lowkey.backend.services.CivicAlertService
import com.lowkey.backend.services.EventService
import com.lowkey.backend.services.FcmService
import com.lowkey.backend.services.LastSeenIpService
import com.lowkey.backend.services.LocationPresenceService
import com.lowkey.backend.services.MarketplaceService
import com.lowkey.backend.services.MetadataRetentionService
import com.lowkey.backend.services.PreKeyBundleService
import com.lowkey.backend.services.ReportService
import com.lowkey.backend.services.SosAlertService
import com.lowkey.backend.websocket.ConnectionRegistry
import com.lowkey.backend.websocket.configureWebSockets
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }
    val challengeService = ChallengeService()
    val accountService = AccountService()
    val preKeyBundleService = PreKeyBundleService()
    val reportService = ReportService()
    val eventService = EventService()
    val marketplaceService = MarketplaceService()
    val civicAlertService = CivicAlertService()
    val civicAlertExpiryService = CivicAlertExpiryService()
    val chatExpiryService = ChatExpiryService()
    val metadataRetentionService = MetadataRetentionService(environment.config)
    val lastSeenIpService = LastSeenIpService()
    val fcmService = FcmService(environment.config)
    val locationPresenceService = LocationPresenceService(environment.config, json)
    val sosAlertService = SosAlertService(
        environment.config,
        locationPresenceService,
        accountService,
        fcmService,
    )
    val connectionRegistry = ConnectionRegistry()
    val chatService = ChatService(connectionRegistry, fcmService, accountService, json)

    install(CallLogging) {
        level = Level.INFO
    }
    install(ContentNegotiation) {
        json(json)
    }
    install(StatusPages) {
        exception<AuthorizationException> { call, cause ->
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse(error = "forbidden", hint = cause.message),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = cause.message ?: "bad_request"))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = cause.message ?: "bad_request"))
        }
    }
    install(Authentication) {
        signedChallenge {
            this.challengeService = challengeService
        }
    }
    // After Authentication — binds ChapterContext from AccountPrincipal.chapterId.
    install(ChapterIsolation)
    install(lastSeenIpTrackingPlugin(lastSeenIpService))

    DatabaseFactory.init(environment.config)
    RedisFactory.init(environment.config)

    configureWebSockets(chatService, connectionRegistry, json)

    routing {
        healthRoutes()
        authRoutes(challengeService)
        chapterRoutes(accountService)
        accountRoutes(accountService, preKeyBundleService)
        chatRoutes(chatService)
        communityRoutes(chatService)
        eventRoutes(eventService)
        marketplaceRoutes(marketplaceService)
        reportRoutes(reportService)
        moderatorRoutes(accountService)
        alertRoutes(sosAlertService, locationPresenceService, civicAlertService)
    }

    startExpiredChatCleanupJob(chatExpiryService)
    startExpiredCivicAlertCleanupJob(civicAlertExpiryService)
    startMetadataRetentionJob(metadataRetentionService)

    monitor.subscribe(ApplicationStopping) {
        fcmService.close()
        RedisFactory.close()
        DatabaseFactory.close()
    }
}
