# KẾ HOẠCH TRIỂN KHAI ĐỒ ÁN ANDROID CHAT — 40 NGÀY

> **Phạm vi đã chốt:** 37 chức năng · **30 ngày phát triển** + **10 ngày kiểm thử, hoàn thiện báo cáo, chuẩn bị demo và nộp bài**  
> **Ứng dụng:** Android viết bằng **Kotlin + Jetpack Compose**  
> **Nhóm:** 03 thành viên · Quản lý qua **GitHub Commit / Pull Request / Code Review**  
> **Mốc thời gian:** D01–D40 tính từ ngày nhóm chính thức bắt đầu (không tự gán ngày tháng dương lịch).  
> **Trạng thái tài liệu:** Kế hoạch triển khai; chưa phải xác nhận rằng các chức năng đã được xây dựng hoặc kiểm thử thành công.

## 0. Thông tin nhóm và nguyên tắc thực hiện

| Thành viên | Vai trò chính | Sở hữu cả Android + backend của module | Trách nhiệm bàn giao |
|---|---|---|---|
| **Đinh Công Huy** | **AUTH** | Xác thực, tài khoản, hồ sơ, tìm người dùng, danh bạ, quyền riêng tư, cài đặt, quản lý phiên, nghiên cứu E2EE | API/auth, màn hình tài khoản, kiểm thử xác thực, tài liệu bảo mật |
| **Nguyễn Quang Huy** | **MEDIA** | Ảnh/video/file, voice message, sticker, vị trí, push notification, gọi thoại/video, tùy biến media | API/media, upload/download, màn hình media và call, kiểm thử thiết bị |
| **Xuân Hoàng** | **CHAT** | Chat realtime, chat nhóm, các thao tác với tin nhắn, trạng thái online/typing/read, lịch sử, ghim, thu hồi, xử lý trạng thái giao diện | API/chat, WebSocket, database hội thoại, kiểm thử đồng bộ và luồng chat |

**Phân công theo độ khó chứ không chia đều số lượng tính năng.** AUTH gánh OTP, quản lý phiên và E2EE; MEDIA gánh upload, voice và WebRTC; CHAT gánh realtime, nhất quán dữ liệu và nghiệp vụ nhóm. Ba người cùng hỗ trợ tích hợp, review, viết test và báo cáo.

### Phạm vi và giới hạn

- **Mục tiêu phát triển:** xử lý cả **37 chức năng** đã chọn trong D01–D30 theo mức ưu tiên dưới đây.
- **P0 — bắt buộc để demo:** tài khoản, chat 1–1, chat nhóm, tìm/thêm bằng số điện thoại, quản lý hồ sơ, đa ngôn ngữ, gửi media/file, xoay màn hình không mất trạng thái, kiểm thử và tối thiểu một chức năng sáng tạo. Gửi voice là điểm nhấn sản phẩm.
- **P1 — mở rộng quan trọng:** OTP và email login, danh bạ, thao tác tin nhắn, sticker, vị trí, presence, typing, read/delivery, push, chặn, tìm lịch sử, gọi 1–1, ghim, thu hồi, dark mode, hình nền, reactions, nhiều ảnh.
- **P2 — rủi ro cao:** **gọi nhóm/hội nghị video**, **mã hóa đầu cuối (E2EE)** và **đa tài khoản**. Có slot phát triển và tiêu chí kiểm chứng rõ ràng; nếu chưa làm xong thì báo cáo trung thực là chưa hoàn thành, tuyệt đối không gắn nhãn “đã hỗ trợ”.
- **D31–D40 chỉ sửa lỗi và hoàn thiện đầu ra.** Không mở thêm tính năng mới, trừ khi nhóm đồng thuận và không ảnh hưởng bài nộp.

### Công nghệ đề xuất (chốt chung ở D02)

| Lớp | Phương án ưu tiên | Ghi chú |
|---|---|---|
| Android UI | Kotlin + Jetpack Compose + Material 3 | Giao diện tham khảo thiết kế Stitch; không sao chép nguyên repo khác |
| Android architecture | MVVM + Repository + Coroutines/StateFlow + Navigation Compose | Ổn định state khi xoay; dùng `SavedStateHandle` khi cần |
| Android data | Room + DataStore | Room cache/hàng đợi local, DataStore settings/phiên không nhạy cảm |
| Backend | Kotlin + Ktor | Có **mã nguồn backend** để nộp cùng Android |
| Database | PostgreSQL | Migration và schema do nhóm tự thiết kế |
| Realtime | WebSocket + JWT | Có ACK, server timestamp, client message ID, reconnect |
| File/media | Object storage hoặc backend upload | Giới hạn MIME/dung lượng, kiểm tra quyền truy cập |
| OTP | Firebase Phone Auth **hoặc** SMS provider phù hợp | Chốt sau spike; test numbers chỉ dùng môi trường thử nghiệm |
| Push | Firebase Cloud Messaging (FCM) | Backend gửi push theo token thiết bị; tránh tiết lộ dữ liệu nhạy cảm |
| Calls | WebRTC + signaling qua backend | STUN/TURN cho gọi 1–1; gọi nhóm cần đánh giá SFU/SDK riêng |
| Tests | JUnit, kotlinx-coroutines-test, Compose UI test; Ktor test | Kiểm thử logic, API, dữ liệu, UI và 2 thiết bị |

**Không tự viết thuật toán mã hóa.** Nếu thử E2EE, ưu tiên protocol/thư viện đã được kiểm chứng, nghiên cứu key management, xác thực khóa và hệ quả đối với tìm kiếm/đa thiết bị. Đây không phải tác vụ “thêm AES là xong”.

---

## 1. Ma trận đầy đủ 37 chức năng — ai làm, khi nào, nghiệm thu ra sao

**Ký hiệu:** P0 = bắt buộc; P1 = quan trọng; P2 = rủi ro cao. Cột thời gian là mục tiêu hoàn thành trong **30 ngày code**, không phải lịch sử hoàn thành thực tế.

