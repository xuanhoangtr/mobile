
# Android Chat Application

Đồ án cuối kỳ môn Lập trình di động Android.

## 1. Công nghệ sử dụng

- Android: Kotlin + Jetpack Compose
- UI: Material 3
- Architecture: MVVM + Repository
- Backend: Kotlin + Ktor (Netty + WebSocket + ContentNegotiation + JWT)
- Database: PostgreSQL (có fallback `InMemoryChatRepository` cho unit/integration test không cần DB)
- Realtime: WebSocket (`/ws/chat`)
- Local Cache: Android `SQLiteOpenHelper` (`SqliteChatLocalCache`)
- Version Control: Git & GitHub

## 2. Thành viên

| Thành viên | Module |
|---|---|
| Đinh Công Huy | AUTH |
| Nguyễn Quang Huy | MEDIA / CALLS |
| Xuân Hoàng | CHAT |

## 3. Cấu trúc repository

- `android-app/`: Android Studio Project (Jetpack Compose, MVVM, `SqliteChatLocalCache`, `OkHttpChatWebSocketClient`)
- `backend/`: Ktor Backend (`Application.kt`, `ChatRoutes.kt`, `ChatWebSocket.kt`, `PostgresChatRepository.kt`, `migrations/`)
- `docs/`: Tài liệu kỹ thuật (`api-contract.md`, `chat-d31-d35-report.md`, `chat-d36-d40-handover.md`)
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
2. Trên Windows, build APK debug và chạy unit test:

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon "-Djava.net.preferIPv4Stack=true"
```

3. Chọn emulator (`10.0.2.2:8080` trỏ tới localhost) hoặc thiết bị thật cùng mạng LAN để chạy ứng dụng.

## 5. Giao diện thiết kế

Google Stitch:
https://stitch.withgoogle.com/projects/11111923560284449667

## 6. Quy trình làm việc nhóm

- Mỗi thành viên làm việc trên feature branch riêng.
- Không push trực tiếp vào main khi chưa review.
- Mỗi chức năng phải có commit và Pull Request rõ ràng.
- Tuân thủ AGENTS.md và kế hoạch 40 ngày.

## 7. Trạng thái dự án & Hạn chế đã biết

- **Module CHAT (Xuân Hoàng — nhánh `feature/chat-realtime`):** Đã hoàn thiện phần backend Ktor REST/WebSocket, SQL migrations, Android UI/ViewModels, `SqliteChatLocalCache` và bộ test JVM/Ktor D01–D40.
- **Hạn chế cần lưu ý khi chạy thực tế:**
  - Chế độ chạy không có `DB_URL` sử dụng `InMemoryChatRepository` (dữ liệu mất khi tắt server); cần cấu hình PostgreSQL + chạy migration `001`–`004` khi demo production.
  - Kiểm thử giao diện trực quan trên 2–3 thiết bị Android thật và kết nối chéo với module AUTH / MEDIA cần thực hiện khi ghép nhánh tích hợp `main`.

## 8. Nguồn tham khảo và AI

Nhóm sử dụng các tài liệu và repository công khai
để nghiên cứu kỹ thuật.

Việc sử dụng công cụ AI phải được khai báo
trong README hoặc báo cáo theo rubric.
Mỗi thành viên chịu trách nhiệm hiểu và giải thích
mã nguồn mình đóng góp.
