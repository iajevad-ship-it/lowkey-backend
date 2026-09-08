package com.lowkey.backend.services

import com.lowkey.backend.db.Accounts
import com.lowkey.backend.db.ChatMembers
import com.lowkey.backend.db.Chats
import com.lowkey.backend.db.ChapterContext
import com.lowkey.backend.db.Messages
import com.lowkey.backend.db.scopedTransaction
import com.lowkey.backend.models.ChatDto
import com.lowkey.backend.models.ChatMemberDto
import com.lowkey.backend.models.CreateChatRequest
import com.lowkey.backend.models.MessageDto
import com.lowkey.backend.models.SenderKeyDistributionDto
import com.lowkey.backend.models.WsServerEnvelope
import com.lowkey.backend.websocket.ConnectionRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ChatService(
    private val registry: ConnectionRegistry,
    private val fcmService: FcmService,
    private val accountService: AccountService,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun createChat(creatorId: UUID, request: CreateChatRequest): ChatDto = scopedTransaction {
        val chapterId = ChapterContext.current()
        if (request.chapterId.isNotBlank()) {
            require(request.chapterId.trim().equals(chapterId, ignoreCase = true)) {
                "chapterId must match your account chapter"
            }
        }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val expiresAt = request.expiresAt?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { OffsetDateTime.parse(raw) }.getOrElse {
                error("expiresAt must be an ISO-8601 timestamp")
            }.also { require(it.isAfter(now)) { "expiresAt must be in the future" } }
        }
        val memberIds = (request.memberIds.map { UUID.fromString(it) } + creatorId).toSet()

        // Every member must be visible under this chapter's RLS.
        for (memberId in memberIds) {
            require(Accounts.selectAll().where { Accounts.id eq memberId }.count() > 0) {
                "member $memberId not found in chapter"
            }
        }

        val chatId = Chats.insertAndGetId {
            it[isGroup] = request.isGroup
            it[name] = request.name?.trim()?.takeIf { n -> n.isNotEmpty() }
            it[Chats.chapterId] = chapterId
            it[Chats.expiresAt] = expiresAt
            it[createdAt] = now
        }

        memberIds.forEach { memberId ->
            ChatMembers.insert {
                it[ChatMembers.chatId] = chatId.value
                it[accountId] = memberId
            }
        }

        toChatDto(chatId.value, creatorId)
    }

    fun getChat(chatId: UUID, requesterId: UUID): ChatDto = scopedTransaction {
        require(isMember(chatId, requesterId)) { "not a member of this chat" }
        toChatDto(chatId, requesterId)
    }

    fun listChats(accountId: UUID): List<ChatDto> = scopedTransaction {
        val chatIds = ChatMembers.selectAll()
            .where { ChatMembers.accountId eq accountId }
            .map { it[ChatMembers.chatId] }
        if (chatIds.isEmpty()) return@scopedTransaction emptyList()

        chatIds
            .map { toChatDto(it, accountId) }
            .sortedByDescending { it.lastMessageAt ?: it.createdAt }
    }

    fun addMember(actorId: UUID, chatId: UUID, newMemberId: UUID): ChatDto = scopedTransaction {
        require(isMember(chatId, actorId)) { "not a member of this chat" }
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
            ?: error("chat not found")
        require(chat[Chats.isGroup]) { "cannot add members to a 1:1 chat" }
        require(!isMember(chatId, newMemberId)) { "already a member" }
        require(Accounts.selectAll().where { Accounts.id eq newMemberId }.count() > 0) {
            "member not found in chapter"
        }

        ChatMembers.insert {
            it[ChatMembers.chatId] = chatId
            it[accountId] = newMemberId
        }
        toChatDto(chatId, actorId)
    }

    fun removeMember(actorId: UUID, chatId: UUID, memberId: UUID): ChatDto = scopedTransaction {
        require(isMember(chatId, actorId)) { "not a member of this chat" }
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
            ?: error("chat not found")
        require(chat[Chats.isGroup]) { "cannot remove members from a 1:1 chat" }
        require(isMember(chatId, memberId)) { "not a member" }

        ChatMembers.deleteWhere {
            (ChatMembers.chatId eq chatId) and (ChatMembers.accountId eq memberId)
        }
        toChatDto(chatId, actorId)
    }

    /**
     * Self-join a [Chats.joinable] group in the caller's chapter.
     * Idempotent: already-members get the chat DTO back without error.
     */
    fun joinChat(accountId: UUID, chatId: UUID): ChatDto = scopedTransaction {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
            ?: error("chat not found")
        require(chat[Chats.chapterId] == ChapterContext.current()) {
            "chat not in your chapter"
        }
        require(chat[Chats.isGroup]) { "can only join group chats" }
        require(chat[Chats.joinable]) { "chat is not joinable" }
        require(Accounts.selectAll().where { Accounts.id eq accountId }.count() > 0) {
            "account not found in chapter"
        }
        if (!isMember(chatId, accountId)) {
            ChatMembers.insert {
                it[ChatMembers.chatId] = chatId
                it[ChatMembers.accountId] = accountId
            }
        }
        toChatDto(chatId, accountId)
    }

    /** Joinable community event rooms in the current chapter. */
    fun listEventRooms(): List<com.lowkey.backend.models.EventRoomDto> = scopedTransaction {
        Chats.selectAll()
            .where { (Chats.joinable eq true) and (Chats.isGroup eq true) }
            .orderBy(Chats.createdAt to SortOrder.DESC)
            .map { row ->
                val chatId = row[Chats.id].value
                val members = ChatMembers.selectAll()
                    .where { ChatMembers.chatId eq chatId }
                    .count()
                    .toInt()
                com.lowkey.backend.models.EventRoomDto(
                    chatId = chatId.toString(),
                    title = row[Chats.name] ?: "Event room",
                    description = "A shared room for everyone attending, opened for this event only",
                    expiresAt = row[Chats.expiresAt]?.toString(),
                    memberCount = members,
                )
            }
    }

    /**
     * Notify every current member (and, for removals, optionally the removed account)
     * so clients rotate sender keys.
     */
    suspend fun notifyMembershipChanged(chat: ChatDto, alsoNotify: UUID? = null) {
        val envelope = json.encodeToString(
            WsServerEnvelope(type = "membership_changed", chat = chat)
        )
        val targets = chat.memberIds.map { UUID.fromString(it) }.toMutableSet()
        if (alsoNotify != null) targets.add(alsoNotify)
        for (accountId in targets) {
            registry.send(accountId, envelope)
        }
    }

    /**
     * Deliver an opaque SenderKeyDistributionMessage to chat members.
     * When [recipientAccountId] is set, only that member receives it (per-recipient
     * ciphertext after client-side 1:1 encryption). Otherwise fan out to all others.
     */
    suspend fun fanoutSenderKeyDistribution(
        senderId: UUID,
        chatId: UUID,
        distributionId: String,
        distributionMessage: String,
        recipientAccountId: UUID? = null,
    ) {
        require(distributionId.isNotBlank()) { "distributionId is required" }
        require(distributionMessage.isNotBlank()) { "distributionMessage is required" }

        val memberIds = scopedTransaction {
            require(isMember(chatId, senderId)) { "not a member of this chat" }
            ChatMembers.selectAll()
                .where { ChatMembers.chatId eq chatId }
                .map { it[ChatMembers.accountId] }
        }

        val targets = if (recipientAccountId != null) {
            require(recipientAccountId != senderId) { "cannot send skdm to self" }
            require(memberIds.contains(recipientAccountId)) { "recipient not a member" }
            listOf(recipientAccountId)
        } else {
            memberIds.filter { it != senderId }
        }

        val dto = SenderKeyDistributionDto(
            chatId = chatId.toString(),
            senderAccountId = senderId.toString(),
            distributionId = distributionId,
            distributionMessage = distributionMessage,
        )
        val envelope = json.encodeToString(
            WsServerEnvelope(type = "sender_key_distribution", senderKey = dto)
        )
        for (recipient in targets) {
            registry.send(recipient, envelope)
        }
    }

    fun listMessages(chatId: UUID, requesterId: UUID, limit: Int = 100): List<MessageDto> = scopedTransaction {
        require(isMember(chatId, requesterId)) { "not a member of this chat" }
        val requesterReceipts = Accounts.selectAll()
            .where { Accounts.id eq requesterId }
            .firstOrNull()
            ?.get(Accounts.readReceiptsEnabled)
            ?: false
        Messages.selectAll()
            .where { Messages.chatId eq chatId }
            .orderBy(Messages.createdAt to SortOrder.ASC)
            .take(limit.coerceIn(1, 500))
            .map { row ->
                val senderId = row[Messages.senderAccountId]
                val rawDelivered = row[Messages.delivered]
                // Delivery/"seen" is only meaningful for the sender, and only when they
                // opted into read receipts. Never leak receipt status otherwise.
                val delivered = rawDelivered &&
                    senderId == requesterId &&
                    requesterReceipts
                MessageDto(
                    id = row[Messages.id].value.toString(),
                    chatId = row[Messages.chatId].toString(),
                    senderAccountId = senderId.toString(),
                    ciphertext = row[Messages.ciphertext],
                    createdAt = row[Messages.createdAt].toString(),
                    delivered = delivered,
                )
            }
    }

    /**
     * Persist an opaque ciphertext (1:1 Signal or group sender-key) and fan out
     * to every other ChatMember over their WebSocket sessions.
     */
    suspend fun sendMessage(senderId: UUID, chatId: UUID, ciphertext: String): MessageDto {
        require(ciphertext.isNotBlank()) { "ciphertext is required" }

        val (saved, memberIds, isGroup, chatName) = scopedTransaction {
            require(isMember(chatId, senderId)) { "not a member of this chat" }
            val chat = Chats.selectAll().where { Chats.id eq chatId }.first()
            val chapterId = chat[Chats.chapterId]
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val id = Messages.insertAndGetId {
                it[Messages.chatId] = chatId
                it[senderAccountId] = senderId
                it[Messages.chapterId] = chapterId
                it[Messages.ciphertext] = ciphertext
                it[createdAt] = now
                it[delivered] = false
            }
            val members = ChatMembers.selectAll()
                .where { ChatMembers.chatId eq chatId }
                .map { it[ChatMembers.accountId] }
            val dto = Messages.selectAll()
                .where { Messages.id eq id }
                .first()
                .toMessageDto()
            Quadruple(dto, members, chat[Chats.isGroup], chat[Chats.name])
        }

        val recipients = memberIds.filter { it != senderId }
        var allOnlineDelivered = true

        val envelope = json.encodeToString(WsServerEnvelope(type = "message", message = saved))
        for (recipient in recipients) {
            val pushed = registry.send(recipient, envelope)
            if (!pushed) {
                allOnlineDelivered = false
                // Content-free push only — never include ciphertext or plaintext.
                notifyOffline(recipient, chatId, isGroup, chatName)
            }
        }

        if (recipients.isEmpty()) allOnlineDelivered = true

        if (allOnlineDelivered) {
            scopedTransaction {
                Messages.update({ Messages.id eq UUID.fromString(saved.id) }) {
                    it[delivered] = true
                }
            }
            return saved.copy(delivered = true)
        }
        return saved
    }

    private suspend fun notifyOffline(
        accountId: UUID,
        chatId: UUID,
        isGroup: Boolean,
        chatName: String?,
    ) {
        val token = accountService.deviceTokenFor(accountId) ?: return
        val body = when {
            isGroup && !chatName.isNullOrBlank() -> "New message in $chatName"
            isGroup -> "New message in a group"
            else -> "New message"
        }
        fcmService.notifyNewMessage(
            deviceToken = token,
            chatId = chatId.toString(),
            body = body,
        )
    }

    suspend fun flushUndelivered(accountId: UUID) {
        val pending = scopedTransaction {
            val chatIds = ChatMembers.selectAll()
                .where { ChatMembers.accountId eq accountId }
                .map { it[ChatMembers.chatId] }
            if (chatIds.isEmpty()) return@scopedTransaction emptyList()

            Messages.selectAll()
                .where {
                    (Messages.chatId inList chatIds) and
                        (Messages.delivered eq false) and
                        (Messages.senderAccountId neq accountId)
                }
                .orderBy(Messages.createdAt to SortOrder.ASC)
                .map { it.toMessageDto() }
        }

        for (message in pending) {
            val envelope = json.encodeToString(WsServerEnvelope(type = "message", message = message))
            registry.send(accountId, envelope)
            maybeMarkDelivered(
                UUID.fromString(message.id),
                UUID.fromString(message.chatId),
                UUID.fromString(message.senderAccountId)
            )
        }
    }

    private fun maybeMarkDelivered(messageId: UUID, chatId: UUID, senderId: UUID) {
        val others = scopedTransaction {
            ChatMembers.selectAll()
                .where { ChatMembers.chatId eq chatId }
                .map { it[ChatMembers.accountId] }
                .filter { it != senderId }
        }
        if (others.all { registry.isOnline(it) }) {
            scopedTransaction {
                Messages.update({ Messages.id eq messageId }) {
                    it[delivered] = true
                }
            }
        }
    }

    private fun toChatDto(chatId: UUID, viewerId: UUID? = null): ChatDto {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.first()
        val members = ChatMembers.selectAll()
            .where { ChatMembers.chatId eq chatId }
            .map { it[ChatMembers.accountId] }
        val last = Messages.selectAll()
            .where { Messages.chatId eq chatId }
            .orderBy(Messages.createdAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
        val isGroup = chat[Chats.isGroup]
        val peerLastSeenAt = if (!isGroup && viewerId != null) {
            val peerId = members.firstOrNull { it != viewerId }
            if (peerId != null) {
                val peer = Accounts.selectAll().where { Accounts.id eq peerId }.firstOrNull()
                if (peer != null && peer[Accounts.lastSeenEnabled]) {
                    peer[Accounts.lastActiveAt]?.toString()
                } else null
            } else null
        } else null

        val memberDetails = members.map { memberId ->
            val verified = Accounts.selectAll()
                .where { Accounts.id eq memberId }
                .firstOrNull()
                ?.get(Accounts.isVerifiedOrganizer)
                ?: false
            ChatMemberDto(
                accountId = memberId.toString(),
                isVerifiedOrganizer = verified,
            )
        }

        // Caller's own vouch head only — never walk another member's chain.
        val vouchHead = viewerId?.let { accountService.vouchChainHeadDisplayName(it) }

        return ChatDto(
            id = chatId.toString(),
            isGroup = isGroup,
            chapterId = chat[Chats.chapterId],
            createdAt = chat[Chats.createdAt].toString(),
            memberIds = members.map { it.toString() },
            name = chat[Chats.name],
            expiresAt = chat[Chats.expiresAt]?.toString(),
            lastCiphertext = last?.get(Messages.ciphertext),
            lastMessageAt = last?.get(Messages.createdAt)?.toString(),
            lastSenderAccountId = last?.get(Messages.senderAccountId)?.toString(),
            peerLastSeenAt = peerLastSeenAt,
            joinable = chat[Chats.joinable],
            members = memberDetails,
            vouchChainHeadDisplayName = vouchHead,
        )
    }

    private fun isMember(chatId: UUID, accountId: UUID): Boolean =
        ChatMembers.selectAll()
            .where { (ChatMembers.chatId eq chatId) and (ChatMembers.accountId eq accountId) }
            .count() > 0

    private fun org.jetbrains.exposed.sql.ResultRow.toMessageDto() = MessageDto(
        id = this[Messages.id].value.toString(),
        chatId = this[Messages.chatId].toString(),
        senderAccountId = this[Messages.senderAccountId].toString(),
        ciphertext = this[Messages.ciphertext],
        createdAt = this[Messages.createdAt].toString(),
        delivered = this[Messages.delivered],
    )
}

/** Local 4-tuple helper for sendMessage destructuring. */
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
