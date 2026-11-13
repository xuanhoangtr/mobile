
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

## 4. Cách chạy Android

Yêu cầu:
- Android Studio
- Android SDK
- JDK tương thích cấu hình Gradle

Thực hiện:

1. Clone repository.
2. Mở thư mục `android-app/` bằng Android Studio.
3. Chờ Gradle Sync hoàn tất.
4. Chọn emulator hoặc thiết bị Android.
5. Nhấn Run để chạy ứng dụng.

Build bằng terminal trên Windows:

    cd android-app
    .\gradlew.bat assembleDebug

## 5. Giao diện thiết kế

Google Stitch:
https://stitch.withgoogle.com/projects/11111923560284449667

## 6. Quy trình làm việc nhóm

- Mỗi thành viên làm việc trên feature branch riêng.
- Không push trực tiếp vào main khi chưa review.
- Mỗi chức năng phải có commit và Pull Request rõ ràng.
- Tuân thủ AGENTS.md và kế hoạch 40 ngày.

## 7. Trạng thái dự án

MEDIA foundation đã hoàn thiện ở mức local source/build/test trên nhánh
`feature/media-foundation`. Đã có picker/upload/download boundary, voice,
batch attachment, sticker, vị trí, wallpaper và call signaling/state POC.
CHAT vẫn sở hữu message/realtime/membership; AUTH sở hữu session/JWT.

WebRTC media thật, FCM provider, TURN/NAT, group media và kiểm thử thiết bị
thật chưa được claim hoàn thành. Xem [bằng chứng và giới hạn MEDIA](docs/media-foundation.md).

## 8. Kiểm thử và release checklist MEDIA

Chạy từ thư mục tương ứng trên Windows:

    cd android-app
    .\gradlew.bat testDebugUnitTest assembleDebug --offline
    cd ..\backend
    .\gradlew.bat test --offline

Build/test output nằm trong `android-app/app/build/` và `backend/build/`, không
được commit. Không đưa JWT secret, provider key, keystore hoặc file `.env`
thật vào source/release ZIP. Debug APK dùng `UnconfiguredMediaUploadService`
nếu host chưa inject upload service thật.

## 9. Nguồn tham khảo và AI

Nhóm sử dụng các tài liệu và repository công khai
để nghiên cứu kỹ thuật.

Việc sử dụng công cụ AI phải được khai báo
trong README hoặc báo cáo theo rubric.
Mỗi thành viên chịu trách nhiệm hiểu và giải thích
mã nguồn mình đóng góp.

## 10. MEDIA foundation D01–D40 (dev-only, DRAFT)

Đã thêm module MEDIA độc lập cho SAF picker/preview/state/repository interface trên Android
và upload streaming/owner-only download trong backend Ktor hiện tại.
API thử nghiệm mặc định tắt; chưa nối root navigation, AUTH network adapter hoặc CHAT message.
Xem [phạm vi, bằng chứng, API đề xuất, ACL và giới hạn](docs/media-foundation.md).

Source MEDIA Android/backend, MEDIA tests và tài liệu foundation được xây dựng với hỗ trợ
Copilot trong task MEDIA D01–D40. Đây chưa phải MEDIA production-ready hoặc demo hai máy.
Chi tiết AI disclosure và các phụ thuộc cần nhóm duyệt nằm trong tài liệu trên.
