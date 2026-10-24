# just downloader

Android app that saves any video to your phone — paste a link and download, or open any page in the built‑in browser, play the video and tap the **⬇ Download** button that appears on top of it.

- Paste a link → download (YouTube, Instagram, TikTok, Facebook, X, Vimeo, Reddit, Bilibili and [many more](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md))
- Built‑in browser: a **Download** button appears on videos the page plays (works on many sites the link method can't handle)
- Saves to `Movies/VideoRef` — visible in your gallery and editing apps
- Keeps downloading with the screen off (progress notification)
- Wi‑Fi‑only option, max resolution, "compatible" mode (H.264 + AAC for editing apps)
- Light / dark theme, 10 languages (English, 한국어, 简体中文, 繁體中文, 日本語, Español, Deutsch, Français, Português (Brasil), Русский)
- No account, no tracking. The app sends nothing to the developer unless you press **Report a problem** (opens your mail app).

## Download

**[Latest release (APK)](https://github.com/gkstmdahr/just-downloader/releases/latest)** — Android 8.0 or newer, 64‑bit ARM phones.

Install: open the APK on your phone and allow "install unknown apps" for your browser / file app when asked.

## Two editions

| | This page (full) | Google Play |
|---|---|---|
| Package | `net.fourdir.justdownloader.full` | `net.fourdir.justdownloader` |
| YouTube | ✅ | ✗ (Play policy) |
| Download engine auto‑update (every 7 days) | ✅ | ✗ — updated with the app |
| Donate button | ✅ | ✗ |

Both can be installed side by side.

## Problems

Settings → Help → **Report a problem** fills in a mail to support@4dir.net with the app log attached. Or open an issue here.

## Support

If the app is useful to you: [ko-fi.com/4dirofficial](https://ko-fi.com/4dirofficial)

## Build

JDK 17 + Android SDK 36.

```
./gradlew assembleSiteDebug      # full edition
./gradlew assemblePlayDebug      # Play edition
```

## License

GPL‑3.0 (see `LICENSE`). Uses
[yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense),
[youtubedl-android](https://github.com/yausername/youtubedl-android) (GPL‑3.0),
FFmpeg (LGPL/GPL), Python, QuickJS (MIT), AndroidX and Material Components (Apache‑2.0).

You are responsible for how you use downloaded videos.

---

# just downloader (한국어)

모든 영상을 폰에 모으는 안드로이드 앱입니다. 영상 주소를 넣고 받거나, 앱 안 브라우저로 페이지를 열어 영상을 재생하면 영상 위에 뜨는 **⬇ 받기** 버튼으로 받습니다.

- 주소 넣고 받기 (유튜브·인스타·틱톡·페이스북·X·비메오·레딧·빌리빌리 등)
- 앱 안 브라우저: 재생 중인 영상 위에 받기 버튼 — 주소로 안 되는 사이트도 많이 됩니다
- `동영상/VideoRef` 에 저장 — 갤러리·편집 앱에서 바로 보임
- 화면을 꺼도 계속 받음, 와이파이에서만 받기, 최대 화질, 호환 우선(편집 앱용 H.264+AAC)
- 밝게/어둡게, 10개 언어. 계정·추적 없음

**[최신 버전 받기 (APK)](https://github.com/gkstmdahr/just-downloader/releases/latest)** — 안드로이드 8.0 이상, 64비트 ARM 폰.

이 페이지 판(전체 기능)은 유튜브 받기와 받기 프로그램 자동 업데이트가 됩니다. 구글 플레이판은 플레이 정책 때문에 둘 다 빠져 있습니다. 두 판은 함께 설치할 수 있습니다.

문제가 있으면 설정 → 도움 → **오류 보고** (support@4dir.net, 기록 파일 자동 첨부). 후원: [ko-fi.com/4dirofficial](https://ko-fi.com/4dirofficial)

라이선스 GPL‑3.0. 받은 영상의 사용 책임은 사용자에게 있습니다.
