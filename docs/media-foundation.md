# MEDIA D01–D05 — foundation và đề xuất tích hợp

Owner module: Nguyễn Quang Huy. Base: `d52b2ba`, branch `feature/media-foundation`.

**DRAFT — chưa phải contract ba thành viên đã phê duyệt. Không production-ready.**
Không thay `docs/api-contract.md`, Message, schema CHAT/AUTH, WebSocket hay JWT/session.

## Phạm vi và trạng thái

| Mốc | Đầu ra | Trạng thái |
|---|---|---|
| D01 | Cấu trúc MEDIA Android/backend, ranh giới AUTH/CHAT, bảng quyền và signaling đề xuất | DONE; Stitch NOT VERIFIED |
| D02 | Đề xuất MediaObject/API/MIME/limits/ACL/storage, điểm chặn tích hợp | PARTIAL — chờ ba owner chốt |
| D03 | SAF OpenDocument, metadata, image/video thumbnail, external file preview, URI access, StateFlow/ViewModel | DONE ở mức source/build/unit test; thiết bị NOT VERIFIED |
| D04 | Ktor dev-only upload streaming + local storage/validation + owner-only metadata/download + tests | DONE ở mức local test host |
| D05 | State progress/cancel/retry có test; client Ktor upload/download local có test | PARTIAL — chưa có Android HTTP/JWT adapter, root navigation và thiết bị thật |

Không báo upload PASS thành ảnh đã tới máy người nhận. Android không giả dữ liệu upload thành công: service mặc định luôn báo INTEGRATION_BLOCKED.

## Android và quyền

`feature/media/` gồm model, data/source/repository/service interface, ViewModel và UI độc lập.
UI là **temporary Material 3**, chưa xác minh màn picker/preview/upload/voice/call trong Stitch.
Root navigation/MainActivity và CHAT giữ nguyên. D05 thêm `MediaDebugActivity` trong Android `src/debug/` cùng debug manifest; đây là launcher thứ hai chỉ có trong debug APK, không tham gia release. Activity dùng `AndroidMediaSource` thật và `StreamMediaRepository` với `UnconfiguredMediaUploadService` mặc định, nên upload sẽ hiện lỗi tích hợp bị chặn, không giả thành công.

Để thử trên emulator, cài/chạy cấu hình `debug` trong Android Studio rồi mở launcher `MEDIA test (debug)`. Có thể gọi trực tiếp bằng `adb shell am start -n vn.edu.hcmute.androidchat/.feature.media.debug.MediaDebugActivity`. Chọn file mở Android system picker; `Mở tệp` dùng app viewer ngoài nếu có. Kiểm thử thiết bị thực tế cần báo riêng, không được suy ra từ build/unit test.

| Nhu cầu | API/quyền |
|---|---|
| Chọn JPEG/PNG/MP4/PDF/TXT | ActivityResultContracts.OpenDocument, content URI; không READ/WRITE_EXTERNAL_STORAGE hoặc MANAGE_EXTERNAL_STORAGE |
| Đọc metadata | ContentResolver + OpenableColumns; size có thể không biết |
| URI read | Thử giữ persistable read grant; báo rõ persisted/temporary. Mất quyền/xóa/di chuyển file cần chọn lại |
| Bỏ chọn/thay selection/ViewModel clear | Chỉ release grant do MediaSource instance này mới acquire, không release grant có sẵn của module khác |
| Preview ảnh | Decode có inSampleSize giới hạn thumbnail ~512px; không đọc toàn file vào RAM |
| Preview video | Scaled frame Android API27+; API24–26 dùng external viewer; chưa playback trong app |
| Preview PDF/TXT/video | ACTION_VIEW với read URI grant, xử lý thiếu app/quyền |
| Camera/micro/location/notifications | Chưa yêu cầu; ngoài foundation này |
| HTTP upload | Chưa thêm INTERNET/cleartext exception vì chưa tích hợp Android network adapter |

State sống qua rotation nếu host lấy ViewModel từ ViewModelStore đúng cách. Không claim restore sau process death; chưa SavedStateHandle/persist selection/upload queue.
Progress hiện đếm byte stream được adapter tiêu thụ, không phải server ACK hay socket delivery. Repository chỉ nhận success khi adapter đọc đến EOF, byte count khớp size đã biết (nếu có), response size khớp dữ liệu đọc và có byteSize dương. Unknown size dùng progress indeterminate và vẫn chặn quá 10 MiB khi đọc. Cancel cooperative, retry mở lại URI stream từ đầu.
Service tương lai phải đóng HTTP connection khi cancel, không follow redirect làm lộ JWT, dùng endpoint và session được AUTH duyệt. Retry sau kết quả mạng mơ hồ có thể tạo object khác: chưa có idempotency upload, không tự retry tự động.

