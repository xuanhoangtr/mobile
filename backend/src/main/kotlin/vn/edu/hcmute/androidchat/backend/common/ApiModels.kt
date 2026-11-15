package vn.edu.hcmute.androidchat.backend.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Shared JSON serializer configured for REST and WebSocket contracts. */
val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class HealthResponse(
    val status: String,
    val service: String,
    val database: String,
    val timestamp: String
)

@Serializable
data class ApiErrorDetail(
    val code: String,
    val message: String,
    val requestId: String? = null
)

@Serializable
data class ApiErrorResponse(
    val error: ApiErrorDetail
)

@Serializable
data class CursorPage<T>(
    val items: List<T>,
    val nextCursor: String? = null,
    val hasMore: Boolean = false
)

@Serializable
data class WsEnvelope(
    val type: String,
    val requestId: String? = null,
    val timestamp: String,
    val payload: JsonElement
)

@Serializable
data class WsErrorPayload(
    val code: String,
    val message: String
)