| Chức năng cụ thể | Chủ trì | Ưu tiên | Dự kiến | Kiểm tra hoàn thành (Definition of Done) |
|---|---|---|---|---|
| Chat 1–1 realtime | Xuân Hoàng | P0 | D05–D10 | Hai máy/tài khoản gửi–nhận ngay, không cần refresh; không tạo tin nhắn trùng sau reconnect |
| Chat nhóm: tạo nhóm, nhắn tin nhóm | Xuân Hoàng | P0 | D11–D16 | Tạo nhóm ≥3 người, tất cả nhận được tin; quyền nhóm được kiểm soát |
| Tìm kiếm người dùng | Đinh Công Huy | P0 | D07–D11 | Tìm theo tên/tên định danh; kết quả phân trang; không lộ dữ liệu riêng tư |
| Tìm và thêm bạn bằng số điện thoại | Đinh Công Huy | P0 | D10–D14 | Chuẩn hóa số; tìm đúng người được phép hiển thị; gửi và chấp nhận lời mời |
| Quản lý tài khoản và hồ sơ | Đinh Công Huy | P0 | D04–D09 | Xem hồ sơ, phiên đăng nhập, đăng xuất; cập nhật dữ liệu trên backend |
| Hỗ trợ đa ngôn ngữ | Đinh Công Huy | P0 | D22–D24 | Tiếng Việt và tiếng Anh; chuyển ngôn ngữ không vỡ layout |
| Gửi ảnh, video, âm thanh và file | Nguyễn Quang Huy | P0 | D09–D17 | Upload, download, mở file; kiểm tra MIME/size/error; quyền truy cập theo chat |
| Xoay màn hình không mất trạng thái | Xuân Hoàng chủ trì; cả nhóm áp dụng | P0 | D18–D27 | Xoay khi nhập/gửi/đang xem chat, không mất draft, không gửi lặp; màn hình khác được kiểm tra |
| Chức năng sáng tạo: gọi thoại/video, gửi voice | Nguyễn Quang Huy | P0 | D14–D28 | Gửi voice hoạt động thực tế; gọi 1–1 là mục tiêu mở rộng có demo khi hoàn thành |
| Đăng ký, đăng nhập bằng OTP | Đinh Công Huy | P1 | D05–D10 | Gửi/xác thực OTP qua provider; rate limit; không bypass xác thực trong production |
| Đăng nhập bằng email/mật khẩu | Đinh Công Huy | P1 | D04–D07 | Đăng ký, đăng nhập, logout, thông báo lỗi; hash mật khẩu phía server |
| Đổi ảnh đại diện, tên, tiểu sử | Đinh Công Huy + Nguyễn Quang Huy (upload) | P1 | D08–D13 | Ảnh đại diện thay đổi trên cả thiết bị; lưu tên/bio trên backend |
| Đồng bộ danh bạ điện thoại | Đinh Công Huy | P1 | D14–D18 | Xin quyền rõ ràng, chuẩn hóa số, chỉ dùng danh bạ khi đồng ý, không công khai toàn bộ danh bạ |
| Quản lý thành viên nhóm | Xuân Hoàng | P1 | D15–D19 | Thêm/xóa/đổi vai trò theo quyền; cập nhật danh sách ở các thiết bị |
| Trả lời tin nhắn (Reply) | Xuân Hoàng | P1 | D17–D20 | Reply giữ tham chiếu tin gốc; nhấn để tới tin gốc khi tồn tại |
| Chuyển tiếp tin nhắn (Forward) | Xuân Hoàng | P1 | D18–D21 | Chọn cuộc trò chuyện đích, có nhãn chuyển tiếp, phân quyền media đúng |
| Chỉnh sửa, xóa, sao chép tin nhắn | Xuân Hoàng | P1 | D19–D22 | Chỉ người đủ quyền sửa/xóa; đánh dấu edited; copy đúng nội dung |
| Gửi tin nhắn thoại (Voice message) | Nguyễn Quang Huy | P0 | D12–D18 | Ghi âm, xem trước, gửi, nghe, tạm dừng, hiển thị thời lượng; xử lý quyền microphone |
| Gửi sticker | Nguyễn Quang Huy | P1 | D19–D21 | Chọn sticker và hiển thị nhất quán ở máy nhận |
| Chia sẻ vị trí | Nguyễn Quang Huy | P1 | D20–D23 | Xin quyền khi sử dụng, gửi tọa độ theo lựa chọn, mở được bản đồ |
| Hiển thị online/offline, lần cuối truy cập | Xuân Hoàng | P1 | D13–D18 | Presence chính xác ở mức hợp lý, không hiển thị online vĩnh viễn khi rớt mạng |
| Hiển thị đang nhập tin nhắn (Typing) | Xuân Hoàng | P1 | D16–D19 | Event có debounce và tự hết hạn, chỉ hiện đúng chat |
| Trạng thái đã gửi, đã nhận, đã đọc | Xuân Hoàng | P1 | D14–D20 | Phân biệt accepted/delivered/read; chuyển trạng thái một chiều và đồng bộ lại |
| Thông báo đẩy khi có tin nhắn mới | Nguyễn Quang Huy | P1 | D18–D23 | Nhận FCM khi app nền; nhấn vào đúng chat; tránh trùng thông báo khi đang mở chat |
| Chặn người dùng | Đinh Công Huy | P1 | D18–D21 | Chặn/bỏ chặn; backend từ chối luồng bị cấm; UI phản ánh đúng |
| Tìm kiếm lịch sử tin nhắn | Xuân Hoàng | P1 | D21–D24 | Tìm theo từ khóa trong phạm vi được phép; kết quả đưa tới đúng tin nhắn |
| Gọi video 1–1 | Nguyễn Quang Huy | P1 | D22–D28 | Hai máy thấy/hội thoại video ổn định trong mạng thử nghiệm; bật/tắt camera/mic |
| Gọi thoại 1–1 | Nguyễn Quang Huy | P1 | D21–D26 | Kết nối, từ chối, kết thúc, bật/tắt mic, xử lý timeout/reconnect tối thiểu |
| Gọi nhóm / hội nghị video | Nguyễn Quang Huy, hỗ trợ signaling từ Xuân Hoàng | P2 | D25–D30 | Nếu hoàn thành: ≥3 người tham gia, join/leave, media ổn định; nếu không: báo cáo prototype và thiếu sót |
| Mã hóa đầu cuối (E2EE) | Đinh Công Huy, phối hợp CHAT/MEDIA | P2 | D23–D30 | Chỉ đánh dấu xong khi server không đọc được nội dung, thiết bị giải mã đúng và quản lý khóa được kiểm thử |
| Hỗ trợ nhiều tài khoản | Đinh Công Huy | P2 | D24–D29 | Đổi tài khoản, token/cache/notification riêng; không lẫn tin nhắn giữa các tài khoản |
| Ghim tin nhắn / cuộc trò chuyện | Xuân Hoàng | P1 | D22–D25 | Ghim/bỏ ghim, thứ tự UI hợp lý, dữ liệu còn sau restart |
| Thu hồi tin nhắn | Xuân Hoàng | P1 | D23–D26 | Thu hồi đồng bộ trên hai máy; không còn nội dung cũ ở UI sau sync |
| Chế độ tối (Dark Mode) | Đinh Công Huy | P1 | D20–D23 | Theo hệ thống và tùy chọn; màn hình chính, biểu mẫu, dialog đều đọc được |
| Tùy chỉnh hình nền chat | Nguyễn Quang Huy | P1 | D23–D25 | Chọn nền/khôi phục mặc định, lưu setting, chữ tin nhắn dễ đọc |
| Thả cảm xúc emoji vào tin nhắn | Xuân Hoàng | P1 | D20–D23 | React/unreact, bộ đếm và người react đồng bộ realtime |
| Gửi nhiều ảnh cùng lúc | Nguyễn Quang Huy | P1 | D17–D20 | Chọn nhiều ảnh, hiển thị progress/lỗi từng ảnh, có quyền xem theo chat |

