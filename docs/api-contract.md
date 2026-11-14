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

### 2.2. Thực thể liên module (AUTH proposal v0.2 — chờ AUTH/CHAT/MEDIA review)
- **AUTH (Đinh Công Huy) — `User`:** `id`, normalized unique `email`, unique lowercase `username`, `displayName`, server-only `passwordHash`, nullable E.164 `normalizedPhone`, nullable provider-owned `phoneVerifiedAt`, `phoneDiscoverable`, `avatarUrl`, `bio`, `privacySettings`, `status`, `createdAt`, `updatedAt`. Never return `passwordHash`; only return a phone number on `/users/me`, and never expose it from general search. A client cannot set verification metadata.
- **AUTH — `Session`:** UUID `id`, `userId`, server-only SHA-256 `refreshTokenHash`, `createdAt`, `accessTokenExpiresAt`, `refreshTokenExpiresAt`, nullable `rotatedAt`, `lastUsedAt`, `revokedAt` and `revokedReason`, `deviceName`, `clientPlatform`. Clear the refresh hash on revoke. Keep one active refresh token per session and rotate it atomically on refresh.
- **AUTH — `FriendRequest` / `ContactRelation`:** `FriendRequest` has string API `id`, sender/recipient public profiles, status (`PENDING`, `ACCEPTED`, `REJECTED`, `CANCELED`), creation time and nullable response time. Accepted requests also create a canonical ordered user-pair row in `friend_relationships`. `UserBlock` remains planned and is not part of V003.
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

### 3.3. AUTH REST API (proposal v0.2 — chờ review của CHAT & MEDIA)

All times are UTC ISO-8601. Private routes require a valid access JWT and an active server-side session. The JWT contract is `iss = JWT_ISSUER`, `aud = JWT_AUDIENCE`, numeric `sub = userId`, UUID `sid = sessionId`, `iat`, and `exp`. Access tokens expire after 15 minutes; opaque refresh tokens expire after 30 days, are returned only at login/register/refresh, are stored as SHA-256 hashes on the server, and rotate on every refresh. Reject an old, expired, revoked, or already-rotated refresh token with the common `UNAUTHORIZED` error. The server checks `sid` against the active `sessions` row for REST and WebSocket authorization, so logout revokes outstanding access tokens immediately.

| Method & Path | Request / response | Access and behavior |
|---|---|---|
| `POST /auth/register` | `{ "email", "password", "displayName", "username?", "deviceName?", "clientPlatform?" }` → `AuthTokenResponse` | Public. Normalize email/username to lowercase; require valid email, username `[a-z0-9_]{3,24}`, display name 1–80 chars, password 10–72 UTF-8 bytes. Hash password with BCrypt cost 12. Duplicate email/username → `409 CONFLICT`. |
| `POST /auth/login` | `{ "email", "password", "deviceName?", "clientPlatform?" }` → `AuthTokenResponse` | Public. Return the same `401 UNAUTHORIZED` for an unknown email and wrong password. Never log credentials. |
| `POST /auth/refresh` | `{ "refreshToken" }` → `AuthTokenResponse` | Public. Atomically rotate a single-use refresh token and issue a new 15-minute access token for the same session. |
| `POST /auth/logout` | Bearer access JWT + `{ "refreshToken" }` → `{ "revoked": true/false }` | Authenticated; refresh token must belong to the caller's JWT `sid` and `sub`. Revoke the session and clear its stored refresh-token hash. |
| `GET /users/me` | → `PublicUser` | Authenticated; returns the caller's email and phone along with public profile fields. |
| `PATCH /users/me` | `{ "displayName?", "bio?" }` → `PublicUser` | Authenticated. At least one field is required; display name 1–80 chars, bio up to 160 chars. Send `bio: ""` to clear it. |
| `GET /users/search?q=...&cursor=...&limit=30` | → `CursorPage<PublicUser>` | Authenticated. Search display name/username only; query 2–80 chars; limit 1–100; excludes caller. Cursor is opaque URL-safe Base64 of the last returned numeric id. No email or phone in results. |
| `GET /users/by-phone?phone=+...` | → `PublicUser` | Authenticated. Normalize visual spaces/parentheses/dashes and require E.164. Return the same `404 NOT_FOUND` for an absent or non-discoverable account. |

#### Phone discovery, friends and blocks (AUTH proposal for D11–D19 — pending group review)

These routes are authenticated and always derive the acting user from the validated JWT `sub`/`sid`; caller-supplied owner IDs are not accepted. `phoneVerifiedAt` is written only by a trusted provider integration. No phone verification route is available until the group selects an OTP provider. Phone-number enrollment remains blocked pending that provider decision; enabling discovery is rejected unless the account has both a phone number and provider-backed verification metadata. Discovery is opt-in and phone numbers are never included in search, friend-request, friends or block-list responses. If either party has blocked the other, phone lookup, user search and friends-list responses hide that pair. Creating a block cancels pending friend requests in either direction; an already accepted friendship remains stored but hidden while the block is active. Only the user who created a block can remove that row.

