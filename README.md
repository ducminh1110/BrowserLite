# BrowserLite

Trình duyệt web cho **Android 4.4 (KitKat)**, tối ưu cho **màn hình e-ink** và **máy rất yếu (256 MB RAM)**,
nhưng vẫn cố gắng mở được web hiện đại (HTTPS đời mới, CSS và JavaScript mới).

**File APK cài được ngay:** [`dist/BrowserLite-1.2.apk`](dist/BrowserLite-1.2.apk) (minSdk 19)

## Cài đặt

1. Chép `BrowserLite-1.2.apk` vào máy.
2. Vào **Cài đặt → Bảo mật → Nguồn không xác định** và bật lên.
3. Mở file APK để cài. Ứng dụng có tên **BrowserLite**.

APK được ký bằng khoá dùng chung trong `keystore/` (v1 + v2, Android 4.4 đọc được v1), nên bản bạn tự build
cài đè lên được. Nếu phát hành rộng rãi, hãy thay bằng khoá riêng của bạn.

## Tính năng

### Thanh trượt "Mức tối ưu" (Menu → Mức tối ưu, hoặc nhấn giữ nút ⇕ ở thanh dưới)
Một thanh trượt 6 nấc, từ trang gốc tới chỉ chữ. Dưới thanh trượt hiện rõ nấc đó bật/tắt những gì:

| Nấc | Làm gì |
|---|---|
| 0 · Gốc | Trang y như bản gốc: màu, hiệu ứng, font, ảnh đầy đủ. Chỉ giữ lớp tương thích (TLS mới, sửa CSS/JS) để trang hiện được trên engine cũ; ảnh khổng lồ vẫn được thu nhỏ để khỏi tràn RAM |
| 1 · Chặn quảng cáo | Như gốc nhưng bỏ quảng cáo, theo dõi, bảng hỏi cookie |
| 2 · Cân bằng | + ảnh thu nhỏ vừa màn hình, video/mạng xã hội nhúng chạm mới tải, tắt hiệu ứng động. Giữ màu |
| 3 · E-ink (mặc định) | + chữ tương phản cao, ảnh đen trắng |
| 4 · Siêu nhẹ | + ảnh nhỏ hơn, không tải font web, bỏ thanh dính |
| 5 · Chỉ chữ | Không ảnh, không JavaScript |

"Thông minh": áp dụng cho **mọi trang** hoặc **chỉ trang đang xem** (mỗi trang nhớ mức riêng); có gợi ý mức hợp với
RAM của máy; khi một trang ngốn quá nhiều RAM, thanh cảnh báo cho chuyển riêng trang đó sang *Siêu nhẹ + tắt
JavaScript* bằng một chạm (đổi lại được trong hộp thoại, nút *Bỏ tuỳ chỉnh trang*).

### Chế độ cuộn cho màn hình A2 (nút ⇕ ở thanh dưới)
Nhiều máy e-ink có chế độ làm mới nhanh A2 hiển thị chuyển động khá tốt. Bật chế độ cuộn thì: cuộn/vuốt bình thường
như điện thoại (tắt vuốt-lật-trang), giữ hiệu ứng động và GIF của trang, nút ▲▼ và phím âm lượng lướt mượt thay vì
nhảy trang, thanh tải trang chạy mịn. Bật/tắt có hiệu lực ngay trên trang đang mở, không cần tải lại.

### Chế độ video: xem YouTube
Trang YouTube thật cần trình duyệt mới hơn rất nhiều, nên BrowserLite tự dựng **giao diện YouTube rút gọn** (không
cần JavaScript, đen trắng, nút to) lấy dữ liệu từ API của YouTube: tìm kiếm, trang xem (mô tả, video liên quan),
kênh, danh sách phát, Shorts, "đã xem gần đây / đang xem dở". Gõ `youtube.com`, bấm link YouTube hay video YouTube
nhúng trong bài báo đều vào đây. Bấm **Xem** để mở trình phát riêng: tua ±10 giây, thanh tua, nhớ chỗ đang xem dở,
tải video (MP4) về máy.

