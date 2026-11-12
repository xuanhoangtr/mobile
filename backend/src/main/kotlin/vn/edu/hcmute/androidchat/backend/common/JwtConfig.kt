package vn.edu.hcmute.androidchat.backend.common

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond

const val AUTH_JWT_NAME = "auth-jwt"

/** Builds a JWT verifier aligned with [AppConfig]. */
fun buildJwtVerifier(config: AppConfig): JWTVerifier =
    JWT.require(Algorithm.HMAC256(config.jwtSecret))
        .withIssuer(config.jwtIssuer)
        .withAudience(config.jwtAudience)
        .build()

/** Installs Ktor JWT authentication guarding private REST and WebSocket endpoints. */
fun Application.configureJwtAuth(config: AppConfig) {
    val verifier = buildJwtVerifier(config)

    install(Authentication) {
        jwt(AUTH_JWT_NAME) {
            realm = config.jwtRealm
            verifier(verifier)
            validate { credential ->
                val userId = credential.payload.subject?.toLongOrNull()
                if (userId != null && userId > 0L) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiErrorResponse(
                        error = ApiErrorDetail(
                            code = "UNAUTHORIZED",
                            message = "Missing or invalid access token"
                        )
                    )
                )
            }
        }
    }
}

/** Extracts authenticated numeric user ID from the verified JWT subject claim. */
fun JWTPrincipal.authenticatedUserId(): Long? = payload.subject?.toLongOrNull()
