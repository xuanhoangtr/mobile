# AGENTS.md — QUY TẮC BẮT BUỘC CHO AI AGENT

> **Dự án:** Android Chat — đồ án cuối kỳ môn Lập trình di động Android  
> **Hiệu lực:** Áp dụng cho toàn bộ repository, mọi AI coding agent, mọi phiên làm việc và cả Android/backend/docs/tests.  
> **Nhóm:** Đinh Công Huy (AUTH) · **Nguyễn Quang Huy (MEDIA)** · Xuân Hoàng (CHAT).  
> **Repository nhóm đang dùng:** `https://github.com/QuangHuy2603/android-chat-app` (kiểm tra `git remote -v` trước thao tác, không giả định remote không thay đổi).  
> **Mục đích:** Ngăn agent tự đổi công nghệ, tự thêm tính năng, làm sai giao diện, phá hợp đồng tích hợp, ghi đè công việc người khác hoặc tạo bằng chứng hoàn thành giả.

## 0. QUY TẮC ĐỌC TRƯỚC KHI LÀM — BẮT BUỘC

1. **Trước khi sửa bất kỳ file nào**, đọc file này, rubric của giảng viên, kế hoạch 40 ngày của nhóm, các hợp đồng API/kiến trúc hiện có và phần Stitch liên quan đến task.
2. **Chỉ làm đúng task được giao.** Không tự mở rộng phạm vi, không tự refactor hàng loạt, không tự viết lại toàn dự án để “cho đẹp”.
3. **Không tự đổi stack đã chốt:** Android **Kotlin + Jetpack Compose + Material 3**, backend **Kotlin + Ktor**, PostgreSQL, WebSocket/JWT, MVVM/Repository.
4. **Rubric của giảng viên có quyền ưu tiên cao nhất.** Không hi sinh yêu cầu tính điểm để lấy tính năng phụ.
5. **Thiết kế Stitch là tham chiếu giao diện nhóm đã chọn.** Không khẳng định đã xem/khớp Stitch nếu chưa truy cập và xác minh được màn hình thực tế.
6. **Không sao chép nguyên mã nguồn, schema hoặc API** từ bốn repository tham khảo rồi coi là sản phẩm nhóm. Tuân thủ yêu cầu liêm chính học thuật và giấy phép nguồn.
7. **Tôn trọng sở hữu module và Git history.** Chỉ làm việc trên branch phù hợp, không đẩy trực tiếp vào `main`, không ghi công thay thành viên khác.
8. **Không tuyên bố “Done”, “đã test”, “đã demo”, “100% rubric”** nếu chưa có kết quả thực tế kiểm chứng được.
9. **Nếu thiếu thông tin chốt một quyết định ảnh hưởng nhiều module**, dừng đúng phần đó, nêu blocker và các lựa chọn; vẫn hoàn thành những phần độc lập, an toàn trong phạm vi task.
10. **Mỗi lần bàn giao** phải báo rõ file đã đổi, chức năng đã làm, bằng chứng kiểm thử, phần chưa chạy được, ảnh hưởng đến module khác và bước tích hợp tiếp theo.

---

## 1. NGUỒN SỰ THẬT VÀ THỨ TỰ ƯU TIÊN

### 1.1. Nguồn đầu vào chính thức

| Mức | Nguồn | Phạm vi có thẩm quyền |
|---|---|---|
| **P1 — cao nhất** | **Rubric đồ án cuối kỳ của giảng viên** (`rubric_vn.pdf`, tên file tải về có thể khác) | Tiêu chí chấm, thành phần nộp, lịch sử Git, liêm chính học thuật |
| **P2** | **`ke_hoach_40_ngay_android_chat_kotlin.md`** do nhóm thống nhất | 37 chức năng, owner, mức P0/P1/P2, lịch D01–D40, stack, API gợi ý, DoD, Git workflow |
| **P3 — về giao diện** | **Stitch của nhóm:** https://stitch.withgoogle.com/projects/11111923560284449667 | Các màn hình, luồng UI, bố cục, thành phần, màu, typography **đã quan sát được và được nhóm duyệt** |
| **P4** | Code, tài liệu `docs/`, contract, migration và quyết định kỹ thuật đã được merge/duyệt trong repository hiện tại | Hành vi tích hợp, schema/API thực tế đã chốt; không tự ý phá tương thích |
| **P5 — chỉ để nghiên cứu** | Bốn repository open-source được liệt kê ở §13 | Học mô hình, ý tưởng và cách xử lý trường hợp khó; **không** lấn át P1–P4 |
| **P6** | Suy đoán/đề xuất của agent, code mẫu trên mạng | Chỉ là đề xuất; không tự động trở thành quyết định nhóm |

