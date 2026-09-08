package com.lowkey.backend.db

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

/**
 * City chapters — the tenancy boundary for all Lowkey data.
 */
object Chapters : Table("chapters") {
    val id = varchar("id", 64)
    val name = varchar("name", 128)
    val isLive = bool("is_live").default(false)

    override val primaryKey = PrimaryKey(id)
}

/**
 * Identity is a cryptographic public key — never phone, email, or legal name.
 * [displayName] is a chosen handle for the product UI only.
 */
object Accounts : UUIDTable("accounts") {
    val publicKey = text("public_key").uniqueIndex()
    val displayName = varchar("display_name", 64)
    val chapterId = varchar("chapter_id", 64)
        .references(Chapters.id, onDelete = ReferenceOption.RESTRICT)
        .index()
    /** Chapter moderators — see reports + vouch graph only, never personal fields. */
    val isModerator = bool("is_moderator").default(false)
    /** Event organizers — set only via POST /moderator/accounts/{id}/verify. */
    val isVerifiedOrganizer = bool("is_verified_organizer").default(false)
    /** Set by moderators via report resolve — blocked from auth when true. */
    val banned = bool("banned").default(false)
    /** FCM registration token for content-free push when the user is offline. */
    val deviceToken = text("device_token").nullable()
    /** When [deviceToken] was last set — used by retention purge. */
    val deviceTokenUpdatedAt = timestampWithTimeZone("device_token_updated_at").nullable()
    /** When false, others must not see read/delivery receipts for this account. */
    val readReceiptsEnabled = bool("read_receipts_enabled").default(false)
    /** When false, [lastActiveAt] is not exposed to other accounts. */
    val lastSeenEnabled = bool("last_seen_enabled").default(false)
    /** When true, new chats created by this account default to a 7-day [Chats.expiresAt]. */
    val disappearingMessagesDefault = bool("disappearing_messages_default").default(true)
    /** Updated on authenticated activity; only shared when [lastSeenEnabled]. */
    val lastActiveAt = timestampWithTimeZone("last_active_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }
}

object InviteCodes : Table("invite_codes") {
    val code = varchar("code", 32)
    val issuedByAccountId = uuid("issued_by_account_id")
        .references(Accounts.id, onDelete = ReferenceOption.CASCADE)
    val redeemedByAccountId = uuid("redeemed_by_account_id")
        .references(Accounts.id, onDelete = ReferenceOption.SET_NULL)
        .nullable()
    val expiresAt = timestampWithTimeZone("expires_at")
    val usedAt = timestampWithTimeZone("used_at").nullable()

    override val primaryKey = PrimaryKey(code)
}

object VouchLinks : Table("vouch_links") {
    val voucherAccountId = uuid("voucher_account_id")
        .references(Accounts.id, onDelete = ReferenceOption.CASCADE)
    val voucheeAccountId = uuid("vouchee_account_id")
        .references(Accounts.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }

    override val primaryKey = PrimaryKey(voucherAccountId, voucheeAccountId)
}

object Chats : UUIDTable("chats") {
    val isGroup = bool("is_group").default(false)
    /** Optional group title for content-free push copy only — never message body. */
    val name = varchar("name", 128).nullable()
    val chapterId = varchar("chapter_id", 64)
        .references(Chapters.id, onDelete = ReferenceOption.RESTRICT)
        .index()
    /**
     * When set and in the past, [com.lowkey.backend.jobs.ExpiredChatCleanupJob]
     * hard-deletes this chat and its messages/members. Null = no expiry.
     */
    val expiresAt = timestampWithTimeZone("expires_at").nullable().index()
    /**
     * When true, any chapter member may [ChatService.joinChat] without an invite.
     * Used for community event rooms.
     */
    val joinable = bool("joinable").default(false)
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }
}

object ChatMembers : Table("chat_members") {
    val chatId = uuid("chat_id").references(Chats.id, onDelete = ReferenceOption.CASCADE)
    val accountId = uuid("account_id").references(Accounts.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(chatId, accountId)
}

/**
 * [ciphertext] is opaque end-to-end ciphertext as far as the server is concerned —
 * stored and forwarded as a string with no server-side crypto.
 */
object Messages : UUIDTable("messages") {
    val chatId = uuid("chat_id").references(Chats.id, onDelete = ReferenceOption.CASCADE).index()
    val senderAccountId = uuid("sender_account_id").references(Accounts.id, onDelete = ReferenceOption.CASCADE)
    val chapterId = varchar("chapter_id", 64)
        .references(Chapters.id, onDelete = ReferenceOption.RESTRICT)
        .index()
    val ciphertext = text("ciphertext")
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }
    val delivered = bool("delivered").default(false)
}

/**
 * Opaque Signal Protocol prekey bundle JSON for an account.
 * Server never parses or interprets the contents — store and serve only.
 */