**Lưu ý về mức hoàn thành:** P2 không được thay bằng một UI mô phỏng rồi báo cáo là đã làm. Nếu còn dở, phải ghi rõ trạng thái thực nghiệm / không hoàn thành.

---

## 2. Hợp đồng tích hợp cần thống nhất ngay từ ngày đầu

### 2.1 Mô hình dữ liệu chính (thống nhất D02–D04)

- `User`: id, username/displayName, normalizedPhone (được bảo vệ), avatarUrl, bio, privacySettings.
- `Session`: userId, deviceId, refresh-token metadata, thời hạn, trạng thái thu hồi.
- `FriendRequest` / `ContactRelation`: requesterId, targetId, trạng thái yêu cầu, thời điểm.
- `Conversation`: id, type (`DIRECT`/`GROUP`), name/avatar, createdBy, metadata.
- `ConversationMember`: conversationId, userId, role, join/leave status.
- `Message`: id, `clientMessageId`, conversationId, senderId, type, content/mediaRef, replyToId, createdAt, editedAt, deletedAt.
- `MessageReceipt`: messageId, recipientId, deliveredAt, readAt.
- `Reaction`: messageId, userId, emoji.
- `MediaObject`: id, ownerId, conversationId, MIME type, byteSize, storageKey, uploadState.
- `CallSession`: id, conversation/participants, status, startedAt, endedAt; signaling dùng sự kiện có phân quyền.
- `DeviceToken` / `NotificationPreference`: userId, deviceId, push token, settings.

### 2.2 API/Realtime contract — ví dụ endpoint để nhóm thống nhất, **không phải API có sẵn**

| Nhóm API | Owner | Hợp đồng tối thiểu cần định nghĩa |
|---|---|---|
| Auth | Đinh Công Huy | `/auth/register`, `/auth/login`, `/auth/otp/request`, `/auth/otp/verify`, `/auth/refresh`, `/auth/logout` |
| Profile / Contact | Đinh Công Huy | `/users/me`, `/users/search`, `/users/by-phone`, `/friend-requests`, `/users/{id}/block` |
| Conversation / Chat | Xuân Hoàng | `/conversations`, `/conversations/{id}/members`, `/messages?cursor=...`, `/messages/search` |
| Realtime | Xuân Hoàng | `message.send`, `message.ack`, `message.created`, `typing`, `presence`, `receipt`, `message.updated`, `message.recalled` |
| Media | Nguyễn Quang Huy | `/media/upload`, `/media/{id}`, `/media/{id}/download` hoặc pre-signed URLs có kiểm soát |
| Push / Call | Nguyễn Quang Huy | `/devices/push-token`, `call.invite`, `call.answer`, `call.ice-candidate`, `call.end` |

**Quy tắc bắt buộc:** mọi request có dữ liệu riêng đều xác thực token và kiểm tra quyền trên server. Chỉ dùng ID nguyên, timestamp server, phân trang và mã lỗi thống nhất; không đổi schema/API tùy tiện mà không báo hai thành viên còn lại.

### 2.3 Quy ước code để giảm conflict

```text
android-app/
  app/src/main/java/.../
    core/             # theme, navigation, networking, common UI
    feature/auth/     # Đinh Công Huy
    feature/contacts/ # Đinh Công Huy
    feature/chat/     # Xuân Hoàng
    feature/groups/   # Xuân Hoàng
    feature/media/    # Nguyễn Quang Huy
    feature/calls/    # Nguyễn Quang Huy
    feature/settings/ # Đinh Công Huy (theme chung phải review)
backend/
  src/main/kotlin/.../
    auth/             # Đinh Công Huy
    users/            # Đinh Công Huy
    chat/             # Xuân Hoàng
    groups/           # Xuân Hoàng
    media/            # Nguyễn Quang Huy
    calls/            # Nguyễn Quang Huy
    common/           # sửa có thông báo/review
  migrations/         # migration đặt số thứ tự, không sửa migration đã merge
  tests/
docs/
  api-contract.md
  architecture.md
  test-cases.md
  demo-script.md
  contribution-log.md
README.md
.gitignore
```

Thư mục là **gợi ý kiến trúc**, không phải cấu trúc có sẵn trong bốn repo tham khảo. Ba người thống nhất một `main` duy nhất; tránh vừa dùng WebSocket Tinode vừa dùng Firebase Chat SDK như hai backend song song.

---

## 3. LỊCH PHÁT TRIỂN CHI TIẾT — D01 ĐẾN D30

### Sprint A — D01–D05: Môi trường, kiến trúc, tài khoản và realtime skeleton

**Mục tiêu cuối D05:** build được APK, backend chạy, đăng ký/đăng nhập cơ bản và kết nối WebSocket bằng token. Có ba branch/PR cá nhân để bắt đầu lưu lịch sử.

| Ngày | Đinh Công Huy — AUTH | Nguyễn Quang Huy — MEDIA | Xuân Hoàng — CHAT | Bằng chứng cần có |
|---|---|---|---|---|
| **D01** | Dựng feature `auth`, xác định login/OTP/profile/screens; viết danh sách trường User/Session. | Dựng `media` + `calls`; rà màn hình Stitch cho upload, voice và call; lập bảng quyền Android. | Tạo repo, `main`, Android Compose/Ktor skeleton; cấu hình PostgreSQL, WebSocket skeleton; GitHub issues/projects. | `README` môi trường, project build, issue có owner, GitHub 3 tài khoản |
| **D02** | Thiết kế request/response Auth, refresh/logout, privacy; chọn provider OTP và phương án test. | Thiết kế MediaObject, API upload, quota/MIME, sơ đồ WebRTC signaling, chọn cách lưu file. | Chủ trì họp API contract, Message/Conversation/receipt, chuẩn event WebSocket; tạo migration khởi đầu. | `docs/api-contract.md`, sơ đồ dữ liệu, quyết định stack được cả 3 xác nhận |
| **D03** | Làm màn hình login/register Compose + ViewModel trạng thái lỗi/loading; backend signup schema. | File picker/permission abstraction, cấu trúc MediaRepository; dựng Compose media preview mẫu. | WebSocket handshake/auth placeholder, connection state; UI chat list/chat screen từ Stitch (chưa giả là chạy). | Commit cá nhân đầu tiên; video build/chụp màn hình; không commit secret |
| **D04** | Hash/password validation, đăng ký email và đăng nhập email bằng API, secure token storage. | Backend upload endpoint skeleton, kiểm tra MIME/size, media model; test file hợp lệ/lỗi. | Implement Conversation/Message tables; WebSocket JWT auth, message event parsing và logging không lộ token. | API auth gọi được; upload endpoint unit test; chat socket kết nối được |
| **D05** | Hoàn thiện email login/logout, test token hết hạn và lỗi mật khẩu; PR `auth-foundation`. | Gửi thử file từ client lên backend có progress; PR `media-foundation`. | WebSocket connect, nhận ACK thử và danh sách chat khung; PR `chat-foundation`. | **Gate A:** ba PR review chéo, `main` build + backend khởi chạy |

