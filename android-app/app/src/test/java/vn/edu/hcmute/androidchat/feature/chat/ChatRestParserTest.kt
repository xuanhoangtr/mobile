package vn.edu.hcmute.androidchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.edu.hcmute.androidchat.feature.chat.data.ChatRestParser
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationType
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType

class ChatRestParserTest {
    @Test
    fun parseConversationsPageReadsItemsAndCursor() {
        val raw = """
            {
              "items": [
                {
                  "id": 101,
                  "type": "DIRECT",
                  "name": "Quang Huy",
                  "isPinned": true,
                  "unreadCount": 3,
                  "lastMessage": {
                    "id": 5001,
                    "clientMessageId": "c-1",
                    "conversationId": 101,
                    "senderId": 2,
                    "type": "TEXT",
                    "content": "Hello",
                    "createdAt": "2026-11-13T10:00:00Z"
                  },
                  "updatedAt": "2026-11-13T10:00:00Z"
                }
              ],
              "nextCursor": "101",
              "hasMore": true
            }
        """.trimIndent()

        val page = ChatRestParser.parseConversationsPage(raw)
        assertTrue(page.hasMore)
        assertEquals("101", page.nextCursor)
        assertEquals(1, page.items.size)
        val item = page.items.single()
        assertEquals(ConversationType.DIRECT, item.type)
        assertEquals("Quang Huy", item.title)
        assertTrue(item.isPinned)
        assertEquals(3, item.unreadCount)
        assertEquals("Hello", item.lastMessagePreview)
    }

    @Test
    fun parseMessagesPageMapsCurrentUserAndFlags() {
        val raw = """
            {
              "items": [
                {
                  "id": 10,
                  "clientMessageId": "uuid-10",
                  "conversationId": 101,
                  "senderId": 42,
                  "type": "TEXT",
                  "content": "My msg",
                  "createdAt": "2026-11-13T10:01:00Z",
                  "editedAt": "2026-11-13T10:02:00Z",
                  "deletedAt": null
                },
                {
                  "id": 11,
                  "clientMessageId": "uuid-11",
                  "conversationId": 101,
                  "senderId": 99,
                  "type": "IMAGE",
                  "content": "photo.jpg",
                  "createdAt": "2026-11-13T10:03:00Z",
                  "editedAt": null,
                  "deletedAt": "2026-11-13T10:04:00Z"
                }
              ],
              "nextCursor": null,
              "hasMore": false
            }
        """.trimIndent()

        val page = ChatRestParser.parseMessagesPage(raw, currentUserId = 42L)
        assertFalse(page.hasMore)
        assertNull(page.nextCursor)
        assertEquals(2, page.items.size)

        assertTrue(page.items[0].isFromCurrentUser)
        assertTrue(page.items[0].isEdited)
        assertFalse(page.items[0].isRecalled)

        assertFalse(page.items[1].isFromCurrentUser)
        assertEquals(MessageType.IMAGE, page.items[1].type)
        assertTrue(page.items[1].isRecalled)
    }

    @Test
    fun parseErrorMessageExtractsContractError() {
        val raw = """{"error":{"code":"FORBIDDEN","message":"Not a member"}}"""
        assertEquals("Not a member", ChatRestParser.parseErrorMessage(raw, "fallback"))
        assertEquals("fallback", ChatRestParser.parseErrorMessage("invalid", "fallback"))
    }

    @Test
    fun parseGroupMembersMapsRolesAndFallbackDisplayName() {
        val raw = """
            [
              {"conversationId":500,"userId":1,"role":"OWNER","status":"ACTIVE","joinedAt":"2026-11-13T11:00:00Z"},
              {"conversationId":500,"userId":2,"role":"ADMIN","status":"ACTIVE","joinedAt":"2026-11-13T11:01:00Z"}
            ]
        """.trimIndent()
        val members = ChatRestParser.parseGroupMembers(raw)
        assertEquals(2, members.size)
        assertEquals("OWNER", members[0].role.name)
        assertEquals("User #2", members[1].displayName)
        assertEquals("ADMIN", members[1].role.name)
    }

    @Test
    fun parseConversationSummaryReadsPresenceAndPeerUserId() {
        val raw = """
            {
              "items": [
                {
                  "id": 101,
                  "type": "DIRECT",
                  "name": "Peer A",
                  "peerUserId": 77,
                  "isOnline": true,
                  "peerLastSeenAt": "2026-11-14T08:30:00Z",
                  "unreadCount": 1
                },
                {
                  "id": 102,
                  "type": "GROUP",
                  "name": "Team",
                  "peerUserId": null,
                  "isOnline": false,
                  "peerLastSeenAt": null,
                  "unreadCount": 0
                }
              ],
              "nextCursor": null,
              "hasMore": false
            }
        """.trimIndent()

        val page = ChatRestParser.parseConversationsPage(raw)
        assertEquals(2, page.items.size)
        assertEquals(77L, page.items[0].peerUserId)
        assertTrue(page.items[0].isOnline)
        assertEquals("2026-11-14T08:30:00Z", page.items[0].peerLastSeenAt)

        assertNull(page.items[1].peerUserId)
        assertFalse(page.items[1].isOnline)
        assertNull(page.items[1].peerLastSeenAt)
    }

    @Test
    fun parseMessagesPageParsesDeliveryStatusWithDeliveredFallback() {
        val raw = """
            {
              "items": [
                {
                  "id": 1,
                  "clientMessageId": "c1",
                  "conversationId": 10,
                  "senderId": 42,
                  "type": "TEXT",
                  "content": "read msg",
                  "createdAt": "2026-11-14T08:00:00Z",
                  "deliveryStatus": "READ"
                },
                {
                  "id": 2,
                  "clientMessageId": "c2",
                  "conversationId": 10,
                  "senderId": 42,
                  "type": "TEXT",
                  "content": "accepted msg",
                  "createdAt": "2026-11-14T08:01:00Z",
                  "deliveryStatus": "ACCEPTED"
                },
                {
                  "id": 3,
                  "clientMessageId": "c3",
                  "conversationId": 10,
                  "senderId": 99,
                  "type": "TEXT",
                  "content": "default msg",
                  "createdAt": "2026-11-14T08:02:00Z",
                  "deliveryStatus": null
                }
              ],
              "nextCursor": null,
              "hasMore": false
            }
        """.trimIndent()

        val page = ChatRestParser.parseMessagesPage(raw, currentUserId = 42L)
        assertEquals(MessageDeliveryStatus.READ, page.items[0].deliveryStatus)
        assertEquals(MessageDeliveryStatus.ACCEPTED, page.items[1].deliveryStatus)
        assertEquals(MessageDeliveryStatus.DELIVERED, page.items[2].deliveryStatus)
    }
}
