
# Android Chat Application

Đồ án cuối kỳ môn Lập trình di động Android.

## 1. Công nghệ sử dụng

- Android: Kotlin + Jetpack Compose
- UI: Material 3
- Architecture: MVVM + Repository
- Backend dự kiến: Kotlin + Ktor
- Database dự kiến: PostgreSQL
- Realtime dự kiến: WebSocket
- Version Control: Git & GitHub

## 2. Thành viên

| Thành viên | Module |
|---|---|
| Đinh Công Huy | AUTH |
| Nguyễn Quang Huy | MEDIA / CALLS |
| Xuân Hoàng | CHAT |

## 3. Cấu trúc repository

- `android-app/`: Android Studio Project
- `backend/`: Ktor Backend (sẽ triển khai)
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

- Chạy backend theo hướng dẫn ở `backend/src/main/kotlin/vn/edu/hcmute/androidchat/backend/auth/README.md`.
- Áp dụng migration theo thứ tự: `backend/migrations/V001__chat_schema_draft.sql`, sau đó `V002__auth_users_sessions.sql`.
- `DB_URL`, `DB_USER`, `DB_PASSWORD` và `JWT_SECRET` được cấu hình qua môi trường chạy backend; không đưa giá trị thật vào Git.
- Chạy kiểm thử backend bằng `cd backend` rồi `.\gradlew.bat test`.

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

Android AUTH có luồng đăng ký/đăng nhập email, session provider, tìm người dùng, cập nhật tên/tiểu sử và đăng xuất.
Backend AUTH có session JWT/refresh có thể thu hồi, hồ sơ người dùng và tìm kiếm/tra cứu theo số điện thoại.
CHAT/MEDIA vẫn cần nối `AuthTokenProvider` vào HTTP/WebSocket client của họ; chat hiện vẫn là skeleton.

## 9. Nguồn tham khảo và AI

Nhóm sử dụng các tài liệu và repository công khai
để nghiên cứu kỹ thuật.

AI hỗ trợ tạo và rà soát một phần mã AUTH, kiểm thử và tài liệu trong nhánh `feature/auth-email`.
Đinh Công Huy cần review, hiểu và giải thích phần đóng góp trước khi tích hợp theo rubric.
