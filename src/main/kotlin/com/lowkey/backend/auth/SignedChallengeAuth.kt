package com.lowkey.backend.auth

import com.lowkey.backend.db.ChapterContext
import com.lowkey.backend.models.AccountPrincipal
import com.lowkey.backend.models.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.principal
import io.ktor.server.response.respond

const val SIGNED_CHALLENGE_AUTH = "signed-challenge"

class SignedChallengeAuthenticationProvider(
    config: Config,
) : AuthenticationProvider(config) {
    private val challengeService = config.challengeService

    class Config(name: String?) : AuthenticationProvider.Config(name) {
        lateinit var challengeService: ChallengeService
    }

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val credentials = SignedChallengeVerifier.parse(call)
        if (credentials == null) {
            context.challenge(SIGNED_CHALLENGE_AUTH, AuthenticationFailedCause.NoCredentials) { challenge, chalCall ->
                chalCall.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(
                        error = "missing_credentials",
                        hint = "POST /auth/challenge then send Authorization: Lowkey-Signature publicKey=\"…\", nonce=\"…\", signature=\"…\""
                    )
                )
                challenge.complete()
            }
            return
        }

        val principal = SignedChallengeVerifier.verify(credentials, challengeService)
        if (principal == null) {
            context.challenge(SIGNED_CHALLENGE_AUTH, AuthenticationFailedCause.InvalidCredentials) { challenge, chalCall ->
                chalCall.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(error = "invalid_signature_or_nonce")
                )
                challenge.complete()
            }
            return
        }

        context.principal(principal)
    }
}

fun AuthenticationConfig.signedChallenge(
    name: String? = SIGNED_CHALLENGE_AUTH,
    configure: SignedChallengeAuthenticationProvider.Config.() -> Unit,
) {
    val provider = SignedChallengeAuthenticationProvider(
        SignedChallengeAuthenticationProvider.Config(name).apply(configure)
    )
    register(provider)
}

fun ApplicationCall.requireAccount(): AccountPrincipal {
    val account = principal<AccountPrincipal>()
        ?: error("Missing AccountPrincipal — route must be authenticated")
    ChapterContext.set(account.chapterId)
    return account
}
