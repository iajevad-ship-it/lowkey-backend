package com.lowkey.backend.auth

import com.lowkey.backend.db.Accounts
import com.lowkey.backend.db.bootstrapTransaction
import com.lowkey.backend.models.AccountPrincipal
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import org.jetbrains.exposed.sql.selectAll

data class SignedChallengeCredentials(
    val publicKey: String,
    val nonce: String,
    val signature: String,
)

/**
 * Shared signed-challenge parsing/verification for HTTP and WebSocket.
 */
object SignedChallengeVerifier {
    fun parse(call: ApplicationCall): SignedChallengeCredentials? {
        val authorization = call.request.header("Authorization")
        if (authorization != null && authorization.startsWith("Lowkey-Signature ", ignoreCase = true)) {
            val params = parseAuthParams(authorization.substringAfter(' ').trim())
            credentialsFrom(params)?.let { return it }
        }

        val fromHeaders = credentialsFrom(
            mapOf(
                "publicKey" to call.request.header("X-Lowkey-Public-Key"),
                "nonce" to call.request.header("X-Lowkey-Nonce"),
                "signature" to call.request.header("X-Lowkey-Signature"),
            )
        )
        if (fromHeaders != null) return fromHeaders

        // WebSocket clients that can't set headers can pass query params.
        val params = call.request.queryParameters
        return credentialsFrom(
            mapOf(
                "publicKey" to params["publicKey"],
                "nonce" to params["nonce"],
                "signature" to params["signature"],
            )
        )
    }

    fun verify(
        credentials: SignedChallengeCredentials,
        challengeService: ChallengeService,
    ): AccountPrincipal? {
        if (!challengeService.isValid(credentials.publicKey, credentials.nonce)) {
            return null
        }
        if (!SignalAuth.verify(credentials.publicKey, credentials.nonce, credentials.signature)) {
            return null
        }

        return bootstrapTransaction {
            Accounts.selectAll()
                .where { Accounts.publicKey eq credentials.publicKey }
                .firstOrNull()
                ?.let { row ->
                    if (row[Accounts.banned]) {
                        return@bootstrapTransaction null
                    }
                    AccountPrincipal(
                        accountId = row[Accounts.id].value,
                        publicKey = row[Accounts.publicKey],
                        chapterId = row[Accounts.chapterId],
                        isModerator = row[Accounts.isModerator],
                    )
                }
        }
    }

    private fun credentialsFrom(params: Map<String, String?>): SignedChallengeCredentials? {
        val publicKey = params["publicKey"]?.takeIf { it.isNotBlank() } ?: return null
        val nonce = params["nonce"]?.takeIf { it.isNotBlank() } ?: return null
        val signature = params["signature"]?.takeIf { it.isNotBlank() } ?: return null
        return SignedChallengeCredentials(publicKey, nonce, signature)
    }

    private fun parseAuthParams(raw: String): Map<String, String?> {
        val result = mutableMapOf<String, String?>()
        Regex("""(\w+)="([^"]*)"""").findAll(raw).forEach { match ->
            result[match.groupValues[1]] = match.groupValues[2]
        }
        if (result.isEmpty()) {
            Regex("""(\w+)=([^,\s]+)""").findAll(raw).forEach { match ->
                result[match.groupValues[1]] = match.groupValues[2].trim('"')
            }
        }
        return result
    }
}
