package vn.edu.hcmute.androidchat.feature.chat.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationType
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType
import vn.edu.hcmute.androidchat.feature.chat.model.MessageUiModel

/**
 * Local persistence contract for chat list & message history across app restarts (D09).
 */
interface ChatLocalCache {
    suspend fun saveConversations(conversations: List<ConversationUiModel>)
    suspend fun getConversations(): List<ConversationUiModel>
    suspend fun saveMessages(conversationId: Long, messages: List<MessageUiModel>)
    suspend fun getMessages(conversationId: Long): List<MessageUiModel>
    suspend fun clear()
}

/**
 * SQLite-backed local cache storing conversations and messages across process restarts.
 */
class SqliteChatLocalCache(context: Context) : ChatLocalCache {
    private val dbHelper = ChatCacheDbHelper(context.applicationContext)

    override suspend fun saveConversations(conversations: List<ConversationUiModel>) {
        withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                for (item in conversations) {
                    val values = ContentValues().apply {
                        put("id", item.id)
                        put("type", item.type.name)
                        put("title", item.title)
                        put("last_preview", item.lastMessagePreview)
                        put("last_time", item.lastMessageTime)
                        put("unread_count", item.unreadCount)
                        put("is_pinned", if (item.isPinned) 1 else 0)
                        put("is_online", if (item.isOnline) 1 else 0)
                    }
                    db.insertWithOnConflict(
                        TABLE_CONVERSATIONS,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override suspend fun getConversations(): List<ConversationUiModel> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val items = mutableListOf<ConversationUiModel>()
        db.rawQuery(
            "SELECT id, type, title, last_preview, last_time, unread_count, is_pinned, is_online FROM $TABLE_CONVERSATIONS ORDER BY is_pinned DESC, id DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items += ConversationUiModel(
                    id = cursor.getLong(0),
                    type = ConversationType.entries.firstOrNull { it.name == cursor.getString(1) }
                        ?: ConversationType.DIRECT,
                    title = cursor.getString(2),
                    lastMessagePreview = cursor.getString(3),
                    lastMessageTime = cursor.getString(4),
                    unreadCount = cursor.getInt(5),
                    isPinned = cursor.getInt(6) == 1,
                    isOnline = cursor.getInt(7) == 1
                )
            }
        }
        items
    }

    override suspend fun saveMessages(conversationId: Long, messages: List<MessageUiModel>) {
        withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                for (msg in messages) {
                    val values = ContentValues().apply {
                        put("client_message_id", msg.clientMessageId)
                        put("server_id", msg.serverId)
                        put("conversation_id", conversationId)
                        put("sender_id", msg.senderId)
                        put("is_from_current_user", if (msg.isFromCurrentUser) 1 else 0)
                        put("type", msg.type.name)
                        put("content", msg.content)
                        put("created_at", msg.createdAt)
                        put("delivery_status", msg.deliveryStatus.name)
                        put("reply_to_id", msg.replyToId)
                        put("is_edited", if (msg.isEdited) 1 else 0)
                        put("is_recalled", if (msg.isRecalled) 1 else 0)
                    }
                    db.insertWithOnConflict(
                        TABLE_MESSAGES,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override suspend fun getMessages(conversationId: Long): List<MessageUiModel> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val items = mutableListOf<MessageUiModel>()
        db.rawQuery(
            """
                SELECT client_message_id, server_id, conversation_id, sender_id, is_from_current_user,
                       type, content, created_at, delivery_status, reply_to_id, is_edited, is_recalled
                FROM $TABLE_MESSAGES
                WHERE conversation_id = ?
                ORDER BY COALESCE(server_id, 9223372036854775807) ASC, rowid ASC
            """.trimIndent(),
            arrayOf(conversationId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val clientMsgId = cursor.getString(0)
                items += MessageUiModel(
                    localId = clientMsgId,
                    serverId = if (cursor.isNull(1)) null else cursor.getLong(1),
                    clientMessageId = clientMsgId,
                    conversationId = cursor.getLong(2),
                    senderId = cursor.getLong(3),
                    isFromCurrentUser = cursor.getInt(4) == 1,
                    type = MessageType.entries.firstOrNull { it.name == cursor.getString(5) }
                        ?: MessageType.TEXT,
                    content = cursor.getString(6),
                    createdAt = cursor.getString(7),
                    deliveryStatus = MessageDeliveryStatus.entries.firstOrNull { it.name == cursor.getString(8) }
                        ?: MessageDeliveryStatus.DELIVERED,
                    replyToId = if (cursor.isNull(9)) null else cursor.getLong(9),
                    isEdited = cursor.getInt(10) == 1,
                    isRecalled = cursor.getInt(11) == 1
                )
            }
        }
        items
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            db.delete(TABLE_MESSAGES, null, null)
            db.delete(TABLE_CONVERSATIONS, null, null)
        }
    }

    private class ChatCacheDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                    CREATE TABLE $TABLE_CONVERSATIONS (
                        id INTEGER PRIMARY KEY,
                        type TEXT NOT NULL,
                        title TEXT NOT NULL,
                        last_preview TEXT,
                        last_time TEXT,
                        unread_count INTEGER NOT NULL DEFAULT 0,
                        is_pinned INTEGER NOT NULL DEFAULT 0,
                        is_online INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent()
            )
            db.execSQL(
                """
                    CREATE TABLE $TABLE_MESSAGES (
                        conversation_id INTEGER NOT NULL,
                        client_message_id TEXT NOT NULL,
                        server_id INTEGER,
                        sender_id INTEGER NOT NULL,
                        is_from_current_user INTEGER NOT NULL DEFAULT 0,
                        type TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        delivery_status TEXT NOT NULL,
                        reply_to_id INTEGER,
                        is_edited INTEGER NOT NULL DEFAULT 0,
                        is_recalled INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (conversation_id, client_message_id)
                    )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_MESSAGES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CONVERSATIONS")
            onCreate(db)
        }
    }

    private companion object {
        const val DB_NAME = "chat_local_cache.db"
        const val DB_VERSION = 1
        const val TABLE_CONVERSATIONS = "cached_conversations"
        const val TABLE_MESSAGES = "cached_messages"
    }
}