## Điểm nối đề xuất, chưa sửa CHAT/root

Host tạo `StreamMediaRepository(AndroidMediaSource(application.contentResolver), sharedMediaUploadService)` và inject vào `MediaViewModel` bằng ViewModelProvider.Factory. Render `MediaPickerScreen(viewModel)` ở destination do Hoàng thống nhất.

Interface đã có:

```kotlin
interface MediaUploadService {
    suspend fun upload(media: SelectedMedia, input: InputStream): UploadedMedia
}
```

Đề xuất callback tại CHAT sau khi nhóm duyệt:

```kotlin
onPickAttachment: () -> Unit
onAttachmentReady: (conversationId: Long, mediaId: Long) -> Unit
```

Đây là interface đề xuất, không phải một giao thức Message mới. CHAT vẫn tạo clientMessageId, lưu/gửi Message và xử lý ACK/created. **Không dùng ID từ dev catalog RAM làm mediaRef CHAT thật.**
AUTH cung cấp token/session, CHAT cung cấp conversation context/membership; MEDIA không nhận userId từ UI để xác định owner.

## API thử nghiệm local (mặc định không đăng ký routes)

Application chỉ gọi extension `configureMediaFoundation(config)` sau khi cài JWT chung.
Chỉ bật khi `MEDIA_DEV_ENABLED=true` và `APP_ENV=dev` hoặc `test`. Bật trong môi trường khác làm startup fail.
Không đổi AppConfig/JwtConfig hoặc cơ chế cấp token. Token phải do AUTH/test harness cung cấp.

```powershell
cd backend
$env:APP_ENV = 'dev'
$env:MEDIA_DEV_ENABLED = 'true'
# Export JWT_SECRET/issuer/audience qua cách cấu hình an toàn đã thống nhất với AUTH.
# Không đặt hoặc in token/secret thật trong source/log/báo cáo.
.\gradlew.bat run
```

Local storage mặc định `backend/storage/media-dev/` khi chạy Gradle từ backend; đã nằm trong ignore hiện có. Không expose storageKey/path, không ánh xạ user filename thành path. Catalog metadata **RAM**, object ID Long dương random trong instance, không phải ID DB bền. Restart làm mất catalog; file còn trên disk và được tính vào quota, endpoint sẽ từ chối ID cũ. Không tự xóa file cũ/startup để tránh xóa dữ liệu người dùng. Người vận hành chỉ dọn thư mục dev chuyên dụng đã xác minh sau khi không cần fixture; chưa có retention/orphan cleanup tự động. Không chạy nhiều instance cùng storage root.

| API | Input | Thành công | Quyền |
|---|---|---|---|
| POST /media/upload | Bearer JWT; multipart đúng một part `file` có filename/Content-Type; không field khác | 201 MediaObject READY | owner lấy subject JWT; không nhận ownerId/userId/conversationId/mediaRef |
| GET /media/{id} | JWT + positive Long id | 200 MediaObject | owner-only |
| GET /media/{id}/download | JWT + id | 200 bytes; attachment + nosniff + no-store | owner-only; user khác/ID lạ đều 404 |

Ví dụ response shape (minh họa, ID/time do server sinh):

```json
{
  "id": 123,
  "ownerId": 7,
  "fileName": "hello.txt",
  "mimeType": "text/plain",
  "byteSize": 9,
  "uploadState": "READY",
  "createdAt": "2026-11-12T00:00:00Z",
  "accessScope": "OWNER_ONLY_DEV"
}
```

READY chỉ nghĩa stream đã ghi xong + validation đạt trong local storage. Không nghĩa đã kiểm conversation, attach Message, broadcast hoặc production malware scanning.
Không trả URL công khai hoặc storage key. Filename chỉ dùng làm display metadata; download dùng tên server sinh.

Lỗi theo `ApiErrorResponse` chung: 400 VALIDATION_ERROR; 401 UNAUTHORIZED (JWT chung); 404 NOT_FOUND; 413 MEDIA_TOO_LARGE; 429 RATE_LIMITED; 408 UPLOAD_TIMEOUT; 503 STORAGE_UNAVAILABLE/MEDIA_CAPACITY. Không trả exception/path nội bộ. Parser/transport IOException có thể vào STORAGE_UNAVAILABLE; chưa phân loại mọi lỗi multipart thấp tầng.