### Sprint B — D06–D10: Chat 1–1 + xác thực + file cơ bản

**Mục tiêu cuối D10:** hai máy chat 1–1 realtime, đăng nhập thực tế, lưu tin nhắn sau khởi động lại; gửi một ảnh/file mẫu.

| Ngày | AUTH — Đinh Công Huy | MEDIA — Nguyễn Quang Huy | CHAT — Xuân Hoàng | Bằng chứng cần có |
|---|---|---|---|---|
| **D06** | Hoàn thiện email/password flow, refresh token, validate đầu vào và màn hình quên mật khẩu nếu có provider. | Media picker nhiều loại; chuẩn bị thumbnail ảnh, preview và upload progress. | API tạo/lấy cuộc trò chuyện 1–1; phân quyền chỉ 2 người được truy cập. | Test API và screenshot screen; commit theo từng phần hoàn chỉnh |
| **D07** | Search user bằng tên/username, phân trang và trạng thái không có kết quả. | Media upload theo chat; tạo MediaObject và trả id/url có kiểm soát. | Gửi tin nhắn tới server: `clientMessageId`, ACK và timestamp server. | `message.send` phản hồi đúng, server lưu DB |
| **D08** | Chuẩn hóa hồ sơ, upload avatar phối hợp MEDIA; API xem/sửa tên, bio, ảnh. | Android gửi ảnh trong chat, thumbnail, retry khi upload lỗi. | Nhận tin nhắn trên máy thứ hai qua WebSocket; hiển thị đúng sender/time. | **Demo 2 máy** gửi/nhận text + ảnh, dù UI còn đơn giản |
| **D09** | Xây OTP request/verify theo provider đã chốt; chống spam/rate limit và test OTP. | Gửi video/audio/file dưới dạng đính kèm; thử tải xuống và mở hợp lệ. | Lấy lịch sử phân trang và lưu Room cache tối thiểu; xử lý tải ban đầu. | Test OTP và lịch sử sau restart, media đúng MIME |
| **D10** | Hoàn tất login OTP + quản lý profile; PR `auth-login-otp-profile`. | PR `media-upload-download` + lỗi upload/download. | ACK/reconnect/idempotency, PR `private-chat-realtime`. | **Gate B:** demo 1–1 giữa 2 máy, logout/login vẫn thấy lịch sử, `main` xanh |

### Sprint C — D11–D15: Thêm bạn bằng số, nhóm chat, ghi âm voice

**Mục tiêu cuối D15:** người dùng tìm và kết bạn bằng số điện thoại, tạo nhóm và gửi tin nhắn nhóm, ghi âm/gửi voice bản đầu.

| Ngày | AUTH — Đinh Công Huy | MEDIA — Nguyễn Quang Huy | CHAT — Xuân Hoàng | Bằng chứng cần có |
|---|---|---|---|---|
| **D11** | API tìm theo số điện thoại được chuẩn hóa; cài đặt privacy discovery; màn hình nhập số/tìm người. | Ghi âm dùng MediaRecorder/recording API, xử lý quyền micro và lifecycle. | Conversation type `GROUP`, schema member/role; API tạo nhóm và invite thành viên. | API search phone; unit test normalize phone; voice recorder lưu file test |
| **D12** | Gửi/nhận lời mời kết bạn, chấp nhận/từ chối; đồng bộ danh sách bạn. | Voice recording UI: nhấn giữ/ghi âm/timer/hủy, xem trước file âm thanh. | UI tạo nhóm chọn người, tên/avatar nhóm; event tạo nhóm. | Tạo nhóm 3 người; ghi âm và preview hoạt động |
| **D13** | Chỉnh sửa tên, avatar, bio và kiểm thử đồng bộ nhiều thiết bị; PR profile/friend core. | Upload voice/audio, message type `VOICE`, hiển thị duration và trạng thái gửi. | Broadcast chat nhóm; hiển thị người gửi và thành viên; presence baseline (online/last seen). | Group chat 3 tài khoản, voice gửi được bản đầu |
| **D14** | Hoàn thiện tìm và thêm bạn bằng số, xử lý user không tồn tại/quyền riêng tư; test chặn truy vấn không phép. | Phát voice: play/pause/progress/thời lượng; xử lý tai nghe/gián đoạn âm thanh. | Receipt server accepted/delivered; thực hiện permission nhóm cơ bản. | Demo end-to-end: search phone → add friend → group → voice |
| **D15** | Xin quyền đọc danh bạ theo ngữ cảnh, mapping số E.164, đồng bộ có consent. | PR `voice-message-v1`, phát trên thiết bị thứ hai; hoàn thiện lỗi mic. | Nhóm: thêm/xóa thành viên, admin/owner; PR `group-chat-v1`. | **Gate C:** bạn bè + nhóm + voice được merge, kiểm thử 3 tài khoản |

### Sprint D — D16–D20: Nghiệp vụ tin nhắn, media nâng cao, cài đặt

**Mục tiêu cuối D20:** đa số chức năng cốt lõi chạy ổn; chat không hỏng khi rớt mạng/xoay; voice và nhiều ảnh dùng được.

| Ngày | AUTH — Đinh Công Huy | MEDIA — Nguyễn Quang Huy | CHAT — Xuân Hoàng | Bằng chứng cần có |
|---|---|---|---|---|
| **D16** | Đồng bộ danh bạ: chỉ gửi dữ liệu tối thiểu; mapping liên hệ; xử lý từ chối permission. | Hoàn thiện voice waveform hoặc progress nhẹ; playback speed tùy thời gian. | Typing indicator debounce/expiry, sự kiện vào đúng hội thoại. | Test bật/tắt quyền danh bạ, typing trên hai máy |
| **D17** | Danh bạ và lời mời kết bạn: empty/loading/error, test số trùng. | Gửi nhiều ảnh một lần, batch upload, trạng thái tiến độ từng ảnh. | Reply tin nhắn (replyToId), jump-to-message, nhóm hoạt động ổn định. | Batch 3–5 ảnh với retry, reply sau restart |
| **D18** | Chặn người dùng: API block/unblock, enforce từ backend; viết các trường hợp test. | Push token, FCM setup, đăng ký/unregister thiết bị, nhận push khi app background. | Read receipt, trạng thái sent/delivered/read; test reconnect và draft/xoay. | Block user không gửi được tin; read marker đúng, app xoay không mất draft |
| **D19** | Privacy settings và UI danh bạ, tạo PR `contacts-block-privacy`. | Sticker picker, render sticker ở máy nhận; tiếp tục FCM deep link. | Forward tin nhắn sang chat khác; Edit/Delete với quyền sở hữu; test sửa/xóa trong nhóm. | Test forward, chỉnh sửa, xóa, sticker, push trên 2 thiết bị |
| **D20** | Dark Mode hệ thống/tùy chỉnh; theme cho auth/contacts/settings. | Sticker + multi-image + notification PR; test voice ghi/phát khi xoay. | Copy tin nhắn, reaction emoji, trạng thái UI khi xoay; PR message-actions. | **Gate D:** chat, nhóm, kết bạn, media và voice demo được; lập bug list, tiếp tục hoàn thiện đa ngôn ngữ/xoay đến D27 |

