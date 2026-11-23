# Alder — Android app, full documentation

Alder is a native Android (Kotlin, Framework Views) client for a Stremio-style account service. It
ships a TV (10-foot, remote-driven) experience and a phone/tablet (touch) experience from one code
base, plays video with media3/ExoPlayer, resolves streams through the built-in Moviebox/House add-on
and the account's Stremio add-ons, and talks to a self-hosted account service (`audit-server`).

- Package / namespace: `dev.alder`
- `compileSdk`/`targetSdk` 36, `minSdk` 23, `versionCode` 7, `versionName` 1.3.1
- ABIs: `armeabi-v7a`, `arm64-v8a` (TVs are ARM; x86 ffmpeg would only add size)
- Signing: release keystore from `keystore.properties` (git-ignored); `-PdebugKey` for an
  optimised build signed with the debug key (installs over a debug build, data kept)
- Kotlin + coroutines; media3 1.9.0; `media3-ffmpeg-decoder` for software audio (DTS/TrueHD);
  Nuvio torrent engine `nuvio-engine-android-0.1.6.aar` (SHA-256 pinned in `build.gradle.kts`)

## Build & install

```
# one-time: set JAVA_HOME to a JDK 21 (Zulu)
$env:JAVA_HOME="C:\Program Files\Zulu\zulu-21"

# unit tests (includes translation-completeness + no-hardcoded-English + logic tests)
.\gradlew.bat :app:testDebugUnitTest

# debug APK -> app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat :app:assembleDebug

# install (emulators, or a real device)
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

Devices in the dev environment: `emulator-5554` (MuMu, hevc=true av1=false), `emulator-5556`
(AOSP TV), real `RFCR401MNSA` (when attached). Logs: `adb logcat -d -s AlderStreams`.

## Configuration

- **Account service base URL** — `Stremio.API` / `Stremio.LINK` in `Stremio.kt`. Dev build points
  at `http://192.168.1.100:8080` (the operator's own account service). `/config` returns the
  operator's branding and settings.
- **Branding** — pulled at runtime from the service (`GET /config` → settings) and persisted in
  `app.xml` prefs. See `Branding.kt`.
- **Cleartext HTTP** is allowed (`usesCleartextTraffic="true"`) so self-hosted add-ons, debrid
  proxies and IPTV links work.
- **Back as a key press** on Android 16: `Screen.kt` registers an `OnBackInvokedCallback` that
  re-sends `KEYCODE_BACK`, because Android 16 stops delivering it to apps that target it.
- **Edge-swipe back/forward** on touch screens: swipe in from the left screen edge = Back, from the
  right edge = Forward (`Screen.dispatchTouchEvent`). Only a clearly horizontal pull inside the
  content band navigates, so vertically scrolling descriptions/posters do not. Works best on
  3-button navigation; on gesture-nav phones the system owns the screen edges.

## Activities & components (`AndroidManifest.xml`)

| Component | Role |
|---|---|
| `ProfilesActivity` | Launcher. Profile picker, add/remove profile, sign-in / QR "Connect", sign-out. Entry to `MainActivity`. Handles `stremio://`, `magnet:`, `strem.io` links and shared text. |
| `MainActivity` | Home + Movies / Shows / Live / My List pages, carousels, hero art, top nav (TV/tablet), phone bottom nav, Continue Watching, card captions, announcement dialog, What's New. |
| `SearchActivity` | Search with add-on results. |
| `SettingsActivity` | Video player choice, text/poster size, tab order, About (source/install links), crash reports. |
| `AddonsActivity` | Manage add-ons (built-in Moviebox/House are hidden; Cinemeta, OpenSubtitles v3, custom). |
| `DetailsActivity` | Detail page, episodes guide, similar titles, cast/info overlay, play/resume. |
| `StreamsActivity` | Source/stream list (transparent theme `Alder.Over`, so the player behind is paused not stopped). |
| `PlayerActivity` | media3 player (`Alder.Player` theme, `sensorLandscape`, PiP). |
| `PlaybackService` | Foreground `mediaPlayback` service: MediaSession + notification. |
| `App` | Application class (`android:name=".App"`). |