- Nếu tài liệu chưa nằm trong checkout hiện tại, **tìm file thật theo tên tương đương** và yêu cầu người giao task cung cấp/đưa vào repo khi cần. Không giả vờ đã đọc.
- Nếu có mâu thuẫn: tuân thủ rubric; với quyết định giữa kế hoạch nhóm và code đã merge, **ghi rõ mâu thuẫn, đề xuất cách hòa giải và yêu cầu duyệt trước khi thay đổi interface công khai**.
- Stitch quyết định **diện mạo** chứ không thể tự động thay đổi **nghiệp vụ, quyền, API hoặc số lượng chức năng** trong kế hoạch.
- Các nhãn `P0`, `P1`, `P2` ở kế hoạch là mức ưu tiên, **không phải** thứ tự nguồn sự thật tại bảng trên.
- D01–D40 là **ngày tương đối từ ngày nhóm thực sự khởi động**; không tự gán ngày lịch, hạn nộp hoặc tiến độ thực tế.

### 1.2. Trạng thái truy cập Stitch

Link Stitch đã được cung cấp nhưng **nội dung các màn hình chưa được xác minh/đọc đầy đủ trong bộ tài liệu đầu vào này**. Agent chỉ được nói “đã đối chiếu với Stitch” sau khi thật sự truy cập được bản thiết kế tương ứng hoặc nhận được screenshot/export có nguồn gốc từ nhóm. Nếu chưa truy cập được:

- Ghi `UI reference: NOT VERIFIED`, ghi tên màn hình nào bị thiếu.
- Có thể dựng logic/ViewModel/test không phụ thuộc giao diện hoặc dùng UI tạm tối thiểu **nếu task cho phép**, có nhãn rõ `temporary UI`.
- **Không tự bịa** pixel, màu sắc, font, icon, spacing, tên màn hình, component hay tính năng chỉ vì “app chat thông thường hay có”.
- Không khẳng định “pixel-perfect”, “đúng 100% Stitch” nếu chưa so sánh ảnh thực tế.

---

## 2. MỤC TIÊU CHẤM ĐIỂM KHÔNG ĐƯỢC BỎ SÓT

Theo rubric giảng viên, tổng **100 điểm**:

| Nhóm tiêu chí | Điểm tối đa | Mục cần chứng minh |
|---|---:|---|
| Bài nộp | 5 | Source Android + backend, `.git`, DOCX, ảnh chức năng, APK, ZIP đúng quy định |
| Chức năng cơ bản | 20 | Chat 1–1, chat nhóm, tìm người dùng, thêm bằng **số điện thoại**, quản lý tài khoản |
| Chức năng nâng cao | 15 | Đa ngôn ngữ, gửi multimedia/file, xoay màn hình |
| Sáng tạo | 5 | Có chức năng sáng tạo chạy thật; rubric nêu ví dụ gọi điện; kế hoạch nhóm nhấn mạnh voice/calls |
| Chất lượng | 15 | UI rõ ràng, unit test chạy đạt; không chỉ tạo file test rỗng |
| Thuyết trình/demo/vấn đáp | 40 | Demo và giải thích được luồng, kiến trúc, phần code do từng thành viên làm |

**Chốt ưu tiên khi thiếu thời gian:** triển khai và kiểm thử P0 trước; chỉ làm P1 khi P0 không bị nguy hiểm; P2 chỉ là mục tiêu rủi ro cao và **không được báo đã hoàn thành nếu thiếu kiểm chứng**.

**Liêm chính học thuật:** Nêu rõ việc dùng AI trong `README.md` hoặc báo cáo; người merge code phải hiểu và giải thích được. Không che giấu đoạn code AI sinh. Không sao chép bài của nhóm khác. Không tạo commit/PR giả để tăng điểm cá nhân.

---

## 3. PHÂN CÔNG — RANH GIỚI SỞ HỮU

| Owner | Module | Trách nhiệm chính |
|---|---|---|
| **Đinh Công Huy — AUTH** | `feature/auth/`, `feature/contacts/`, `feature/settings/`; backend `auth/`, `users/` | Email/OTP, tài khoản, hồ sơ, tìm người dùng, kết bạn số điện thoại, danh bạ, chặn, đa ngôn ngữ, dark mode, E2EE/multi-account spike |
| **Nguyễn Quang Huy — MEDIA** | `feature/media/`, `feature/calls/`; backend `media/`, `calls/` | Upload/download ảnh, video, âm thanh, file; voice message; sticker, nhiều ảnh, vị trí, hình nền chat, FCM, thoại/video, group-call spike |
| **Xuân Hoàng — CHAT** | `feature/chat/`, `feature/groups/`; backend `chat/`, `groups/` | Chat realtime 1–1/nhóm; cuộc trò chuyện, tin nhắn, WebSocket, receipt, typing, presence, reply/forward/edit/delete, reaction, tìm lịch sử, pin, recall |

