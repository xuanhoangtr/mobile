package vn.edu.hcmute.androidchat.backend.auth

import com.auth0.jwt.JWTVerifier
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.path
import io.ktor.server.response.respond
import vn.edu.hcmute.androidchat.backend.common.ApiErrorDetail
import vn.edu.hcmute.androidchat.backend.common.ApiErrorResponse
import vn.edu.hcmute.androidchat.backend.common.AppConfig
import vn.edu.hcmute.androidchat.backend.common.DatabaseUnavailableException
import vn.edu.hcmute.androidchat.backend.common.buildJwtVerifier
import java.sql.SQLException
import java.util.UUID

/**
 * Keeps JDBC session checks out of Ktor's JWT credential validator, which treats validator
 * exceptions as authentication failures. This interceptor preserves storage failures as 503.
 */
internal fun Application.installAuthSessionValidation(config: AppConfig, authService: AuthService) {
    val verifier: JWTVerifier = buildJwtVerifier(config)

    intercept(ApplicationCallPipeline.Plugins) {
        val currentCall = context
        val path = currentCall.request.path()
        if (path in PUBLIC_AUTH_PATHS || path == "/health") {
            proceed()
            return@intercept
        }

        val token = currentCall.request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val decoded = token?.let { raw -> runCatching { verifier.verify(raw) }.getOrNull() }
        val userId = decoded?.subject?.toLongOrNull()
        val sessionId = decoded?.getClaim(SESSION_ID_CLAIM)?.asString()
            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }

        if (userId == null || userId <= 0 || sessionId == null) {
            proceed() // The JWT authentication provider reports malformed/unsigned tokens as 401.
            return@intercept
        }

        try {
            if (authService.isSessionActive(sessionId, userId)) {
                proceed()
            } else {
                currentCall.respond(
                    HttpStatusCode.Unauthorized,
                    ApiErrorResponse(ApiErrorDetail(code = "UNAUTHORIZED", message = "Session is expired or revoked"))
                )
                finish()
            }
        } catch (_: DatabaseUnavailableException) {
            currentCall.respondStorageUnavailable()
            finish()
        } catch (_: SQLException) {
            currentCall.respondStorageUnavailable()
            finish()
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondStorageUnavailable() {
    respond(
        HttpStatusCode.ServiceUnavailable,
        ApiErrorResponse(ApiErrorDetail(code = "SERVICE_UNAVAILABLE", message = "Authentication storage is unavailable"))
    )
}

private const val SESSION_ID_CLAIM = "sid"
private val PUBLIC_AUTH_PATHS = setOf("/auth/register", "/auth/login", "/auth/refresh")