Trình phát **tự chọn bộ giải mã** theo đúng những gì máy có (Cài đặt → *Bộ giải mã trên máy* liệt kê H.264 / HEVC /
VP9 / AV1 / AAC / MP3, phần cứng hay phần mềm):
1. bộ giải mã phần cứng của máy nếu có (nhẹ pin nhất);
2. H.264 phần mềm của máy;
3. **bộ giải mã tích hợp** (FFmpeg H.264/AAC/MP3 tối giản, 1,4 MB) cho máy bị cắt bộ giải mã, kể cả máy không có
   bộ giải mã nào. Tự vẽ hình (đen trắng cho e-ink: bỏ luôn bước chuyển màu), tự bỏ bớt khung khi CPU không theo kịp;
4. không bao giờ chọn HEVC/AV1, và VP9 phần mềm chỉ dùng khi không còn cách nào khác.
Nếu trình phát của máy báo lỗi giữa chừng, app tự chuyển sang bộ giải mã tích hợp ở đúng vị trí đang xem.

**Mặc định không giải mã tiếng** (đa số máy đọc sách không có loa): khi đó app dùng luồng *chỉ có hình* của YouTube
ở độ phân giải tự động theo CPU (chỉnh 144p–480p trong Cài đặt), đỡ cả RAM, CPU lẫn dữ liệu. Bật *Phát tiếng* trong Cài đặt → Tối ưu & video
nếu máy có loa/tai nghe; khi đó có thêm nút *Chỉ nghe*.

Trong lúc xem video, trên máy RAM thấp trang web phía sau được cho "ngủ" để nhường RAM cho bộ giải mã. Video trên các
trang khác (thẻ `<video>` có link MP4/HLS) cũng mở bằng trình phát này; mở file video từ trình quản lý file cũng được.

**Chống giật và lỗi 403 giữa chừng.** Trình phát không tải thẳng từ máy chủ YouTube mà đọc từ một **bộ đệm trên bộ
nhớ máy**: video được tải trước theo từng đoạn 1 MB (tới ~32 MB phía trước chỗ đang xem, tối đa 96 MB và không quá ¼
chỗ trống; đoạn đã xem xa phía sau bị xoá, thoát trình phát là xoá hết). Thanh tua có vạch nhạt cho biết đã tải trước
tới đâu. Nhờ vậy:
- mạng chập chờn không làm đứng hình: đang còn đệm thì vẫn chạy, đứt kết nối thì tự tải lại đúng đoạn đó (chờ dần 1–8
  giây, nhiều lần);
- khi máy chủ YouTube **đổi ý giữa chừng** (403: link hết hạn, bị thu hồi, hoặc kiểu link chỉ cho tải phần đầu), app
  tự xin link mới — thử các "client" YouTube khác trước — cho **đúng định dạng, đúng dung lượng** rồi tải tiếp từ đúng
  byte đó; trình phát không hề biết;
- trước khi phát, app thử tải một mẩu ở **giữa file** chứ không chỉ ở đầu, nên loại sớm những link chỉ xem được đoạn đầu.
Tải video YouTube về máy cũng đi qua cơ chế này, nên file tải về không bị cụt.

**Phụ đề** (nút ▭ trên thanh điều khiển): phụ đề viết tay và tự tạo của YouTube, **tự dịch sang ngôn ngữ của máy**
(tiếng Việt) khi video không có sẵn. Mặc định (Cài đặt → *Phụ đề*): theo ngôn ngữ của máy, tự dịch nếu cần; hoặc
ngôn ngữ gốc của video; hoặc tắt. Chữ đen trên nền trắng — rõ nhất trên e-ink — và chỉ vẽ lại khi đổi câu. Máy không
bật tiếng thì phụ đề thay cho tiếng.

**Xoay ngang** (nút xoay): lần lượt nằm ngang → nằm ngang lật ngược → dọc (máy đọc sách thường không có cảm biến xoay),
nhấn giữ để trở lại theo máy. App nhớ lựa chọn cho lần xem sau.