## Giới hạn tài nguyên và validation

- 10 MiB/file; đếm byte thực tế từng buffer 8 KiB, không tin metadata client. Toàn HTTP request upload có giới hạn `maxBytes + 64 KiB` tính cả multipart headers, preamble, parts và epilogue; bridge streaming đếm cả request kể cả khi thiếu Content-Length. Boundary phải là ASCII hiển thị dài tối đa70 ký tự để closing-delimiter detector luôn có hiệu lực. Nếu vượt ngưỡng, route hủy body, không publish object và trả413.
- Body phải có multipart closing boundary hợp lệ trước khi metadata được publish; EOF giữa part hoặc thiếu delimiter bị từ chối. Nhánh lỗi/hủy hủy source channel, không gọi `part.dispose()` để drain đồng bộ phần chưa tiêu thụ. Upload semaphore chỉ được trả sau khi request-body pump kết thúc. Cleanup file staging bị lỗi giữ reservation quota; lần khởi động tiếp theo tính lại quota từ file còn trên dev disk.
- 100 MiB disk quota trong một storage instance, tính cả file tồn tại lúc khởi tạo; catalog tối đa1000 object; hai upload đồng thời; upload timeout60s. Đây là giá trị dev, không phải quota nhóm đã chốt.
- Chỉ JPEG(.jpg/.jpeg), PNG, MP4, PDF, TXT UTF-8. Sai/thiếu MIME, extension khác MIME, signature không khớp hoặc tệp rỗng bị từ chối.
- JPEG/PNG/PDF/MP4 kiểm magic/brand; **không phải full codec/container decoder, antivirus, sandbox hoặc chứng minh file phát/mở được**. Text UTF-8 kiểm toàn stream và từ chối control chars ngoài CR/LF/TAB.
- Filename NFC, tối đa180 ký tự, không slash/backslash/drive/control/path traversal. Storage key UUID nội bộ; NOFOLLOW_LINKS; user không chọn key/path.
- File staging chỉ publish metadata sau khi đọc hết một multipart request có boundary đóng hợp lệ. Sai MIME, quá file/request size hoặc quota, extra part, cancel, timeout, body thiếu delimiter hoặc lỗi trước publish sẽ dọn staging. Nếu staging deletion thất bại, quota được giữ bảo thủ và orphan vẫn chiếm dung lượng; dev storage reconcile khi khởi động bằng cách quét file trên disk. Cancel sau publish có thể đã lưu object dù client không nhận response: chưa upload idempotency/reconciliation.
- Request-body bridge hiện dùng `PipelineRequest.setReceiveChannel` được đánh dấu `InternalAPI` của Ktor 3.4.3 để đặt bộ đếm trước multipart parser. Không nâng Ktor nếu chưa kiểm tra lại hook này và chạy Netty regression.
- Root dev storage là cấu hình tin cậy của server; chưa bảo vệ trước người có quyền sửa filesystem trên máy server. Không multi-instance, per-user quota hoặc background cleanup.

## Contract production đề xuất — BLOCKED

MediaObject bền cần ownerId, conversationId có kiểm quyền (avatar cần purpose riêng), mimeType/byteSize/storageKey/uploadState, timestamps và metadata type-specific. Chỉ trả mediaRef sau READY; dùng cùng Long ID với contract hiện tại.
CHAT cần membership active + block rule + media.owner/conversation/READY khi attach. Download phải kiểm quyền hiện tại, không dựa riêng conversationId query hay chỉ owner nếu muốn chia sẻ trong chat.
Cần chốt một file/message hay message_attachments, caption, duration/dimensions/thumbnail, forward/delete/retention, upload retry/idempotency và thứ tự migration. Không tạo migration/media FK khi chưa được duyệt.

## CALLS D01–D02 (chỉ thiết kế)

```text
Caller -- shared JWT --> Ktor signaling guard -- validate participants/membership --> Callee
Caller <-------------------- offer / answer / ICE ----------------------------> Callee
Caller <================ WebRTC media via ICE/STUN/TURN =====================> Callee
```

Giữ namespace dự thảo call.invite/call.answer/call.ice-candidate/call.end. Cần nhóm chốt endpoint chung/riêng, CallSession ID/states, caller/callee, reject/busy/timeout/revoke, multi-device, push incoming call và STUN/TURN. Chưa triển khai FCM/WebRTC hoặc protocol riêng.

