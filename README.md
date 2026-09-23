# BrowserLite

Trình duyệt web cho **Android 4.4 (KitKat)**, tối ưu cho **màn hình e-ink** và **máy rất yếu (256 MB RAM)**,
nhưng vẫn cố gắng mở được web hiện đại (HTTPS đời mới, CSS và JavaScript mới).

**File APK cài được ngay:** [`dist/BrowserLite-1.0.apk`](dist/BrowserLite-1.0.apk) (khoảng 2 MB, minSdk 19)

## Cài đặt

1. Chép `BrowserLite-1.0.apk` vào máy.
2. Vào **Cài đặt → Bảo mật → Nguồn không xác định** và bật lên.
3. Mở file APK để cài. Ứng dụng có tên **BrowserLite**.

APK được ký bằng khoá dùng chung trong `keystore/` (v1 + v2, Android 4.4 đọc được v1), nên bản bạn tự build
cài đè lên được. Nếu phát hành rộng rãi, hãy thay bằng khoá riêng của bạn.

## Tính năng

### Cho màn hình e-ink
- **Lật trang** thay vì cuộn: phím âm lượng, phím Page Up/Down, nút ▲▼ ở thanh dưới; tuỳ chọn *vuốt để lật trang*
  và *chạm mép màn hình để lật trang*. Có phần chồng giữa hai trang (mặc định 10%) để không mất dòng.
- Bộ đếm trang (ví dụ `3/12`) ở thanh dưới.
- Tắt mọi hiệu ứng động của trang web: animation/transition kết thúc tức thì (vẫn giữ trạng thái cuối nên nội dung
  không bị ẩn), không cuộn mượt, không nháy khi chạm, GIF động bị "đóng băng" ở khung đầu.
- **Chữ tương phản cao**: chữ đen trên nền trắng, bỏ nền màu và bóng đổ (tắt được). Tuỳ chọn *chữ đậm hơn*.
- **Ảnh đen trắng** tăng tương phản nhẹ, thu nhỏ theo độ phân giải màn hình.
- **Chế độ đọc** (nút ≡): tách phần nội dung bài viết, trình bày kiểu sách, ước tính thời gian đọc.
- *Làm mới màn hình e-ink* (nháy đen/trắng để xoá bóng mờ), có thể tự động sau N lần lật trang.
- Giao diện ứng dụng thuần đen trắng, không animation, nút to, thanh tiến trình chỉ vẽ lại theo bước lớn.