**Làm sạch bóng mờ e-ink khi xem video** (nút ◐, và tự động): cả màn hình nháy đen rồi trắng (~0,4 giây) để xoá
bóng mờ tích tụ ở chế độ A2. *Tự động* (mặc định): bộ giải mã tích hợp nhận ra lúc **chuyển cảnh** (so bản đồ độ sáng
8×8 của từng khung, gần như không tốn CPU) và nháy đúng lúc đó — hình đằng nào cũng thay nên ít thấy nhất — cách nhau
ít nhất 30 giây, và tối đa 2,5 phút một lần dù không có chuyển cảnh; với bộ giải mã của máy thì mỗi phút. Chọn được mỗi
30 giây / 1 / 2 / 5 phút hoặc tắt.

### Tối ưu cho ARM Cortex-A9 và tự tối ưu theo RAM còn trống
- Bộ giải mã tích hợp được biên dịch **ưu tiên tốc độ** cho Cortex-A9: FFmpeg `-O3`, mã lệnh ARM (không dùng
  Thumb), lập lịch lệnh theo Cortex-A9. Tập lệnh giữ ở ARMv7 + VFPv3-D16 nên chạy được cả trên A9 không có NEON
  (Tegra 2); trên máy có NEON, FFmpeg tự dùng các hàm NEON khi chạy. Giải mã nhiều luồng theo số nhân CPU (tối đa 4).
  Bộ lọc khối trên khung không-tham-chiếu luôn được bỏ (nhanh hơn ~10%, gần như không thấy); khi CPU vẫn không theo
  kịp thì tự bỏ khung không-tham-chiếu, và bật lại khi đã theo kịp. Chỉ bỏ những phần không khung nào dựa vào, nên
  lỗi không lan sang khung sau.
- **Tự tối ưu theo RAM còn trống** (bật sẵn, Cài đặt → Tối ưu & video): app đo RAM trống liên tục (mỗi 10 giây và
  trước mỗi lần mở trang), chia 3 mức:
  - *dư* (≥ 110 MB): đúng mức tối ưu đã chọn;
  - *thiếu*: video/mạng xã hội nhúng chạm mới tải, ngân sách ảnh giảm một nửa;
  - *gần cạn* (< 48 MB hoặc hệ thống báo thiếu RAM): bỏ thêm font web, quảng cáo, thanh dính, ảnh chuyển đen trắng
    và nhỏ hơn nữa, giải phóng bộ nhớ đệm trước khi mở trang mới.

  Video cũng theo RAM và CPU: độ phân giải *Tự động* (1 nhân → 240p, ≥ 2 nhân → 360p, gần cạn RAM → hạ một bậc),
  số luồng giải mã (1 luồng khi gần cạn RAM) và bộ đệm đọc trước (4 MB / 2 MB / 1 MB).

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

Đo khách quan trên emulator Android 4.4.2 (WebView Chromium 30) có **đúng 256 MB RAM vật lý** (kernel `mem=256M`,
MemTotal 237 MB, chế độ low-RAM của Android bật) và **CPU Cortex-A9** mô phỏng. Số đo là tổng RAM (PSS) của cả tiến
trình kể cả engine hiển thị, mỗi lần đo đều khởi động lại app, đọc sau 60 giây:

| Trang | RAM |
|---|---|
| Trang chủ VnExpress (nhiều ảnh) | ~74 MB |
| Bài Wikipedia "Hà Nội" (1,45 MB HTML) | ~61 MB |
| YouTube (chế độ video): tìm kiếm | ~34 MB |
| YouTube: trang xem video | ~33 MB |
| Đang phát video YouTube 240p bằng bộ giải mã tích hợp (không tiếng) | ~37 MB |

Khi phát video, khung hình do bộ giải mã tích hợp giải ra trên máy đã được so khớp với bản giải mã chuẩn của FFmpeg
trên máy tính: giống nhau (không lỗi khối). Trên emulator giả lập ARM (chậm hơn máy thật nhiều), video 240p/360p
vẫn giải mã theo kịp nhờ tự bỏ khung không-tham-chiếu, hiển thị khoảng 10-12 hình/giây.

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
- Ứng dụng web nặng (Facebook bản đầy đủ, Google Docs...) sẽ chậm hoặc không chạy; hãy dùng bản nhẹ (m.facebook.com)
  hoặc đổi nhận dạng sang Opera Mini. YouTube thì chạy qua *chế độ video* (giao diện riêng, không phải trang thật):
  không đăng nhập, không bình luận; video giới hạn độ tuổi hoặc cần đăng nhập không xem được. Luồng có tiếng của
  YouTube chỉ có 360p; khi tắt tiếng thì chọn được 144p–480p.