## Kiểm chứng và AI disclosure

Lệnh chuẩn: `android-app/.\gradlew.bat assembleDebug testDebugUnitTest`; `backend/.\gradlew.bat test`.
MEDIA Android tests gồm state selection/access error, progress/reopen/size/cancel/retry, known/unknown-size partial reads, EOF, empty input, IOException và response size mismatch. Backend test host kiểm multipart, JWT, ACL, MIME, traversal, file/request size, quota và storage failure; test socket loopback chạy Ktor Netty thật cho chunked epilogue vượt cap, MIME reject trên part chưa hoàn thành, timeout, disconnect và slot/staging cleanup. Storage tests inject delete failure, kiểm disk bytes/quota/startup reconciliation và hai write đồng thời.
Toàn bộ test dùng temporary storage và test JWT; không production. Test progress Android dùng service double, không phải HTTP adapter. **Chưa Android HTTP/JWT upload, chưa thiết bị thật, chưa PostgreSQL/CHAT e2e**. Netty loopback không chứng minh hành vi của reverse proxy/deployment production.

Phần MEDIA foundation (source Android/backend, tests và tài liệu này) được xây dựng với hỗ trợ Codex trong phiên task MEDIA D01–D05. Người review/merge cần đọc, hiểu và giải thích code; không gán tác giả commit hay tạo lịch sử thay thành viên. Không sao chép module từ reference repositories.

Nguồn API chính thức đã đối chiếu: [Android SAF](https://developer.android.com/training/data-storage/shared/documents-files), [Ktor multipart streaming](https://ktor.io/docs/server-requests.html), [Ktor test host](https://ktor.io/docs/server-testing.html).

### Kết quả chạy sau bản sửa MR-01–MR-04

- Android `assembleDebug testDebugUnitTest --rerun-tasks`: PASS; XML xác nhận 14 tests, 0 failure/error/skipped (9 MEDIA, 4 CHAT/GROUPS, 1 example).
- Backend `test --rerun-tasks`: PASS; XML xác nhận 30 tests, 0 failure/error/skipped (18 MediaRoutes test-host, 4 MediaNetty loopback, 5 storage/validation, 3 CHAT cũ).
- Client Ktor trong test host gửi multipart `file=hello.txt`, `Content-Type=text/plain`, 9 byte UTF-8 `Xin chào` bằng JWT test: POST trả201, object Long dương, owner7, READY/OWNER_ONLY_DEV; metadata200 và download200 có bytes bằng tuyệt đối input. Không có storageKey trong JSON.
- Cùng ID với JWT user8, kể cả thêm conversationId101 query: metadata/download404. Không JWT: upload/metadata/download401. MIME giả/traversal/extra conversation field400, quá file limit413, quota/storage503, concurrent gate429.
- Regression MR-01: unknown-length file hợp lệ vẫn upload201; unknown-length epilogue và Content-Length lớn hơn request cap đều bị413; Netty chunked epilogue vượt cap bị413 và không có staging/object.
- Regression MR-02: Netty socket với MIME sai trả400 trước khi client gửi boundary; upload chưa hoàn chỉnh timeout trả408; client disconnect mid-part không trả READY, dọn staging và trả slot. Kết quả trên local Netty loopback/test host; không test reverse proxy hoặc deployment.
- Regression MR-03: adapter đọc1/5 byte với size biết trước và adapter đọc prefix khi size không biết đều UPLOAD_FAILED; known-size exact read, unknown-size EOF mới được nhận; empty/IOException/mismatch đều thất bại.
- Regression MR-04: injected delete failure giữ 7 byte disk và quota accounting7; upload kế tiếp bị quota từ chối, restart scan vẫn thấy 7 byte; cleanup thành công trả quota; hai write đồng thời với quota7 chỉ một file4 byte được lưu.
- Trong quá trình triển khai, compile backend ban đầu lỗi import API readAvailable/CharsetDecoder và một lần regression test phát hiện stream download đóng quá sớm. Đã sửa; kết quả trên là suite chạy sau các sửa lỗi đó, không che kết quả lỗi ban đầu.
- Chưa chạy UI/SAF/content provider/video playback trên thiết bị, chưa kiểm thử reverse proxy/deployment, chưa Android HTTP adapter, chưa PostgreSQL/migration hay CHAT e2e.
