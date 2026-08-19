# StreamBrowser

Trình duyệt Android chuyên dụng để **phát hiện, phát và tải các luồng video trực tuyến** (HLS/DASH/MP4), tích hợp sẵn bộ công cụ dành cho nhà phát triển (DevTools). Dự án viết bằng Kotlin, dùng WebView + ExoPlayer (Media3).

## Tính năng

- **Phát hiện stream đa tầng**
  - Chặn request ở tầng WebView (`shouldInterceptRequest`)
  - Hook XHR/fetch/WebSocket/MediaSource trong JavaScript
  - Hook riêng cho hls.js, dash.js, videojs, JWPlayer, ArtPlayer, Plyr
  - Quét DOM, Shadow DOM, iframe cùng nguồn, SPA
  - Parse HLS/DASH manifest, lấy chất lượng/bandwidth/codec
  - Bắt phụ đề `.vtt/.srt/.ass`
- **Tải xuống**
  - HLS `.m3u8` đa luồng, ghép segment
  - Hỗ trợ **AES-128-CBC** (tự lấy key qua Referer/Cookie)
  - **Remux MPEG-TS → MP4** bằng `MediaExtractor`/`MediaMuxer` (không cần FFmpeg), tự fallback `.ts` khi codec không hỗ trợ
  - Chạy bằng **foreground service** kèm notification tiến trình (%, trạng thái, nút Dừng)
  - fMP4/CMAF và byte-range (`#EXT-X-BYTERANGE`)
- **Phát lại** bằng ExoPlayer (HLS, DASH, progressive) với Referer/Origin, Picture-in-Picture
- **DevTools overlay**: network log, request/response body, WebSocket, JWT/token, HAR/cURL/Cookie export, element picker, HTML export, chặn request
- **Anti-bot fingerprint**: giả mạo `navigator` nhất quán với User-Agent, che `Function.prototype.toString`, nhiễu canvas, xóa dấu vết automation
- Hỗ trợ nhiều tab, bookmark/lịch sử (Room + Hilt), desktop mode, incognito, proxy

## Yêu cầu

- Android Studio (AGP 8.2.x), JDK 17
- `compileSdk/targetSdk = 36`, `minSdk = 24`

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

APK debug ra ở `app/build/outputs/apk/debug/`.

## Kiểm thử

Unit test (JVM, không cần thiết bị):
- `HlsPlaylistResolverTest` — master/media playlist, AES-128, IV tường minh/ngầm, byterange
- `StreamItemTest` — phân loại stream và giảm false-positive
- `JwtDecoderTest`, `SnapshotPrivacyTest`

## Cấu trúc

```
app/src/main/java/com/aho/streambrowser/
├─ detector/        # StreamDetector, WebView client, JS bridge
├─ feature/
│  ├─ downloader/hls/  # HlsEngine, HlsDownloadService, resolver, AES, remuxer
│  └─ devtools/token/
├─ data/            # Room (bookmark/history) + repository
├─ di/              # Hilt module
├─ model/           # StreamItem, NetworkRequest, TabModel
├─ ui/              # Main/Player activity, overlay, adapter
├─ util/            # M3U8, HAR/Cookie/Curl export, JWT, blocker...
└─ viewmodel/
app/src/main/assets/hook.js   # JS tiêm vào WebView
```

## Lưu ý pháp lý

Chỉ sử dụng để tải những nội dung bạn **sở hữu hoặc có quyền tải**. Việc tải/tái phân phối nội dung có bản quyền mà không được phép có thể vi phạm pháp luật tại quốc gia của bạn. Dự án không kèm cơ chế vượt DRM (Widevine/EME).
