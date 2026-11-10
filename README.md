
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

Đang khởi tạo bộ khung Android.

Các chức năng chat, tài khoản, media và backend
chưa được triển khai trên bộ khung mới.

## 8. Nguồn tham khảo và AI

Nhóm sử dụng các tài liệu và repository công khai
để nghiên cứu kỹ thuật.

Việc sử dụng công cụ AI phải được khai báo
trong README hoặc báo cáo theo rubric.
Mỗi thành viên chịu trách nhiệm hiểu và giải thích
mã nguồn mình đóng góp.
