package vn.edu.hcmute.androidchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatTimeFormatter
import java.time.Instant
import java.time.ZoneOffset

class ChatTimeFormatterTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private val utc = ZoneOffset.UTC

    @Test
    fun formatLastSeenFormatsTodayYesterdayOlderAndInvalid() {
        assertEquals(
            "09:15",
            ChatTimeFormatter.formatLastSeen(
                isoTimestamp = "2026-11-14T09:15:00Z",
                yesterdayLabel = "Yesterday",
                nowInstant = now,
                zone = utc
            )
        )
        assertEquals(
            "Hôm qua 21:40",
            ChatTimeFormatter.formatLastSeen(
                isoTimestamp = "2026-11-13T21:40:00Z",
                yesterdayLabel = "Hôm qua",
                nowInstant = now,
                zone = utc
            )
        )
        assertEquals(
            "10/11 08:05",
            ChatTimeFormatter.formatLastSeen(
                isoTimestamp = "2026-11-10T08:05:00Z",
                yesterdayLabel = "Yesterday",
                nowInstant = now,
                zone = utc
            )
        )
        assertNull(ChatTimeFormatter.formatLastSeen(null, nowInstant = now, zone = utc))
        assertNull(ChatTimeFormatter.formatLastSeen("  ", nowInstant = now, zone = utc))
        assertNull(ChatTimeFormatter.formatLastSeen("not-a-timestamp", nowInstant = now, zone = utc))
    }
}