### Sprint E — D21–D25: Calls, tìm kiếm, cài đặt nâng cao, bảo mật thử nghiệm

**Mục tiêu cuối D25:** voice/call 1–1 ở mức demo, lịch sử tìm kiếm, ghim/thu hồi, đa ngôn ngữ/dark mode; có kết luận tính khả thi E2EE và nhiều tài khoản.

| Ngày | AUTH — Đinh Công Huy | MEDIA — Nguyễn Quang Huy | CHAT — Xuân Hoàng | Bằng chứng cần có |
|---|---|---|---|---|
| **D21** | Hoàn thiện Block + settings đồng bộ; unit test phân quyền; kiểm tra theme toàn app. | Chia sẻ vị trí tĩnh (không live tracking), quyền location, mở map; WebRTC audio POC. | Tìm kiếm lịch sử tin nhắn trong chat, phân trang kết quả. | Test block, vị trí, search và bản thử audio call |
| **D22** | `strings.xml`/Compose resources cho VI/EN, rà toàn bộ màn hình chính và lỗi tràn chữ. | Gọi thoại 1–1: signaling invite/answer/end, micro mute, timeout và UI cuộc gọi. | Ghim cuộc trò chuyện/tin nhắn, đồng bộ sau khởi động lại. | Thử gọi audio giữa hai máy qua mạng; search/pin test |
| **D23** | Chốt ngôn ngữ + dark mode; nghiên cứu E2EE: chọn thư viện/protocol, mô hình key, thử encrypt/decrypt riêng, chưa tuyên bố tích hợp. | Gửi vị trí + màn hình nền chat, chọn nền mặc định/cá nhân; thử WebRTC video. | Thu hồi tin nhắn, synchronize delete tombstone giữa hai máy; reaction ổn định. | PR language/theme; thử mã hóa có unit test; video call POC |
| **D24** | Nhiều tài khoản: thiết kế isolated storage/token/cache và switcher UI; đánh giá effort với nhóm. | Gọi thoại ổn định, hỗ trợ incoming/outgoing/decline; push incoming call ở mức thí nghiệm. | Search và ghim/thu hồi hoàn thiện; test state rotation từng màn hình chat. | PR search-pin-recall; demo audio call 2 máy |
| **D25** | Spike E2EE + multi-account: quyết định **go/no-go** bằng checklist bảo mật, báo cáo lý do nếu chưa đủ. | Tích hợp video 1–1, màn hình call cơ bản; PR `calls-audio-v1`, wallpaper/location. | Rà consistency tin nhắn sau reconnect, duplicate sends, cập nhật API signaling cho MEDIA. | **Gate E:** demo gọi 1–1; quyết định P2, không đánh đổi bản ổn định |

### Sprint F — D26–D30: Hoàn thiện, tích hợp và khóa tính năng

**Mục tiêu cuối D30:** một bản APK tích hợp được, backend deploy/test được, có danh sách chức năng thực sự hoàn thành; không thêm mới sau D30.

| Ngày | AUTH — Đinh Công Huy | MEDIA — Nguyễn Quang Huy | CHAT — Xuân Hoàng | Bằng chứng cần có |
|---|---|---|---|---|
| **D26** | Nếu P2 khả thi: triển khai switching account isolation; nếu không: sửa Auth/contacts/security bugs P0/P1. | Gọi video 1–1: mute/camera flip/end call, permission handling; tập hợp clip demo. | Hoàn tất thu hồi/copy/reaction; fix đồng bộ chat nhóm/permission. | Test session riêng, video 1–1, nhóm 3 thiết bị |
| **D27** | Kiểm tra xoay màn hình toàn bộ auth/profile/settings; test logout/refresh/OTP giới hạn. | Video 1–1 kiểm thử trên hai mạng, đánh giá TURN; xử lý mất kết nối và audio focus. | Xoay màn hình/draft/scroll position; chống gửi lặp và out-of-order messages. | Smoke test UI rotation và call stability |
| **D28** | Nếu E2EE đạt điều kiện an toàn: tích hợp thử cho chat 1–1 **tách feature flag**, không phá chat chuẩn; nếu không: đóng spike và sửa bảo mật. | Gọi nhóm POC: đánh giá SFU/SDK, join/leave ≥3 người nếu khả thi; nếu không ghi rõ giới hạn. | Cùng MEDIA kiểm thử signaling; tối ưu pagination, reconnect và quyền member. | Bản thử nghiệm P2 được tách khỏi release; có log kiểm chứng |
| **D29** | Multi-account nếu đã qua gate thì test isolation 2 tài khoản; nếu chưa thì hoàn thiện test auth/profile. | PR hoàn thiện video call và media; nếu group call có POC thì PR riêng có nhãn experimental. | PR cleanup chat; chuẩn hóa lỗi, cancel/retry, cập nhật endpoint; review tích hợp. | PR cuối của các module, test và review chéo |
| **D30** | Đóng PR, rà quyền/credentials/log, lập tài liệu API Auth và checklist test. | Chốt media/calls, cleanup file tạm, kiểm tra dung lượng APK và crash. | Tích hợp `main`, build Android + backend, tag release candidate `v0.9.0-rc1`. | **Gate F: FEATURE FREEZE**, danh sách 37 mục: Done / Partial / Not done, backlog lỗi có mức độ |

### Điểm kiểm soát bắt buộc tại mỗi Gate

| Gate | Điều kiện thông qua | Nếu không đạt |
|---|---|---|
| **D05** | Project chạy, ba môi trường tương thích, API contract chốt | Ngừng thêm UI, sửa build/dependencies trước |
| **D10** | 1–1 realtime trên hai máy, auth login được, lịch sử còn sau restart | Ưu tiên sửa realtime/auth, trì hoãn nâng cao |
| **D15** | Thêm bạn qua số điện thoại, nhóm, voice hoạt động end-to-end | MEDIA/CHAT hỗ trợ tập trung cho P0 |
| **D20** | Chat 1–1, nhóm, thêm bạn bằng số, tài khoản, media và voice demo được; đa ngôn ngữ/xoay còn mốc hoàn thiện D24–D27 | Cắt scope P1 ít quan trọng để bảo toàn điểm |
| **D25** | Call 1–1 POC khả thi, bug severity cao được kiểm soát, quyết định P2 | Không triển khai E2EE/gọi nhóm vào release chính nếu chưa đạt |
| **D30** | `main` build/test đạt tiêu chí nhóm, APK có thể demo, feature freeze | D31–D40 chỉ fix, không phát triển tính năng mới |