**Mỗi owner chịu cả Android + backend của module mình**, không đẩy phần backend cho người khác chỉ vì task Android đã xong.

Quy tắc đụng chạm cross-module:

- Auth cung cấp session/JWT và quyền truy cập; MEDIA, CHAT **không tự dựng cơ chế xác thực riêng**.
- CHAT sở hữu vòng đời tin nhắn và WebSocket chat; MEDIA cung cấp attachment metadata, upload/download, phát/ghi âm và luồng call. **Không tạo hai nguồn dữ liệu `Message` khác nhau.**
- MEDIA phối hợp CHAT cho `mediaRef`, tin `IMAGE`/`VIDEO`/`FILE`/`VOICE` và signaling call. Phối hợp AUTH cho token và avatar upload.
- File dùng chung như navigation root, theme, Gradle, `common/`, shared model/schema/API contract **phải báo ảnh hưởng và được review** trước khi sửa.
- Agent nhận task của **Nguyễn Quang Huy** thì mặc định giới hạn ở MEDIA/CALLS và tài liệu/test liên quan; không tự sửa AUTH/CHAT chỉ để làm feature trông như chạy được.

---

## 4. CÔNG NGHỆ ĐÃ THỐNG NHẤT — KHÔNG TỰ Ý THAY

**Android:** Kotlin, Jetpack Compose, Material 3, MVVM + Repository, Coroutines/StateFlow, Navigation Compose, Room (cache/queue), DataStore (cài đặt và dữ liệu phiên không nhạy cảm; bí mật cần lưu an toàn phù hợp).

**Backend:** Kotlin + Ktor, PostgreSQL, JWT, WebSocket, migration theo phiên bản.

**Tích hợp:** Media qua backend/object storage có kiểm soát, OTP qua provider được nhóm chọn, Firebase Cloud Messaging (FCM) cho push, WebRTC + signaling backend cho call, JUnit/Compose UI test/Ktor test.

**Cấm mặc định:**

- Đổi dự án sang **Java + XML**, Flutter, React Native, Firebase Chat làm backend chính, Node.js/Spring Boot hoặc database khác khi chưa được nhóm duyệt.
- Ghép trực tiếp Tinode backend, Mesibo cloud, Chat SDK/XMPP/Firebase thành **backend chat thứ hai** song song Ktor/WebSocket.
- Thêm thư viện/SDK trả phí, khóa license, yêu cầu billing, dịch vụ có rủi ro vận hành hoặc thay đổi `minSdk`/AGP/Kotlin version không có lý do và duyệt.
- Viết thuật toán mật mã riêng. Nếu thử E2EE, cần protocol/thư viện được kiểm chứng và kết quả test thực, không dùng demo AES sơ sài để nhận là E2EE.

Các bản package/version cụ thể, provider OTP, nơi lưu media và STUN/TURN **chưa được chốt trong kế hoạch**; agent phải kiểm tra quyết định kỹ thuật hiện có, không tự tuyên bố đã chốt.

---

## 5. PHẠM VI CHỨC NĂNG — BẢO TOÀN ĐÚNG KẾ HOẠCH 37 MỤC

Bảng sau cô đọng ma trận kế hoạch; **lịch Dxx và Definition of Done chi tiết phải đọc ở file kế hoạch gốc**.

### P0 — cốt lõi để demo (10 mục)

1. CHAT: Chat 1–1 realtime.
2. CHAT: Tạo nhóm và nhắn tin nhóm.
3. AUTH: Tìm kiếm người dùng.
4. AUTH: Tìm và thêm bạn bằng số điện thoại.
5. AUTH: Quản lý tài khoản/hồ sơ.
6. AUTH: Đa ngôn ngữ Việt/Anh.
7. MEDIA: Gửi ảnh, video, âm thanh và file (upload + download).
8. CHAT chủ trì, cả nhóm áp dụng: Xoay màn hình không làm mất trạng thái/nhân đôi hành động.
9. MEDIA: Chức năng sáng tạo (kế hoạch nhấn mạnh voice; gọi 1–1 chỉ trình diễn nếu thật sự chạy).
10. MEDIA: Voice message — ghi, xem trước, gửi, nghe, tạm dừng, thời lượng, xử lý quyền micro.

### P1 — mở rộng quan trọng (24 mục)

