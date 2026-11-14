package vn.edu.hcmute.androidchat.feature.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.edu.hcmute.androidchat.feature.chat.data.ChatRealtimeParser
import vn.edu.hcmute.androidchat.feature.chat.data.ChatServerEvent
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType

class ChatRealtimeParserTest {
    @Test
    fun encodeMessageSendProducesContractEnvelope() {
        val raw = ChatRealtimeParser.encodeMessageSend(
            clientMessageId = "client-123",
            conversationId = 101L,
            content = "Xin chao D05",
            type = MessageType.TEXT,
            replyToId = 55L,
            requestId = "req-1",
            timestamp = "2026-11-13T04:00:00Z"
        )
        val root = JSONObject(raw)
        assertEquals("message.send", root.getString("type"))
        assertEquals("req-1", root.getString("requestId"))
        assertEquals("2026-11-13T04:00:00Z", root.getString("timestamp"))

        val payload = root.getJSONObject("payload")
        assertEquals("client-123", payload.getString("clientMessageId"))
        assertEquals(101L, payload.getLong("conversationId"))
        assertEquals("TEXT", payload.getString("type"))
        assertEquals("Xin chao D05", payload.getString("content"))
        assertEquals(55L, payload.getLong("replyToId"))
    }

    @Test
    fun decodeMessageAckAndCreatedAndErrorEvents() {
        val ackJson = """
            {
              "type": "message.ack",
              "requestId": "req-1",
              "timestamp": "2026-11-13T04:00:01Z",
              "payload": {
                "clientMessageId": "client-123",
                "conversationId": 101,
                "messageId": 9001,
                "status": "ACCEPTED",
                "serverCreatedAt": "2026-11-13T04:00:01Z"
              }
            }
        """.trimIndent()
        val ack = ChatRealtimeParser.decodeEvent(ackJson) as? ChatServerEvent.MessageAck
        assertNotNull(ack)
        assertEquals("client-123", ack!!.clientMessageId)
        assertEquals(9001L, ack.messageId)
        assertEquals("ACCEPTED", ack.status)

        val createdJson = """
            {
              "type": "message.created",
              "requestId": null,
              "timestamp": "2026-11-13T04:00:01Z",
              "payload": {
                "id": 9001,
                "clientMessageId": "client-123",
                "conversationId": 101,
                "senderId": 42,
                "type": "TEXT",
                "content": "Hello",
                "createdAt": "2026-11-13T04:00:01Z"
              }
            }
        """.trimIndent()
        val created = ChatRealtimeParser.decodeEvent(createdJson) as? ChatServerEvent.MessageCreated
        assertNotNull(created)
        assertEquals(9001L, created!!.id)
        assertEquals(42L, created.senderId)
        assertEquals(MessageType.TEXT, created.type)
        assertEquals("Hello", created.content)

        val errorJson = """
            {
              "type": "error",
              "requestId": "req-9",
              "timestamp": "2026-11-13T04:00:02Z",
              "payload": {
                "code": "FORBIDDEN",
                "message": "Not a member"
              }
            }
        """.trimIndent()
        val error = ChatRealtimeParser.decodeEvent(errorJson) as? ChatServerEvent.Error
        assertNotNull(error)
        assertEquals("FORBIDDEN", error!!.code)
        assertEquals("Not a member", error.message)
    }

    @Test
    fun decodeReceiptAndIgnoreInvalidJson() {
        val receiptJson = """
            {
              "type": "receipt",
              "requestId": null,
              "timestamp": "2026-11-13T04:00:03Z",
              "payload": {
                "conversationId": 101,
                "messageId": 9001,
                "userId": 77,
                "status": "READ",
                "at": "2026-11-13T04:00:03Z"
              }
            }
        """.trimIndent()
        val receipt = ChatRealtimeParser.decodeEvent(receiptJson) as? ChatServerEvent.Receipt
        assertNotNull(receipt)
        assertEquals(MessageDeliveryStatus.READ, receipt!!.status)
        assertTrue(ChatRealtimeParser.decodeEvent("not-json") == null)
        assertNull(ChatRealtimeParser.decodeEvent("""{"type":"unknown","payload":{}}"""))
    }

    @Test
    fun decodeMessageUpdatedRecalledAndReactionEvents() {
        val updatedJson = """{"type":"message.updated","payload":{"conversationId":101,"messageId":55,"content":"Edited!","editedAt":"2026-11-13T11:30:00Z"}}"""
        val updated = ChatRealtimeParser.decodeEvent(updatedJson) as? ChatServerEvent.MessageUpdated
        assertNotNull(updated)
        assertEquals("Edited!", updated!!.content)

        val recalledJson = """{"type":"message.recalled","payload":{"conversationId":101,"messageId":55,"deletedAt":"2026-11-13T11:31:00Z"}}"""
        val recalled = ChatRealtimeParser.decodeEvent(recalledJson) as? ChatServerEvent.MessageRecalled
        assertNotNull(recalled)
        assertEquals(55L, recalled!!.messageId)

        val reactionJson = """{"type":"message.reaction","payload":{"conversationId":101,"messageId":55,"userId":99,"emoji":"❤️","added":true,"counts":{"❤️":2}}}"""
        val reaction = ChatRealtimeParser.decodeEvent(reactionJson) as? ChatServerEvent.ReactionUpdated
        assertNotNull(reaction)
        assertEquals(2, reaction!!.counts["❤️"])
    }

    @Test
    fun encodeAndDecodeCallSignalForMediaIntegration() {
        val encoded = ChatRealtimeParser.encodeCallSignal(
            conversationId = 101L,
            callId = "call-abc",
            signalType = "OFFER",
            targetUserId = 99L,
            sdpOrCandidate = "v=0",
            requestId = "r-call",
            timestamp = "2026-11-13T11:55:00Z"
        )
        val root = JSONObject(encoded)
        assertEquals("call.signal", root.getString("type"))

        val serverJson = """
            {
              "type":"call.signal",
              "payload":{
                "conversationId":101,
                "callId":"call-abc",
                "signalType":"OFFER",
                "fromUserId":42,
                "targetUserId":99,
                "sdpOrCandidate":"v=0"
              }
            }
        """.trimIndent()
        val decoded = ChatRealtimeParser.decodeEvent(serverJson) as? ChatServerEvent.CallSignal
        assertNotNull(decoded)
        assertEquals(42L, decoded!!.fromUserId)
        assertEquals("OFFER", decoded.signalType)
        assertEquals("v=0", decoded.sdpOrCandidate)
    }
}