---

## 4. LỊCH KIỂM THỬ, BÁO CÁO VÀ NỘP BÀI — D31 ĐẾN D40

**Quy tắc:** D31–D40 là buffer và giai đoạn chất lượng, **không** được mặc định phát triển tiếp những tính năng dang dở. Mọi bản sửa đều qua PR.

| Ngày | AUTH — Đinh Công Huy | MEDIA — Nguyễn Quang Huy | CHAT — Xuân Hoàng | Đầu ra cuối ngày |
|---|---|---|---|---|
| **D31** | Test auth: OTP, email/password, logout, refresh, phone search, block, multi-account nếu có. | Test upload/download các định dạng, quyền máy, voice, push, gọi. | Test chat 1–1/nhóm, receipt, reconnect, lịch sử và đồng bộ. | Bug report tổng hợp có ID, mức độ, người sửa |
| **D32** | Viết unit/integration test auth/profile/permission; fix lỗi blocker. | Viết test media validator, playback state, push handler; fix blocker. | Test repository/WebSocket/DB message/permissions, fix blocker. | CI chạy test; tỷ lệ pass thực tế, không tự khai đạt nếu còn đỏ |
| **D33** | Test dữ liệu nhạy cảm, API unauthorized, password/OTP throttling, session. | Test hai thiết bị Android khác nhau, nền/foreground, micro/camera permissions. | Test offline/online, gửi trùng, load more, reset kết nối, xoay màn hình. | Regression test lần 1; ảnh/video lỗi đã sửa |
| **D34** | Viết phần báo cáo: yêu cầu, use case auth, kiến trúc xác thực, bảo mật và danh bạ. | Viết phần báo cáo: upload/file/voice/WebRTC/push; flow và hạn chế. | Viết phần báo cáo: WebSocket, database, message lifecycle, nhóm, reconciliation. | Báo cáo Word bản nháp có đóng góp cả 3 người |
| **D35** | Hoàn thiện test auth; chỉnh hình minh họa và sơ đồ use case. | Kiểm tra media upload/download, voice/call trên network khác; chụp màn hình. | Chạy kiểm thử xuyên module, mở PR sửa regression; đối chiếu 37 tính năng. | **Release candidate 2** + test evidence; không thêm feature |
| **D36** | Rà phần giới thiệu, đặc tả yêu cầu, phân công và lịch sử GitHub. | Rà screenshot, chức năng multimedia/call và nội dung demo voice. | Tổng hợp kiến trúc tổng thể, ERD/API, diagram state chat, hướng dẫn chạy. | Báo cáo bản gần hoàn chỉnh, README installation chạy được |
| **D37** | Đóng góp slides: auth/danh bạ/đa ngôn ngữ/security; chuẩn bị trả lời vấn đáp. | Slides: media/voice/calls, demo ghi âm/gửi/nhận voice. | Slides: realtime/group, luồng gửi nhận, database/WebSocket. | Slides + kịch bản demo 8–12 phút (điều chỉnh theo yêu cầu lớp) |
| **D38** | Người kiểm thử luồng auth và tài khoản; chốt README những hạn chế. | Thử call/voice/file trên hai máy thật, lưu video fallback demo. | Điều phối chạy demo từ đầu đến cuối, bản build cuối, ký APK nếu cần. | **Dry-run demo 1**, checklist mọi chức năng làm thật |
| **D39** | Rà license, nguồn tham khảo, khai báo sử dụng AI; xác nhận commit cá nhân. | Rà dependency, không lộ key và không đóng gói file build/keystore. | Đóng issue/blocker, tạo tag final và chuẩn bị ZIP giữ nguyên `.git`. | File DOCX, APK, ảnh màn hình, backend source, `.git`, ZIP thử mở lại |
| **D40** | Kiểm tra phần báo cáo, chữ ký/tên/mã SV và phần trình bày của mình. | Cài APK từ bản nộp và demo lần cuối trên thiết bị. | Đối chiếu rubric, giải nén thử ZIP, kiểm tra khả năng build, nộp bài đúng hạn. | **FINAL:** ZIP hợp lệ + DOCX + APK + ảnh + toàn bộ source/backend và Git history |

### Bộ test nghiệm thu tối thiểu (thực hiện D31–D38)

1. **Xác thực:** sai mật khẩu, token hết hạn, OTP quá hạn/sai nhiều lần, logout, thay ảnh/tên/bio, chuyển ngôn ngữ.
2. **Tìm bạn:** tìm tên, số có/không có tài khoản, số có/không có mã quốc gia, block, xóa quyền danh bạ.
3. **Chat realtime:** hai máy nhắn liên tiếp, mạng yếu/mất kết nối, trùng `clientMessageId`, xóa/thu hồi, đọc/trạng thái.
4. **Chat nhóm:** ba người, thêm/xóa thành viên, user bị xóa không đọc/gửi được, typing/receipt trong nhóm.
5. **Media và voice:** ảnh/video/pdf, nhiều ảnh, file lớn/quá giới hạn, quyền mic, audio pause/resume, gửi lúc mạng yếu.
6. **Calls:** gọi 1–1, từ chối cuộc gọi, tắt mic/camera, gọi khi app background, mất mạng, quyền camera/mic bị từ chối.
7. **UI:** xoay khi đang soạn tin, thay theme dark/light, chuyển VI/EN, keyboard và navigation back, màn hình nhỏ.
8. **Bảo mật:** API không có token hoặc sai quyền, truy cập file của chat khác, log chứa secret, kiểm soát truy cập server.
9. **P2 nếu được tuyên bố hoàn thành:** E2EE phải chứng minh server không giải mã được; 2 tài khoản không lẫn session/data; gọi nhóm test thực tế từ 3 máy.

**Mỗi ca kiểm thử có:** ID, điều kiện, bước thực hiện, kết quả mong đợi, kết quả thực tế, ảnh/video/log, người test, bug ID nếu thất bại.

---

## 5. Quy trình GitHub: Commit 2–3 ngày/lần vẫn đúng

### 5.1 Nhánh và quyền

