package com.lowkey.backend.websocket

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of accountId → active WebSocket sessions.
 * A single account may have multiple devices/tabs connected.
 */
class ConnectionRegistry {
    private val sessions = ConcurrentHashMap<UUID, MutableSet<WebSocketSession>>()

    fun register(accountId: UUID, session: WebSocketSession) {
        sessions.compute(accountId) { _, existing ->
            val set = existing ?: ConcurrentHashMap.newKeySet()
            set.add(session)
            set
        }
    }

    fun unregister(accountId: UUID, session: WebSocketSession) {
        sessions.computeIfPresent(accountId) { _, set ->
            set.remove(session)
            if (set.isEmpty()) null else set
        }
    }

    fun isOnline(accountId: UUID): Boolean = sessions[accountId]?.isNotEmpty() == true

    /**
     * Push [payload] to every live session for [accountId].
     * @return true if at least one session accepted the frame
     */
    suspend fun send(accountId: UUID, payload: String): Boolean {
        val targets = sessions[accountId]?.toList().orEmpty()
        if (targets.isEmpty()) return false
        var delivered = false
        for (session in targets) {
            try {
                session.send(Frame.Text(payload))
                delivered = true
            } catch (_: Exception) {
                unregister(accountId, session)
            }
        }
        return delivered
    }
}
