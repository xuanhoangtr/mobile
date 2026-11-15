# Báo cáo kiểm thử & kỹ thuật module CHAT (D31–D35)

- **Phụ trách:** Xuân Hoàng (`xuanhoangtr`) — Module CHAT
- **Nhánh:** `feature/chat-realtime` (worktree `wt-chat`)
- **Tham chiếu:** `ke_hoach_40_ngay_android_chat_kotlin.md` (§4 D31–D35), `docs/api-contract.md`

---

## 1. Kiến trúc kỹ thuật module CHAT (D34)

### 1.1. WebSocket Realtime (`/ws/chat`)
- Xác thực bắt buộc bằng JWT (`Authorization: Bearer <accessToken>`), từ chối kết nối thiếu/sai token (`401 Unauthorized`).
- Khung gói tin chuẩn `WsEnvelope`: `{ "type", "requestId", "timestamp", "payload" }`.
- Các sự kiện hỗ trợ:
  - `message.send` → kiểm tra active membership → lưu idempotent theo `(sender_id, client_message_id)` → trả `message.ack` (`ACCEPTED` / `DUPLICATE`) cho người gửi và broadcast `message.created` tới toàn bộ thành viên active.
  - `typing` → kiểm tra membership → broadcast trạng thái đang nhập (`expiresInMs = 5000`); client debounce 300ms khi soạn và tự hết hạn theo `expiresInMs`.
  - `presence` → `ChatSessionRegistry` theo dõi phiên đầu/cuối của từng `userId`; phát `ONLINE` khi mở phiên đầu tiên và `OFFLINE` kèm `lastSeenAt` khi đóng phiên cuối cùng.
  - `receipt` → lưu trạng thái một chiều `ACCEPTED → DELIVERED → READ` (không bao giờ lùi từ `READ` về `DELIVERED`) và broadcast tới thành viên.
  - `message.updated` / `message.recalled` / `message.reaction` → đồng bộ sửa, thu hồi (tombstone) và cảm xúc emoji realtime.
  - `call.signal` → relay tín hiệu WebRTC (`OFFER`/`ANSWER`/`ICE`/`END`) giữa các thành viên active trong hội thoại cho module MEDIA.

### 1.2. Cơ sở dữ liệu & Lưu trữ cục bộ
- **Backend (`PostgresChatRepository` & `InMemoryChatRepository`):**
  - `conversations`: `DIRECT` (khóa duy nhất `direct_pair_key = min:max` chống tạo trùng 1–1) và `GROUP`.
  - `conversation_members`: phân quyền `OWNER`, `ADMIN`, `MEMBER`, trạng thái `ACTIVE` / `LEFT` / `REMOVED`, cờ `is_pinned`.
  - `messages`: khóa chống trùng `UNIQUE(sender_id, client_message_id)`, hỗ trợ `reply_to_id`, `forwarded_from_id`, `edited_at`, `deleted_at`.
  - `message_receipts`, `message_reactions`, `pinned_messages`.
- **Android (`SqliteChatLocalCache`):**
  - Lưu bảng `cached_conversations` và `cached_messages` (khóa chính `(conversation_id, client_message_id)`) qua `SQLiteOpenHelper`, giữ dữ liệu khi khởi động lại ứng dụng.

### 1.3. Vòng đời tin nhắn & Reconciliation (D33–D34)
1. Người dùng gửi tin → `ChatRoomViewModel` sinh `clientMessageId` (UUID v4).
2. Nếu WebSocket `CONNECTED` → gửi `message.send`, trạng thái cục bộ `SENDING`. Nếu mất mạng → trạng thái `FAILED` (có nút `retryMessage` hoặc `cancelFailedMessage`).
3. Khi WebSocket kết nối lại (`OkHttpChatWebSocketClient` dùng exponential backoff 1s → 30s) → `ChatRoomViewModel` tự động gửi lại các tin `FAILED`/`SENDING` với **cùng `clientMessageId`**.
4. Server nhận lại `clientMessageId` cũ → trả `message.ack` (`DUPLICATE`) kèm `messageId` ban đầu, không tạo bản ghi thứ 2.
5. `ChatRoomViewModel.mergeMessages` loại trùng theo `clientMessageId` và `serverId`, đồng thời sắp xếp tin đã xác nhận theo `serverId` tăng dần để chống lệch thứ tự khi phân trang đan xen realtime.

---

## 2. Bộ test nghiệm thu CHAT (D31–D33)