- `main`: bản tích hợp ổn định; **không push trực tiếp**, bật branch protection, yêu cầu tối thiểu 1 reviewer và build/test cần thiết.
- Branch tính năng nhỏ, không tạo một branch duy nhất cho cả 30 ngày:
  - Đinh Công Huy: `feature/auth-email`, `feature/auth-otp`, `feature/contacts-phone`, `feature/settings-language`, `spike/e2ee`.
  - Nguyễn Quang Huy: `feature/media-upload`, `feature/voice-message`, `feature/push`, `feature/call-audio`, `feature/call-video`, `spike/group-call`.
  - Xuân Hoàng: `feature/chat-realtime`, `feature/group-chat`, `feature/message-actions`, `feature/read-receipts`, `feature/search-history`.
- Nguyên tắc review xoay vòng: PR AUTH → CHAT hoặc MEDIA review; PR MEDIA → AUTH hoặc CHAT review; PR CHAT → AUTH hoặc MEDIA review. Tác giả không tự phê duyệt PR của mình.
- PR phải có nội dung, tiêu chí kiểm thử, screenshot/video nếu sửa UI. **Không tạo commit giả, không ép số dòng additions/deletions.**

### 5.2 Nhịp làm việc đề xuất

| Thời điểm | Việc cần làm | Bắt buộc hay gợi ý? |
|---|---|---|
| Mỗi buổi làm việc | Save file, chạy/đọc log, cập nhật issue nếu có blocker | Bắt buộc theo thực tế |
| Sau 1–3 ngày hoặc một thay đổi logic hoàn chỉnh | **Git commit local** trên feature branch, message có ý nghĩa | **Gợi ý**, không phải rubric quy định ngày |
| Sau commit quan trọng | `git push` branch lên GitHub, để tránh chỉ lưu ở máy | Gợi ý mạnh |
| Mỗi 3–5 ngày hoặc xong một phần review được | Tạo **Pull Request** nhỏ, có tests hoặc steps manual | Gợi ý |
| Khi PR đạt | Người khác review, xử lý comment, kiểm tra CI, merge `main` | Bắt buộc quy trình nhóm |
| D05/D10/D15/D20/D25/D30 | Demo tích hợp và chốt trạng thái issue | Mốc quản trị nhóm |

**Số commit/PR mục tiêu tham khảo:** khoảng **10–16 commit và 5–8 PR/người** trong 30 ngày phát triển; có thể nhiều/ít hơn tùy khối lượng. Chất lượng đóng góp quan trọng hơn số lượng commit.

### 5.3 Ví dụ thao tác của mỗi người

```bash
# Tạo branch mới từ main đã cập nhật
git switch main
git pull --ff-only origin main
git switch -c feature/voice-message

# Sau khi làm xong một phần có ý nghĩa
git status
git add android-app/app/src/main/java/...
git commit -m "feat(voice): record and preview audio messages"
git push -u origin feature/voice-message

# Khi đang ở feature branch và main đã thay đổi
git fetch origin
git merge origin/main
# Nếu có conflict: giải quyết trong IDE, test rồi mới commit/push
```

`git add` ở trên là ví dụ đường dẫn, phải đổi theo tên package và file thực tế. Không dùng `git add .` mà chưa kiểm tra `git status`; không commit `local.properties`, keystore, token, mật khẩu, file build.

### 5.4 Mẫu Pull Request (copy vào GitHub)

```md
## Mục tiêu
- Triển khai chức năng: [tên cụ thể]

## Các thay đổi
- Android UI/ViewModel/Repository: ...
- Backend endpoint/WebSocket/DB migration: ...

## Cách test
- [ ] Chạy Android build
- [ ] Chạy backend/test liên quan
- [ ] Test trên 2 tài khoản / 2 thiết bị (nếu có)
- [ ] Không làm hỏng luồng đã chạy

## Bằng chứng
- Screenshot/video/log không chứa dữ liệu nhạy cảm: ...

## Phụ thuộc / giới hạn
- ...
```

### 5.5 Cách xử lý conflict / review

- Tách file/module theo owner; không để cả ba sửa cùng `MainActivity`, navigation root, Gradle và schema trong một ngày mà không thông báo.
- API schema được duyệt qua PR; DB migration tạo file mới theo thứ tự, không sửa migration cũ đã merge.
- Merge `origin/main` vào branch trước khi gửi PR; dùng Android Studio merge tool để xử lý conflict và chạy lại test.
- PR chưa build hoặc CI đỏ **không merge**. Nếu gấp, nhóm quyết định bỏ tính năng/rút branch thay vì phá `main`.
- Tài liệu `docs/contribution-log.md` ghi issue/PR/commit link, đầu ra và người review; không dùng tài liệu này thay thế Git history thật.

---

## 6. Chứng cứ và bàn giao tương ứng rubric

Theo rubric đồ án cuối kỳ đã cung cấp, tổng 100 điểm gồm: **bài nộp 5; chức năng cơ bản 20; chức năng nâng cao 15; sáng tạo 5; giao diện + unit test 15; thuyết trình/demo/vấn đáp 40**. Đồ án nhóm chiếm 50% điểm học phần. **Mã nguồn Android và backend, báo cáo Word, ảnh chức năng và APK** là các thành phần bắt buộc.

### Checklist bài nộp

- [ ] Toàn bộ mã nguồn Android Kotlin + backend Kotlin/Ktor có thể build theo README.
- [ ] Thư mục `.git` còn nguyên lịch sử thật **bên trong file ZIP**.
- [ ] Không có `build/`, `node_modules/`, keystore hoặc `keystore.properties` trong ZIP (đúng yêu cầu rubric).
- [ ] Báo cáo dạng **Word `.docx`**, đúng cấu trúc do giảng viên yêu cầu.
- [ ] Ảnh chụp các chức năng đã hoàn thành và màn hình trọng yếu.
- [ ] File APK có thể cài và mở trên thiết bị demo.
- [ ] Test log/kết quả unit test, mô tả test case và điều kiện môi trường.
- [ ] README nêu cách chạy backend, cấu hình môi trường, cách build APK, các yêu cầu dịch vụ ngoài.
- [ ] GitHub có commit/PR/review thật của cả 3 người; mỗi người giải thích được phần code mình viết.
- [ ] Khai báo sử dụng AI và thư viện/repository tham khảo; kiểm tra giấy phép và tránh sao chép bài giữa các nhóm.
- [ ] Tên ZIP theo đúng quy tắc: `GroupXX_MSSV1_MSSV2_MSSV3.zip` (thay dữ liệu thật; không tự thêm MSSV chưa được cung cấp).
- [ ] Thử **giải nén** file cuối, xác nhận `.git` tồn tại và APK/DOCX/ảnh/source đầy đủ.

### Mức độ đóng góp cá nhân

Rubric dùng lịch sử **commit và Pull Request** để xem đóng góp, với hệ số cá nhân **0,9–1,1**; **không yêu cầu commit mỗi ngày**. Thành viên không có đóng góp thực chất có nguy cơ **0 điểm đồ án**. Mỗi người tự chịu trách nhiệm giải thích code mình merge; không nhờ người khác commit hộ để tạo lịch sử đẹp.

