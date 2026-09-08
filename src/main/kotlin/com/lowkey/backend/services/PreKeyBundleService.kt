package com.lowkey.backend.services

import com.lowkey.backend.db.Accounts
import com.lowkey.backend.db.ChapterContext
import com.lowkey.backend.db.PreKeyBundles
import com.lowkey.backend.db.scopedTransaction
import com.lowkey.backend.models.PreKeyBundleResponse
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class PreKeyBundleService {
    fun upsert(
        accountId: UUID,
        bundle: String,
        chapterId: String? = null,
    ): PreKeyBundleResponse = scopedTransaction(chapterId ?: ChapterContext.current()) {
        require(bundle.isNotBlank()) { "bundle is required" }
        require(Accounts.selectAll().where { Accounts.id eq accountId }.count() > 0) {
            "account not found"
        }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val existing = PreKeyBundles.selectAll()
            .where { PreKeyBundles.accountId eq accountId }
            .firstOrNull()

        if (existing == null) {
            PreKeyBundles.insert {
                it[PreKeyBundles.accountId] = accountId
                it[PreKeyBundles.bundle] = bundle
                it[updatedAt] = now
            }
        } else {
            PreKeyBundles.update({ PreKeyBundles.accountId eq accountId }) {
                it[PreKeyBundles.bundle] = bundle
                it[updatedAt] = now
            }
        }

        PreKeyBundleResponse(
            accountId = accountId.toString(),
            bundle = bundle,
            updatedAt = now.toString(),
        )
    }

    fun get(
        accountId: UUID,
        chapterId: String? = null,
    ): PreKeyBundleResponse? = scopedTransaction(chapterId ?: ChapterContext.current()) {
        PreKeyBundles.selectAll()
            .where { PreKeyBundles.accountId eq accountId }
            .firstOrNull()
            ?.let { row ->
                PreKeyBundleResponse(
                    accountId = row[PreKeyBundles.accountId].toString(),
                    bundle = row[PreKeyBundles.bundle],
                    updatedAt = row[PreKeyBundles.updatedAt].toString(),
                )
            }
    }
}
