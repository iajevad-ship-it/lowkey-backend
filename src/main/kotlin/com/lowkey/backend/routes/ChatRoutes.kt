package com.lowkey.backend.routes

import com.lowkey.backend.auth.SIGNED_CHALLENGE_AUTH
import com.lowkey.backend.auth.requireAccount
import com.lowkey.backend.models.AddMemberRequest
import com.lowkey.backend.models.CreateChatRequest
import com.lowkey.backend.models.ErrorResponse
import com.lowkey.backend.models.PatchMembersRequest
import com.lowkey.backend.services.ChatService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

fun Route.chatRoutes(chatService: ChatService) {
    authenticate(SIGNED_CHALLENGE_AUTH) {
        route("/chats") {
            get {
                val principal = call.requireAccount()
                call.respond(chatService.listChats(principal.accountId))
            }

            post {
                val principal = call.requireAccount()
                val body = call.receive<CreateChatRequest>()
                val chat = chatService.createChat(principal.accountId, body)
                // Creator should rotate/distribute sender keys client-side when isGroup.
                chatService.notifyMembershipChanged(chat)
                call.respond(HttpStatusCode.Created, chat)
            }

            get("/{id}") {
                val principal = call.requireAccount()
                val chatId = parseUuid(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid_chat_id"))
                    return@get
                }
                try {
                    call.respond(chatService.getChat(chatId, principal.accountId))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(error = e.message ?: "forbidden"))
                }
            }

            get("/{id}/messages") {
                val principal = call.requireAccount()
                val chatId = parseUuid(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid_chat_id"))
                    return@get
                }
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                try {
                    call.respond(chatService.listMessages(chatId, principal.accountId, limit))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(error = e.message ?: "forbidden"))
                }
            }

            post("/{id}/join") {
                val principal = call.requireAccount()
                val chatId = parseUuid(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid_chat_id"))
                    return@post
                }
                try {
                    val chat = chatService.joinChat(principal.accountId, chatId)
                    chatService.notifyMembershipChanged(chat)
                    call.respond(HttpStatusCode.OK, chat)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                }
            }

            patch("/{id}/members") {
                val principal = call.requireAccount()
                val chatId = parseUuid(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid_chat_id"))
                    return@patch
                }
                val body = call.receive<PatchMembersRequest>()
                val memberId = parseUuid(body.accountId) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid_account_id"))
                    return@patch
                }
                try {
                    when (body.action.trim().lowercase()) {
                        "add" -> {
                            val chat = chatService.addMember(principal.accountId, chatId, memberId)
                            chatService.notifyMembershipChanged(chat)
                            call.respond(HttpStatusCode.OK, chat)
                        }
                        "remove" -> {
                            val chat = chatService.removeMember(principal.accountId, chatId, memberId)
                            chatService.notifyMembershipChanged(chat, alsoNotify = memberId)
                            call.respond(HttpStatusCode.OK, chat)
                        }
                        else -> call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(error = "action must be add or remove"),
                        )
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                }
            }

            post("/{id}/members") {
                val principal = call.requireAccount()
                val chatId = parseUuid(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid_chat_id"))
                    return@post
                }
                val body = call.receive<AddMemberRequest>()
                val newMemberId = parseUuid(body.accountId) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid_account_id"))
                    return@post
                }
                try {
                    val chat = chatService.addMember(principal.accountId, chatId, newMemberId)
                    chatService.notifyMembershipChanged(chat)
                    call.respond(HttpStatusCode.OK, chat)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                }
            }

            delete("/{id}/members/{accountId}") {
                val principal = call.requireAccount()
                val chatId = parseUuid(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid_chat_id"))
                    return@delete
                }
                val memberId = parseUuid(call.parameters["accountId"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid_account_id"))
                    return@delete
                }
                try {
                    val chat = chatService.removeMember(principal.accountId, chatId, memberId)
                    chatService.notifyMembershipChanged(chat, alsoNotify = memberId)
                    call.respond(HttpStatusCode.OK, chat)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "bad_request"))
                }
            }
        }
    }
}

private fun parseUuid(raw: String?): UUID? =
    raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