### Báo cáo nên có các chương

1. **Giới thiệu, yêu cầu và phạm vi:** rubric, mục tiêu, 37 chức năng dự định, phạm vi thực tế hoàn thành.
2. **Khảo sát và lựa chọn giải pháp:** Kotlin/Compose, backend, tham khảo 4 repository ở mức nghiên cứu, lý do không ghép code trực tiếp.
3. **Phân tích và thiết kế:** use case, UI flow từ Stitch, kiến trúc MVVM, ERD, API, WebSocket event, bảo mật.
4. **Hiện thực:** Auth/Contacts, Chat/Groups, Media/Voice/Calls — ghi rõ đóng góp từng thành viên.
5. **Kiểm thử và kết quả:** unit test/API/UI, test hai thiết bị, ảnh, lỗi gặp phải và cách sửa.
6. **Kết luận:** chức năng hoàn thành, chưa hoàn thành, hạn chế, hướng phát triển (E2EE/gọi nhóm nếu chưa xong).
7. **Phụ lục:** môi trường build, link commit/PR, hướng dẫn demo, khai báo AI/nguồn tham khảo.

---

## 7. Kịch bản demo tích hợp — dùng cho D38–D40

| Bước demo | Người thao tác | Điều cần chứng minh |
|---|---|---|
| 1. Tạo/đăng nhập tài khoản, thay ảnh/tên | Đinh Công Huy | Auth, profile, trạng thái dữ liệu thật |
| 2. Tìm bằng số điện thoại, gửi/chấp nhận kết bạn | Đinh Công Huy | Đúng tiêu chí thêm người bằng điện thoại |
| 3. Chat 1–1 giữa hai máy, reply, reaction, read receipt | Xuân Hoàng | Realtime, lưu lịch sử, đồng bộ trạng thái |
| 4. Tạo nhóm ba người, mời thành viên và gửi tin | Xuân Hoàng | Nhóm, phân quyền, broadcast |
| 5. Gửi ảnh/video/file, nhiều ảnh, ghi âm/gửi/nghe voice | Nguyễn Quang Huy | Multimedia và chức năng sáng tạo voice |
| 6. Gọi thoại/video 1–1 (nếu đạt), mở thông báo push | Nguyễn Quang Huy | Xử lý thiết bị, cuộc gọi, FCM |
| 7. Xoay màn hình, đổi ngôn ngữ, dark mode | Cả nhóm; AUTH dẫn dắt | Đảm bảo UI/state và yêu cầu nâng cao |
| 8. Mở GitHub PR/commit, chạy unit test và giải thích code | Mỗi người nói phần mình | Đóng góp thực tế và khả năng vấn đáp |

**Dự phòng khi mạng demo không ổn định:** có recording từ lần chạy thật và dữ liệu thử nghiệm; vẫn phải nói rõ đó là video dự phòng, không giả là live demo.

---

## 8. Đánh giá rủi ro và phương án dự phòng

| Rủi ro | Xác suất/ảnh hưởng | Tín hiệu sớm | Xử lý |
|---|---|---|---|
| WebSocket/chat 1–1 không ổn định | Cao / Rất cao | D08 chưa chat được hai máy | Cả nhóm tập trung sửa chat/auth; hoãn sticker/call |
| OTP phụ thuộc SMS provider/billing | Trung bình / Cao | D05 chưa chọn được provider | Dùng test number hợp lệ trong demo, giữ email/password; không dùng OTP giả làm bản production |
| Upload file lớn/crash/permission | Cao / Trung bình | D12–D15 lỗi liên tục | Ưu tiên ảnh/file nhỏ + voice; áp giới hạn rõ ràng |
| WebRTC không xuyên NAT, thiếu TURN | Cao / Cao | D22 POC chỉ chạy cùng Wi-Fi | Test TURN sớm; coi call là tính năng có điều kiện, ưu tiên voice message |
| E2EE phức tạp, cần thay đổi data flow | Rất cao / Rất cao | D23 chưa có mô hình khóa đáng tin cậy | Không bật feature flag trên bản chính; nêu rõ là spike/định hướng |
| Gọi nhóm 3 người quá khó | Rất cao / Cao | D25 chưa có SFU/SDK khả thi | Chỉ báo cáo POC nếu chưa xong, không ảnh hưởng gọi 1–1 |
| Nhiều tài khoản lẫn session/FCM | Cao / Cao | D26 không tách cache và token | Ẩn multi-account khỏi release nếu isolation chưa đạt |
| Merge conflict Gradle/nav/data model | Trung bình / Cao | PR thường xuyên phá build | Một owner cho core config; API contract, PR nhỏ và review mỗi 3–5 ngày |
| Thiếu thời gian báo cáo/kiểm thử | Cao / Rất cao | D28 vẫn thêm feature mới | Bắt buộc feature freeze D30, D31–D40 chỉ hoàn thiện |

### Quy tắc quyết định khi chậm tiến độ

- Nếu **D10 chưa có chat 1–1 end-to-end**, mọi người cùng xử lý blocker, **không** làm gọi video/hội nghị video.
- Nếu **D15 chưa có chat nhóm + kết bạn bằng số + voice**, hoãn những tính năng phụ như sticker, hình nền, hội nghị video.
- Nếu **D20 chưa demo được lõi P0** (auth, chat 1–1/nhóm, thêm bạn theo số, media, voice), chỉ giữ P1 đang hoàn thành; không khởi động E2EE hoặc multi-account. Đa ngôn ngữ và xoay tiếp tục theo mốc D24–D27.
- Nếu **D25 nhóm chưa có bản demo end-to-end**, đóng tất cả tính năng P2 và tập trung ổn định trước D30.
- Nếu E2EE chưa đạt điều kiện kiểm thử: **không gọi bản chat là E2EE**; nếu gọi nhóm chưa kết nối được 3 máy thì **không gọi là đã làm gọi nhóm**.

---

## 9. Mẫu cập nhật tiến độ nhóm (mỗi 2–3 ngày, ngắn gọn)

```md
### Cập nhật Dxx — [Tên thành viên]
- Đã làm: [Tên tính năng và kết quả cụ thể]
- Commit/PR: [link GitHub]
- Bằng chứng: [ảnh / video / test]
- Vướng mắc: [lỗi / phụ thuộc / ai hỗ trợ]
- Kế hoạch 2–3 ngày tới: [task rõ ràng]
- Trạng thái: Not started / In progress / In review / Done / Blocked
```

**Kết thúc kế hoạch:** D30 có release candidate đóng băng chức năng. D40 bàn giao bản nộp đủ mã nguồn, Git history, APK, DOCX, hình ảnh, hướng dẫn chạy và bằng chứng test. Thành công được đo bằng **chức năng chạy thật và cả ba người hiểu code**, không đo bằng số commit hay additions/deletions.
