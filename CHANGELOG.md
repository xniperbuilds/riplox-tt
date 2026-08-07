# Changelog

All notable changes to Riplox TT are documented here.
Versioning: **MAJOR.MINOR.PATCH** — new features bump MINOR, fixes bump PATCH.

## [1.1.4] — 2026-07-26

Everything in 1.1.3 (below), plus update notices.

### Added
- **The app tells you when a new version is ready** (Play installs). This uses Play's own in-app update flow: the update downloads in the background while you keep using the app, then a card offers to restart and finish it — and if the update is marked urgent, or your version is more than a week old, Play shows its full-screen update prompt instead. The app never downloads or installs anything by itself.

## [1.1.3] — 2026-07-26

Downloads work again, plus an optional login.

### Fixed
- **Downloads that never started.** TT only hands a video's data to a request that looks like a desktop browser — a mobile request gets an "open in the app" page with no video in it — and the bundled download engine can't imitate a browser on Android. Riplox TT now reads the link itself with a desktop user agent (falling back to the phone's own browser engine, then to the old engine), so downloads start within a couple of seconds instead of sitting at 0% forever.
- **Quality options actually apply now.** TT videos are vertical, so the old height-based filter never matched anything and every download silently fell back to "best". Selection is now based on the video's real width (1080p / 720p / 480p).
- **Big downloads survive a dropped connection.** A transfer that breaks part-way resumes from where it stopped, including across automatic retries, instead of starting over.

### Added
- **Connect TikTok (optional)** — log in once for private, region-locked or age-restricted videos. Public videos still need no login. You sign in on TikTok's own page; the app never sees or stores your password, the session stays on the phone only, and it's excluded from cloud backup and device transfer. A "Use my login for downloads" switch turns it off again without logging out, and a failed session automatically retries as a guest.

### Changed
- MP3 extraction now runs on the downloaded file, and the redundant preview lookup before each download was removed — one less thing to fail, and a faster start.
- Ad unit IDs moved into the build configuration: test ads and real ads can no longer be mixed up by hand.

## [1.1.2] — 2026-07-25

Reliability release — everything from 1.1.1 plus:

### Fixed
- Downloads no longer stall at "finishing": thumbnail/metadata embedding was removed from the download pipeline (that post-processing step could hang on Android, leaving a download at 100% forever). Files still save exactly as before — only cover art and file tags are gone.
- Links that failed with "no video formats" / "unable to extract" on a fresh install now recover on their own: the first such failure updates the download engine and retries automatically, instead of showing a failed download.

### Changed
- The daily engine update now follows the nightly channel, so extractor fixes arrive as soon as they're published.
- Clearer message when the service is rate-limiting a request (wait a minute and retry), and a share-sheet hint if a download doesn't appear to start.

## [1.1.1] — 2026-07-17 A focused, TikTok-only companion to [Riplox](https://github.com/xniperbuilds/riplox).

### Highlights
- No-watermark TikTok video downloads — paste a link or share from the app
- Auto-paste: detects a TikTok link on your clipboard and fills it in
- Quality selector — Best / 1080p / 720p / 480p, or **MP3** audio extraction (choice is remembered)
- Zero-tap share target — share from the TikTok app and the download starts on its own
- Rock-solid background downloads: live progress notification (cancel anytime), survives app-close & reboot, auto-retry with backoff, and a stall-watchdog that recovers stuck downloads on its own
- One-time **"Fix background downloads"** setup guide — stops aggressive phones (XOS/HiOS/MIUI etc.) from freezing downloads in the background
- Clear finishing states — "Finishing — merging & saving…" and live "Saving to gallery… %" so big downloads never look stuck
- Failed downloads show **Copy link** + **Retry** — inline on the home screen and as a notification action
- Recent list — tap to play, copy link, download again, or delete
- Saves to your gallery: `Movies/RiploxTT` (video), `Music/RiploxTT` (audio), `Pictures/RiploxTT` (photo posts)
- Self-updating engine so links keep working
- AMOLED-black Material 3 UI — no account, no login
