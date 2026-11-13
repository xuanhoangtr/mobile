package vn.edu.hcmute.androidchat.backend.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import vn.edu.hcmute.androidchat.backend.common.AUTH_JWT_NAME
import vn.edu.hcmute.androidchat.backend.common.ApiErrorDetail
import vn.edu.hcmute.androidchat.backend.common.ApiErrorResponse
import vn.edu.hcmute.androidchat.backend.common.DatabaseUnavailableException
import java.sql.SQLException
import java.util.UUID

@Serializable
private data class LogoutResponse(val revoked: Boolean)

@Serializable
private data class MessageResponse(val message: String)

internal fun Application.registerAuthAndUserRoutes(authService: AuthService) {
    routing {
        registerAuthRoutes(authService)
        registerUserRoutes(authService)
    }
}

private fun Routing.registerAuthRoutes(authService: AuthService) {
    post("/auth/register") {
        val request = call.receiveBodyOrRespondError<RegisterRequest>() ?: return@post
        call.respondAuthResult(HttpStatusCode.Created) { authService.register(request) }
    }

    post("/auth/login") {
        val request = call.receiveBodyOrRespondError<LoginRequest>() ?: return@post
        call.respondAuthResult(HttpStatusCode.OK) { authService.login(request) }
    }

    post("/auth/refresh") {
        val request = call.receiveBodyOrRespondError<RefreshRequest>() ?: return@post
        call.respondAuthResult(HttpStatusCode.OK) { authService.refresh(request.refreshToken) }
    }

    authenticate(AUTH_JWT_NAME) {
        post("/auth/logout") {
            val request = call.receiveBodyOrRespondError<LogoutRequest>() ?: return@post
            val identity = call.authenticatedIdentityOrRespond() ?: return@post
            call.respondAuthResult(HttpStatusCode.OK) {
                LogoutResponse(authService.logout(request.refreshToken, identity.userId, identity.sessionId))
            }
        }
    }
}

private fun Routing.registerUserRoutes(authService: AuthService) {
    authenticate(AUTH_JWT_NAME) {
        get("/users/me") {
            val identity = call.authenticatedIdentityOrRespond() ?: return@get
            call.respondAuthResult(HttpStatusCode.OK) {
                authService.currentUser(identity.userId).toPublicUser(includeEmail = true, includePhone = true)
            }
        }

        patch("/users/me") {
            val request = call.receiveBodyOrRespondError<UpdateProfileRequest>() ?: return@patch
            val identity = call.authenticatedIdentityOrRespond() ?: return@patch
            call.respondAuthResult(HttpStatusCode.OK) {
                authService.updateProfile(identity.userId, request)
                    .toPublicUser(includeEmail = true, includePhone = true)
            }
        }

        get("/users/search") {
            val identity = call.authenticatedIdentityOrRespond() ?: return@get
            val query = call.request.queryParameters["q"].orEmpty()
            val cursor = call.request.queryParameters["cursor"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
            call.respondAuthResult(HttpStatusCode.OK) {
                authService.searchUsers(query, cursor, limit, identity.userId)
            }
        }

        get("/users/by-phone") {
            val phone = call.request.queryParameters["phone"]
            if (phone.isNullOrBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Phone number is required")
                return@get
            }
            call.respondAuthResult(HttpStatusCode.OK) {
                authService.findByPhone(phone).toPublicUser()
            }
        }
    }
}

private data class AuthenticatedIdentity(val userId: Long, val sessionId: UUID)

private suspend fun ApplicationCall.authenticatedIdentityOrRespond(): AuthenticatedIdentity? {
    val principal = principal<JWTPrincipal>()
    val userId = principal?.payload?.subject?.toLongOrNull()
    val sessionId = principal?.payload?.getClaim("sid")?.asString()
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (userId == null || userId <= 0 || sessionId == null) {
        respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "A valid authenticated session is required")
        return null
    }
    return AuthenticatedIdentity(userId, sessionId)
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveBodyOrRespondError(): T? = try {
    receive<T>()
} catch (exception: CancellationException) {
    throw exception
} catch (_: Exception) {
    respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Request body is invalid")
    null
}

private suspend inline fun <reified T : Any> ApplicationCall.respondAuthResult(
    successStatus: HttpStatusCode,
    crossinline action: suspend () -> T
) {
    try {
        respond(successStatus, withContext(Dispatchers.IO) { action() })
    } catch (exception: AuthValidationException) {
        respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", exception.message ?: "Request is invalid")
    } catch (exception: AuthConflictException) {
        respondError(HttpStatusCode.Conflict, "CONFLICT", exception.message ?: "Request conflicts with existing data")
    } catch (exception: InvalidCredentialsException) {
        respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", exception.message ?: "Credentials are invalid")
    } catch (exception: InvalidRefreshTokenException) {
        respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", exception.message ?: "Refresh token is invalid")
    } catch (_: UserNotFoundException) {
        respondError(HttpStatusCode.NotFound, "NOT_FOUND", "User was not found")
    } catch (_: DatabaseUnavailableException) {
        respondError(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE", "Authentication storage is unavailable")
    } catch (_: SQLException) {
        respondError(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE", "Authentication storage is unavailable")
    }
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) {
    respond(status, ApiErrorResponse(ApiErrorDetail(code = code, message = message)))
}
