package com.lowkey.backend.websocket

import com.lowkey.backend.auth.SIGNED_CHALLENGE_AUTH
import com.lowkey.backend.auth.requireAccount
import com.lowkey.backend.db.ChapterContext
import com.lowkey.backend.models.WsClientMessage
import com.lowkey.backend.models.WsServerEnvelope
import com.lowkey.backend.services.ChatService
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

fun Application.configureWebSockets(
    chatService: ChatService,
    registry: ConnectionRegistry,
    json: Json = Json { ignoreUnknownKeys = true },
) {
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {
        authenticate(SIGNED_CHALLENGE_AUTH) {
            webSocket("/ws") {
                val principal = call.requireAccount()
                val accountId = principal.accountId

                // Propagate chapter across WS coroutine dispatches for scopedTransaction/RLS.
                withContext(ChapterContext.Element(principal.chapterId)) {
                    registry.register(accountId, this@webSocket)
                    try {
                        chatService.flushUndelivered(accountId)

                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val text = frame.readText()
                            val inbound = runCatching { json.decodeFromString<WsClientMessage>(text) }.getOrNull()
                            if (inbound == null) {
                                send(Frame.Text(json.encodeToString(WsServerEnvelope(type = "error", error = "invalid_json"))))
                                continue
                            }

                            when (inbound.type) {
                                "send" -> handleSend(chatService, json, accountId, inbound)
                                "sender_key_distribution" -> handleSenderKey(chatService, json, accountId, inbound)
                                else -> send(
                                    Frame.Text(
                                        json.encodeToString(WsServerEnvelope(type = "error", error = "unknown_type"))
                                    )
                                )
                            }
                        }
                    } finally {
                        registry.unregister(accountId, this@webSocket)
                    }
                }
            }
        }
    }
}

private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.handleSend(
    chatService: ChatService,
    json: Json,
    accountId: UUID,
    inbound: WsClientMessage,
) {
    val chatId = inbound.chatId
    val ciphertext = inbound.ciphertext
    if (chatId.isNullOrBlank() || ciphertext.isNullOrBlank()) {
        send(
            Frame.Text(
                json.encodeToString(WsServerEnvelope(type = "error", error = "chatId_and_ciphertext_required"))
            )
        )
        return
    }
    try {
        val saved = chatService.sendMessage(
            senderId = accountId,
            chatId = UUID.fromString(chatId),
            ciphertext = ciphertext,
        )
        send(Frame.Text(json.encodeToString(WsServerEnvelope(type = "message", message = saved))))
    } catch (e: IllegalArgumentException) {
        send(Frame.Text(json.encodeToString(WsServerEnvelope(type = "error", error = e.message ?: "bad_request"))))
    } catch (e: Exception) {
        send(Frame.Text(json.encodeToString(WsServerEnvelope(type = "error", error = e.message ?: "send_failed"))))
    }
}

private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.handleSenderKey(
    chatService: ChatService,
    json: Json,
    accountId: UUID,
    inbound: WsClientMessage,
) {
    val chatId = inbound.chatId
    val distributionId = inbound.distributionId
    val distributionMessage = inbound.distributionMessage
    if (chatId.isNullOrBlank() || distributionId.isNullOrBlank() || distributionMessage.isNullOrBlank()) {
        send(
            Frame.Text(
                json.encodeToString(
                    WsServerEnvelope(type = "error", error = "chatId_distributionId_and_distributionMessage_required")
                )
            )
        )
        return
    }
    try {
        chatService.fanoutSenderKeyDistribution(
            senderId = accountId,
            chatId = UUID.fromString(chatId),
            distributionId = distributionId,
            distributionMessage = distributionMessage,
            recipientAccountId = inbound.recipientAccountId?.let { UUID.fromString(it) },
        )
        send(Frame.Text(json.encodeToString(WsServerEnvelope(type = "sender_key_ack"))))
    } catch (e: IllegalArgumentException) {
        send(Frame.Text(json.encodeToString(WsServerEnvelope(type = "error", error = e.message ?: "bad_request"))))
    } catch (e: Exception) {
        send(Frame.Text(json.encodeToString(WsServerEnvelope(type = "error", error = e.message ?: "skdm_failed"))))
    }
}
