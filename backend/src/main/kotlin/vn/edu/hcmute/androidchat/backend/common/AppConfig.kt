package vn.edu.hcmute.androidchat.backend.common

/**
 * Runtime configuration loaded from environment variables.
 * Sensitive values (DB credentials, JWT secret) must never be hardcoded for production.
 */
data class AppConfig(
    val env: String,
    val port: Int,
    val dbUrl: String?,
    val dbUser: String?,
    val dbPassword: String?,
    val jwtSecret: String,
    val jwtIssuer: String,
    val jwtAudience: String,
    val jwtRealm: String = "android-chat"
) {
    companion object {
        private const val DEV_FALLBACK_JWT_SECRET = "dev-only-local-jwt-secret-do-not-use-in-prod"

        fun fromEnv(envProvider: (String) -> String? = System::getenv): AppConfig {
            val appEnv = (envProvider("APP_ENV") ?: "dev").trim().lowercase()
            val port = envProvider("PORT")?.toIntOrNull() ?: 8080
            val dbUrl = envProvider("DB_URL")?.trim()?.takeIf { it.isNotEmpty() }
            val dbUser = envProvider("DB_USER")?.trim()?.takeIf { it.isNotEmpty() }
            val dbPassword = envProvider("DB_PASSWORD")?.takeIf { it.isNotEmpty() }
            val jwtSecretRaw = envProvider("JWT_SECRET")?.trim()?.takeIf { it.isNotEmpty() }

            val jwtSecret = when {
                jwtSecretRaw != null -> jwtSecretRaw
                appEnv == "dev" || appEnv == "test" -> DEV_FALLBACK_JWT_SECRET
                else -> error("JWT_SECRET environment variable is required when APP_ENV=$appEnv")
            }

            return AppConfig(
                env = appEnv,
                port = port,
                dbUrl = dbUrl,
                dbUser = dbUser,
                dbPassword = dbPassword,
                jwtSecret = jwtSecret,
                jwtIssuer = envProvider("JWT_ISSUER")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "android-chat-backend",
                jwtAudience = envProvider("JWT_AUDIENCE")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "android-chat-clients"
            )
        }
    }
}