- Bộ giải mã tích hợp chỉ giải H.264 (mọi profile), AAC, MP3. Video chỉ có HEVC/VP9/AV1 thì cần bộ giải mã của máy.
- YouTube đôi khi **chặn theo địa chỉ IP** ("xác minh không phải bot", hay gặp ở mạng dùng chung, mạng công ty, máy
  chủ) hoặc đòi mã "PO token" cho mọi cách lấy link. Mã đó chỉ tạo được bằng JavaScript chống bot của YouTube trên
  trình duyệt đời mới, nên app không tự vượt được: khi đó hãy thử lại sau ít phút, đổi Wi-Fi ↔ 4G, hoặc đặt một máy
  chủ Invidious ở Cài đặt → *Máy chủ dự phòng* (máy chủ đó lấy video giúp từ mạng của nó).
- Dịch phụ đề do máy chủ YouTube làm; khi máy chủ đó bận (báo 429) app hiện phụ đề gốc thay vì để trống.

Mẹo khi trang hiển thị kém: bấm **Chế độ đọc**; hoặc **Menu → Tải lại bản nhẹ**; hoặc tắt *JavaScript cho trang này*
(nhiều trang tin tức hiện đầy đủ hơn khi không chạy JS); hoặc đổi *Nhận dạng trình duyệt là* trong Cài đặt.

## Build

Cần JDK 17+ và Android SDK (platform 34). Thư viện native (`app/src/main/jniLibs/`) đã được build sẵn và nằm trong
repo, nên build APK không cần NDK. Muốn build lại bộ giải mã tích hợp: cần Android NDK r25c (bản cuối còn hỗ trợ
Android 4.4) và mã nguồn FFmpeg 6.1.2:

```bash
NDK=/path/android-ndk-r25c FFMPEG_SRC=/path/ffmpeg-6.1.2 tools/build-ffmpeg.sh   # FFmpeg + trình phát
NDK=/path/android-ndk-r25c PLAYER_ONLY=1 tools/build-ffmpeg.sh                   # chỉ app/src/main/cpp/blplayer.c
```

```bash
./gradlew assembleRelease          # APK: app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest        # unit test: CSS/HTML, URL, chặn quảng cáo, YouTube, phụ đề, bộ đệm video
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
| `MainActivity` | Giao diện, tab, lật trang, chế độ cuộn, quản lý bộ nhớ |
| `Profile`, `LevelDialog`, `MemoryState` | Các mức tối ưu, thanh trượt, tự điều chỉnh theo RAM còn trống |
| `net/YouTube`, `web/YouTubePages` | API YouTube (InnerTube) và các trang YouTube rút gọn |
| `net/StreamPicker`, `video/MediaCaps` | Chọn luồng và bộ giải mã theo đúng những gì máy có |
| `video/VideoActivity`, `video/VideoProxy` | Trình phát (bộ giải mã của máy hoặc tích hợp, phụ đề, xoay, làm sạch bóng mờ), proxy 127.0.0.1 qua engine TLS mới |
| `net/ChunkCache`, `video/YouTubeLinks` | Bộ đệm tải trước trên bộ nhớ máy, tải lại khi đứt, tự thay link bị từ chối (403) |
| `net/Captions` | Danh sách phụ đề, tải và đọc các định dạng phụ đề của YouTube/Invidious, chọn/dịch theo ngôn ngữ |
| `cpp/blplayer.c` | Trình phát native: FFmpeg giải mã, vẽ thẳng vào Surface (đen trắng cho e-ink), đồng bộ, tua |

## Giấy phép thành phần

Bộ giải mã tích hợp dùng [FFmpeg](https://ffmpeg.org) 6.1.2 (LGPL 2.1 trở lên), build tối giản bằng
`tools/build-ffmpeg.sh` (chỉ các thành phần LGPL). FFmpeg nằm trong thư viện dùng chung riêng `libblffmpeg.so`,
có thể thay bằng bản tự build. Mã nguồn FFmpeg: <https://ffmpeg.org/releases/ffmpeg-6.1.2.tar.xz>.