## Source modules (`app/src/main/java/dev/alder`)

### Account / API
- **`Stremio.kt`** — the service client. `login`/`register`/`logout`/`getUser`; add-on collection
  (`addons`/`setAddons`, cached per authKey); library & `libraryItem` changes; meta; streams;
  subtitles; `billingStatus`/`billingCheckout`; `reportActivity` (watch slices + `event="error"` +
  `event="upgrade_shown"`); `config()`/`announcement()`; `Link` create/read; Plan/BillingStatus.
  Endpoints hit the native account service (`/api/...`, `/api/v2/...`, `/api/statsActivity`).
- **`Branding.kt`** — operator brand. Persists and exposes `productName`, `tagline`,
  `loginHeadline`, `loginSubtitle`, `supportEmail`, `termsUrl`, `privacyUrl`, `sourceUrl`,
  `serverDownloadUrl`. `name(c, fallback)` is the product name; `brand(text, c)` replaces the words
  "Stremio"/"Alder" with the brand; `bt()` = `brand(getString(...))`. `sourceName`/`internalSourceName`
  (fallback "Alder") names the built-in source for display **and** as the stable match key.
  `init(c)`/`update(c,b)` wired in `ProfilesActivity`/`MainActivity`.
- **`Profiles.kt`** — profile store + multi-device sync. `Profile(id, name, color, authKey, pin,
  mod, deleted)` (real ids `1..MAX=5`, household cards `h<hex>`); `ProfileSync` does a per-profile
  3-way merge by `mod` (tombstones, deterministic tiebreak) under the primary account, and
  `materializeHousehold` turns the other family members into profile cards (excluding anyone who
  already has a real profile here). See **`docs/HOUSEHOLD_AND_PROFILE_SYNC.md`**.
- **`ProfileGate.kt`** — seat gating for adding a profile (`Allow`/`Upgrade`/`Full`, `overLimit`).
- **`HouseholdLink.kt`** — best-effort "link a new sign-in into the owner's household" on add.
- **`Device.kt`** — device info.
- **`Crashes.kt`**, **`Failures.kt`** — crash capture + branded failure messages.

### Discovery & data
- **`Moviebox.kt`** — Moviebox/House resolver **and** add-on (`MovieboxAddon` id "moviebox",
  `HouseAddon` id `org.local.streamimdb`, hidden from the add-ons list). Searches Moviebox, groups
  one subject per **dub language** (`bingeGroup="MBDUB_<subjectId>"`), reads sources
  (HEVC/h264 MP4 via HLS/`/media`, DASH via same-origin proxy), subtitles (WebVTT), and builds
  `Stream`s with audio languages. Manifest names use `internalSourceName()`; stream titles/descriptions
  use `"<brand> · …"`. Spanish/`esla` and "no playable stream" variants are dropped with a logged reason.
- **`Addon.kt`** — add-on/stream/subtitle/video/meta models; language detection
  (`audioLanguagesIn`, `LANG_FULL_NAMES`, flag/tag parsing); episode labels (`S..E..`), `episodeNumber`,
  `hasEpisodes`, `parseTrailerStreams`.
- **`StreamsResolver.kt`** — resolves streams across the account's add-ons plus built-in
  Moviebox/House. Keeps the best `TRY` streams per language/quality; `brandPending` makes it wait up
  to 3 s for the built-in add-on so dubs aren't locked onto untagged sources.
- **`StreamsActivity.kt`** — shows all sources; match/display use `internalSourceName(context)`.
- **`Library.kt`** — library items, watched bitfield (`state.watched`), new-episode counts.
- **`Ratings.kt`**, **`Tmdb.kt`**, **`Updates.kt`** — ratings, TMDB metadata, What's New (reads the
  bundled `CHANGELOG.md` asset).