- **AUTH (6):** Đăng ký/đăng nhập OTP; đăng nhập email/mật khẩu; đổi avatar/tên/bio (MEDIA hỗ trợ upload); đồng bộ danh bạ; chặn người dùng; dark mode.
- **CHAT:** Quản lý thành viên nhóm; reply; forward; chỉnh sửa/xóa/sao chép; online/offline; typing; trạng thái đã gửi/nhận/đọc; tìm lịch sử; ghim; thu hồi; reaction.
- **MEDIA:** Sticker; vị trí; FCM; gọi video 1–1; gọi thoại 1–1; hình nền chat; gửi nhiều ảnh.

> **Lưu ý tính số lượng:** Các danh sách trên dùng để định hướng, không được thay cho **37 hàng chính thức** ở §1 của kế hoạch. Nếu cần báo cáo số lượng/đánh dấu hoàn tất, lấy từng hàng từ file kế hoạch gốc; không tính gộp/tách theo phỏng đoán.

### P2 — thử nghiệm rủi ro cao (3 mục)

- MEDIA + CHAT signaling: Gọi nhóm / hội nghị video.
- AUTH + CHAT/MEDIA phối hợp: E2EE.
- AUTH: Nhiều tài khoản.

**Không đưa P2 vào bản release chính** nếu không đạt tiêu chí kiểm thử, không có khả năng giải thích hoặc làm ảnh hưởng P0/P1. Một màn hình mock không được tính là chức năng đã hoàn thiện.

---

## 6. KẾ HOẠCH THỜI GIAN VÀ ĐIỂM CHẶN (GATES)

| Mốc | Tiêu chí cần đạt theo kế hoạch |
|---|---|
| **D01–D05** | Skeleton Android/backend, kết nối WebSocket, đăng nhập cơ bản, PR đầu tiên của 3 thành viên |
| **D06–D10** | Chat 1–1 trên 2 máy, lịch sử còn sau restart, gửi một ảnh/file cơ bản |
| **D11–D15** | Tìm/thêm bằng số điện thoại, chat nhóm ≥3 người, voice message bản đầu |
| **D16–D20** | Củng cố chat/media/voice, trạng thái tin nhắn, FCM, thao tác tin, kiểm tra rotation |
| **D21–D25** | Media/call 1–1 POC, tìm kiếm/ghim/thu hồi, ngôn ngữ/theme; quyết định go/no-go P2 |
| **D26–D30** | Fix tích hợp, xác thực tính năng thật sự chạy, build release candidate, **FEATURE FREEZE ở D30** |
| **D31–D40** | **Chỉ kiểm thử, sửa lỗi, tài liệu, báo cáo, demo và nộp bài**; không tự thêm feature mới |

- Không đánh dấu `D05`/`D10`/… “đã đạt” chỉ vì code đã được sinh. Cần test và bằng chứng tại gate.
- Nếu P0 chưa đạt, agent phải ưu tiên xử lý blocker; không tự kéo P2 vào sprint.
- Không tự đổi lịch Dxx, owner hay ưu tiên P0/P1/P2 nếu chưa có quyết định nhóm.

---

## 7. CẤU TRÚC CODE VÀ QUY ƯỚC TÍCH HỢP

Cấu trúc dưới đây là **đề xuất từ kế hoạch nhóm**, không phải khẳng định repo đã có đủ thư mục:

