package vn.edu.hcmute.androidchat.backend.common

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import javax.sql.DataSource

/**
 * Manages optional PostgreSQL HikariCP connection pool for the backend skeleton.
 */
class DatabaseManager(private val config: AppConfig) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(DatabaseManager::class.java)
    private var hikariDataSource: HikariDataSource? = null

    val dataSource: DataSource?
        get() = hikariDataSource

    fun initIfConfigured() {
        val jdbcUrl = config.dbUrl ?: run {
            logger.info("DB_URL not set; running backend skeleton without PostgreSQL pool.")
            return
        }

        val hikariConfig = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = config.dbUser
            password = config.dbPassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 5
            minimumIdle = 1
            connectionTimeout = 3_000
            initializationFailTimeout = -1 // Allow app startup even if DB boots slightly later
        }

        hikariDataSource = HikariDataSource(hikariConfig)
        logger.info("PostgreSQL HikariDataSource initialized.")
        ensureSchemaUpgrades()
    }

    private fun ensureSchemaUpgrades() {
        val ds = hikariDataSource ?: return
        val statements = listOf(
            "ALTER TABLE IF EXISTS users ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ",
            """
            CREATE TABLE IF NOT EXISTS media_objects (
                id BIGINT PRIMARY KEY,
                owner_id BIGINT NOT NULL,
                conversation_id BIGINT,
                file_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                byte_size BIGINT NOT NULL,
                upload_state TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                access_scope TEXT NOT NULL,
                storage_key TEXT
            )
            """.trimIndent(),
            "ALTER TABLE IF EXISTS media_objects ADD COLUMN IF NOT EXISTS storage_key TEXT",
            "CREATE INDEX IF NOT EXISTS idx_media_objects_owner_id ON media_objects (owner_id)",
            "CREATE INDEX IF NOT EXISTS idx_media_objects_conversation_id ON media_objects (conversation_id) WHERE conversation_id IS NOT NULL",
            """
            CREATE TABLE IF NOT EXISTS device_push_tokens (
                user_id BIGINT NOT NULL,
                device_id TEXT NOT NULL,
                token TEXT NOT NULL,
                enabled BOOLEAN NOT NULL DEFAULT TRUE,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                PRIMARY KEY (user_id, device_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS call_sessions (
                call_id TEXT PRIMARY KEY,
                conversation_id BIGINT,
                caller_id BIGINT NOT NULL,
                callee_id BIGINT,
                media TEXT NOT NULL,
                mode TEXT NOT NULL DEFAULT 'DIRECT',
                state TEXT NOT NULL,
                participant_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
                active_participant_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """.trimIndent(),
            "ALTER TABLE IF EXISTS call_sessions ADD COLUMN IF NOT EXISTS active_participant_ids JSONB NOT NULL DEFAULT '[]'::jsonb",
            "CREATE INDEX IF NOT EXISTS idx_call_sessions_conversation_id ON call_sessions (conversation_id) WHERE conversation_id IS NOT NULL",
            """
            CREATE TABLE IF NOT EXISTS message_hidden (
                message_id BIGINT NOT NULL,
                user_id BIGINT NOT NULL,
                hidden_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                PRIMARY KEY (message_id, user_id)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_message_hidden_user_id ON message_hidden (user_id, message_id)"
        )
        try {
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    for (sql in statements) {
                        stmt.execute(sql)
                    }
                }
            }
        } catch (ex: Exception) {
            logger.warn("Skipping idempotent startup schema upgrade: {}", ex.message)
        }
    }

    fun healthStatus(): String {
        val ds = hikariDataSource ?: return "NOT_CONFIGURED"
        return try {
            ds.connection.use { conn ->
                if (conn.isValid(2)) "UP" else "DOWN"
            }
        } catch (ex: Exception) {
            logger.warn("PostgreSQL health check failed: {}", ex.javaClass.simpleName)
            "DOWN"
        }
    }

    /** Uses the shared application pool without exposing its lifecycle to feature repositories. */
    fun <T> withConnection(block: (Connection) -> T): T {
        val pool = dataSource ?: throw DatabaseUnavailableException()
        return pool.connection.use(block)
    }

    override fun close() {
        hikariDataSource?.close()
        hikariDataSource = null
    }
}

class DatabaseUnavailableException : IllegalStateException("Database is not configured")
