package vn.edu.hcmute.androidchat.backend.auth

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.delete
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import vn.edu.hcmute.androidchat.backend.common.AUTH_JWT_NAME
import vn.edu.hcmute.androidchat.backend.common.ApiErrorDetail
import vn.edu.hcmute.androidchat.backend.common.ApiErrorResponse
import vn.edu.hcmute.androidchat.backend.common.DatabaseUnavailableException
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
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
    get("/users/avatars/{fileName}") {
        val fileName = call.parameters["fileName"].orEmpty()
        if (!AVATAR_FILE_PATTERN.matches(fileName)) {
            call.respondError(HttpStatusCode.NotFound, "NOT_FOUND", "Avatar was not found")
            return@get
        }
        val path = avatarStorageDirectory().resolve(fileName)
        val bytes = withContext(Dispatchers.IO) {
            if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
        }
        if (bytes == null) {
            call.respondError(HttpStatusCode.NotFound, "NOT_FOUND", "Avatar was not found")
            return@get
        }
        val contentType = when {
            fileName.endsWith(".png") -> ContentType.Image.PNG
            fileName.endsWith(".webp") -> ContentType("image", "webp")
            else -> ContentType.Image.JPEG
        }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        call.respondBytes(bytes, contentType)
    }

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

        post("/users/me/avatar") {
            val identity = call.authenticatedIdentityOrRespond() ?: return@post
            val bytes = readBoundedBytes(call.receiveChannel(), MAX_AVATAR_BYTES)
            if (bytes == null) {
                call.respondError(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE", "Avatar image must not exceed 5 MB")
                return@post
            }
            val ext = detectAvatarExtension(bytes)
            if (ext == null) {
                call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Avatar must be a PNG, JPEG, or WebP image")
                return@post
            }
            val fileName = "avatar-${identity.userId}-${System.currentTimeMillis()}.$ext"
            call.respondAuthResult(HttpStatusCode.OK) {
                val dir = avatarStorageDirectory()
                Files.createDirectories(dir)
                Files.write(dir.resolve(fileName), bytes)
                authService.updateProfile(
                    identity.userId,
                    UpdateProfileRequest(avatarUrl = "/users/avatars/$fileName")
                ).toPublicUser(includeEmail = true, includePhone = true)
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
            val identity = call.authenticatedIdentityOrRespond() ?: return@get
            val phone = call.request.queryParameters["phone"]
            if (phone.isNullOrBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Phone number is required")
                return@get
            }
            call.respondAuthResult(HttpStatusCode.OK) {
                authService.findByPhone(phone, identity.userId).toPublicUser()
            }
        }

        get("/users/me/phone-discovery") {
            val identity = call.authenticatedIdentityOrRespond() ?: return@get
            call.respondAuthResult(HttpStatusCode.OK) {
                authService.phoneDiscoverySettings(identity.userId)
            }
        }

        patch("/users/me/phone-discovery") {
            val request = call.receiveBodyOrRespondError<UpdatePhoneDiscoveryRequest>() ?: return@patch
            val identity = call.authenticatedIdentityOrRespond() ?: return@patch
            call.respondAuthResult(HttpStatusCode.OK) {
                authService.updatePhoneDiscovery(identity.userId, request)
            }
        }

        post("/friend-requests") {
            val request = call.receiveBodyOrRespondError<CreateFriendRequestRequest>() ?: return@post
            val identity = call.authenticatedIdentityOrRespond() ?: return@post
            call.respondAuthResult(HttpStatusCode.Created) {
                authService.createFriendRequest(identity.userId, request)
            }
        }

        get("/friend-requests") {
            val identity = call.authenticatedIdentityOrRespond() ?: return@get
            val direction = call.request.queryParameters["direction"].orEmpty()
            call.respondAuthResult(HttpStatusCode.OK) {
                authService.listFriendRequests(identity.userId, direction)
            }
        }

        post("/friend-requests/{id}/accept") {
            val identity = call.authenticatedIdentityOrRespond() ?: return@post
            val requestId = call.friendRequestIdOrRespond() ?: return@post
            call.respondAuthResult(HttpStatusCode.OK) {
                authService.acceptFriendRequest(requestId, identity.userId)
            }
        }

        post("/friend-requests/{id}/reject") {
            val identity = call.authenticatedIdentityOrRespond() ?: return@post
            val requestId = call.friendRequestIdOrRespond() ?: return@post
            call.respondAuthResult(HttpStatusCode.OK) {
                authService.rejectFriendRequest(requestId, identity.userId)
            }
        }

        delete("/friend-requests/{id}") {
            val identity = call.authenticatedIdentityOrRespond() ?: return@delete
            val requestId = call.friendRequestIdOrRespond() ?: return@delete
            call.respondAuthNoContent {
                authService.cancelFriendRequest(requestId, identity.userId)
            }
        }

        get("/friends") {
            val identity = call.authenticatedIdentityOrRespond() ?: return@get
            call.respondAuthResult(HttpStatusCode.OK) {
                authService.listFriends(identity.userId)
            }
        }

        get("/users/blocked") {
            val identity = call.authenticatedIdentityOrRespond() ?: return@get
            call.respondAuthResult(HttpStatusCode.OK) {
                authService.listBlockedUsers(identity.userId)
            }
        }

        post("/users/{targetUserId}/block") {
            val identity = call.authenticatedIdentityOrRespond() ?: return@post
            val targetUserId = call.targetUserIdOrRespond() ?: return@post
            call.respondAuthNoContent {
                authService.blockUser(identity.userId, targetUserId)
            }
        }

        delete("/users/{targetUserId}/block") {
            val identity = call.authenticatedIdentityOrRespond() ?: return@delete
            val targetUserId = call.targetUserIdOrRespond() ?: return@delete
            call.respondAuthNoContent {
                authService.unblockUser(identity.userId, targetUserId)
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

private suspend fun ApplicationCall.friendRequestIdOrRespond(): Long? =
    parameters["id"]?.toLongOrNull()?.takeIf { it > 0L } ?: run {
        respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Friend request id must be a positive integer")
        null
    }

private suspend fun ApplicationCall.targetUserIdOrRespond(): Long? =
    parameters["targetUserId"]?.toLongOrNull()?.takeIf { it > 0L } ?: run {
        respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Target user ID must be a positive integer")
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
    } catch (_: UserBlockedException) {
        respondError(HttpStatusCode.Forbidden, "BLOCKED", "Interaction is blocked between these users")
    } catch (exception: InvalidCredentialsException) {
        respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", exception.message ?: "Credentials are invalid")
    } catch (exception: InvalidRefreshTokenException) {
        respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", exception.message ?: "Refresh token is invalid")
    } catch (_: UserNotFoundException) {
        respondError(HttpStatusCode.NotFound, "NOT_FOUND", "User was not found")
    } catch (_: FriendRequestNotFoundException) {
        respondError(HttpStatusCode.NotFound, "NOT_FOUND", "Friend request was not found")
    } catch (_: AlreadyFriendsException) {
        respondError(HttpStatusCode.Conflict, "CONFLICT", "Users are already friends")
    } catch (_: PendingFriendRequestExistsException) {
        respondError(HttpStatusCode.Conflict, "CONFLICT", "A friend request is already pending between these users")
    } catch (_: DatabaseUnavailableException) {
        respondError(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE", "Authentication storage is unavailable")
    } catch (_: SQLException) {
        respondError(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE", "Authentication storage is unavailable")
    }
}

private suspend inline fun ApplicationCall.respondAuthNoContent(crossinline action: suspend () -> Unit) {
    try {
        withContext(Dispatchers.IO) { action() }
        respond(HttpStatusCode.NoContent)
    } catch (exception: AuthValidationException) {
        respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", exception.message ?: "Request is invalid")
    } catch (exception: AuthConflictException) {
        respondError(HttpStatusCode.Conflict, "CONFLICT", exception.message ?: "Request conflicts with existing data")
    } catch (_: UserNotFoundException) {
        respondError(HttpStatusCode.NotFound, "NOT_FOUND", "User was not found")
    } catch (_: FriendRequestNotFoundException) {
        respondError(HttpStatusCode.NotFound, "NOT_FOUND", "Friend request was not found")
    } catch (_: DatabaseUnavailableException) {
        respondError(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE", "Authentication storage is unavailable")
    } catch (_: SQLException) {
        respondError(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE", "Authentication storage is unavailable")
    }
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) {
    respond(status, ApiErrorResponse(ApiErrorDetail(code = code, message = message)))
}

private const val MAX_AVATAR_BYTES = 5 * 1024 * 1024
private val AVATAR_FILE_PATTERN = Regex("""^avatar-\d+-\d+\.(jpg|png|webp)$""")

private fun avatarStorageDirectory(): Path {
    val configured = System.getenv("AVATAR_STORAGE_DIR")?.takeIf { it.isNotBlank() }
    return Path.of(configured ?: "storage/avatars")
}

private fun detectAvatarExtension(bytes: ByteArray): String? {
    if (bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte() &&
        bytes[4] == 0x0D.toByte() &&
        bytes[5] == 0x0A.toByte() &&
        bytes[6] == 0x1A.toByte() &&
        bytes[7] == 0x0A.toByte()
    ) {
        return "png"
    }
    if (bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()
    ) {
        return "jpg"
    }
    if (bytes.size >= 12 &&
        String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
    ) {
        return "webp"
    }
    return null
}

private suspend fun readBoundedBytes(channel: ByteReadChannel, maxBytes: Int): ByteArray? {
    val buffer = ByteArray(8192)
    val output = ByteArrayOutputStream()
    var total = 0
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
