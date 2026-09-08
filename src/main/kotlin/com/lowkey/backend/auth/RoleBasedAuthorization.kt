package com.lowkey.backend.auth

import com.lowkey.backend.models.AccountPrincipal
import com.lowkey.backend.models.Roles
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext

/** Thrown when an authenticated caller lacks a required role — mapped to HTTP 403. */
class AuthorizationException(message: String = "forbidden") : RuntimeException(message)

/**
 * Ktor Authorization plugin — requires the authenticated principal to hold
 * at least one of [RoleAuthorizationConfig.roles].
 */
class RoleAuthorizationConfig {
    var roles: Set<String> = emptySet()
}

val RoleBasedAuthorization = createRouteScopedPlugin(
    name = "RoleBasedAuthorization",
    createConfiguration = ::RoleAuthorizationConfig,
) {
    val required = pluginConfig.roles
    on(AuthenticationChecked) { call ->
        val principal = call.principal<AccountPrincipal>()
        val granted = principal?.roles().orEmpty()
        if (principal == null || required.none { it in granted }) {
            throw AuthorizationException("requires role: ${required.joinToString()}")
        }
    }
}

fun AccountPrincipal.roles(): Set<String> = buildSet {
    if (isModerator) add(Roles.MODERATOR)
}

private class AuthorizedRouteSelector(private val description: String) : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
        RouteSelectorEvaluation.Transparent

    override fun toString(): String = description
}

/** Nested route that requires any of the given roles (after [io.ktor.server.auth.authenticate]). */
fun Route.authorize(vararg roles: String, build: Route.() -> Unit): Route {
    val child = createChild(AuthorizedRouteSelector("authorize(${roles.joinToString()})"))
    child.install(RoleBasedAuthorization) {
        this.roles = roles.toSet()
    }
    child.build()
    return child
}

fun Route.moderatorOnly(build: Route.() -> Unit): Route =
    authorize(Roles.MODERATOR, build = build)
