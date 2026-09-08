package com.lowkey.backend.db

import kotlinx.coroutines.ThreadContextElement
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Request-scoped chapter tenancy. Set by [com.lowkey.backend.plugins.ChapterIsolation]
 * (or explicitly for account registration) before any [scopedTransaction].
 */
object ChapterContext {
    private val local = ThreadLocal<String?>()

    fun current(): String =
        local.get()?.takeIf { it.isNotBlank() }
            ?: error("No chapter scope — ChapterIsolation plugin must run first")

    fun currentOrNull(): String? = local.get()?.takeIf { it.isNotBlank() }

    fun set(chapterId: String): String? {
        val previous = local.get()
        local.set(chapterId)
        return previous
    }

    fun clear() {
        local.remove()
    }

    fun restore(previous: String?) {
        if (previous == null) local.remove() else local.set(previous)
    }

    /** Propagates chapter scope across coroutine dispatchers. */
    class Element(val chapterId: String) :
        AbstractCoroutineContextElement(Key),
        ThreadContextElement<String?> {
        companion object Key : CoroutineContext.Key<Element>

        override fun updateThreadContext(context: CoroutineContext): String? = set(chapterId)
        override fun restoreThreadContext(context: CoroutineContext, oldState: String?) = restore(oldState)
    }
}

private fun escapeLiteral(value: String): String = value.replace("'", "''")

/**
 * Opens an Exposed transaction with Postgres `app.chapter_id` set so RLS
 * policies reject cross-chapter rows at the database engine.
 */
fun <T> scopedTransaction(
    chapterId: String = ChapterContext.current(),
    statement: Transaction.() -> T,
): T {
    require(chapterId.isNotBlank()) { "chapterId is required for scopedTransaction" }
    val previous = ChapterContext.set(chapterId)
    return try {
        transaction {
            applyChapterSession(chapterId, bypassRls = false)
            statement()
        }
    } finally {
        ChapterContext.restore(previous)
    }
}

/**
 * Bootstrap / seed only — bypasses RLS. Never use from request handlers.
 */
fun <T> bootstrapTransaction(statement: Transaction.() -> T): T = transaction {
    applyChapterSession(chapterId = "", bypassRls = true)
    statement()
}

private fun Transaction.applyChapterSession(chapterId: String, bypassRls: Boolean) {
    exec("SELECT set_config('app.chapter_id', '${escapeLiteral(chapterId)}', true)")
    exec("SELECT set_config('app.bypass_rls', '${if (bypassRls) "on" else "off"}', true)")
    // Touch connection so settings stick for this transaction
    TransactionManager.current()
}

/**
 * Install FORCE ROW LEVEL SECURITY so even the table owner cannot escape chapter isolation.
 */