### UI framework
- **`Screen.kt`** — base Activity. Chrome/back handling, helpers (`text`, `pill`, `column`,
  `caps`, `glass`, `card`, `nav`), `upgradeDialog` (paywall: plans, tagline, terms/privacy links,
  support email), `linkText` (clickable link), `profileMenu`, nav tab dispatch, and **edge-swipe
  back/forward** (`dispatchTouchEvent`: swipe in from the left margin = back, from the right margin
  = forward; only when the pull is horizontal and inside the vertical band, so description/posters
  that scroll vertically do not navigate; the player opts out with `swipeNav = false`).
- **`Cards.kt`** — `MediaCardView`, horizontal rails, focus/scale.
- **`Handheld.kt`** — TV vs handheld vs phone/tablet detection (`isTv`, `isHandheld`, `isPhone`,
  `narrow`, `isPortrait`), density fitting (`fittedDpi`), design-dp conversion, text boost, grid
  columns, phone bottom nav (search/home/profile).
- **`MainActivity.kt`** — Home/Movies/Shows/Live/My List. Top nav with search icon (shown on TV and
  tablets, hidden on phones which have it in the bottom nav), hero art, rows, card captions
  (`showCaption`: `timeLeft` › next-episode › `name · detail` where detail = seasons/runtime/year),
  announcement dialog, Continue Watching.
- **`DetailsActivity.kt`** — episodes guide opened by Down/swipe/label; closed by Up from its first
  row (season buttons, or first episode for single-season shows), Back, or the close button; info
  overlay; similar titles; cast; play/resume.
- **`SearchActivity.kt`** — body is phone-style (single column, system keyboard) on phones **and**
  tablets (`upright`); the top header stays TV-style on tablets. TV uses a two-pane layout with an
  on-screen QWERTY keyboard.
- **`SettingsActivity.kt`**, **`AddonsActivity.kt`** — settings/add-on management; About shows the
  operator's source and streaming-server download links when set.
- **`Images.kt`** — poster/background/logo loading & caching.
- **`Qr.kt`** — QR bitmap generation for the connect screen.

### Playback
- **`PlayerActivity.kt`** — ExoPlayer. Paywall gate (`begin()`), media source building (MP4 through
  the media proxy so Moviebox audio survives; HLS; DASH), audio/dub tab (`openOptions(1)`, the
   "Audio & dub" button), subtitle tab, sync/style tab, badges for HDR/Dolby Vision, audio
   codec + channels, subtitle language, remux/debrid (`refreshBadges`; the resolution/quality
   badge 4K/1440p/1080p/720p/480p was removed), language-priority source order
   (`PlaybackOrder.order`: every source in the wanted audio language first, then English/original, then
   the rest, each best quality first, so a slow or failed link gives way to the next one in the same
   language), next-episode, PiP, external player, MediaSession hand-off,
  watch-slice + error + upgrade-shown reporting. The info button opens `DetailsActivity` **without**
  finishing the player, so Back returns to it.
- **Audio language choice & fallback** (`PlayerActivity`): the starting audio is the explicit Settings
  language (`Settings.audioLanguageExplicit`) if set, else this title's remembered audio, else the
  locale language. A source whose own audio can't be decoded is handled in stages before the language
  is given up on: (1) try the next audio track inside the same file; (2) **borrow** audio from another
  source of the **same dub** while keeping this source's video — `borrowAudioFromSameDub` builds a
  `MergingMediaSource` (video from the playing source, audio from a same-language donor), `applyBorrowedAudio`
  forces that video group + the donor's audio group, and `audioDead` skips donors that already failed;
  (3) `switchDubForAudio` moves to another source of the same dub. Only after all that does it cross to
  a different dub. (Cross-release borrowing can drift A/V, so it is a last resort before changing dub.)
