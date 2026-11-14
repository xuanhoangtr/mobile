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
