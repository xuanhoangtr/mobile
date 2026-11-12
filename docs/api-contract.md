# API & Realtime Contract (`docs/api-contract.md`)

> **Trạng thái:** `DRAFT v0.1` — Chủ trì soạn thảo: **Xuân Hoàng (CHAT)** (mốc D02)  
> **Cần review & chốt chung:** **Đinh Công Huy (AUTH)** · **Nguyễn Quang Huy (MEDIA)**  
> **Tham chiếu:** [ke_hoach_40_ngay_android_chat_kotlin.md](../ke_hoach_40_ngay_android_chat_kotlin.md) · [AGENTS.md](../AGENTS.md)

---

## 1. Quy ước chung bắt buộc

1. **Xác thực & phân quyền:**
   - Mọi API có dữ liệu riêng đều yêu cầu `Authorization: Bearer <accessToken>` (JWT).
   - Server luôn xác thực danh tính từ JWT (không tin `userId`/`senderId` do client tự gửi) và kiểm tra quyền thành viên/sở hữu/trạng thái chặn trước khi đọc hoặc ghi dữ liệu (chống IDOR).
   - Không ghi token, OTP, mật khẩu hay nội dung nhạy cảm vào log.
2. **Định danh & thời gian:**
   - Khóa chính thực thể (`User`, `Conversation`, `Message`, `MediaObject`, `CallSession`): số nguyên 64-bit (`Long` / PostgreSQL `BIGINT`).
   - `clientMessageId`: chuỗi `UUID v4` do client sinh trước khi gửi, dùng làm khóa chống gửi lặp (idempotency).
   - Thời gian: do server quyết định theo UTC, chuẩn **ISO-8601** (ví dụ `2026-11-12T13:00:00Z`).
3. **Phân trang (Cursor Pagination):**
   - Tham số query: `?cursor=<string>&limit=<int>` (`limit` mặc định `30`, tối đa `100`).
   - Phản hồi chuẩn:
     ```json
     {
       "items": [],
       "nextCursor": "opaque-cursor-or-null",
       "hasMore": false
     }
     ```
4. **Định dạng lỗi thống nhất:**
   ```json
   {
     "error": {
       "code": "FORBIDDEN",
       "message": "User is not a member of this conversation",
       "requestId": "req-123e4567"
     }
   }
   ```
   - Mã lỗi chuẩn: `UNAUTHORIZED` (401), `FORBIDDEN` (403), `NOT_FOUND` (404), `VALIDATION_ERROR` (400), `CONFLICT` (409), `BLOCKED` (403), `RATE_LIMITED` (429), `INTERNAL_ERROR` (500).

---

## 2. Cấu trúc dữ liệu cốt lõi (Database Schema / Domain Models)

### 2.1. Bảng thuộc module CHAT (Owner: Xuân Hoàng)

#### `conversations` (`Conversation`)
| Cột | Kiểu (PostgreSQL) | Ràng buộc / Ghi chú |
|---|---|---|
| `id` | `BIGSERIAL` | `PRIMARY KEY` |
| `type` | `VARCHAR(16)` | `NOT NULL` (`DIRECT` \| `GROUP`) |
| `name` | `VARCHAR(120)` | `NULL` với `DIRECT`, bắt buộc với `GROUP` |
| `avatar_url` | `TEXT` | `NULL` |
| `direct_pair_key` | `VARCHAR(64)` | `UNIQUE NULL` (ví dụ `minUserId:maxUserId` để chống tạo trùng 2 hội thoại 1–1) |
| `created_by` | `BIGINT` | `NOT NULL REFERENCES users(id)` |
| `last_message_id` | `BIGINT` | `NULL` (cập nhật khi có tin nhắn mới hợp lệ) |
| `metadata` | `JSONB` | `NOT NULL DEFAULT '{}'::jsonb` |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT NOW()` |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT NOW()` |