object PreKeyBundles : Table("prekey_bundles") {
    val accountId = uuid("account_id")
        .references(Accounts.id, onDelete = ReferenceOption.CASCADE)
    val bundle = text("bundle")
    val updatedAt = timestampWithTimeZone("updated_at").clientDefault { OffsetDateTime.now() }

    override val primaryKey = PrimaryKey(accountId)
}

/**
 * Chapter-scoped moderation reports. Responses expose only anonymous account IDs
 * and vouch-link edges — never display names, keys, or device tokens.
 */
object Reports : UUIDTable("reports") {
    /** Null when the report flags the whole chat rather than a specific account. */
    val reportedAccountId = uuid("reported_account_id")
        .references(Accounts.id, onDelete = ReferenceOption.CASCADE)
        .nullable()
        .index()
    val reportedByAccountId = uuid("reported_by_account_id")
        .references(Accounts.id, onDelete = ReferenceOption.CASCADE)
        .index()
    val chatId = uuid("chat_id")
        .references(Chats.id, onDelete = ReferenceOption.CASCADE)
        .index()
    val reason = text("reason")
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }
    val resolvedAt = timestampWithTimeZone("resolved_at").nullable()
}

/**
 * Prior FCM device tokens for an account. Purged after [retention] days;
 * never needed for push once superseded.
 */
object DeviceTokenHistory : UUIDTable("device_token_history") {
    val accountId = uuid("account_id")
        .references(Accounts.id, onDelete = ReferenceOption.CASCADE)
        .index()
    val token = text("token")
    val recordedAt = timestampWithTimeZone("recorded_at").clientDefault { OffsetDateTime.now() }.index()
}

/**
 * Last-seen client IP addresses (approximate, from request / X-Forwarded-For).
 * Purged after [retention] days.
 */
object AccountLastSeenIps : UUIDTable("account_last_seen_ips") {
    val accountId = uuid("account_id")
        .references(Accounts.id, onDelete = ReferenceOption.CASCADE)
        .index()
    val ipAddress = varchar("ip_address", 64)
    val seenAt = timestampWithTimeZone("seen_at").clientDefault { OffsetDateTime.now() }.index()

    init {
        uniqueIndex(accountId, ipAddress)
    }
}

/**
 * Immutable record of retention purges (and similar ops) for lawful-access queries:
 * what category of metadata was removed, for which account, when, and how many rows —
 * without re-storing the purged secrets (tokens / IPs).
 */
object AuditLog : UUIDTable("audit_log") {
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }.index()
    /** e.g. metadata_purge */
    val action = varchar("action", 64).index()
    /** e.g. device_token_history | active_device_token | last_seen_ip | purge_run */
    val category = varchar("category", 64).index()
    val accountId = uuid("account_id").nullable().index()
    val retentionDays = integer("retention_days").nullable()
    val cutoffAt = timestampWithTimeZone("cutoff_at").nullable()
    val deletedCount = integer("deleted_count").default(0)
    /** JSON details safe for disclosure (counts, cutoffs — not token/IP values). */
    val detail = text("detail").nullable()
}

/**
 * Chapter calendar events (distinct from joinable chat "event rooms").
 */
object Events : UUIDTable("events") {
    val chapterId = varchar("chapter_id", 64)
        .references(Chapters.id, onDelete = ReferenceOption.RESTRICT)
        .index()
    val title = varchar("title", 200)
    val description = text("description")
    val location = varchar("location", 256)
    val eventTime = timestampWithTimeZone("event_time").index()
    val createdByAccountId = uuid("created_by_account_id")
        .references(Accounts.id, onDelete = ReferenceOption.CASCADE)
        .index()
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }
}

/**
 * Chapter marketplace listings. All rows are treated as active until deleted.
 */
object MarketplaceListings : UUIDTable("marketplace_listings") {
    val chapterId = varchar("chapter_id", 64)
        .references(Chapters.id, onDelete = ReferenceOption.RESTRICT)
        .index()
    val title = varchar("title", 200)
    val description = text("description")
    val sellerAccountId = uuid("seller_account_id")
        .references(Accounts.id, onDelete = ReferenceOption.CASCADE)
        .index()
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }
}

/**
 * Neighbour-reported civic alerts (water / road / traffic / other).
 * Hard-deleted past [expiresAt] by [com.lowkey.backend.jobs.ExpiredCivicAlertCleanupJob].
 */
object CivicAlerts : UUIDTable("civic_alerts") {
    val chapterId = varchar("chapter_id", 64)
        .references(Chapters.id, onDelete = ReferenceOption.RESTRICT)
        .index()
    val category = enumerationByName("category", 16, CivicAlertCategory::class)
    val title = varchar("title", 200)
    val description = text("description")
    val postedByAccountId = uuid("posted_by_account_id")
        .references(Accounts.id, onDelete = ReferenceOption.CASCADE)
        .index()
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }
    val expiresAt = timestampWithTimeZone("expires_at").index()
}

enum class CivicAlertCategory {
    water,
    road,
    traffic,
    other,
}
