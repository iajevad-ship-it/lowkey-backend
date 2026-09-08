package com.lowkey.backend.services

import com.lowkey.backend.db.Accounts
import com.lowkey.backend.db.Chapters
import com.lowkey.backend.db.DeviceTokenHistory
import com.lowkey.backend.db.InviteCodes
import com.lowkey.backend.db.PreKeyBundles
import com.lowkey.backend.db.VouchLinks
import com.lowkey.backend.db.bootstrapTransaction
import com.lowkey.backend.db.scopedTransaction
import com.lowkey.backend.models.AccountDto
import com.lowkey.backend.models.ContactDto
import com.lowkey.backend.models.CreateAccountRequest
import com.lowkey.backend.models.DeviceDto
import com.lowkey.backend.models.InviteCodeDto
import com.lowkey.backend.models.PreferencesDto
import com.lowkey.backend.models.UpdatePreferencesRequest
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notLike
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class AccountService {
    private val random = SecureRandom()

    fun getById(accountId: UUID, includePrivateLastSeen: Boolean = true): AccountDto? = scopedTransaction {
        Accounts.selectAll()
            .where { Accounts.id eq accountId }
            .firstOrNull()
            ?.toDto(viewerIsSelf = includePrivateLastSeen)
    }

    fun updatePreferences(accountId: UUID, request: UpdatePreferencesRequest): AccountDto = scopedTransaction {
        Accounts.selectAll()
            .where { Accounts.id eq accountId }
            .firstOrNull()
            ?: error("account not found")
        Accounts.update({ Accounts.id eq accountId }) {
            if (request.readReceipts != null) {
                it[readReceiptsEnabled] = request.readReceipts
            }
            if (request.lastSeen != null) {
                it[lastSeenEnabled] = request.lastSeen
            }
            if (request.disappearingMessages != null) {
                it[disappearingMessagesDefault] = request.disappearingMessages
            }
        }
        Accounts.selectAll()
            .where { Accounts.id eq accountId }
            .first()
            .toDto(viewerIsSelf = true)
    }

    fun preferencesFor(accountId: UUID): PreferencesDto = scopedTransaction {
        Accounts.selectAll()
            .where { Accounts.id eq accountId }
            .firstOrNull()
            ?.let {
                PreferencesDto(
                    readReceipts = it[Accounts.readReceiptsEnabled],
                    lastSeen = it[Accounts.lastSeenEnabled],
                    disappearingMessages = it[Accounts.disappearingMessagesDefault],
                )
            }
            ?: PreferencesDto()
    }

    fun readReceiptsEnabled(accountId: UUID): Boolean = scopedTransaction {
        Accounts.selectAll()
            .where { Accounts.id eq accountId }
            .firstOrNull()
            ?.get(Accounts.readReceiptsEnabled)
            ?: false
    }

    /**
     * Public last-seen timestamp for [accountId], or null when they disabled last-seen.
     */
    fun publicLastSeenAt(accountId: UUID): String? = scopedTransaction {
        val row = Accounts.selectAll()
            .where { Accounts.id eq accountId }
            .firstOrNull()
            ?: return@scopedTransaction null
        if (!row[Accounts.lastSeenEnabled]) return@scopedTransaction null
        row[Accounts.lastActiveAt]?.toString()
    }

    /**
     * Stub device list until multi-device Signal sessions exist.
     * Returns the current FCM-registered device when a token is present, otherwise a single
     * "This device" placeholder keyed to the account.
     */
    fun listDevices(accountId: UUID): List<DeviceDto> = scopedTransaction {
        val row = Accounts.selectAll()
            .where { Accounts.id eq accountId }
            .firstOrNull()
            ?: return@scopedTransaction emptyList()
        val lastActive = row[Accounts.lastActiveAt]?.toString()
        val hasToken = !row[Accounts.deviceToken].isNullOrBlank()
        listOf(
            DeviceDto(
                id = if (hasToken) "fcm-current" else "local-current",
                label = "This device",
                isCurrent = true,
                lastActiveAt = lastActive,
            )
        )
    }

    fun touchLastActive(accountId: UUID, chapterId: String? = null) =
        scopedTransaction(chapterId ?: com.lowkey.backend.db.ChapterContext.current()) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val row = Accounts.selectAll()
            .where { Accounts.id eq accountId }
            .firstOrNull()
            ?: return@scopedTransaction
        val previous = row[Accounts.lastActiveAt]
        if (previous == null || previous.isBefore(now.minusMinutes(5))) {
            Accounts.update({ Accounts.id eq accountId }) {
                it[lastActiveAt] = now
            }
        }
    }

    fun updateDeviceToken(accountId: UUID, deviceToken: String) = scopedTransaction {
        require(deviceToken.isNotBlank()) { "deviceToken is required" }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val normalized = deviceToken.trim()
        val existing = Accounts.selectAll()
            .where { Accounts.id eq accountId }
            .firstOrNull()
            ?: error("account not found")

        val previous = existing[Accounts.deviceToken]
        if (!previous.isNullOrBlank() && previous != normalized) {
            DeviceTokenHistory.insert {
                it[DeviceTokenHistory.accountId] = accountId
                it[token] = previous
                it[recordedAt] = existing[Accounts.deviceTokenUpdatedAt] ?: now
            }
        }

        val updated = Accounts.update({ Accounts.id eq accountId }) {
            it[Accounts.deviceToken] = normalized
            it[deviceTokenUpdatedAt] = now
        }
        require(updated == 1) { "account not found" }
    }

    fun deviceTokenFor(accountId: UUID): String? = scopedTransaction {
        Accounts.selectAll()
            .where { Accounts.id eq accountId }
            .firstOrNull()
            ?.get(Accounts.deviceToken)
            ?.takeIf { it.isNotBlank() }
    }

    fun createInviteCode(issuerAccountId: UUID, ttlDays: Long = 14): InviteCodeDto = scopedTransaction {
        val code = generateInviteCode()
        val expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(ttlDays)
        InviteCodes.insert {
            it[InviteCodes.code] = code
            it[issuedByAccountId] = issuerAccountId
            it[InviteCodes.expiresAt] = expiresAt
        }
        InviteCodeDto(code = code, expiresAt = expiresAt.toString())
    }

    /**
     * Registration is unauthenticated — chapter comes from the invite issuer
     * (and must match a live [Chapters] row). Cross-chapter invites cannot work
     * because the issuer row is only visible under their chapter once scoped.
     */
    fun createAccount(request: CreateAccountRequest): AccountDto {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val normalizedKey = request.publicKey.trim()
        require(normalizedKey.isNotEmpty()) { "publicKey is required" }
        val displayName = request.displayName.trim().ifBlank { "member" }

        // Resolve invite + issuer chapter with RLS bypass (public registration).
        val (issuerId, chapterId) = bootstrapTransaction {
            val invite = InviteCodes.selectAll()
                .where { InviteCodes.code eq request.inviteCode.trim() }
                .firstOrNull()
                ?: error("invite code not found")

            require(invite[InviteCodes.usedAt] == null && invite[InviteCodes.redeemedByAccountId] == null) {
                "invite code already used"
            }
            require(invite[InviteCodes.expiresAt].isAfter(now)) { "invite code expired" }

            val issuer = Accounts.selectAll()
                .where { Accounts.id eq invite[InviteCodes.issuedByAccountId] }
                .first()
            val chapter = issuer[Accounts.chapterId]

            val chapterRow = Chapters.selectAll().where { Chapters.id eq chapter }.firstOrNull()
                ?: error("chapter not found")
            require(chapterRow[Chapters.isLive]) { "chapter is not live" }

            if (request.chapterId.isNotBlank()) {
                val requested = request.chapterId.trim().lowercase()
                require(requested == chapter || requested == chapterRow[Chapters.name].lowercase()) {
                    "invite is not valid for chapter '${request.chapterId}'"
                }
            }

            invite[InviteCodes.issuedByAccountId] to chapter
        }

        return scopedTransaction(chapterId) {
            val existing = Accounts.selectAll()
                .where { Accounts.publicKey eq normalizedKey }
                .firstOrNull()
            require(existing == null) { "publicKey already registered" }

            val newId = Accounts.insertAndGetId {
                it[publicKey] = normalizedKey
                it[Accounts.displayName] = displayName
                it[Accounts.chapterId] = chapterId
                it[createdAt] = now
            }

            // Mark invite used (issuer is same chapter; RLS allows).
            InviteCodes.update({ InviteCodes.code eq request.inviteCode.trim() }) {
                it[redeemedByAccountId] = newId.value
                it[usedAt] = now
            }

            VouchLinks.insert {
                it[voucherAccountId] = issuerId
                it[voucheeAccountId] = newId.value
                it[createdAt] = now
            }

            Accounts.selectAll()
                .where { Accounts.id eq newId }
                .first()
                .toDto(viewerIsSelf = true)
        }
    }

    fun updateDisplayName(accountId: UUID, displayName: String): AccountDto = scopedTransaction {
        val trimmed = displayName.trim()
        require(trimmed.isNotBlank()) { "displayName is required" }
        require(trimmed.length <= 64) { "displayName too long" }
        val updated = Accounts.update({ Accounts.id eq accountId }) {
            it[Accounts.displayName] = trimmed
        }
        require(updated == 1) { "account not found" }
        Accounts.selectAll()
            .where { Accounts.id eq accountId }
            .first()
            .toDto(viewerIsSelf = true)
    }

    /**
     * Other messageable accounts in the caller's chapter (RLS-scoped).
     * Only returns members who have uploaded a prekey bundle — otherwise E2E
     * sessions cannot be established and the client would hit 404s.
     */
    fun listContacts(excludeAccountId: UUID): List<ContactDto> = scopedTransaction {
        Accounts
            .innerJoin(PreKeyBundles, { Accounts.id }, { PreKeyBundles.accountId })
            .selectAll()
            .where {
                (Accounts.id neq excludeAccountId) and
                    (Accounts.banned eq false) and
                    (Accounts.displayName neq "genesis") and
                    (Accounts.publicKey notLike "bootstrap:%")
            }
            .orderBy(Accounts.displayName to SortOrder.ASC)
            .map {
                ContactDto(
                    id = it[Accounts.id].value.toString(),
                    displayName = it[Accounts.displayName].ifBlank { "member" },
                    lastSeenAt = if (it[Accounts.lastSeenEnabled]) {
                        it[Accounts.lastActiveAt]?.toString()
                    } else null,
                )
            }
    }

    fun listChapters(): List<com.lowkey.backend.models.ChapterDto> = bootstrapTransaction {
        Chapters.selectAll()
            .orderBy(Chapters.name to SortOrder.ASC)
            .map {
                com.lowkey.backend.models.ChapterDto(
                    id = it[Chapters.id],
                    name = it[Chapters.name],
                    isLive = it[Chapters.isLive],
                )
            }
    }

    private fun generateInviteCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val bytes = ByteArray(10)
        random.nextBytes(bytes)
        return buildString(10) {
            bytes.forEach { b -> append(alphabet[(b.toInt() and 0xff) % alphabet.length]) }
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toDto(viewerIsSelf: Boolean): AccountDto {
        val prefs = PreferencesDto(
            readReceipts = this[Accounts.readReceiptsEnabled],
            lastSeen = this[Accounts.lastSeenEnabled],
            disappearingMessages = this[Accounts.disappearingMessagesDefault],
        )
        val lastSeenAt = when {
            viewerIsSelf -> this[Accounts.lastActiveAt]?.toString()
            this[Accounts.lastSeenEnabled] -> this[Accounts.lastActiveAt]?.toString()
            else -> null
        }
        return AccountDto(
            id = this[Accounts.id].value.toString(),
            publicKey = this[Accounts.publicKey],
            displayName = this[Accounts.displayName],
            chapterId = this[Accounts.chapterId],
            createdAt = this[Accounts.createdAt].toString(),
            isModerator = this[Accounts.isModerator],
            isVerifiedOrganizer = this[Accounts.isVerifiedOrganizer],
            banned = this[Accounts.banned],
            preferences = prefs,
            lastSeenAt = lastSeenAt,
            // Own vouch head only — never when serializing another account for a third party.
            vouchChainHeadDisplayName = if (viewerIsSelf) {
                vouchChainHeadDisplayName(this[Accounts.id].value)
            } else {
                null
            },
        )
    }

    /**
     * Walk voucher → voucher until the root. Returns that account's displayName,
     * or null if [accountId] has no vouch link.
     */
    fun vouchChainHeadDisplayName(accountId: UUID): String? {
        var current = accountId
        var headId: UUID? = null
        val seen = mutableSetOf<UUID>()
        while (seen.add(current)) {
            val link = VouchLinks.selectAll()
                .where { VouchLinks.voucheeAccountId eq current }
                .firstOrNull()
                ?: break
            headId = link[VouchLinks.voucherAccountId]
            current = headId
        }
        if (headId == null) return null
        return Accounts.selectAll()
            .where { Accounts.id eq headId }
            .firstOrNull()
            ?.get(Accounts.displayName)
            ?.takeIf { it.isNotBlank() }
    }

    /** Moderator-only: mark an in-chapter account as a verified organizer. */
    fun verifyOrganizer(targetAccountId: UUID): AccountDto = scopedTransaction {
        Accounts.selectAll().where { Accounts.id eq targetAccountId }.firstOrNull()
            ?: error("account not found")
        Accounts.update({ Accounts.id eq targetAccountId }) {
            it[isVerifiedOrganizer] = true
        }
        // viewerIsSelf=false so we never return the target's vouch chain to the moderator.
        Accounts.selectAll().where { Accounts.id eq targetAccountId }.first()
            .toDto(viewerIsSelf = false)
    }
}
