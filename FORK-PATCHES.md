# Fork Patches

Personal patch set maintained on this fork (`github-hasan-ali/StreamVault-IPTV`),
on top of upstream `Davidona/StreamVault-IPTV`. This file documents changes made
here; it is **not** the official product changelog (see `docs/CHANGELOG.md` for that).

Most of this targets a setup where the playlist is added as an **"M3U URL"** that is
actually an **Xtream Codes** provider (`get.php?...&type=m3u_plus`), so series/episodes
and VOD come from the Xtream API.

## [Unreleased - fork] - 2026-05-30

Branch: `fix/episode-loss-403-and-tr-localization`

### Fixed

- **Series episodes disappearing a while after first load.** `SeriesDao` inserted via
  `OnConflictStrategy.REPLACE`; on the periodic Xtream summary sync, re-inserting an
  existing series did a SQLite DELETE+INSERT and the DELETE cascaded to the `episodes`
  table (`ON DELETE CASCADE`), wiping episodes while the series row reappeared. Existing
  rows now go through `@Update` (`SeriesDao.upsertPreservingEpisodes`) — no delete, no
  cascade. Also de-dupe Xtream movie/series summary batches by `streamId`/`seriesId`.
- **Movie/Live playback dropping mid-stream (HTTP 403).** Xtream panels typically allow
  ~1 connection; background catalog/index/EPG fetching stole the slot during playback.
  The Stalker playback-deferral coordinator is now wired for Xtream too, so heavy
  background sync is deferred while a stream is active (user-initiated category browsing
  is still allowed through). A 6-hour backstop prevents a leaked "playback active" marker
  from starving the catalog forever, and the marker map is cleaned up on a clean stop.
- **Audio/subtitle selection resetting on buffering retry.** A retry re-ran
  `applyInitialParameters`, which cleared track overrides; the chosen audio track and
  subtitle were lost on every "retrying…". Selections (language + track id) now survive
  retries and are restored, while a genuine new stream still resets to defaults.
- **Selected category staying blank / reload storm during bulk hydration.** The
  Movies/Series selected-category pipeline combined `getLibraryCount` straight into
  `flatMapLatest` with no debounce, so each inserted batch cancel-restarted the in-flight
  query. It now reloads on catalog growth via a debounced (500 ms) trigger after an
  immediate first load.

### Changed

- Periodic catalog/index sync cadence changed from 6 h to 24 h (`XtreamIndexWorker`,
  `ProviderSyncWorker`), so the catalog refreshes about once a day instead of every few
  hours.

### Added

- **Turkish localization overhaul** (`values-tr/strings.xml`):
  - Added 57 previously-missing keys that were falling back to English (Crash Reports,
    Audio Output, FFmpeg compatibility, VOD HTTP protocol, Xtream Live Sync setup,
    hidden category/channel dialogs, Plugins, welcome strings, etc.).
  - Fixed ~30 mistranslations (e.g. the recurring possessive "Serisi" → "Diziler"/"Dizi",
    `HAREKET` → `TAŞI`, `HAREKETLİ` → `TAŞINIYOR`, "Favorim" → "FAV", "Açık Seri" →
    "Dizileri Aç", "Yazılım Uzantıları" → "Yazılım Kod Çözücü", `Multiview` →
    "Çoklu Görünüm", `LIVE` → "CANLI", `LOCAL DVR` → "YEREL DVR", casing fixes).
  - Disambiguated catch-up from rewind: catch-up is now consistently "Geri İzleme",
    while rewind stays "Geri sarma".
- Unit tests for `StalkerTrafficCoordinator` (deferral while active, 6 h backstop, map
  cleanup on stop, nested-playback timestamp refresh).

### Build / packaging notes

- Patched APKs are built locally and not committed (`releases/` is git-ignored):
  a debug build (~51 MB) and an R8-minified + resource-shrunk **release** build
  (~16.9 MB), signed with the local Android debug key for sideloading.
- A separate local-only commit pins `buildToolsVersion = "36.1.0"` in the app/data/player
  modules because this machine lacks build-tools 35 and cmdline-tools. **That commit is
  environment-only and should be dropped before opening a PR upstream.**

## Backlog / ideas (not implemented yet)

### Player seek / fast-forward UX — deferred (raised 2026-05-30)

Fast-forwarding VOD feels cumbersome on the TV remote. Investigated; intentionally
**not changed yet** (needs on-device testing, no emulator available here).

Current behavior (evidence):
- Seek is a fixed **10 s** step only; no larger/variable jump. `PlayerEngine.seekForward/
  seekBackward(ms = 10_000)` (`player/.../PlayerEngine.kt:71-72`), ViewModel wrappers call
  it with no argument (`app/.../player/PlayerPlaybackControlActions.kt:12-20`), ExoPlayer
  `setSeek*IncrementMs(10_000)` (`Media3PlayerEngine.kt:1003-1004`).
- D-pad Left/Right only seek while the controls overlay is **hidden**; once controls are
  visible they `return false` and fall through to focus navigation
  (`PlayerScreen.kt:724` and `:743`) — so the "press right to skip ahead" gesture gets
  interrupted. This is the most likely cause of the "cumbersome" feel.
- Hold-to-repeat exists only on the on-screen ⏪/⏩ buttons and fires fixed 10 s steps with
  no acceleration (`PlayerControlsChrome.kt:1455-1506`); the direct D-pad path has no
  key-repeat at all (`PlayerScreen.kt:720-752`).
- The VOD slider has no D-pad seek step, while the **live** timeshift scrubber does
  (`PlayerControlsChrome.kt:1820-1832`).
- Each press is an immediate `seekTo` with no debounce / scrubbing mode, so rapid presses
  re-buffer on slow streams (`Media3PlayerEngine.kt:415-453`).
- Seek thumbnail/time preview only appears while dragging the slider, not on ±10 s / D-pad
  seeks (`PlayerControlsChrome.kt:1267`).

Proposed approach (low→medium risk, keep focus navigation intact):
1. Accelerating/growing increment (10 s → 30 s → 60 s) and/or long-press continuous seek on
   the direct D-pad path (controls hidden).
2. Debounce rapid presses into a single `seekTo` and enable scrubbing mode during a burst so
   it doesn't re-buffer per press.
3. Give the VOD slider the same direct-key ±10/±30 s seeking the live scrubber already has,
   and focus the slider when controls open — so Left/Right seeks without breaking transport-
   button navigation.
4. Surface the position/preview indicator on D-pad seeks too (time-only for HLS/DASH/live).

Items 1–3 would resolve most of the friction.