| Method & Path | Request / response | Access and behavior |
|---|---|---|
| `GET /users/me/phone-discovery` | → `{ "phoneVerified": boolean, "discoverable": boolean }` | Authenticated; returns only the caller's verification state and discovery preference. |
| `PATCH /users/me/phone-discovery` | `{ "discoverable": boolean }` → same settings object | Authenticated; only the caller's preference changes. Enabling requires `normalizedPhone` and non-null `phoneVerifiedAt`; otherwise return `409 CONFLICT`. Disabling is always allowed. |
| `POST /friend-requests` | `{ "targetUserId": number }` → `FriendRequest` (`201`) | Authenticated. Reject self-target, missing users, existing friendship, or any pending request for the same unordered pair. A pending reverse-direction request must be accepted by its recipient instead. |
| `GET /friend-requests?direction=incoming\|outgoing` | → `{ "items": [FriendRequest] }` | Authenticated; lists requests addressed to or created by the caller. The nested profiles use `PublicUser` without email or phone. |
| `POST /friend-requests/{id}/accept` | → updated `FriendRequest` | Authenticated recipient only. In one transaction, mark the request accepted and persist the canonical friendship. Wrong owner, unknown ID, or non-pending request returns `404 NOT_FOUND`. |
| `POST /friend-requests/{id}/reject` | → updated `FriendRequest` | Authenticated recipient only; sets `REJECTED` and `respondedAt`. |
| `DELETE /friend-requests/{id}` | → `204 No Content` | Authenticated sender only; cancels a pending request (`CANCELED`). |
| `GET /friends` | → `{ "items": [PublicUser] }` | Authenticated; returns accepted friends only, without email or phone. |
| `GET /users/blocked` | → `{ "items": [PublicUser] }` | Authenticated; lists only users blocked by the caller, without email or phone. |
| `POST /users/{targetUserId}/block` | → `204 No Content` | Authenticated; creates an idempotent caller-owned block. Reject self-block and missing users. Cancels pending friend requests for the pair. |
| `DELETE /users/{targetUserId}/block` | → `204 No Content` | Authenticated; removes only the caller's block. A missing block is idempotent; a missing user returns `404`. |

`FriendRequest` is `{ "id": string, "sender": PublicUser, "recipient": PublicUser, "status": "PENDING" | "ACCEPTED" | "REJECTED" | "CANCELED", "createdAt": ISO-8601, "respondedAt": ISO-8601 | null }`. Public request IDs are decimal strings so clients do not lose 64-bit precision. Invalid bodies/directions return `400 VALIDATION_ERROR`; duplicate or already-friends requests return `409 CONFLICT`; a request between blocked users returns `403 BLOCKED`. Request ownership is enforced in SQL predicates; friend acceptance stores both state changes atomically. `V003__auth_phone_discovery_and_friends.sql` and `V004__auth_user_blocks.sql` follow V001/V002 without editing earlier migrations. **CHAT integration is required before claiming message/WebSocket delivery enforcement; this AUTH proposal does not itself block chat events.**

`AuthTokenResponse` contains `user`, `sessionId`, `accessToken`, `accessTokenExpiresAt`, `refreshToken`, `refreshTokenExpiresAt`, and `tokenType: "Bearer"`. Passwords, access tokens and refresh tokens are never persisted in Android preferences as plaintext. Validation errors use `400 VALIDATION_ERROR`; duplicate accounts use `409 CONFLICT`; unavailable PostgreSQL uses `503 SERVICE_UNAVAILABLE`.

#### Android session provider shared with CHAT and MEDIA

AUTH owns `AuthTokenProvider` in `android-app/.../feature/auth/session/`. The shared interface exposes `suspend fun accessToken(): String?` and `suspend fun refreshAfterUnauthorized(): String?`; AUTH's implementation serializes refresh requests, rotates/persists tokens, and clears the local session if refresh is rejected. Store the access token, refresh token and expiry metadata in AUTH-owned `AuthSessionStore`, encrypted with an Android Keystore AES-GCM key; never use `local.properties` or ordinary preferences for token plaintext.

HTTP clients for CHAT/MEDIA obtain `accessToken()` for `Authorization: Bearer <accessToken>`. On `401`, call `refreshAfterUnauthorized()` once and retry only when the request is safe/idempotent or carries the endpoint's idempotency key; do not blindly replay a non-idempotent mutation. WebSocket `/ws/chat` obtains the same token before handshake; on `401`, refresh and reconnect once. Other modules depend on the interface, not AUTH's storage implementation. MainActivity/network-root wiring remains a shared integration point for review.

#### OTP dev/test decision

This auth-foundation slice does not implement OTP (the plan schedules provider request/verification for D09). The provider remains unselected. Unit/integration tests inject an in-memory fake delivery adapter and inspect its test-only code; no fixed OTP, production bypass, or OTP in logs/API responses. Android development uses the selected provider's official sandbox/test numbers once the group chooses a provider. Production must fail closed when no real provider is configured; no developer OTP fallback is permitted.

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
