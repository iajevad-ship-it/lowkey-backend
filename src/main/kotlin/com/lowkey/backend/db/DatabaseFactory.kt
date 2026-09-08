package com.lowkey.backend.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.ApplicationConfig
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

object DatabaseFactory {
    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)
    private var dataSource: HikariDataSource? = null

    const val VADODARA_CHAPTER_ID = "vadodara"

    fun init(config: ApplicationConfig) {
        val resolved = resolveDatabase(config)
        val driver = config.property("database.driver").getString()
        val maxPoolSize = config.propertyOrNull("database.maxPoolSize")?.getString()?.toIntOrNull() ?: 10

        try {
            val hikari = HikariConfig().apply {
                jdbcUrl = resolved.jdbcUrl
                resolved.username?.let { username = it }
                resolved.password?.let { password = it }
                driverClassName = driver
                maximumPoolSize = maxPoolSize
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                connectionTimeout = 10_000
                validate()
            }

            dataSource = HikariDataSource(hikari)
            Database.connect(dataSource!!)

            bootstrapTransaction {
                // Chapters first so FKs can resolve.
                SchemaUtils.createMissingTablesAndColumns(Chapters)
                ensureVadodaraChapter()
                // Soft-add messages.chapter_id before NOT NULL / FK if table already has rows.
                ensureNullableMessageChapterColumn()
                SchemaUtils.createMissingTablesAndColumns(
                    Accounts,
                    InviteCodes,
                    VouchLinks,
                    Chats,
                    ChatMembers,
                    Messages,
                    PreKeyBundles,
                    Reports,
                    DeviceTokenHistory,
                    AccountLastSeenIps,
                    AuditLog,
                    Events,
                    MarketplaceListings,
                    CivicAlerts,
                )
                backfillMessageChapterIds()
                normalizeLegacyChapterIds()
                enforceMessageChapterNotNull()
            }

            installChapterRowLevelSecurity()
            seedBootstrapInviteIfNeeded()
            seedVadodaraEventRoomIfNeeded()
            log.info("Connected to PostgreSQL at {}", resolved.jdbcUrl.substringBefore('?').substringBefore('@').let {
                // Avoid logging credentials if user/pass were embedded before rewrite.
                resolved.jdbcUrl.replace(Regex("://[^@]+@"), "://***@").substringBefore('?')
            })
        } catch (e: Exception) {
            log.error("Failed to connect to PostgreSQL", e)
            throw e
        }
    }

    private data class ResolvedDatabase(
        val jdbcUrl: String,
        val username: String?,
        val password: String?,
    )

    /**
     * Accepts either a JDBC URL or a Render-style `postgres://` / `postgresql://` URL.
     * Render always needs SSL (`sslmode=require`).
     */
    private fun resolveDatabase(config: ApplicationConfig): ResolvedDatabase {
        val rawUrl = config.property("database.url").getString().trim()
        val configUser = config.propertyOrNull("database.user")?.getString()?.takeIf { it.isNotBlank() }
        val configPassword = config.propertyOrNull("database.password")?.getString()

        if (rawUrl.startsWith("jdbc:", ignoreCase = true)) {
            val withSsl = ensureSslMode(rawUrl)
            return ResolvedDatabase(withSsl, configUser, configPassword)
        }

        if (rawUrl.startsWith("postgres://", ignoreCase = true) ||
            rawUrl.startsWith("postgresql://", ignoreCase = true)
        ) {
            val normalized = rawUrl.replaceFirst(Regex("^postgres(ql)?://", RegexOption.IGNORE_CASE), "http://")
            val uri = java.net.URI(normalized)
            val userInfo = uri.userInfo?.split(":", limit = 2)
            val user = userInfo?.getOrNull(0)?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) } ?: configUser
            val pass = userInfo?.getOrNull(1)?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) } ?: configPassword
            val port = if (uri.port > 0) uri.port else 5432
            val path = uri.path.ifBlank { "/lowkey" }
            val query = uri.query
            val base = "jdbc:postgresql://${uri.host}:$port$path"
            val jdbc = when {
                query.isNullOrBlank() -> ensureSslMode(base)
                else -> ensureSslMode("$base?$query")
            }
            return ResolvedDatabase(jdbc, user, pass)
        }

        error("Unsupported database.url — expected jdbc:postgresql://… or postgres://…")
    }

    private fun ensureSslMode(jdbcUrl: String): String {
        if (jdbcUrl.contains("sslmode=", ignoreCase = true)) return jdbcUrl
        // Local docker/dev usually has no TLS; Render managed Postgres requires it.
        val host = jdbcUrl.substringAfter("://").substringBefore("/").substringBefore("?")
        val isLocal = host.startsWith("localhost") || host.startsWith("127.0.0.1")
        if (isLocal) return jdbcUrl
        return if (jdbcUrl.contains("?")) "$jdbcUrl&sslmode=require" else "$jdbcUrl?sslmode=require"
    }

    private fun org.jetbrains.exposed.sql.Transaction.ensureVadodaraChapter() {
        if (Chapters.selectAll().where { Chapters.id eq VADODARA_CHAPTER_ID }.empty()) {
            Chapters.insert {
                it[id] = VADODARA_CHAPTER_ID
                it[name] = "Vadodara"
                it[isLive] = true
            }
            log.info("Seeded chapter '{}'", VADODARA_CHAPTER_ID)
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.ensureNullableMessageChapterColumn() {
        exec(
            """
            DO ${'$'}${'$'}
            BEGIN
              IF EXISTS (
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'messages'
              ) AND NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'messages' AND column_name = 'chapter_id'
              ) THEN
                ALTER TABLE messages ADD COLUMN chapter_id VARCHAR(64);
              END IF;
            END
            ${'$'}${'$'};
            """.trimIndent()
        )
    }

    /** Copy chat.chapter_id onto messages that were created before the column existed. */
    private fun org.jetbrains.exposed.sql.Transaction.backfillMessageChapterIds() {
        exec(
            """
            UPDATE messages m
            SET chapter_id = c.chapter_id
            FROM chats c
            WHERE m.chat_id = c.id
              AND (m.chapter_id IS NULL OR m.chapter_id = '')
            """.trimIndent()
        )
        exec(
            """
            UPDATE messages
            SET chapter_id = 'vadodara'
            WHERE chapter_id IS NULL OR chapter_id = ''
            """.trimIndent()
        )
    }

    private fun org.jetbrains.exposed.sql.Transaction.enforceMessageChapterNotNull() {
        exec(
            """
            DO ${'$'}${'$'}
            BEGIN
              IF EXISTS (
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'messages'
                  AND column_name = 'chapter_id' AND is_nullable = 'YES'
              ) THEN
                ALTER TABLE messages ALTER COLUMN chapter_id SET NOT NULL;
              END IF;
            END
            ${'$'}${'$'};
            """.trimIndent()
        )
    }

    /** Map old free-form chapter labels onto the seeded chapter id. */
    private fun org.jetbrains.exposed.sql.Transaction.normalizeLegacyChapterIds() {
        exec(
            """
            UPDATE accounts SET chapter_id = 'vadodara'
            WHERE lower(chapter_id) IN ('vadodara', 'system') OR chapter_id = 'Vadodara'
            """.trimIndent()
        )
        exec(
            """
            UPDATE chats SET chapter_id = 'vadodara'
            WHERE lower(chapter_id) IN ('vadodara', 'system') OR chapter_id = 'Vadodara'
            """.trimIndent()
        )
        exec(
            """
            UPDATE messages SET chapter_id = 'vadodara'
            WHERE chapter_id IS NOT NULL
              AND (lower(chapter_id) IN ('vadodara', 'system') OR chapter_id = 'Vadodara')
            """.trimIndent()
        )
    }

    /**
     * First-run only: creates a non-loginable genesis account and one invite
     * so the first real user can register without an existing issuer.
     */
    private fun seedBootstrapInviteIfNeeded() {
        bootstrapTransaction {
            if (!Accounts.selectAll().empty()) return@bootstrapTransaction

            val genesisId = Accounts.insertAndGetId {
                it[publicKey] = "bootstrap:${UUID.randomUUID()}"
                it[displayName] = "genesis"
                it[chapterId] = VADODARA_CHAPTER_ID
            }

            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val rnd = SecureRandom()
            val code = buildString(10) {
                repeat(10) { append(alphabet[rnd.nextInt(alphabet.length)]) }
            }
            val expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30)
            InviteCodes.insert {
                it[InviteCodes.code] = code
                it[issuedByAccountId] = genesisId.value
                it[InviteCodes.expiresAt] = expiresAt
            }
            log.info("Bootstrap invite code (use once to create the first account): {}", code)
        }
    }

    /** Chapter community event room — joinable via POST /chats/{id}/join. */
    private fun seedVadodaraEventRoomIfNeeded() {
        bootstrapTransaction {
            val title = "Allaiya Ballaiya 2026"
            val existing = Chats.selectAll()
                .where {
                    (Chats.chapterId eq VADODARA_CHAPTER_ID) and (Chats.name eq title)
                }
                .firstOrNull()
            if (existing != null) {
                if (!existing[Chats.joinable]) {
                    Chats.update({ Chats.id eq existing[Chats.id] }) {
                        it[joinable] = true
                        it[isGroup] = true
                    }
                }
                return@bootstrapTransaction
            }
            // Event ends Sept 28 2026; room closes 3 days later.
            val expiresAt = OffsetDateTime.of(2026, 10, 1, 18, 0, 0, 0, ZoneOffset.UTC)
            Chats.insertAndGetId {
                it[isGroup] = true
                it[name] = title
                it[chapterId] = VADODARA_CHAPTER_ID
                it[joinable] = true
                it[Chats.expiresAt] = expiresAt
                it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
            log.info("Seeded joinable event room '{}'", title)
        }
    }

    fun isReady(): Boolean = dataSource != null && !dataSource!!.isClosed

    fun close() {
        dataSource?.close()
        dataSource = null
        log.info("PostgreSQL connection pool closed")
    }
}
