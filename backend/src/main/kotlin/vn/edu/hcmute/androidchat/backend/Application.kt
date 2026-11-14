package vn.edu.hcmute.androidchat.backend

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import org.slf4j.event.Level
import java.sql.SQLException
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import vn.edu.hcmute.androidchat.backend.auth.AuthRepository
import vn.edu.hcmute.androidchat.backend.auth.AuthService
import vn.edu.hcmute.androidchat.backend.auth.PostgresAuthRepository
import vn.edu.hcmute.androidchat.backend.auth.installAuthSessionValidation
import vn.edu.hcmute.androidchat.backend.auth.registerAuthAndUserRoutes
import vn.edu.hcmute.androidchat.backend.chat.ChatRepository
import vn.edu.hcmute.androidchat.backend.chat.ChatSessionRegistry
import vn.edu.hcmute.androidchat.backend.chat.InMemoryChatRepository
import vn.edu.hcmute.androidchat.backend.chat.PostgresChatRepository
import vn.edu.hcmute.androidchat.backend.chat.registerChatRoutes
import vn.edu.hcmute.androidchat.backend.chat.registerChatWebSocketRoutes
import vn.edu.hcmute.androidchat.backend.common.ApiErrorDetail
import vn.edu.hcmute.androidchat.backend.common.ApiErrorResponse
import vn.edu.hcmute.androidchat.backend.common.AppConfig
import vn.edu.hcmute.androidchat.backend.common.AppJson
import vn.edu.hcmute.androidchat.backend.common.DatabaseManager
import vn.edu.hcmute.androidchat.backend.common.DatabaseUnavailableException
import vn.edu.hcmute.androidchat.backend.common.HealthResponse
import vn.edu.hcmute.androidchat.backend.common.configureJwtAuth
import vn.edu.hcmute.androidchat.backend.groups.registerGroupRoutes
import vn.edu.hcmute.androidchat.backend.media.configureMediaFoundation

fun main() {
    val config = AppConfig.fromEnv()
    embeddedServer(Netty, port = config.port) {
        module(config)
    }.start(wait = true)
}

/** Configures Ktor plugins, JWT authentication, health route, and module routing. */
fun Application.module(config: AppConfig = AppConfig.fromEnv()) {
    module(config, authRepositoryOverride = null, chatRepositoryOverride = null)
}

internal fun Application.module(
    config: AppConfig,
    authRepositoryOverride: AuthRepository? = null,
    chatRepositoryOverride: ChatRepository? = null
) {
    val dbManager = DatabaseManager(config).apply { initIfConfigured() }
    val chatRepository = chatRepositoryOverride
        ?: dbManager.dataSource?.let { PostgresChatRepository(it) }
        ?: InMemoryChatRepository()
    monitor.subscribe(ApplicationStopped) {
        dbManager.close()
    }

    install(ContentNegotiation) {
        json(AppJson)
    }

    install(CallLogging) {
        level = Level.INFO
        // Log only method/path; never log Authorization headers, tokens, or request bodies.
        filter { call -> !call.request.path().startsWith("/health") }
    }

    install(StatusPages) {
        exception<DatabaseUnavailableException> { call, _ ->
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiErrorResponse(ApiErrorDetail(code = "SERVICE_UNAVAILABLE", message = "Authentication storage is unavailable"))
            )
        }
        exception<SQLException> { call, _ ->
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiErrorResponse(ApiErrorDetail(code = "SERVICE_UNAVAILABLE", message = "Authentication storage is unavailable"))
            )
        }
        exception<Throwable> { call, _ ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiErrorResponse(
                    ApiErrorDetail(
                        code = "INTERNAL_ERROR",
                        message = "Unexpected server error"
                    )
                )
            )
        }
    }

    install(WebSockets) {
        pingPeriod = 25.seconds
        timeout = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    val authService = AuthService(authRepositoryOverride ?: PostgresAuthRepository(dbManager), config)
    configureJwtAuth(config)
    if (authRepositoryOverride != null || dbManager.dataSource != null) {
        installAuthSessionValidation(config, authService)
    }
    val chatSessions = ChatSessionRegistry()
    configureMediaFoundation(
        config = config,
        dataSource = dbManager.dataSource,
        isConversationMember = { convId, uid -> uid in chatRepository.activeMemberIds(convId) },
        onCallEvent = { targets, type, payload ->
            chatSessions.broadcastToUsers(
                userIds = targets,
                type = type,
                requestId = null,
                payload = payload
            )
        }
    )

    routing {
        get("/health") {
            call.respond(
                HttpStatusCode.OK,
                HealthResponse(
                    status = "UP",
                    service = "android-chat-backend",
                    database = dbManager.healthStatus(),
                    timestamp = Instant.now().toString()
                )
            )
        }

        registerChatRoutes(chatRepository, chatSessions)
        registerChatWebSocketRoutes(chatRepository, chatSessions)
        registerGroupRoutes()
        registerAuthAndUserRoutes(authService)
    }
}
