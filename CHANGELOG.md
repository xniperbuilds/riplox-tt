# Changelog

All notable changes to Riplox TT are documented here.
Versioning: **MAJOR.MINOR.PATCH** — new features bump MINOR, fixes bump PATCH.

## [1.3.1] — 2026-11-16

The home screen puts the link box first, and quiet downloads get their sound back.

### Changed
- **The link box is at the top.** The big centre logo is gone; the link box, Best quality | MP3 and a TikTok account button sit at the top of the screen.
- **Video is always Best quality.** The 1080p / 720p / 480p choices are removed.
- **The TikTok account moved into a sheet** behind the new button next to MP3 (a green dot shows when you are connected).
- **Download shows once there is a link**, and says "Download MP3" in MP3 mode.
- **Downloading · Downloaded · Failed tabs** under the link box. Downloading shows live progress with Cancel; Downloaded shows the newest five with "Show all", plus search and Delete all once there are five or more; the open tab follows your downloads.
- Downloaded rows show a video icon when the phone cannot draw a picture from the file, and photo posts are labelled "Photos".

### Fixed
- **Downloads could sound much quieter than in the TikTok app.** TikTok raises the volume while it plays, but the file itself does not carry that — saved videos ranged from loud to very quiet. Quiet videos and MP3s are now raised to a normal listening level when they are saved (only the sound is touched; the picture stays exactly as it was). Videos that are already loud are left alone.

## [1.3.0] — 2026-11-01

The share sheet shows you the video, the app keeps its download engine honest, and the app's own ads are rearranged around the sheet.

### Changed
- **The share sheet shows the video, large.** On phones with room for it, sharing a TikTok to Riplox TT now opens a taller sheet with a big preview of what is being saved, above the progress and the buttons. Smaller screens and landscape keep the compact sheet.
- **One ad on the share sheet, inside it.** The tall sheet carries a single medium-sized ad at its top, inside the panel, with a clear gap before the preview and the buttons — and the strip that used to sit under the buttons is gone there. The panel never changes height while it is open, so nothing moves under your finger.
- **A short welcome screen when you open the app.** Opening Riplox TT can now show an ad on a brief splash before the home screen — at most once an hour, never on the first two opens, never on the share sheet, and never while your ad-free time is running.

### Fixed
- **The download engine could get stuck on an old version for good.** Its update check trusted a stored version note and never looked at what was actually installed, so a note written while the files were not meant every later check answered "nothing to do". The version is now read by asking the engine itself, and a note that disagrees is discarded so the update really happens.
- **The engine could never repair itself after a failure.** Downloads started from the share sheet only refreshed the engine after one had *succeeded*, and a download waiting to be retried counted as "busy", which skipped the update too. A failure now triggers the refresh — that is exactly when a fresh engine helps.
- **The banner's retry interval now really stops at 60 seconds.** It was meant to back off to a minute between attempts and stopped at 40 seconds instead.

## [1.2.1] — 2026-09-24

### Changed
- **Check in with a short ad.** When an ad is ready, the check-in button says so — **Watch a short ad → check in** — and your streak moves on once the ad finishes. If no ad has loaded, or you are inside your ad-free time, **Check in** stays free, so a streak never breaks because an ad did not arrive.

### Fixed
- **Sharing a link while the app was closed could crash it on some phones.** The privacy (consent) check and the download sheet were both setting up the same screen at the same moment from two threads. The download itself still finished, but the sheet vanished with a "has stopped" message. Measured on one phone: 3 of 10 such shares crashed before the fix, 0 of 20 after.
- **A second shared link could be lost.** Sharing another TikTok link while the download sheet from the first one was still open did nothing — the new link was never queued and the sheet kept showing the old download. Every share now starts its own download and the sheet follows the newest one.

## [1.2.0] — 2026-09-08

A reason to come back, two hours without ads, and the full-screen ad that was barely ever showing.

### Added
- **Daily check-in streak.** Open Riplox TT, tap **Check in**, and your streak grows. Keep it going and accent colours unlock along the way — **Ocean** at 3 days, **Neon** at 7, **Ember** at 14, **Aurora** at 30 — and a 7-day streak pays a **whole ad-free day**, with nothing to watch for it.
- **Two hours with no ads, whenever you want them.** Watch one short ad and the app goes quiet — banner and full-screen both — for two hours. Watch another and the time is added to what you already had, up to a day. Nothing that used to be free has been put behind it: every quality, MP3, batch paste and the optional login work exactly as before.
- **A privacy form for users in the EEA, the UK and Switzerland**, so ads there are handled the way those rules require.

### Fixed
- **The full-screen ad was almost never appearing.** It was being fetched when the app opened and when a link was shared — moments when it could not be shown — so it expired unseen. Over one measured week the app fetched 57 and showed 2. It is now fetched one download before it is due, and dropped if it goes stale.
- **The bottom banner sits in a wider slot** and asks again after a failure instead of leaving the space blank until the app is reopened.

## [1.1.5] — 2026-09-01

Faster downloads, a screen that shows them happening, and photo posts.

### Added
- **You can watch a shared download happen.** Share a TikTok link and a small sheet appears with the video's thumbnail and title, live progress, and a **Cancel** button — instead of the share sheet closing and leaving you to guess whether anything started. It has four states (preparing, downloading, saved, failed), and **Open app** takes you into the full app from any of them.
- **Paste several links at once.** Multiple TikTok links in one paste are all queued, instead of only the first one being taken.
- **Search your downloads**, and **Delete all** in one tap — behind a confirmation, so it cannot happen by accident.
- **Photo and slideshow posts download too** — a carousel saves every image, not just the cover.
- **The download engine says how current it is.** About now shows the engine version and when it last updated, with an **Update engine now** button. The automatic daily check runs *after* a download instead of before one, so it never delays the thing you actually asked for.

### Fixed
- Typing in the **Connect TikTok** screen no longer comes out reversed.
- Carousel links that reported a download and then saved nothing.
- The share-sheet message that stayed on screen after the download had already finished.
- An engine update whose result was silently discarded, so the same update ran again the next day.

### Changed
- Downloads are noticeably faster, and the saved file is the clean, watermark-free rendition far more reliably than before.

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
