
# Android Chat Application

Đồ án cuối kỳ môn Lập trình di động Android.

## 1. Công nghệ sử dụng

- Android: Kotlin + Jetpack Compose
- UI: Material 3
- Architecture: MVVM + Repository
- Backend: Kotlin + Ktor
- Database: PostgreSQL (cần cấu hình để dùng persistence)
- Realtime: Ktor WebSocket; luồng chat hiện vẫn là khung tích hợp, chưa được xác nhận end-to-end
- Version Control: Git & GitHub

## 2. Thành viên

| Thành viên | Module |
|---|---|
| Đinh Công Huy | AUTH |
| Nguyễn Quang Huy | MEDIA / CALLS |
| Xuân Hoàng | CHAT |

## 3. Cấu trúc repository

- `android-app/`: dự án Android Studio
- `backend/`: ứng dụng Ktor, AUTH/users và khung CHAT/groups
- `docs/`: Tài liệu kỹ thuật và rubric
- `AGENTS.md`: Quy tắc dành cho AI Agent

## 4. Chạy Android

Yêu cầu:
- Android Studio
- Android SDK
- JDK tương thích cấu hình Gradle

Thực hiện:

1. Mở thư mục `android-app/` bằng Android Studio và chờ Gradle Sync.
2. Chọn emulator hoặc thiết bị Android.
3. Backend URL mặc định cho debug emulator là `http://10.0.2.2:8080`.
   Đổi URL bằng Gradle property `AUTH_API_BASE_URL`, ví dụ:

       .\gradlew.bat -PAUTH_API_BASE_URL=https://api.example.invalid assembleDebug

   Debug chỉ cho phép HTTP với `10.0.2.2`, `127.0.0.1` hoặc `localhost`; mọi URL khác phải dùng HTTPS.
   Release mặc định dùng một hostname placeholder HTTPS và cần cấu hình hostname triển khai thật.
4. Đăng ký/đăng nhập dùng `/auth/register` và `/auth/login`. AUTH khôi phục phiên đã mã hóa khi mở lại app.
   Tab Danh bạ hỗ trợ tìm theo tên/tên định danh và tra cứu số điện thoại có bật discoverability;
   trang cá nhân cập nhật tên/tiểu sử và đăng xuất.

Build bằng terminal trên Windows:

    cd android-app
    .\gradlew.bat testDebugUnitTest assembleDebug

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

## 8. Trạng thái dự án

Android AUTH có đăng ký/đăng nhập email, session provider, tìm kiếm người dùng, danh bạ/lời mời, chặn, cập nhật tên/tiểu sử, ngôn ngữ và theme.
Backend AUTH có session JWT/refresh có thể thu hồi, hồ sơ, tìm kiếm/tra cứu số điện thoại theo metadata xác minh, quan hệ bạn bè và chặn. Nhóm chưa chọn OTP provider; đăng ký/xác minh OTP chưa được triển khai.
Màn hình và route CHAT/GROUPS có mặt, nhưng Android chưa có chat transport thật; tin nhắn gửi đi hiện được đánh dấu `FAILED`. Backend chat routes/WebSocket còn là skeleton. CHAT chưa tích hợp `AuthTokenProvider` vào WebSocket; MEDIA chưa tích hợp provider vào HTTP/call. AUTH chưa kiểm soát việc chặn tin nhắn và WebSocket ở CHAT.
Kết quả kiểm thử local ngày 2026-11-14: Android `testDebugUnitTest` 52 test và backend `test` 12 test đều đạt. Đây là unit/Ktor tests; backend tests dùng repository in-memory. PostgreSQL integration, thiết bị Android, CI và tích hợp toàn nhóm chưa được xác minh.

## 9. Nguồn tham khảo và AI

Nhóm sử dụng các tài liệu và repository công khai
để nghiên cứu kỹ thuật.

AI hỗ trợ tạo và rà soát một phần mã AUTH, kiểm thử, tài liệu bàn giao, phần báo cáo và slide AUTH trong nhánh `feature/auth-email`.
Đinh Công Huy cần review, hiểu và giải thích phần đóng góp trước khi tích hợp theo rubric. Slide và báo cáo AUTH là phần bàn giao riêng, chưa phải báo cáo hoặc deck cuối cùng của cả nhóm.
