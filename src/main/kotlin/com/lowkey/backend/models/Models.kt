package com.lowkey.backend.models

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class AccountDto(
    val id: String,
    val publicKey: String,
    val displayName: String,
    val chapterId: String,
    val createdAt: String,
    val isModerator: Boolean = false,
    val isVerifiedOrganizer: Boolean = false,
    val banned: Boolean = false,
    val preferences: PreferencesDto = PreferencesDto(),
    /** ISO-8601 — only set when this account has lastSeenEnabled (or for self). */
    val lastSeenAt: String? = null,
    /**
     * Display name of the root voucher in **this** account's invite chain.
     * Only populated when the caller is viewing themselves — never another account's chain.
     */
    val vouchChainHeadDisplayName: String? = null,
)

@Serializable
data class PreferencesDto(
    val readReceipts: Boolean = false,
    val lastSeen: Boolean = false,
    val disappearingMessages: Boolean = true,
)

@Serializable
data class UpdatePreferencesRequest(
    val readReceipts: Boolean? = null,
    val lastSeen: Boolean? = null,
    val disappearingMessages: Boolean? = null,
)

/**
 * Registered push / session devices for an account.
 * Multi-device Signal sessions are not implemented yet — currently at most the FCM device.
 */
@Serializable
data class DeviceDto(
    val id: String,
    val label: String,
    val isCurrent: Boolean = true,
    val lastActiveAt: String? = null,
)

/** Chapter peers available to start chats / add to groups (no publicKey). */
@Serializable
data class ContactDto(
    val id: String,
    val displayName: String,
    /** Present only when the contact has lastSeenEnabled. */
    val lastSeenAt: String? = null,
)

object Roles {
    const val MODERATOR = "moderator"
}

@Serializable
data class CreateAccountRequest(
    val inviteCode: String,
    val publicKey: String,
    /** Optional at registration — set later via PATCH /accounts/me/display-name. */
    val displayName: String = "",
    val chapterId: String = "",
)

@Serializable
data class UpdateDisplayNameRequest(
    val displayName: String,
)

@Serializable
data class ChapterDto(
    val id: String,
    val name: String,
    val isLive: Boolean,
)

@Serializable
data class InviteCodeDto(
    val code: String,
    val expiresAt: String,
)

@Serializable
data class ChallengeRequest(
    val publicKey: String,
)

@Serializable
data class ChallengeResponse(
    val nonce: String,
    val expiresInSeconds: Long,
)

@Serializable
data class ErrorResponse(
    val error: String,
    val hint: String? = null,
)

data class AccountPrincipal(
    val accountId: UUID,
    val publicKey: String,
    val chapterId: String,
    val isModerator: Boolean = false,
)

@Serializable
data class CreateReportRequest(
    /** Null when flagging the whole chat rather than a specific account. */
    val reportedAccountId: String? = null,
    val chatId: String,
    val reason: String,
)

@Serializable
data class ResolveReportRequest(
    /** When true, sets [AccountDto.banned] on the reported account (if any). */
    val ban: Boolean = false,
)

/** Anonymous vouch edge — IDs only, no personal fields. */
@Serializable
data class VouchLinkDto(
    val voucherAccountId: String,
    val voucheeAccountId: String,
    val createdAt: String,
)

/**
 * Moderator-facing report. Only anonymous account IDs + vouch graph —
 * never displayName, publicKey, or deviceToken.
 */
@Serializable
data class ReportDto(
    val id: String,
    val reportedAccountId: String? = null,
    val reportedByAccountId: String,
    val chatId: String,
    val reason: String,
    val createdAt: String,
    val resolvedAt: String? = null,
    val vouchChain: List<VouchLinkDto> = emptyList(),
    val reportedAccountBanned: Boolean = false,
)

@Serializable
data class MessageDto(
    val id: String,
    val chatId: String,
    val senderAccountId: String,
    val ciphertext: String,
    val createdAt: String,
    val delivered: Boolean,
)

@Serializable
data class DeviceTokenRequest(
    val deviceToken: String,
)

@Serializable
data class StatusResponse(
    val status: String,
)