#### `conversation_members` (`ConversationMember`)
| Cột | Kiểu (PostgreSQL) | Ràng buộc / Ghi chú |
|---|---|---|
| `conversation_id` | `BIGINT` | `NOT NULL REFERENCES conversations(id)` |
| `user_id` | `BIGINT` | `NOT NULL REFERENCES users(id)` |
| `role` | `VARCHAR(16)` | `NOT NULL DEFAULT 'MEMBER'` (`OWNER` \| `ADMIN` \| `MEMBER`) |
| `status` | `VARCHAR(16)` | `NOT NULL DEFAULT 'ACTIVE'` (`ACTIVE` \| `LEFT` \| `REMOVED`) |
| `is_pinned` | `BOOLEAN` | `NOT NULL DEFAULT FALSE` |
| `pinned_at` | `TIMESTAMPTZ` | `NULL` |
| `last_read_message_id` | `BIGINT` | `NULL` |
| `joined_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT NOW()` |
| `left_at` | `TIMESTAMPTZ` | `NULL` |
| — | — | `PRIMARY KEY (conversation_id, user_id)` |

#### `messages` (`Message`)
| Cột | Kiểu (PostgreSQL) | Ràng buộc / Ghi chú |
|---|---|---|
| `id` | `BIGSERIAL` | `PRIMARY KEY` |
| `client_message_id` | `UUID` | `NOT NULL` |
| `conversation_id` | `BIGINT` | `NOT NULL REFERENCES conversations(id)` |
| `sender_id` | `BIGINT` | `NOT NULL REFERENCES users(id)` |
| `type` | `VARCHAR(24)` | `NOT NULL` (`TEXT` \| `IMAGE` \| `VIDEO` \| `AUDIO` \| `FILE` \| `VOICE` \| `STICKER` \| `LOCATION` \| `SYSTEM`) |
| `content` | `TEXT` | `NULL` (nội dung text hoặc caption) |
| `media_ref` | `BIGINT` | `NULL` (tham chiếu `media_objects(id)` do MEDIA quản lý) |
| `metadata` | `JSONB` | `NOT NULL DEFAULT '{}'::jsonb` (tọa độ vị trí, mã sticker, kích thước ảnh, thời lượng voice…) |
| `reply_to_id` | `BIGINT` | `NULL REFERENCES messages(id)` |
| `forwarded_from_id` | `BIGINT` | `NULL REFERENCES messages(id)` |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT NOW()` (server timestamp) |
| `edited_at` | `TIMESTAMPTZ` | `NULL` |
| `deleted_at` | `TIMESTAMPTZ` | `NULL` (tombstone khi thu hồi/xóa) |
| — | — | `UNIQUE (sender_id, client_message_id)` (bắt buộc để chống trùng khi reconnect) |

#### `message_receipts` (`MessageReceipt`)
| Cột | Kiểu (PostgreSQL) | Ràng buộc / Ghi chú |
|---|---|---|
| `message_id` | `BIGINT` | `NOT NULL REFERENCES messages(id)` |
| `recipient_id` | `BIGINT` | `NOT NULL REFERENCES users(id)` |
| `delivered_at` | `TIMESTAMPTZ` | `NULL` |
| `read_at` | `TIMESTAMPTZ` | `NULL` |
| — | — | `PRIMARY KEY (message_id, recipient_id)` |

> **Quy tắc chuyển trạng thái một chiều:** `ACCEPTED` (đã lưu ở server và trả `message.ack`) → `DELIVERED` (`delivered_at != null`) → `READ` (`read_at != null`). Không bao giờ lùi trạng thái từ `READ` về `DELIVERED`.

#### `message_reactions` (`Reaction`) & `pinned_messages` (mở rộng P1)
- `message_reactions`: `(message_id, user_id, emoji, created_at)`, `PRIMARY KEY (message_id, user_id, emoji)`.
- `pinned_messages`: `(conversation_id, message_id, pinned_by, pinned_at)`, `PRIMARY KEY (conversation_id, message_id)`.

### 2.2. Thực thể liên module (chốt chi tiết với AUTH & MEDIA ở D02)
- **AUTH (Đinh Công Huy):** `User`, `Session`, `FriendRequest` / `ContactRelation`, `UserBlock`.
- **MEDIA (Nguyễn Quang Huy):** `MediaObject` (`id`, `ownerId`, `conversationId`, `mimeType`, `byteSize`, `storageKey`, `uploadState`), `CallSession`, `DeviceToken` / `NotificationPreference`.

---

## 3. REST API Contract

### 3.1. Hệ thống
- `GET /health` → `200 OK`:
  ```json
  {
    "status": "UP",
    "service": "android-chat-backend",
    "timestamp": "2026-11-12T13:00:00Z"
  }
  ```

### 3.2. Conversation & Chat (Owner: Xuân Hoàng — CHAT)

| Method & Path | Mục đích | Quyền / Ghi chú |
|---|---|---|
| `GET /conversations?cursor=...&limit=30` | Lấy danh sách hội thoại của user hiện tại | Chỉ trả hội thoại có `status = ACTIVE`, sắp xếp theo `is_pinned DESC, updated_at DESC` |
| `POST /conversations/direct` | Tạo hoặc lấy lại hội thoại 1–1 với `targetUserId` | Kiểm tra user tồn tại và không bị block giữa 2 phía |
| `POST /conversations/group` | Tạo nhóm mới (`name`, `memberIds[]`) | Người tạo là `OWNER`; nhóm khởi tạo tối thiểu 3 thành viên (tính cả creator) |
| `GET /conversations/{id}/members` | Danh sách thành viên nhóm | Chỉ thành viên `ACTIVE` mới xem được |
| `POST /conversations/{id}/members` | Thêm thành viên vào nhóm (`userIds[]`) | Chỉ `OWNER` hoặc `ADMIN` |
| `PATCH /conversations/{id}/members/{userId}` | Đổi vai trò (`ADMIN` / `MEMBER`) | Chỉ `OWNER` |
| `DELETE /conversations/{id}/members/{userId}` | Xóa thành viên hoặc tự rời nhóm | Thành viên tự xóa chính mình; `OWNER`/`ADMIN` xóa người khác theo phân cấp |
| `PATCH /conversations/{id}/pin` | Ghim / bỏ ghim hội thoại cho user hiện tại | Body `{ "pinned": true }` |
| `GET /conversations/{id}/messages?cursor=...&limit=30` | Lấy lịch sử tin nhắn theo cursor | Chỉ thành viên đang được phép truy cập |
| `GET /messages/search?conversationId=...&q=...&cursor=...&limit=20` | Tìm lịch sử tin nhắn | Chỉ tìm trong các hội thoại user có quyền truy cập |
| `PATCH /messages/{id}` | Sửa nội dung tin nhắn `TEXT` | Chỉ `sender_id` và tin chưa bị thu hồi; cập nhật `edited_at` |
| `POST /messages/{id}/recall` | Thu hồi tin nhắn trên mọi thiết bị | Chỉ `sender_id` (hoặc `OWNER`/`ADMIN` theo rule nhóm); đặt `deleted_at` |
| `POST /conversations/{id}/pinned-messages/{messageId}` | Ghim tin nhắn trong hội thoại | Thành viên có quyền trong hội thoại |
| `DELETE /conversations/{id}/pinned-messages/{messageId}` | Bỏ ghim tin nhắn | Thành viên có quyền trong hội thoại |

#### Ví dụ `GET /conversations` response item
```json
{
  "id": 101,
  "type": "DIRECT",
  "name": "Nguyễn Quang Huy",
  "avatarUrl": null,
  "isPinned": false,
  "unreadCount": 2,
  "lastMessage": {
    "id": 5001,
    "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
    "conversationId": 101,
    "senderId": 2,
    "type": "TEXT",
    "content": "Chiều nay họp API contract nhé",
    "createdAt": "2026-11-12T12:55:00Z",
    "editedAt": null,
    "deletedAt": null
  },
  "updatedAt": "2026-11-12T12:55:00Z"
}
```

### 3.3. Nhóm API tham khảo của AUTH & MEDIA (chờ owner chốt chi tiết ở D02)
- **AUTH (Đinh Công Huy):** `POST /auth/register`, `POST /auth/login`, `POST /auth/otp/request`, `POST /auth/otp/verify`, `POST /auth/refresh`, `POST /auth/logout`, `GET/PATCH /users/me`, `GET /users/search`, `GET /users/by-phone`, `/friend-requests`, `/users/{id}/block`.
- **MEDIA (Nguyễn Quang Huy):** `POST /media/upload`, `GET /media/{id}`, `GET /media/{id}/download`, `POST /devices/push-token`.

---

## 4. WebSocket Realtime Contract (`/ws/chat`)

### 4.1. Kết nối & Xác thực
- **Endpoint:** `GET /ws/chat` (nâng cấp HTTP → WebSocket).
- **Xác thực:** Header `Authorization: Bearer <accessToken>` khi bắt tay WebSocket. Nếu thiếu token, token hết hạn hoặc bị thu hồi → từ chối nâng cấp (`401 Unauthorized`) hoặc đóng socket với mã `1008` (Policy Violation).
- **Keep-alive:** WebSocket Ping/Pong mỗi ~20–30 giây để phát hiện rớt mạng và cập nhật `presence`.

### 4.2. Khung gói tin chuẩn (Envelope)
Mọi frame JSON gửi hai chiều đều dùng chung vỏ bọc:
```json
{
  "type": "event.name",
  "requestId": "req-uuid-optional",
  "timestamp": "2026-11-12T13:00:00Z",
  "payload": {}
}
```

### 4.3. Đặc tả 6 sự kiện WebSocket cốt lõi

#### 1) `message.send` (Client → Server)
Gửi một tin nhắn mới tới hội thoại. Server xác thực membership, kiểm tra block (với `DIRECT`), kiểm tra `mediaRef` (nếu có) thuộc đúng hội thoại/chủ sở hữu, rồi lưu DB theo `(senderId, clientMessageId)`.
```json
{
  "type": "message.send",
  "requestId": "req-001",
  "timestamp": "2026-11-12T13:00:00Z",
  "payload": {
    "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
    "conversationId": 101,
    "type": "TEXT",
    "content": "Xin chào mọi người",
    "mediaRef": null,
    "replyToId": null,
    "metadata": {}
  }
}
```

#### 2) `message.ack` (Server → Sender)
Phản hồi xác nhận server đã tiếp nhận và lưu thành công (hoặc phát hiện trùng `clientMessageId` khi client retry sau reconnect).
```json
{
  "type": "message.ack",
  "requestId": "req-001",
  "timestamp": "2026-11-12T13:00:00Z",
  "payload": {
    "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
    "conversationId": 101,
    "messageId": 5002,
    "status": "ACCEPTED",
    "serverCreatedAt": "2026-11-12T13:00:00Z"
  }
}
```
- `status`: `"ACCEPTED"` (tạo mới thành công) hoặc `"DUPLICATE"` (đã tồn tại theo `(senderId, clientMessageId)` — trả về cùng `messageId` và `serverCreatedAt` cũ, **không tạo bản ghi thứ 2**).

#### 3) `message.created` (Server → Active Conversation Members)
Phát realtime tới các thành viên đang kết nối của hội thoại (bao gồm các thiết bị khác của người gửi).
```json
{
  "type": "message.created",
  "requestId": null,
  "timestamp": "2026-11-12T13:00:00Z",
  "payload": {
    "id": 5002,
    "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
    "conversationId": 101,
    "senderId": 3,
    "type": "TEXT",
    "content": "Xin chào mọi người",
    "mediaRef": null,
    "replyToId": null,
    "metadata": {},
    "createdAt": "2026-11-12T13:00:00Z",
    "editedAt": null,
    "deletedAt": null
  }
}
```

#### 4) `typing` (Client → Server → Conversation Members)
- Client gửi tối đa 1 event mỗi 2–3 giây khi người dùng đang gõ; gửi `isTyping: false` khi xóa trắng/gửi xong/rời màn hình.
- Server kiểm tra membership và broadcast kèm `userId` lấy từ JWT; trạng thái typing tự hết hạn sau **5 giây** nếu không nhận thêm `isTyping: true`.
```json
{
  "type": "typing",
  "requestId": null,
  "timestamp": "2026-11-12T13:00:05Z",
  "payload": {
    "conversationId": 101,
    "userId": 3,
    "isTyping": true,
    "expiresInMs": 5000
  }
}
```

#### 5) `presence` (Server → Subscribers / Client Heartbeat)
- Client định kỳ gửi ping hoặc server dùng WebSocket heartbeat để phát hiện mất kết nối (timeout ~45–60s chuyển `OFFLINE`, tránh treo `ONLINE` vĩnh viễn khi rớt mạng).
```json
{
  "type": "presence",
  "requestId": null,
  "timestamp": "2026-11-12T13:00:10Z",
  "payload": {
    "userId": 2,
    "status": "ONLINE",
    "lastSeenAt": "2026-11-12T13:00:10Z"
  }
}
```
- `status`: `"ONLINE"` \| `"OFFLINE"` (tôn trọng cài đặt quyền riêng tư từ module AUTH nếu user tắt hiển thị `lastSeenAt`).

#### 6) `receipt` (Client → Server → Conversation Members)
Cập nhật trạng thái đã nhận (`DELIVERED`) hoặc đã đọc (`READ`). Server đảm bảo chuyển trạng thái **một chiều** và không cho phép báo receipt cho hội thoại user không phải thành viên.
```json
{
  "type": "receipt",
  "requestId": "req-002",
  "timestamp": "2026-11-12T13:00:12Z",
  "payload": {
    "conversationId": 101,
    "messageId": 5002,
    "userId": 2,
    "status": "READ",
    "at": "2026-11-12T13:00:12Z"
  }
}
```

### 4.4. Sự kiện lỗi & vòng đời mở rộng
- `error` (Server → Client): trả về khi frame không hợp lệ hoặc không đủ quyền:
  ```json
  {
    "type": "error",
    "requestId": "req-001",
    "timestamp": "2026-11-12T13:00:00Z",
    "payload": {
      "code": "FORBIDDEN",
      "message": "You are not allowed to send messages to this conversation"
    }
  }
  ```
- Các sự kiện mở rộng đã đặt tên thống nhất theo kế hoạch để triển khai ở Sprint D–E:
  - CHAT: `message.updated`, `message.recalled`, `message.reaction`
  - MEDIA (signaling cuộc gọi): `call.invite`, `call.answer`, `call.ice-candidate`, `call.end`

---

## 5. Chiến lược đồng bộ khi mất mạng & Reconnect
1. Khi gửi tin lúc offline/mạng yếu: client lưu tin tạm vào Room với `clientMessageId` và trạng thái `SENDING` / `PENDING`.
2. Khi kết nối WebSocket lại: client gửi lại các tin chưa nhận `message.ack` giữ nguyên `clientMessageId`; server trả `message.ack` (`ACCEPTED` hoặc `DUPLICATE`).
3. Đồng thời client gọi `GET /conversations/{id}/messages?cursor=...` để kéo bù các tin nhắn bị lỡ trong khoảng thời gian mất kết nối và hợp nhất theo `id` / `clientMessageId`.