```text
<repo-root>/
  AGENTS.md
  android-app/
    app/src/main/java/<package>/
      core/                 # navigation, theme, network, shared UI
      feature/auth/         # AUTH
      feature/contacts/     # AUTH
      feature/chat/         # CHAT
      feature/groups/       # CHAT
      feature/media/        # MEDIA
      feature/calls/        # MEDIA
      feature/settings/     # AUTH
  backend/
    src/main/kotlin/<package>/
      auth/ users/           # AUTH
      chat/ groups/         # CHAT
      media/ calls/         # MEDIA
      common/               # shared / review
    migrations/
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

**Không tạo trùng project/Gradle root** nếu repo đã có project Android hợp lệ. Trước khi tạo file, khám phá cấu trúc hiện có và chọn đường dẫn tương thích với quy ước nhóm; đổi cấu trúc lớn phải được duyệt.

### 7.1. Contract chung

Các entity được kế hoạch gợi ý: `User`, `Session`, `FriendRequest`/`ContactRelation`, `Conversation`, `ConversationMember`, `Message`, `MessageReceipt`, `Reaction`, `MediaObject`, `CallSession`, `DeviceToken`/`NotificationPreference`.

- `Message` phải có định danh bền vững, `clientMessageId`, thông tin sender/conversation/type, `mediaRef` khi có file, timestamp server và trạng thái cần thiết.
- MEDIA quản lý object upload: quyền sở hữu, hội thoại, MIME, kích thước, khóa lưu, trạng thái; chỉ trả tệp cho người có quyền truy cập. Không phát tán URL nội bộ hoặc URL không kiểm soát.
- CHAT chịu trách nhiệm lưu/đồng bộ `Message`, tránh nhân đôi khi reconnect và biểu diễn ACK, delivered, read.
- Call sử dụng signaling có xác thực/kiểm quyền. Nếu chưa chốt event schema, đặt đề xuất trong `docs/api-contract.md`, **không âm thầm tạo protocol riêng trong code**.
- API/DB changes ảnh hưởng module khác phải cập nhật contract, migration mới và test; không sửa migration đã merge.

Các endpoint/sự kiện trong kế hoạch là **hợp đồng ví dụ cần nhóm chốt**, không phải API đang tồn tại:

- AUTH: `/auth/register`, `/auth/login`, `/auth/otp/request`, `/auth/otp/verify`, `/auth/refresh`, `/auth/logout`; `/users/me`, `/users/search`, `/users/by-phone`.
- CHAT: `/conversations`, `/conversations/{id}/members`, `/messages?...`, WebSocket `message.send`, `message.ack`, `message.created`, `typing`, `presence`, `receipt`.
- MEDIA: `/media/upload`, `/media/{id}`, `/media/{id}/download`, `/devices/push-token`; signaling `call.invite`, `call.answer`, `call.ice-candidate`, `call.end`.

### 7.2. Quy tắc viết code

- Kotlin idiomatic, nullable rõ ràng, coroutines structured concurrency, state một nguồn sự thật, không network/database trên main thread.
- UI Compose chỉ nhận và phát trạng thái/sự kiện; nghiệp vụ ở ViewModel/use case/repository; backend kiểm quyền thật.
- Có loading/empty/error/retry, xử lý offline/timeout, lifecycle và rotation; tránh hardcode chuỗi UI nếu yêu cầu đa ngôn ngữ.
- Không tạo class/file khổng lồ cho tiện agent; module rõ trách nhiệm, test được, hạn chế duplicate.
- Không xóa, rename hoặc thay đổi file không liên quan chỉ để “dọn dẹp”; không format toàn repo vô cớ.
- Chỉ nâng dependency khi cần thiết, ghi lý do và rủi ro compatibility; không xoá test để build xanh.

---

## 8. QUY TẮC GIAO DIỆN STITCH

**Link chính thức:** https://stitch.withgoogle.com/projects/11111923560284449667

Đối với từng task UI:

1. Liệt kê chính xác màn hình/component của task và tham chiếu màn hình Stitch đã xem (nếu được cấp quyền truy cập).
2. Đối chiếu layout, text, ảnh/icon, component, trạng thái, navigation với bản nhóm thống nhất; dùng Compose/Material 3 theo stack.
3. Chỉ thêm trạng thái kỹ thuật cần thiết (loading, error, permission, empty) theo phong cách tương thích; không tự thiết kế lại luồng nghiệp vụ.
4. Dùng `stringResource`/resources cho text người dùng; hỗ trợ VI/EN; kiểm tra giao diện khi xoay và khi font lớn/màn hình nhỏ.
5. Báo `VISUAL_VERIFIED` chỉ nếu đã so sánh bằng ảnh thật. Nếu chưa, báo `VISUAL_UNVERIFIED` kèm lý do/ảnh còn thiếu.

**Không được:** tự suy diễn màn hình từ tên feature; thay giao diện Stitch bằng giao diện “Messenger/Zalo/Tinode” ngẫu nhiên; dùng hình logo/thương hiệu không được phép; đưa screenshot từ internet rồi nói là screenshot của bản nhóm.

---

## 9. QUY TRÌNH LÀM VIỆC BẮT BUỘC CỦA AGENT

### 9.1. Trước khi code (Preflight)

- [ ] Đọc `AGENTS.md`, rubric, kế hoạch nhóm, tài liệu contract/architecture hiện có.
- [ ] Xác định **owner**, feature, P0/P1/P2, mốc Dxx, Definition of Done gốc và phần liên quan từ Stitch.
- [ ] Kiểm tra repo status (`git status`), branch hiện tại, project structure, các file có sẵn, build/test command thực tế.
- [ ] Xác định phụ thuộc với AUTH/CHAT/MEDIA; không phá API đã merge.
- [ ] Nêu **phạm vi sửa file** và tiêu chí test sẽ chạy. Nếu task đã rõ, thực hiện ngay; không hỏi lại những gì tài liệu đã trả lời.

### 9.2. Trong lúc code

- [ ] Chia thành thay đổi nhỏ có thể build/test, ưu tiên end-to-end hơn mock UI lớn.
- [ ] Sửa đúng phạm vi; không sửa unrelated code, `main`, dữ liệu bí mật hoặc config production.
- [ ] Backend phải xác thực/ủy quyền; client không quyết định quyền một mình.
- [ ] Thêm/sửa test tương ứng; cập nhật docs/contract nếu hành vi công khai thay đổi.
- [ ] Khi bị blocker, báo rõ; không dùng dữ liệu giả để lấp chỗ còn thiếu rồi đánh dấu hoàn thành.

### 9.3. Trước bàn giao (Verification)

- [ ] Build Android thực tế hoặc nêu rõ vì sao không thể chạy; nếu có backend, build/test backend liên quan.
- [ ] Chạy unit/integration/UI test phù hợp hoặc ghi `NOT RUN` và lý do.
- [ ] Kiểm tra ít nhất trường hợp thành công + lỗi + hết quyền/mất mạng cho feature.
- [ ] Với chat/media/call: nếu nghiệm thu đòi hỏi, chạy trên **2 thiết bị/2 tài khoản thật**; gọi nhóm cần ≥3 thiết bị.
- [ ] Xem `git diff`, tránh secret, file build, file tự sinh không cần thiết; không ghi đè thay đổi người dùng.
- [ ] Báo đầy đủ `DONE / PARTIAL / BLOCKED / NOT TESTED`, không diễn đạt mập mờ.

---

## 10. GIT, BRANCH, REVIEW, LỊCH SỬ ĐÓNG GÓP

- `main` là branch tích hợp ổn định; **không push thẳng**, không force push, không rewrite lịch sử, không xóa `.git`.
- Mỗi thay đổi làm trên branch ngắn, đặt tên theo feature. Ví dụ của **Nguyễn Quang Huy**: `feature/media-upload`, `feature/voice-message`, `feature/push`, `feature/call-audio`, `feature/call-video`, `spike/group-call`.
- AUTH và CHAT dùng branch feature theo §5 trong kế hoạch. Không lập 1 branch tồn tại suốt 30 ngày.
- Commit có ý nghĩa và gắn đúng tác giả thật. **Không tự gán danh tính/tài khoản Git của người khác**, không forge commit để tạo đóng góp.
- Agent **không tự commit/push/merge** khi phiên giao việc chưa cho phép thao tác Git đó; nếu được phép thì vẫn phải dùng branch và báo commit/PR thật.
- Pull Request có mục tiêu, files changed, cách test, ảnh/log không nhạy cảm, phụ thuộc và reviewer; reviewer phải là người khác tác giả.
- Không merge PR đang build/test lỗi vào `main`. Không bỏ qua reviewer/protection chỉ để cho xong task.
- Duy trì `docs/contribution-log.md` bằng **link commit/PR thật**; log không thể thay thế lịch sử Git.
- Nhịp commit/PR trong kế hoạch chỉ là **gợi ý quản trị**, không phải thủ thuật tạo số commit. Giá trị là đóng góp thực chất và giải thích được code.

---

## 11. AN TOÀN, BẢO MẬT VÀ DỮ LIỆU

- Không commit: `local.properties`, `.env` chứa secret, access/refresh token, SMS/FCM private keys, `google-services.json` nếu chứa cấu hình không phù hợp để public theo chính sách nhóm, keystore, `keystore.properties`, mật khẩu, file khóa service account, dữ liệu cá nhân thật.
- Không log token, OTP, mật khẩu, private key, nội dung nhạy cảm; không hardcode test credentials vào bản release.
- Mọi API truy cập nội dung riêng: xác thực JWT **và** kiểm quyền membership/owner tại server; chống IDOR.
- File picker/camera/micro/location/contacts: quyền đúng ngữ cảnh, có nhánh từ chối, giới hạn MIME/kích thước, xử lý upload lỗi, không tự tải cả danh bạ khi chưa có đồng ý.
- Upload/download phải chặn truy cập media của chat khác, kiểm tra MIME/size và xử lý filename/path nguy hiểm; tránh public URL vĩnh viễn.
- OTP cần provider có thật, rate limit và test number chỉ dùng test. Không giả thông báo “OTP đã gửi” nếu backend chưa gửi.
- FCM không chứa dữ liệu nhạy cảm không cần thiết; revoke token device phù hợp khi logout.
- WebRTC: signaling phải có quyền; xử lý permission, từ chối, kết thúc, timeout; không khẳng định chạy qua mạng khác nếu chưa test STUN/TURN thực tế.
- Không gắn nhãn E2EE nếu server vẫn đọc được nội dung hay không có cơ chế quản lý khóa được kiểm thử.

---

## 12. KIỂM THỬ VÀ ĐỊNH NGHĨA HOÀN THÀNH (DoD)

**Quy tắc chung:** UI hiển thị ≠ chức năng chạy; mock response ≠ backend chạy; code sinh xong ≠ pass test.

### 12.1. DoD nền tảng

- Android compile/build được; backend compile/chạy được khi task liên quan.
- API/DB/UI tích hợp đúng contract, có test và lỗi được xử lý có ý nghĩa.
- Không phá login, chat 1–1, nhóm, tìm bạn số điện thoại, media, đa ngôn ngữ và rotation.
- Có xác minh bản chạy thực tế, không chỉ screenshot Design/Preview.
- Có bằng chứng: tên test, lệnh chạy, kết quả, thông số môi trường và (nếu cần) ảnh/video của app thật.
- Documentation cập nhật đủ để thành viên khác chạy và thuyết trình được.

### 12.2. DoD riêng MEDIA (Nguyễn Quang Huy)

| Feature | Mức chứng minh tối thiểu |
|---|---|
| Ảnh/video/audio/file | Chọn → upload có progress → lưu metadata → CHAT tạo message có `mediaRef` → máy khác tải/mở đúng; xử lý MIME/size/permission/error |
| Voice message | Xin quyền micro → ghi → xem trước/hủy → upload/gửi → máy khác phát/pause và xem duration; thử xoay/mất mạng |
| Nhiều ảnh | Upload 3–5 ảnh, tiến độ/lỗi theo từng ảnh và retry có kiểm soát |
| Sticker/vị trí | Hai máy thể hiện nhất quán; vị trí xin quyền và mở bản đồ đúng, không tự gửi live location |
| FCM | Token đăng ký với backend; app nền nhận, bấm vào đúng chat; tránh notification trùng; test logout |
| Audio/video 1–1 | Hai thiết bị gọi, nghe/thấy thật, answer/reject/end, mute/camera permission; kiểm thử timeout/mất mạng |
| Group call P2 | ≥3 máy tham gia thực tế mới đánh dấu Done; nếu chỉ POC thì ghi Partial/Experimental |

### 12.3. Test bổ sung cho toàn nhóm

- AUTH: login sai, hết hạn token, rate limit OTP, kết bạn số điện thoại, block, dữ liệu riêng.
- CHAT: reconnect, trùng `clientMessageId`, pagination, receipt, membership nhóm, draft khi xoay.
- UI: Việt/Anh, dark/light, màn hình nhỏ, font lớn, xoay, background/foreground.
- Backend: unauthenticated/forbidden, dữ liệu sai, media của chat khác, kiểm thử migration.

**Kết quả test phải là sự thật**: nếu chưa chạy thiết bị thật, ghi `NOT TESTED ON DEVICE`; nếu dùng mock, ghi `MOCK ONLY`.

---

## 13. 4 REPOSITORY THAM KHẢO — CHỈ NGHIÊN CỨU

1. Delta Chat Android — https://github.com/deltachat/deltachat-android
2. Tinode / Tindroid — https://github.com/tinode/tindroid
3. Chat SDK Android — https://github.com/chat-sdk/chat-sdk-android
4. Mesibo Messenger Android — https://github.com/mesibo/messenger-app-android

**Quy tắc:**

- Có thể đọc kiến trúc, issue, README và nghiên cứu luồng người dùng; tự thiết kế/hiện thực thích hợp Kotlin + Compose + Ktor.
- **Không copy nguyên file/module, DB schema, API hoặc assets** vào project mà không có phê duyệt, ghi nguồn và kiểm tra giấy phép cụ thể của đúng file/phiên bản/dependency.
- Một repo có code công khai **không đồng nghĩa** mọi phần đều được dùng tự do; kiểm tra LICENSE, điều kiện SDK, module Pro, copyleft và yêu cầu ghi công.
- Không để code tham khảo làm thay đổi mục tiêu rubric, schema đã chốt hoặc UI Stitch.
- Ghi sự tham khảo và phần AI trợ giúp trong `README.md`/báo cáo để đáp ứng yêu cầu giảng viên.

---

## 14. YÊU CẦU NỘP BÀI & BÁO CÁO — KHÔNG QUÊN

Bài nộp cuối phải có:

- [ ] **Android source + backend source** và **`.git` chứa history thực** trong ZIP cuối.
- [ ] **Báo cáo Word `.docx`**.
- [ ] **Ảnh chụp các chức năng chạy thật**.
- [ ] **APK** có thể cài/chạy.
- [ ] README hướng dẫn cấu hình, build, chạy Android/backend/database, dependency/giới hạn và khai báo AI/nguồn tham khảo.
- [ ] Unit test, mô tả test và bằng chứng demo phù hợp.
- [ ] Tên ZIP cho **nhóm 3 thành viên** theo ví dụ rubric: `GroupXX_MSSV1_MSSV2_MSSV3.zip` (thay số nhóm và 3 MSSV thật; không tự bịa MSSV). Mẫu tổng quát trong rubric minh họa đến `MSSV5`.
- [ ] ZIP **không chứa** `build/`, `node_modules/`, file keystore, `keystore.properties`.
- [ ] Giải nén thử file ZIP và kiểm tra `.git`, APK, DOCX, ảnh và source vẫn có đầy đủ.

**Điểm cá nhân:** rubric dùng commit/PR thực tế để xét hệ số đóng góp từ **0,9 đến 1,1**; thành viên không có đóng góp thực chất nhận 0 điểm đồ án. Không tối ưu số commit giả thay cho kết quả thật.

---

## 15. TRƯỜNG HỢP AGENT PHẢI DỪNG PHẦN LIÊN QUAN VÀ BÁO BLOCKER

1. Task yêu cầu vi phạm rubric, dùng mã sao chép không được phép hoặc làm giả đóng góp/test/demo.
2. Task yêu cầu đổi stack, DB, backend, scheme xác thực, API contract hoặc dữ liệu liên module nhưng chưa có sự đồng ý của nhóm.
3. Task yêu cầu khẳng định bám sát Stitch nhưng agent không thể xem thiết kế/screenshots thực tế.
4. Task đòi quyền truy cập, API key, FCM/SMS billing, signing key, credentials, dịch vụ chưa được cấp; **không tự chế key hoặc bỏ bảo mật**.
5. Thay đổi có nguy cơ xóa lịch sử Git, data production, migration cũ, branch công việc người khác, file ngoài phạm vi.
6. Task P2 gây nguy cơ phá P0 hoặc vượt mốc feature freeze D30.

**Cách xử lý:** xác định đúng phần bị chặn; thực hiện phần còn lại có thể làm an toàn; ghi blocker, tác động, phương án và kiểm thử chưa thực hiện. **Không báo xong toàn bộ**.

---

## 16. KHUÔN BÀN GIAO CHO MỖI TASK

Khi kết thúc một lượt làm việc, trả lời bằng mẫu sau, điền sự thật chứ không ghi mặc định “Pass”:

```md
### TASK REPORT
- Task / Issue: ...
- Owner: AUTH | MEDIA | CHAT — <tên thành viên>
- Priority / Planned window: P0 | P1 | P2 — Dxx–Dyy (theo kế hoạch)
- Status: DONE | PARTIAL | BLOCKED | NOT TESTED
- Sources checked: rubric / plan / Stitch (VERIFIED or NOT VERIFIED) / API contract
- Scope: ...
- Files created/changed: ...
- Behavior actually working: ...
- Android build: PASS | FAIL | NOT RUN (lệnh + lý do)
- Backend build/test: PASS | FAIL | NOT RUN (lệnh + lý do)
- Unit/UI/device tests: PASS | FAIL | NOT RUN (bằng chứng)
- Git branch / commit / PR: ... (chỉ nếu thật sự tồn tại)
- Security / license / AI disclosure: ...
- Remaining issues / cross-module dependencies: ...
- Next integration step: ...
```

## 17. CHECKLIST TỰ KIỂM TRA 30 GIÂY TRƯỚC KHI NÓI “XONG”

- [ ] Có đi đúng **rubric + 37 chức năng + phân công nhóm** không?
- [ ] Có giữ Kotlin/Compose/Ktor/PostgreSQL/WebSocket không?
- [ ] Có đối chiếu đúng phần Stitch thực tế hay đã ghi rõ chưa xác minh?
- [ ] Có tránh thêm backend/schema/UI/feature riêng không được duyệt không?
- [ ] Có đúng branch, không hỏng `.git` và không ghi công giả không?
- [ ] Có code thật chạy, test thật và bằng chứng trung thực không?
- [ ] Có đảm bảo tài liệu, bảo mật, nguồn tham khảo và AI disclosure không?

> **NGUYÊN TẮC CUỐI:** *Làm đúng yêu cầu nhóm và rubric; ưu tiên phần chạy thật, kiểm thử được, có lịch sử đóng góp thật và giải thích được khi vấn đáp. Không đánh đổi những điều đó để agent tạo ra nhiều code hoặc nhiều tính năng trông đẹp.*

## REFERENCE REPOSITORIES

Thư mục `reference-repos/` chứa các repository
GitHub bên ngoài phục vụ nghiên cứu.

Quy tắc bắt buộc:

1. Được đọc và phân tích mã nguồn tham khảo.
2. Không chỉnh sửa mã nguồn trong reference-repos/.
3. Không sao chép nguyên module hoặc ứng dụng.
4. Không thay đổi kiến trúc đã thống nhất của nhóm.
5. Ưu tiên Kotlin + Jetpack Compose + Ktor.
6. Khi tham khảo giải pháp phải kiểm tra license.
7. Code triển khai phải giải thích được và tuân thủ rubric.
8. Chỉ sửa code đồ án trong module được giao.