fun installChapterRowLevelSecurity() {
    bootstrapTransaction {
        exec(
            """
            DO ${'$'}${'$'}
            BEGIN
              -- Accounts
              ALTER TABLE IF EXISTS accounts ENABLE ROW LEVEL SECURITY;
              ALTER TABLE IF EXISTS accounts FORCE ROW LEVEL SECURITY;
              DROP POLICY IF EXISTS accounts_chapter_isolation ON accounts;
              CREATE POLICY accounts_chapter_isolation ON accounts
                USING (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR chapter_id = current_setting('app.chapter_id', true)
                )
                WITH CHECK (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR chapter_id = current_setting('app.chapter_id', true)
                );

              -- Chats
              ALTER TABLE IF EXISTS chats ENABLE ROW LEVEL SECURITY;
              ALTER TABLE IF EXISTS chats FORCE ROW LEVEL SECURITY;
              DROP POLICY IF EXISTS chats_chapter_isolation ON chats;
              CREATE POLICY chats_chapter_isolation ON chats
                USING (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR chapter_id = current_setting('app.chapter_id', true)
                )
                WITH CHECK (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR chapter_id = current_setting('app.chapter_id', true)
                );

              -- Messages
              ALTER TABLE IF EXISTS messages ENABLE ROW LEVEL SECURITY;
              ALTER TABLE IF EXISTS messages FORCE ROW LEVEL SECURITY;
              DROP POLICY IF EXISTS messages_chapter_isolation ON messages;
              CREATE POLICY messages_chapter_isolation ON messages
                USING (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR chapter_id = current_setting('app.chapter_id', true)
                )
                WITH CHECK (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR chapter_id = current_setting('app.chapter_id', true)
                );

              -- Chat members: visible only when the parent chat is in-chapter
              ALTER TABLE IF EXISTS chat_members ENABLE ROW LEVEL SECURITY;
              ALTER TABLE IF EXISTS chat_members FORCE ROW LEVEL SECURITY;
              DROP POLICY IF EXISTS chat_members_chapter_isolation ON chat_members;
              CREATE POLICY chat_members_chapter_isolation ON chat_members
                USING (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR EXISTS (
                    SELECT 1 FROM chats c
                    WHERE c.id = chat_id
                      AND c.chapter_id = current_setting('app.chapter_id', true)
                  )
                )
                WITH CHECK (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR EXISTS (
                    SELECT 1 FROM chats c
                    WHERE c.id = chat_id
                      AND c.chapter_id = current_setting('app.chapter_id', true)
                  )
                );

              -- Prekey bundles: via owning account's chapter
              ALTER TABLE IF EXISTS prekey_bundles ENABLE ROW LEVEL SECURITY;
              ALTER TABLE IF EXISTS prekey_bundles FORCE ROW LEVEL SECURITY;
              DROP POLICY IF EXISTS prekey_bundles_chapter_isolation ON prekey_bundles;
              CREATE POLICY prekey_bundles_chapter_isolation ON prekey_bundles
                USING (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR EXISTS (
                    SELECT 1 FROM accounts a
                    WHERE a.id = account_id
                      AND a.chapter_id = current_setting('app.chapter_id', true)
                  )
                )
                WITH CHECK (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR EXISTS (
                    SELECT 1 FROM accounts a
                    WHERE a.id = account_id
                      AND a.chapter_id = current_setting('app.chapter_id', true)
                  )
                );

              -- Invite codes / vouch links: via issuer / voucher account chapter
              ALTER TABLE IF EXISTS invite_codes ENABLE ROW LEVEL SECURITY;
              ALTER TABLE IF EXISTS invite_codes FORCE ROW LEVEL SECURITY;
              DROP POLICY IF EXISTS invite_codes_chapter_isolation ON invite_codes;
              CREATE POLICY invite_codes_chapter_isolation ON invite_codes
                USING (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR EXISTS (
                    SELECT 1 FROM accounts a
                    WHERE a.id = issued_by_account_id
                      AND a.chapter_id = current_setting('app.chapter_id', true)
                  )
                )
                WITH CHECK (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR EXISTS (
                    SELECT 1 FROM accounts a
                    WHERE a.id = issued_by_account_id
                      AND a.chapter_id = current_setting('app.chapter_id', true)
                  )
                );

              ALTER TABLE IF EXISTS vouch_links ENABLE ROW LEVEL SECURITY;
              ALTER TABLE IF EXISTS vouch_links FORCE ROW LEVEL SECURITY;
              DROP POLICY IF EXISTS vouch_links_chapter_isolation ON vouch_links;
              CREATE POLICY vouch_links_chapter_isolation ON vouch_links
                USING (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR EXISTS (
                    SELECT 1 FROM accounts a
                    WHERE a.id = voucher_account_id
                      AND a.chapter_id = current_setting('app.chapter_id', true)
                  )
                )
                WITH CHECK (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR EXISTS (
                    SELECT 1 FROM accounts a
                    WHERE a.id = voucher_account_id
                      AND a.chapter_id = current_setting('app.chapter_id', true)
                  )
                );

              -- Reports: scoped via reported account (or chat when whole-chat / null reported)
              ALTER TABLE IF EXISTS reports ENABLE ROW LEVEL SECURITY;
              ALTER TABLE IF EXISTS reports FORCE ROW LEVEL SECURITY;
              DROP POLICY IF EXISTS reports_chapter_isolation ON reports;
              CREATE POLICY reports_chapter_isolation ON reports
                USING (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR EXISTS (
                    SELECT 1 FROM accounts a
                    WHERE a.id = reported_account_id
                      AND a.chapter_id = current_setting('app.chapter_id', true)
                  )
                  OR (
                    reported_account_id IS NULL
                    AND EXISTS (
                      SELECT 1 FROM chats c
                      WHERE c.id = chat_id
                        AND c.chapter_id = current_setting('app.chapter_id', true)
                    )
                  )
                )
                WITH CHECK (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR (
                    EXISTS (
                      SELECT 1 FROM accounts a
                      WHERE a.id = reported_by_account_id
                        AND a.chapter_id = current_setting('app.chapter_id', true)
                    )
                    AND EXISTS (
                      SELECT 1 FROM chats c
                      WHERE c.id = chat_id
                        AND c.chapter_id = current_setting('app.chapter_id', true)
                    )
                    AND (
                      reported_account_id IS NULL
                      OR EXISTS (
                        SELECT 1 FROM accounts a
                        WHERE a.id = reported_account_id
                          AND a.chapter_id = current_setting('app.chapter_id', true)
                      )
                    )
                  )
                );

              -- Device token history / last-seen IPs: via owning account chapter
              ALTER TABLE IF EXISTS device_token_history ENABLE ROW LEVEL SECURITY;
              ALTER TABLE IF EXISTS device_token_history FORCE ROW LEVEL SECURITY;
              DROP POLICY IF EXISTS device_token_history_chapter_isolation ON device_token_history;
              CREATE POLICY device_token_history_chapter_isolation ON device_token_history
                USING (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR EXISTS (
                    SELECT 1 FROM accounts a
                    WHERE a.id = account_id
                      AND a.chapter_id = current_setting('app.chapter_id', true)
                  )
                )
                WITH CHECK (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR EXISTS (
                    SELECT 1 FROM accounts a
                    WHERE a.id = account_id
                      AND a.chapter_id = current_setting('app.chapter_id', true)
                  )
                );

              ALTER TABLE IF EXISTS account_last_seen_ips ENABLE ROW LEVEL SECURITY;
              ALTER TABLE IF EXISTS account_last_seen_ips FORCE ROW LEVEL SECURITY;
              DROP POLICY IF EXISTS account_last_seen_ips_chapter_isolation ON account_last_seen_ips;
              CREATE POLICY account_last_seen_ips_chapter_isolation ON account_last_seen_ips
                USING (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR EXISTS (
                    SELECT 1 FROM accounts a
                    WHERE a.id = account_id
                      AND a.chapter_id = current_setting('app.chapter_id', true)
                  )
                )
                WITH CHECK (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR EXISTS (
                    SELECT 1 FROM accounts a
                    WHERE a.id = account_id
                      AND a.chapter_id = current_setting('app.chapter_id', true)
                  )
                );

              -- Audit log: system table — only accessible with RLS bypass (retention job / ops)
              ALTER TABLE IF EXISTS audit_log ENABLE ROW LEVEL SECURITY;
              ALTER TABLE IF EXISTS audit_log FORCE ROW LEVEL SECURITY;
              DROP POLICY IF EXISTS audit_log_bypass_only ON audit_log;
              CREATE POLICY audit_log_bypass_only ON audit_log
                USING (current_setting('app.bypass_rls', true) = 'on')
                WITH CHECK (current_setting('app.bypass_rls', true) = 'on');

              -- Chapter calendar events
              ALTER TABLE IF EXISTS events ENABLE ROW LEVEL SECURITY;
              ALTER TABLE IF EXISTS events FORCE ROW LEVEL SECURITY;
              DROP POLICY IF EXISTS events_chapter_isolation ON events;
              CREATE POLICY events_chapter_isolation ON events
                USING (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR chapter_id = current_setting('app.chapter_id', true)
                )
                WITH CHECK (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR chapter_id = current_setting('app.chapter_id', true)
                );

              -- Chapter marketplace listings
              ALTER TABLE IF EXISTS marketplace_listings ENABLE ROW LEVEL SECURITY;
              ALTER TABLE IF EXISTS marketplace_listings FORCE ROW LEVEL SECURITY;
              DROP POLICY IF EXISTS marketplace_listings_chapter_isolation ON marketplace_listings;
              CREATE POLICY marketplace_listings_chapter_isolation ON marketplace_listings
                USING (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR chapter_id = current_setting('app.chapter_id', true)
                )
                WITH CHECK (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR chapter_id = current_setting('app.chapter_id', true)
                );

              -- Chapter civic alerts
              ALTER TABLE IF EXISTS civic_alerts ENABLE ROW LEVEL SECURITY;
              ALTER TABLE IF EXISTS civic_alerts FORCE ROW LEVEL SECURITY;
              DROP POLICY IF EXISTS civic_alerts_chapter_isolation ON civic_alerts;
              CREATE POLICY civic_alerts_chapter_isolation ON civic_alerts
                USING (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR chapter_id = current_setting('app.chapter_id', true)
                )
                WITH CHECK (
                  current_setting('app.bypass_rls', true) = 'on'
                  OR chapter_id = current_setting('app.chapter_id', true)
                );

              -- Chapters catalog is globally readable (no tenant data)
              ALTER TABLE IF EXISTS chapters ENABLE ROW LEVEL SECURITY;
              ALTER TABLE IF EXISTS chapters FORCE ROW LEVEL SECURITY;
              DROP POLICY IF EXISTS chapters_read ON chapters;
              CREATE POLICY chapters_read ON chapters
                FOR SELECT USING (true);
              DROP POLICY IF EXISTS chapters_write_bypass ON chapters;
              CREATE POLICY chapters_write_bypass ON chapters
                FOR ALL USING (current_setting('app.bypass_rls', true) = 'on')
                WITH CHECK (current_setting('app.bypass_rls', true) = 'on');
            END
            ${'$'}${'$'};
            """.trimIndent()
        )
    }
}
