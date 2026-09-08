package com.lowkey.backend.plugins

import com.lowkey.backend.db.ChapterContext
import com.lowkey.backend.models.AccountPrincipal
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.principal
import io.ktor.server.request.uri
import io.ktor.util.AttributeKey
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

val ChapterIdKey = AttributeKey<String>("ChapterId")

private val log = LoggerFactory.getLogger("ChapterIsolation")

/**
 * Binds every authenticated call to [AccountPrincipal.chapterId] so
 * [com.lowkey.backend.db.scopedTransaction] applies Postgres RLS.
 *
 * Chapter id is stored on the call and installed into the coroutine context via
 * [ChapterContext.Element] so it survives Netty / dispatcher thread hops.
 * A bare ThreadLocal set in [AuthenticationChecked] is not enough on its own.
 */
val ChapterIsolation = createApplicationPlugin(name = "ChapterIsolation") {
    on(AuthenticationChecked) { call ->
        val principal = call.principal<AccountPrincipal>()
        if (principal != null) {
            call.attributes.put(ChapterIdKey, principal.chapterId)
            log.trace("Chapter scope {} for {}", principal.chapterId, call.request.uri)
        }
    }

    application.intercept(ApplicationCallPipeline.Call) {
        val chapterId = call.attributes.getOrNull(ChapterIdKey)
            ?: call.principal<AccountPrincipal>()?.chapterId
        if (chapterId != null) {
            withContext(ChapterContext.Element(chapterId)) {
                proceed()
            }
        } else {
            proceed()
        }
    }
}