### Cho web hiện đại trên engine cũ
Android 4.4 dùng WebView Chromium 30 (4.4.0–4.4.2) hoặc 33 (4.4.3–4.4.4) và kho chứng chỉ từ 2013. BrowserLite
bù lại bằng nhiều lớp:
- **Engine mạng riêng**: OkHttp + BoringSSL (Conscrypt) với **TLS 1.3**, HTTP/2 và **bộ chứng chỉ gốc Mozilla mới**
  (có ISRG Root X1 của Let's Encrypt), tự tải chứng chỉ trung gian còn thiếu (AIA). Nhờ vậy mở được rất nhiều trang
  HTTPS mà Android 4.4 báo lỗi chứng chỉ hoặc không bắt tay được TLS. Cookie dùng chung với WebView.
- **Chuyển đổi CSS mới → CSS cũ** ngay khi tải: biến CSS `var()`, `@layer`, CSS nesting, `:is()/:where()`,
  `:not(a, b)`, `:focus-visible`, màu `oklch()/oklab()/color-mix()/rgb(r g b / a)/#rrggbbaa`, `clamp()/min()/max()`,
  đơn vị `dvh/svh`, cú pháp media `(width >= 40rem)`, thuộc tính logic (`margin-inline`, `inset`...), `place-items`,
  `translate/rotate/scale`, thêm tiền tố `-webkit-` cho transform/animation/keyframes...
- **Polyfill ES5** được chèn trước mọi script của trang: Promise, fetch, Map/Set/WeakMap, Symbol, URL/URLSearchParams,
  Object.assign/entries, Array.from/find/includes/flat, String.padStart/replaceAll, TextEncoder, AbortController,
  IntersectionObserver, ResizeObserver, PointerEvent, `addEventListener({once, passive})`, FormData.get,
  Element.closest/append/remove, `scrollTo({top})`, document.fonts, Web Animations (kết thúc tức thì)...
- Sửa lỗi layout flexbox của Chromium cũ (`min-width: auto`), ảnh `srcset`/`<picture>`/lazy-load, `loading="lazy"`.
- Gỡ CSP/`integrity` cản trở, mở khoá phóng to trang (`user-scalable=no`).

### Tiết kiệm RAM (máy 256 MB)
- Chỉ **một WebView** sống; các tab khác chỉ lưu trạng thái, chuyển tab thì WebView cũ bị huỷ hẳn.
- Ảnh được thu nhỏ trước khi WebView giải mã (một ảnh 4000×3000 tốn 48 MB RAM nếu để nguyên).
- **Chặn quảng cáo & theo dõi**, ẩn khung quảng cáo trống, ẩn banner cookie.
- Video/Facebook/Twitter/TikTok/bản đồ nhúng chỉ tải khi chạm vào.
- Chặn `<link rel=prerender/prefetch>` và video tự phát.
- Theo dõi bộ nhớ hệ thống: tự giải phóng bộ nhớ đệm khi RAM thấp, cảnh báo khi một trang ăn quá nhiều RAM và gợi ý
  *Tải bản nhẹ* (tắt JavaScript cho trang đó). Khi ở nền mà máy thiếu RAM, WebView được "ngủ đông" và khôi phục khi quay lại.
- Kết xuất bằng phần mềm (ít RAM hơn GPU) tự động trên máy ≤ 400 MB.
- Menu *Thoát và giải phóng RAM* kết thúc hẳn tiến trình.

Đo trên emulator Android 4.4.2 (WebView Chromium 30), tổng RAM (PSS) của cả tiến trình kể cả engine hiển thị:

| Trang | RAM |
|---|---|
| Bài Wikipedia | ~46 MB |
| Trang chủ VnExpress (nhiều ảnh) | ~62 MB |
| Trang GitHub | ~64 MB |

Ở chế độ RAM thấp của Android 4.4 (`ro.config.low_ram=true`), lật liên tục 25 trang trên VnExpress (ảnh tải dần
theo trang) RAM vẫn ổn định quanh 63 MB và ứng dụng không bị hệ thống đóng.

### Tiện ích khác
Tab, dấu trang, lịch sử, gợi ý khi gõ địa chỉ, tìm trong trang, cỡ chữ, trang cho máy tính, JavaScript/chặn quảng
cáo bật tắt theo từng trang, tải file (qua engine mạng mới, hiện trong ứng dụng Tải xuống), tải lên file, chia sẻ,
đổi nhận dạng trình duyệt (mặc định, Chrome mới, máy tính, **Opera Mini** siêu nhẹ), giao diện tiếng Việt/English.
Mặc định tìm kiếm bằng DuckDuckGo bản HTML (chạy tốt không cần JavaScript); có Google, Bing, Cốc Cốc...

## Giới hạn (nói thẳng)

BrowserLite vẫn dùng engine hiển thị có sẵn của Android 4.4, vì không có engine hiện đại nào chạy được trên
Android 4.4 với 256 MB RAM (Chrome và Firefox mới cần Android 5+ và nhiều RAM hơn nhiều). Vì vậy:
- **Cú pháp JavaScript mới** (arrow function, `class`, `async/await`, `?.`...) không thể polyfill. Trang nào chỉ
  gửi mã JS hiện đại sẽ lỗi phần tương tác, nhưng nội dung dựng sẵn từ máy chủ vẫn hiện. Nhiều trang lớn tự gửi bản
  JS cũ cho trình duyệt cũ, và BrowserLite cố ý xưng đúng phiên bản engine để nhận bản đó.
- **CSS Grid**, Web Components, WebGL, WebRTC không có trong Chromium 30/33.
- Ứng dụng web nặng (Facebook bản đầy đủ, Google Docs, YouTube...) sẽ chậm hoặc không chạy; hãy dùng bản nhẹ
  (m.facebook.com, m.youtube.com) hoặc đổi nhận dạng sang Opera Mini.

Mẹo khi trang hiển thị kém: bấm **Chế độ đọc**; hoặc **Menu → Tải lại bản nhẹ**; hoặc tắt *JavaScript cho trang này*
(nhiều trang tin tức hiện đầy đủ hơn khi không chạy JS); hoặc đổi *Nhận dạng trình duyệt là* trong Cài đặt.

## Build

Cần JDK 17+ và Android SDK (platform 34).

```bash
./gradlew assembleRelease          # APK: app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest        # unit test cho bộ chuyển đổi CSS/HTML, URL, chặn quảng cáo
```

Script chèn vào trang nằm ở `app/src/main/js/` (ES5 thuần). Sau khi sửa, chạy `./tools/build-web.sh` (cần Node.js)
để sinh bản rút gọn vào `app/src/main/assets/web/`; script cũng kiểm tra đầu ra vẫn là ES5. GitHub Actions
(`.github/workflows/build.yml`) tự chạy test, build APK và đính kèm APK vào mỗi lần chạy.

## Cấu trúc mã

| Thành phần | Vai trò |
|---|---|
| `web/Interceptor` | Nhận mọi request của WebView: chặn quảng cáo, tải trang/ảnh/CSS qua engine mới, xử lý redirect/tải file |
| `net/NetEngine`, `net/TrustStore` | OkHttp + Conscrypt TLS 1.3, chứng chỉ gốc Mozilla, AIA, đồng bộ cookie với WebView |
| `net/HtmlRewriter` | Viết lại HTML dạng stream theo byte (mọi bảng mã): chèn polyfill, gỡ CSP, hoãn ảnh lazy |
| `net/CssCompat` | Chuyển CSS hiện đại sang cú pháp Chromium 30 hiểu được |
| `net/ImageOptimizer` | Thu nhỏ, chuyển xám, đóng băng GIF |
| `web/CertVerifier` | Kiểm lại chứng chỉ mà WebView cũ từ chối, bằng kho chứng chỉ mới |
| `js/polyfill.js`, `js/page.js`, `js/reader.js` | Polyfill, tiện ích trang (lật trang, ảnh lazy, flexbox...), chế độ đọc |
| `MainActivity` | Giao diện, tab, lật trang, quản lý bộ nhớ |
