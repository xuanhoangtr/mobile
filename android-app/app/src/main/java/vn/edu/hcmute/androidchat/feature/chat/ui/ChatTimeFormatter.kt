package vn.edu.hcmute.androidchat.feature.chat.ui

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object ChatTimeFormatter {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val shortDateFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.getDefault())
    private val fullDateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault())

    fun formatTimeOnly(raw: String): String {
        val zoned = parseZonedDateTime(raw) ?: return raw
        return zoned.format(timeFormatter)
    }

    fun formatConversationListTime(raw: String): String {
        val zoned = parseZonedDateTime(raw) ?: return raw
        val today = LocalDate.now(zoned.zone)
        return if (zoned.toLocalDate() == today) {
            zoned.format(timeFormatter)
        } else {
            zoned.format(shortDateFormatter)
        }
    }

    fun dateKey(raw: String): LocalDate? = parseZonedDateTime(raw)?.toLocalDate()

    fun formatDateHeader(
        date: LocalDate,
        todayLabel: String,
        yesterdayLabel: String,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val today = LocalDate.now(zone)
        return when (date) {
            today -> todayLabel
            today.minusDays(1) -> yesterdayLabel
            else -> date.format(fullDateFormatter)
        }
    }

    private fun parseZonedDateTime(raw: String): ZonedDateTime? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        return runCatching { Instant.parse(trimmed).atZone(zone) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(trimmed).atZoneSameInstant(zone) }.getOrNull()
    }
}
