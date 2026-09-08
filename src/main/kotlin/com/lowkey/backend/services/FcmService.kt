package com.lowkey.backend.services

import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Firebase Cloud Messaging via the HTTP v1 API.
 * Push bodies are intentionally content-free — never include message ciphertext or plaintext.
 */
class FcmService(
    private val config: ApplicationConfig,
) {
    private val log = LoggerFactory.getLogger(FcmService::class.java)

    private val enabled: Boolean =
        config.propertyOrNull("fcm.enabled")?.getString()?.equals("true", ignoreCase = true) == true

    private val projectId: String =
        config.propertyOrNull("fcm.projectId")?.getString()?.trim().orEmpty()

    private val credentialsPath: String =
        config.propertyOrNull("fcm.credentialsPath")?.getString()?.trim().orEmpty()

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
    }

    private val cachedCredentials = AtomicReference<GoogleCredentials?>(null)

    val isConfigured: Boolean =
        enabled && projectId.isNotBlank() && credentialsPath.isNotBlank()

    init {
        if (enabled && !isConfigured) {
            log.warn("FCM enabled but FCM_PROJECT_ID / GOOGLE_APPLICATION_CREDENTIALS incomplete — pushes disabled")
        } else if (isConfigured) {
            log.info("FCM HTTP v1 ready for project {}", projectId)
        }
    }

    /**
     * Notify an offline device that a new message is waiting.
     * [body] must never contain message content — only generic copy.
     */
    suspend fun notifyNewMessage(deviceToken: String, chatId: String, body: String) {
        send(
            deviceToken = deviceToken,
            notificationBody = body,
            data = mapOf(
                "type" to "new_message",
                "chatId" to chatId,
            ),
        )
    }

    /**
     * Content-free SOS alert for nearby chapter members.
     * Includes approximate coordinates so the client can show a map pin —
     * never includes account names, keys, or other personal fields.
     */
    suspend fun notifySos(
        deviceToken: String,
        lat: Double,
        lng: Double,
        distanceMeters: Double,
    ) {
        send(
            deviceToken = deviceToken,
            notificationBody = "Someone nearby needs help",
            data = mapOf(
                "type" to "sos",
                "lat" to lat.toString(),
                "lng" to lng.toString(),
                "distanceMeters" to distanceMeters.toInt().toString(),
            ),
        )
    }

    private suspend fun send(
        deviceToken: String,
        notificationBody: String,
        data: Map<String, String>,
    ) {
        if (!isConfigured || deviceToken.isBlank()) return
        try {
            val accessToken = accessToken()
            val url = "https://fcm.googleapis.com/v1/projects/$projectId/messages:send"
            val payload = FcmSendRequest(
                message = FcmMessage(
                    token = deviceToken,
                    notification = FcmNotification(
                        title = "Lowkey",
                        body = notificationBody,
                    ),
                    data = data,
                )
            )
            val response = http.post(url) {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            if (!response.status.isSuccess()) {
                log.warn("FCM send failed ({}): {}", response.status, response.bodyAsText())
            }
        } catch (e: Exception) {
            log.warn("FCM send error: {}", e.message)
        }
    }

    fun close() {
        http.close()
    }

    private fun accessToken(): String {
        val existing = cachedCredentials.get()
        val credentials = existing ?: synchronized(this) {
            cachedCredentials.get() ?: loadCredentials().also { cachedCredentials.set(it) }
        }
        credentials.refreshIfExpired()
        return credentials.accessToken.tokenValue
    }

    private fun loadCredentials(): GoogleCredentials {
        FileInputStream(credentialsPath).use { stream ->
            return GoogleCredentials.fromStream(stream)
                .createScoped(listOf("https://www.googleapis.com/auth/firebase.messaging"))
        }
    }
}

@Serializable
private data class FcmSendRequest(val message: FcmMessage)

@Serializable
private data class FcmMessage(
    val token: String,
    val notification: FcmNotification,
    val data: Map<String, String> = emptyMap(),
)

@Serializable
private data class FcmNotification(
    val title: String,
    val body: String,
)