@Serializable
data class ChatDto(
    val id: String,
    val isGroup: Boolean,
    val chapterId: String,
    val createdAt: String,
    val memberIds: List<String>,
    val name: String? = null,
    val expiresAt: String? = null,
    /** Most recent message ciphertext (opaque / plaintext until E2E lands). */
    val lastCiphertext: String? = null,
    val lastMessageAt: String? = null,
    val lastSenderAccountId: String? = null,
    /** 1:1 peer last-seen when that peer has lastSeenEnabled. */
    val peerLastSeenAt: String? = null,
    val joinable: Boolean = false,
    /** Per-member flags (e.g. verified organizer). Does not include vouch chains. */
    val members: List<ChatMemberDto> = emptyList(),
    /**
     * Display name of the root voucher in **the calling account's** invite chain.
     * Never another member's vouch head.
     */
    val vouchChainHeadDisplayName: String? = null,
)

@Serializable
data class ChatMemberDto(
    val accountId: String,
    val isVerifiedOrganizer: Boolean = false,
)

/** Community event room listed on the chapter hub. */
@Serializable
data class EventRoomDto(
    val chatId: String,
    val title: String,
    val description: String,
    val expiresAt: String? = null,
    val memberCount: Int = 0,
)

@Serializable
data class CreateChatRequest(
    val isGroup: Boolean = false,
    val chapterId: String,
    val memberIds: List<String> = emptyList(),
    val name: String? = null,
    /** ISO-8601 timestamp; omit or null for a chat that never auto-expires. */
    val expiresAt: String? = null,
)

@Serializable
data class AddMemberRequest(
    val accountId: String,
)

/** PATCH /chats/{id}/members — add or remove a member in one call. */
@Serializable
data class PatchMembersRequest(
    val accountId: String,
    /** "add" or "remove" */
    val action: String,
)

@Serializable
data class SenderKeyDistributionDto(
    val chatId: String,
    val senderAccountId: String,
    val distributionId: String,
    /** Opaque Base64 SenderKeyDistributionMessage — server never inspects it. */
    val distributionMessage: String,
)

@Serializable
data class WsClientMessage(
    val type: String,
    val chatId: String? = null,
    val ciphertext: String? = null,
    /** For type=sender_key_distribution */
    val distributionId: String? = null,
    val distributionMessage: String? = null,
    /**
     * Optional. When set on sender_key_distribution, deliver only to this account
     * (client encrypts SKDM per recipient via 1:1 Signal sessions).
     */
    val recipientAccountId: String? = null,
)

@Serializable
data class WsServerEnvelope(
    val type: String,
    val message: MessageDto? = null,
    val error: String? = null,
    val chat: ChatDto? = null,
    val senderKey: SenderKeyDistributionDto? = null,
)

/** Opaque Signal prekey bundle payload — server never inspects contents. */
@Serializable
data class PreKeyBundleRequest(
    val bundle: String,
)

@Serializable
data class PreKeyBundleResponse(
    val accountId: String,
    val bundle: String,
    val updatedAt: String,
)

@Serializable
data class SosAlertRequest(
    val lat: Double,
    val lng: Double,
)

@Serializable
data class SosAlertResponse(
    val status: String = "ok",
    val notifiedCount: Int,
    val nearbyCount: Int,
    val radiusMeters: Double,
    val locationTtlSeconds: Long,
)

@Serializable
data class CreateEventRequest(
    val title: String,
    val description: String,
    val location: String,
    /** ISO-8601 timestamp. */
    val eventTime: String,
)

@Serializable
data class EventDto(
    val id: String,
    val chapterId: String,
    val title: String,
    val description: String,
    val location: String,
    val eventTime: String,
    val createdByAccountId: String,
    val createdAt: String,
)

@Serializable
data class CreateMarketplaceListingRequest(
    val title: String,
    val description: String,
)

@Serializable
data class MarketplaceListingDto(
    val id: String,
    val chapterId: String,
    val title: String,
    val description: String,
    val sellerAccountId: String,
    val createdAt: String,
)

@Serializable
enum class CivicAlertCategoryDto {
    water,
    road,
    traffic,
    other,
}

@Serializable
data class CreateCivicAlertRequest(
    val category: CivicAlertCategoryDto,
    val title: String,
    val description: String,
    /** ISO-8601 timestamp after which the alert is removed. */
    val expiresAt: String,
)

@Serializable
data class CivicAlertDto(
    val id: String,
    val chapterId: String,
    val category: CivicAlertCategoryDto,
    val title: String,
    val description: String,
    val postedByAccountId: String,
    val createdAt: String,
    val expiresAt: String,
)