- **Adaptive quality** (`stalled`/`switchQuality`/`autoQuality`/`dubPeers`): a stall drops one quality
  step within the **same dub** at once (never another dub); after `STABLE_MS` of stable playback it
  climbs back up. `dubPeers` excludes audio-dead sources so the ladder never bounces into a broken one.
- **Loading & retry reliability** (`startTimeout`/`openTimeout`/retry logic):
  - The start wait is **progress-aware**: a source that is buffering real data (`totalBufferedDuration`
    growing) gets up to `START_MAX_RETRIES` extra `startWait` windows before it is skipped, so a slow
    4K start is not killed; a source with no data still moves on at the first deadline.
  - **Mid-stream network errors are retried on the same source** (bounded by `MID_RETRIES`, resumed from
    the current position, budget refreshed after `RESUME_REFRESH_MS` of forward progress) instead of
    kicking the viewer to the source list — this fixes single-source titles getting stuck on a brief drop.
  - Two phases that previously had **no timeout** now do: torrent/debrid open (`openTimeout`, `OPEN_WAIT`)
    and add-on subtitle prep (`prepTimeout`, `SUB_PREP_WAIT`, which plays without the file if it stalls).
- **Subtitle / dub loading retries**: add-on subtitles are refetched up to `SUB_RETRIES` times after an
  empty/failed answer, and a wanted-language file that arrives late is attached; each source's embedded
  audio probe is retried (`PROBE_RETRIES`) so a slow source still lists its dubs; the embedded-subtitle
  auto-pick waits until text tracks are actually reported, so a subtitle that loads a moment late is still
  selected.
- **`AudioProbe.kt`** — probes embedded audio tracks of sources off the main thread (reusable
  ExoPlayer on a `HandlerThread`), returning `(lang,label)` tracks.
- **`PlaybackOrder.kt`** — pure, unit-tested source-ordering logic. `score(s)` ranks one source by how
  well its audio languages match the wanted audio (wanted › English/original › other dubs, then higher
  quality first); `order(list, wantAudio, originalFirst)` groups all sources by that score so playback
  tries the wanted language (and English/original) first. Used by the player's start order, the dub
  fallback and the audio-dub menu. Covered by `PlaybackOrderTest`.
- **`PlaybackAudio.kt`**, **`DialogueAudioProcessor.kt`** — audio track selection / dialogue clarity.
- **`SubtitleFiles.kt`** — downloads/unzips/converts subtitles to UTF-8 for ExoPlayer.
- **`TorrentSource.kt`** — Nuvio torrent engine source.
- **`MkvChapters.kt`** — MKV chapter parsing.
- **`ExternalPlayer.kt`** — external video-player intents (VLC/MX/mpv/Vimu) with resume/subs.
- **`PlaybackService.kt`** — foreground service + notification (brand name), MediaSession.
- **`ProgressSaves.kt`** — watch-progress persistence.
- **`WatchNext.kt`** — Google TV "Watch Next" EPG integration.
- **`TimelineMarkers.kt`** — timeline markers.
- **`PhoneEntry.kt`** — phone entry point.

## Branding system

The operator edits settings in the web operator panel (`/operator`). The app pulls them from
`GET /config` and applies:

| Setting | Where it shows in the app |
|---|---|
| `productName` | App name everywhere (replaces "Stremio"/"Alder" via `bt()`), notification, source titles |
| `tagline` | Under the paywall heading |
| `loginHeadline` / `loginSubtitle` | Sign-in screen heading / subheading |
| `supportEmail` | Paywall (tap to email) |
| `termsUrl` / `privacyUrl` | Paywall footer links |
| `sourceUrl` | Settings › About › "Source code" |
| `serverDownloadUrl` | Settings › About › "Install streaming server" |
| `announcement*` | One dialog per announcement revision per profile |

Internally, `internalSourceName()` (operator brand, fallback "Alder") names the built-in Moviebox/House
add-on and its stream titles, so the source shows the operator's brand while matching stays stable
within a session. `MovieboxAddon`/`HouseAddon` manifest `name` also use it.

