package com.lowkey.backend.services

import com.lowkey.backend.db.ChatMembers
import com.lowkey.backend.db.Chats
import com.lowkey.backend.db.Messages
import com.lowkey.backend.db.bootstrapTransaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Hard-deletes chats whose [Chats.expiresAt] is in the past, including
 * related [Messages] and [ChatMembers] rows (actual DELETE, not a soft flag).
 * Runs under RLS bypass so every chapter is covered.
 */
class ChatExpiryService {
    private val log = LoggerFactory.getLogger(ChatExpiryService::class.java)

    fun purgeExpired(): Int = bootstrapTransaction {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val expiredIds: List<UUID> = Chats.selectAll()
            .where { (Chats.expiresAt.isNotNull()) and (Chats.expiresAt less now) }
            .map { it[Chats.id].value }

        if (expiredIds.isEmpty()) return@bootstrapTransaction 0

        val messagesDeleted = Messages.deleteWhere { Messages.chatId inList expiredIds }
        val membersDeleted = ChatMembers.deleteWhere { ChatMembers.chatId inList expiredIds }
        val chatsDeleted = Chats.deleteWhere { Chats.id inList expiredIds }

        log.info(
            "Purged {} expired chat(s): deleted {} message(s), {} member row(s), {} chat(s)",
            expiredIds.size,
            messagesDeleted,
            membersDeleted,
            chatsDeleted,
        )
        chatsDeleted
    }
}
