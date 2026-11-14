
# Android Chat Application

Đồ án cuối kỳ môn Lập trình di động Android.

## 1. Công nghệ sử dụng

- Android: Kotlin + Jetpack Compose
- UI: Material 3
- Architecture: MVVM + Repository
- Backend: Kotlin + Ktor (Netty + WebSocket + ContentNegotiation + JWT)
- Database: PostgreSQL (có fallback `InMemoryChatRepository` cho unit/integration test CHAT; các route AUTH dùng PostgreSQL hoặc `InMemoryAuthRepository` trong test)
- Realtime: WebSocket (`/ws/chat`)
- Local Cache: Android `SQLiteOpenHelper` (`SqliteChatLocalCache`) & Encrypted Session Store (`AuthSessionStore`)
- Version Control: Git & GitHub

## 2. Thành viên

| Thành viên | Module |
|---|---|
| Đinh Công Huy | AUTH |
| Nguyễn Quang Huy | MEDIA / CALLS |
| Xuân Hoàng | CHAT |

## 3. Cấu trúc repository

- `android-app/`: Android Studio Project (Jetpack Compose, MVVM, AUTH/Contacts, CHAT/Groups, `SqliteChatLocalCache`, `OkHttpChatWebSocketClient`)
- `backend/`: Ktor Backend (`Application.kt`, AUTH/users, CHAT/groups, `migrations/`)
- `docs/`: Tài liệu kỹ thuật (`api-contract.md`, báo cáo D31–D40 của các module)
- `AGENTS.md`: Quy tắc dành cho AI Agent

## 4. Cách chạy Backend & Android

### 4.1. Chạy Backend (Ktor)

Yêu cầu: JDK 17+, (tùy chọn) PostgreSQL 15+ với các file migration trong `backend/migrations/`.

Cấu hình biến môi trường (tham khảo `backend/.env.example`):
- `JWT_SECRET` (khóa bí mật JWT, mặc định có giá trị dev nếu chưa set)
- `DB_URL`, `DB_USER`, `DB_PASSWORD` (nếu bỏ trống `DB_URL`, server tự chạy chế độ `InMemoryChatRepository` phục vụ test/demo nhanh)

Chạy server và unit/integration test trên Windows:

```powershell
cd backend
.\gradlew.bat test --no-daemon
.\gradlew.bat run --no-daemon
```

### 4.2. Chạy Android

Yêu cầu: Android Studio, Android SDK (API 35), JDK 17+.

Thực hiện:

1. Mở thư mục `android-app/` bằng Android Studio hoặc chạy qua terminal.
2. Backend URL mặc định cho debug emulator là `http://10.0.2.2:8080`. Đổi URL bằng Gradle property `AUTH_API_BASE_URL` (debug chỉ cho phép HTTP với `10.0.2.2`, `127.0.0.1`, `localhost`; mọi URL khác phải dùng HTTPS).
3. Trên Windows, build APK debug và chạy unit test:

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon "-Djava.net.preferIPv4Stack=true"
```

4. Chọn emulator (`10.0.2.2:8080` trỏ tới localhost) hoặc thiết bị thật cùng mạng LAN để chạy ứng dụng.

Compose UI test trên emulator/thiết bị đang kết nối:

    .\gradlew.bat connectedDebugAndroidTest

## 5. Backend và migration

- Yêu cầu JDK 21 cho backend. Chạy từ PowerShell:

      cd backend
      .\gradlew.bat run

- Trước khi chạy các route cần dữ liệu, cấu hình `DB_URL`, `DB_USER` và `DB_PASSWORD` trong môi trường local. `DB_URL` dùng dạng JDBC, ví dụ `jdbc:postgresql://localhost:5432/android_chat`. Nếu thiếu `DB_URL`, server vẫn khởi động nhưng không tạo PostgreSQL pool; `/health` báo `NOT_CONFIGURED` và thao tác AUTH cần database không dùng được.
- Cấu hình `JWT_ISSUER`, `JWT_AUDIENCE` và `PORT` nếu cần giá trị khác mặc định. `JWT_SECRET` có secret development fallback khi `APP_ENV=dev` hoặc `test`; production yêu cầu secret được cấp qua môi trường. Không commit giá trị bí mật.
- Các migration hiện có phải áp dụng thủ công theo thứ tự: `V001__chat_schema_draft.sql`, `V002__auth_users_sessions.sql`, `V003__auth_phone_discovery_and_friends.sql`, `V004__auth_user_blocks.sql`. Gradle chưa cấu hình migration runner.
- Chạy kiểm thử backend bằng `cd backend` rồi `.\gradlew.bat test`. Các Ktor route tests hiện dùng repository in-memory, không thay thế kiểm thử PostgreSQL.

OTP/password recovery chưa kết nối provider vì nhóm chưa chọn dịch vụ; không có OTP cố định hay bypass production.
Luồng email/password hiện thực được dùng cho giai đoạn auth-foundation.

## 6. Giao diện thiết kế

Google Stitch:
https://stitch.withgoogle.com/projects/11111923560284449667

## 7. Quy trình làm việc nhóm

- Mỗi thành viên làm việc trên feature branch riêng.
- Không push trực tiếp vào main khi chưa review.
- Mỗi chức năng phải có commit và Pull Request rõ ràng.
- Tuân thủ AGENTS.md và kế hoạch 40 ngày.

## 8. Trạng thái dự án & Hạn chế đã biết

- **AUTH (Đinh Công Huy):** Đăng ký/đăng nhập email, session JWT/refresh có thể thu hồi, tìm kiếm/tra cứu SĐT, bạn bè, chặn, cài đặt ngôn ngữ VI/EN và theme. OTP provider thực và rate limiting tổng quát chưa triển khai.
- **CHAT (Xuân Hoàng):** Hoàn thiện backend Ktor REST/WebSocket, SQL migrations, Android UI/ViewModels, `SqliteChatLocalCache` và bộ test JVM/Ktor D01–D40.
- **Tích hợp & kiểm thử thực địa:** Cần chạy PostgreSQL live với các file migration và kiểm thử chéo trên thiết bị Android thật.

## 9. Nguồn tham khảo và AI

Nhóm sử dụng các tài liệu và repository công khai
để nghiên cứu kỹ thuật.

AI hỗ trợ tạo và rà soát một phần mã AUTH, kiểm thử, tài liệu bàn giao, phần báo cáo và slide AUTH trong nhánh `feature/auth-email`.
Đinh Công Huy cần review, hiểu và giải thích phần đóng góp trước khi tích hợp theo rubric. Slide và báo cáo AUTH là phần bàn giao riêng, chưa phải báo cáo hoặc deck cuối cùng của cả nhóm.