## Localisation

- Base strings in `res/values/strings.xml`; translations in
  `values-es/pt/fr/de/it/hi/ar/nl/th/tr` (10 locales + base = 11 files).
- `TranslationStringsTest` enforces: every English key present + non-blank in all 10 locales with
  matching format placeholders; every string referenced in code; no hardcoded English in UI helpers
  (`text(...)`, `pill(...)`, etc.) except a small allow-list.
- When adding a string, add it to **all 11** files. `&` must be XML-escaped as `&amp;`; apostrophes
  as `\'`.

## Tests

`app/src/test/java/dev/alder/`:
- `TranslationStringsTest` — completeness + no-hardcoded-English.
- `AddonLanguageTest`, `MovieboxLanguageTest` — language/dub parsing.
- `StreamsResolverTest`, `NoSourceReasonsTest` — resolution/failure reasons.
- `PlaybackOrderTest` — language-priority source ordering (wanted dub first, then English/original,
  then the rest; best quality first; `originalFirst`).

Run: `.\gradlew.bat :app:testDebugUnitTest` (217 unit tests).
- `LibraryTest`, `ContinueWatchingTest`, `CardSystemTest`, `FailuresTest`, `ImagesTest`,
  `HandheldLayoutTest`, etc.

Run: `.\gradlew.bat :app:testDebugUnitTest`.

## Notable recent changes (this fork)

- **Household / family plan + per-profile multi-device sync** (profiles, PINs and colours merge by
  `mod` across devices; household members materialise as switchable profile cards; seats gate
  adding a profile; owner/manager/member roles). Full write-up: **`docs/HOUSEHOLD_AND_PROFILE_SYNC.md`**.
- Operator-driven branding incl. `sourceUrl` / `serverDownloadUrl`; Moviebox/House manifests and
  stream titles use `internalSourceName()`.
- QR "Connect" screen shows the real pairing host (no hardcoded `link.stremio.com`).
- Upgrade funnel: one viewer = one "seen", repeat views counted separately (server side).
- Player: "Audio & dub" button opens the Audio tab; info button keeps the player on the back stack.
- **Edge-swipe back/forward** on touch screens (swipe in from the left/right screen margin; the
  player opts out so sideways drags seek/adjust volume).
- Player: the resolution/quality badge (4K/1440p/1080p/720p/480p) was removed; the other badges
  (HDR/Dolby Vision, audio codec + channels, subtitle language, remux/debrid) stay.
- Player: dub/language selection keeps the wanted language first across ALL its sources before
  falling back — every source in the Settings audio language (best quality first; an explicit Settings
  choice wins over any per-title pick), then English/original, then other languages
  (`PlaybackOrder.order`). When the TV language has no source (e.g. Dutch with an English-only
  release) it starts on English/original. A **timeout or stall never switches to another dub**: it
  tries the remaining wanted/English sources and then the source picker, so a slow link does not
  jump to Hindi/Kannada.
- Search page on tablets = phone body + TV header; tablets get the top-nav search icon.
- Home card captions now show `name · detail` (seasons/runtime/year) for popular movies/shows.
- Episodes guide closes with Up from its first row.
- Player: a source with un-decodable audio keeps its video and borrows audio from another source of
  the **same dub** (`MergingMediaSource`); only if no same-dub donor works does it switch source/dub.
- Player: adaptive quality — a stall steps down one quality in the same dub and climbs back when stable;
  the quality ladder skips audio-dead sources.
- Player: loading is more reliable — progress-aware start wait, same-source retry on mid-stream network
  drops (no more getting stuck/kicked out on a single source), and deadlines for torrent open and
  add-on subtitle prep so nothing spins on the loading screen forever.
- Player: late subtitles/dubs are recovered — add-on subtitle fetch retries + late attach, audio-probe
  retries, and the embedded-subtitle auto-pick waits for text tracks to be reported.