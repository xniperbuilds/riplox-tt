<div align="center">

<img src="docs/assets/icon.png" width="120" alt="Riplox TT icon" />

# Riplox TT

**Fast, clean TikTok video downloader for Android.**

Paste a link or share from the app — the no-watermark video lands straight in your gallery. Public videos need no account at all; an optional login is there only for private, region-locked or age-restricted ones.

[![Release](https://img.shields.io/github/v/release/xniperbuilds/riplox-tt?label=Download&color=2BE9E0)](https://github.com/xniperbuilds/riplox-tt/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/xniperbuilds/riplox-tt/total?color=2BE9E0)](https://github.com/xniperbuilds/riplox-tt/releases)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)

<img src="docs/assets/banner.png" width="760" alt="Riplox TT banner" />

</div>

---

## Screenshots

| Home | Downloading | Recent | Failed → retry |
|:---:|:---:|:---:|:---:|
| ![Home](docs/screens/home.png) | ![Downloading](docs/screens/downloading.png) | ![Recent](docs/screens/recent.png) | ![Failed](docs/screens/failed.png) |

## Features

- ⬇️ **No-watermark downloads** — paste a TikTok link, or share one straight from the app
- 📋 **Auto-paste** — copy a link, open Riplox TT, and it fills itself in
- 🎚️ **Pick your quality** — Best / 1080p / 720p / 480p, or extract **MP3** audio. Your choice is remembered
- ⚡ **Zero-tap share** — share from the TikTok app and the download just starts
- 🔔 **Live notification** — real progress, cancel anytime; downloads survive app-close & reboot with auto-retry
- ♻️ **Failed? One tap** — every failure gives you **Copy link** and **Retry**, right where you are
- 👀 **Watch a shared download** — sharing a link opens a small sheet with the thumbnail, title, live progress and **Cancel**, so you never have to guess whether it started
- 📥 **Paste several links at once** — they all queue, not just the first
- 🖼️ **Photo & slideshow posts** — a carousel saves every image, into `Pictures/RiploxTT`
- 🕘 **Recent list** — search it, tap to play, copy link, download again, delete one or clear them all
- 📁 **Straight to your gallery** — video in `Movies/RiploxTT`, audio in `Music/RiploxTT`
- 🔓 **No account needed** — public videos download straight away. **Connect TikTok** is optional, for private / region-locked / age-restricted videos, and can be switched off again without logging out
- 🎨 **Clean AMOLED-black UI** — Material 3, one calm screen, no clutter
- 🔥 **Daily check-in streak** — tap **Check in** each day and accent colours unlock at 3, 7, 14 and 30 days; a 7-day streak pays a **whole ad-free day**
- 🎁 **Two hours with no ads** — watch one short ad and the app goes quiet, banner and full screen both. Watch again to add more, up to a day. Nothing that was free has moved behind it

## Install

1. Download the latest APK from [**Releases**](https://github.com/xniperbuilds/riplox-tt/releases/latest)
2. Open it — allow *"Install from unknown sources"* if Android asks
3. Open Riplox TT, paste a TikTok link, tap **Download**

> Android 8.0 (Oreo) or newer. Works on both 64-bit and 32-bit phones.

## How it works

Riplox TT is a native Jetpack Compose app that does **one thing well**: grab a TikTok video (or its audio) cleanly and quickly, with a background queue that doesn't give up if your connection hiccups.

It reads a video's data itself, using a desktop user agent, and falls back to the phone's own WebView if TikTok asks for a JS challenge. That matters: TikTok only serves video data to a request that looks like a desktop browser, and [yt-dlp](https://github.com/yt-dlp/yt-dlp) on Android cannot imitate one (the bundled `youtubedl-android` ships without `curl_cffi`, so impersonation is unavailable — the same wall [yt-dlp#15653](https://github.com/yt-dlp/yt-dlp/issues/15653) describes). yt-dlp + FFmpeg are still bundled and still used — as a fallback extractor and for MP3 conversion — and the engine updates itself quietly so links keep working.

From **1.1.5** the first step — turning a link into the video's addresses — runs on a small XniperBuilds lookup service instead of on the phone, because TikTok serves the clean rendition only to a request the phone is not allowed to make (browser JavaScript may not set `sec-fetch-mode: navigate`). Measured over five fresh installs, the on-device routes returned the clean video once; the other four took about 55 seconds each and returned the watermarked one. Only the **link** is sent, the service stores and logs nothing, the video itself is downloaded by your phone directly from TikTok, and if the service cannot be reached the app falls back to the on-device routes described above. See the [privacy policy](https://xniperbuilds.com/riplox-tt/privacy/).

Transfers resume from where they stopped, so a dropped connection on a big video costs you nothing.

## FAQ

**Does it add a watermark?**
No — Riplox TT saves the clean video without the TikTok watermark.

**A video won't download / says "blocked"?**
TikTok occasionally rate-limits a request. Tap **Retry**, or **Copy link** and paste the *full* video link (from the app's Share → Copy Link) instead of a short one. Private or deleted videos can't be downloaded.

**Where do my files go?**
Your gallery — `Movies/RiploxTT` for video, `Music/RiploxTT` for MP3. Photo posts go to `Pictures/RiploxTT`.

**Is it safe?**
It's open source — read the code. No analytics and no tracking of what you download. Two things are worth stating plainly: the released build **does include the AdMob SDK** (a banner, plus an interstitial after every second completed download), and **if** you use the optional Connect TikTok login, the session cookies stay in the app's private storage on your phone — excluded from cloud backup and device transfer, never sent anywhere, and your password is typed on TikTok's own page, never seen by the app.

**Do I have to log in?**
No. Public videos download without an account. Connect TikTok exists only for private, region-locked or age-restricted videos, and the "Use my login for downloads" switch turns it off again without logging you out.

**Only TikTok?**
Yes, by design. Riplox TT is TikTok-only. For 1000+ other sites, see [Riplox](https://github.com/xniperbuilds/riplox).

## Building from source

```bash
git clone https://github.com/xniperbuilds/riplox-tt.git
cd riplox-tt
./gradlew assembleDebug
```

Android Studio (JDK 17+) recommended. Release builds are signed with the maintainer's key; your local builds fall back to a debug signature automatically.

## Credits

- [yt-dlp](https://github.com/yt-dlp/yt-dlp) · [youtubedl-android](https://github.com/yausername/youtubedl-android) · [FFmpeg](https://ffmpeg.org/)
- [Coil](https://coil-kt.github.io/coil/) · Jetpack Compose / Material 3

## License

[GPL-3.0](LICENSE) — free forever. If you distribute a modified version, it must stay open source.

## Disclaimer

Riplox TT is an independent app and is **not affiliated with, endorsed by, or connected to TikTok or ByteDance Ltd.** "TikTok" is a trademark of its respective owner and is used here only to describe compatibility. Please download only content you have the right to save, and respect creators' rights.

---

<div align="center">

**Riplox TT** is a [XniperBuilds](https://xniperbuilds.com) product.

⭐ Star the repo if Riplox TT saves you time — it helps more people find it.

</div>