| Mã test | Hạng mục (§4) | Môi trường kiểm chứng | Kết quả |
|---|---|---|---|
| TC-CHAT-01 | Chat 1–1 idempotent & phân quyền 2 người (`POST /conversations/direct`) | Ktor `testApplication` (`ApplicationTest`) | **PASS** |
| TC-CHAT-02 | Gửi/nhận WS đa client & broadcast đúng thành viên | Ktor `testApplication` (`ApplicationTest`) | **PASS** |
| TC-CHAT-03 | Chống trùng `clientMessageId` qua 2 phiên WS (reconnect) | Ktor `testApplication` (`ChatRegressionD31D35Test`) | **PASS** |
| TC-CHAT-04 | Phân trang cursor `GET /conversations/{id}/messages?cursor=...` | Ktor `testApplication` + Android `ChatViewModelsTest` | **PASS** |
| TC-CHAT-05 | Tạo nhóm `>= 3` thành viên & phân quyền `OWNER`/`ADMIN`/`MEMBER` | Ktor `testApplication` + `CreateGroupViewModelTest` | **PASS** |
| TC-CHAT-06 | Thành viên bị xóa mất quyền REST & WS ngay lập tức | Ktor `testApplication` (`ChatRegressionD31D35Test`) | **PASS** |
| TC-CHAT-07 | Receipt một chiều `READ` không lùi về `DELIVERED` | Ktor `testApplication` + Android `ChatViewModelsTest` | **PASS** |
| TC-CHAT-08 | Typing debounce/expiry, Reply/Jump, Edit, Recall, Forward, Copy, Reaction | Ktor `testApplication` + Android `ChatViewModelsTest` | **PASS** |
| TC-CHAT-09 | Tìm kiếm tin nhắn (loại tin recall) & ghim hội thoại/tin nhắn | Ktor `testApplication` + Android `ChatViewModelsTest` | **PASS** |
| TC-CHAT-10 | Chặn truy cập không có JWT (`401 Unauthorized`) | Ktor `testApplication` (`ChatRegressionD31D35Test`) | **PASS** |
| TC-CHAT-11 | Lưu draft, vị trí cuộn (`firstVisibleMessageIndex`), cancel/retry khi xoay | Android JVM `ChatViewModelsTest` | **PASS** |
| TC-CHAT-12 | Kiểm thử trực quan trên 2 thiết bị thật & PostgreSQL live | Chưa chạy trong phiên JVM/Ktor | **VISUAL_UNVERIFIED / NOT RUN** |

---

## 3. Đối chiếu danh mục tính năng của Xuân Hoàng (D35)

| Tính năng | Mức ưu tiên | Trạng thái code & unit test | Ghi chú kiểm chứng |
|---|---|---|---|
| Chat 1–1 realtime | P0 | Hoàn tất logic & unit/Ktor test | Chờ test 2 thiết bị thật |
| Chat nhóm: tạo nhóm, nhắn tin nhóm | P0 | Hoàn tất logic & unit/Ktor test | Chờ test 3 thiết bị thật |
| Xoay màn hình không mất trạng thái | P0 | Hoàn tất state (`draft`, `scroll`) | Chờ smoke test trên device |
| Quản lý thành viên nhóm | P1 | Hoàn tất API & `GroupMembersViewModel` | Đã test phân quyền |
| Trả lời tin nhắn (Reply) | P1 | Hoàn tất `replyToId` & jump index | Đã test JVM/Ktor |
| Chuyển tiếp tin nhắn (Forward) | P1 | Hoàn tất API & `forwardedFromId` | Đã test phân quyền 2 đầu |
| Chỉnh sửa, xóa/thu hồi, sao chép | P1 | Hoàn tất `edit`, `recall`, `getCopyableText` | Đã test ownership |
| Hiển thị online/offline, lần cuối | P1 | Hoàn tất `presence` theo phiên đầu/cuối | Đã test JVM/Ktor |
| Hiển thị đang nhập (Typing) | P1 | Hoàn tất debounce 300ms & auto-expiry | Đã test JVM/Ktor |
| Trạng thái sent/delivered/read | P1 | Hoàn tất một chiều `ACCEPTED→DELIVERED→READ` | Đã test không lùi trạng thái |
| Tìm kiếm lịch sử tin nhắn | P1 | Hoàn tất `GET /messages/search` phân trang | Đã test loại tin recall |
| Ghim tin nhắn / cuộc trò chuyện | P1 | Hoàn tất pin hội thoại & pinned-messages | Đồng bộ cache + server |
| Thả cảm xúc emoji | P1 | Hoàn tất toggle react/unreact + WS | Đã test đếm & đồng bộ |
