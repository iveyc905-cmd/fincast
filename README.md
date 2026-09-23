# Ember

An original Android TV IPTV player — M3U and Xtream Codes playlists, XMLTV
guide, D-pad-first UI, ExoPlayer under the hood.

## What this is, and what it isn't

This is a clean-room implementation written against **public standards**, not a
port of any existing app:

| Standard | Where it's implemented |
|---|---|
| Extended M3U (`#EXTINF`, `#EXTGRP`, `#EXTVLCOPT`, `#EXTHTTP`) | `data/parse/M3uParser.kt` |
| XMLTV | `data/parse/XmltvParser.kt` |
| Xtream Codes `player_api.php` | `data/net/XtreamClient.kt` |
| Catch-up URL conventions (default/append/shift/Flussonic/Xtream) | `util/CatchupUrls.kt` |

No commercial app was decompiled, disassembled, or consulted as source material.
Playlist and EPG formats are open interchange formats; UI conventions for TV
guides (a channel column, a time grid, up/down zapping) predate every app that
uses them.

**No content is included.** You supply your own playlist from a provider you
subscribe to. The app is a player, the same way VLC is.

## Features

**Sources**
- Xtream Codes login (portal URL + username + password), with account status and
  expiry validated at setup
- M3U/M3U8 by URL, or a local file
- Multiple playlists side by side
- Background refresh via WorkManager — playlists daily, EPG every 6 hours

**Browsing**
- Category column + channel column overlaid on live video
- Favourites, per-channel, with a colour-button toggle
- Now/next with a progress bar on every row
- Direct channel-number entry with a 2-second commit timeout, like a tuner
- Last-channel recall

**Guide**
- Full time-grid EPG with a time cursor: up/down keeps the moment and changes
  channel, left/right walks the schedule
- Programme detail strip with description and category
- Press OK on a past programme to start catch-up, if the provider offers it

**Playback**
- Media3 / ExoPlayer with HLS, DASH, RTSP, and raw MPEG-TS
- Per-channel `User-Agent` and `Referer` (many providers gate on these)
- Audio and subtitle track selection
- Four aspect-ratio modes
- Tunneled decoding toggle (fixes black screens on some cheap boxes)
- Human-readable playback errors, including "provider is at its connection limit"

**Movies and series**
- Xtream movie and series catalogues, and films/episodes detected in M3U playlists
- Poster grids by category, detail pages, season picker
- Resume where you stopped, "Continue watching", next-episode autoplay

**Casting**
- Google Cast to Chromecast and Google TV, with the phone as the remote
- Xtream live channels are cast as HLS, which a Chromecast can play
- Streams that need custom headers, or servers without CORS, may refuse to cast

**Not implemented yet:** recording/DVR, multi-view, DLNA casting, parental PIN.

## Build

### In the cloud (no local toolchain needed)

Every push to `main` runs `.github/workflows/build.yml` on GitHub Actions. It
builds a signed, R8-optimised release APK, attaches it to a release tagged
`latest`, then runs the unit tests. Signing uses the `EMBER_KEYSTORE_B64`
repository secret; without it the build compiles but publishes nothing. On a public repo that gives a fixed download URL for the TV:

```
https://github.com/iveyc905-cmd/fincast/releases/download/latest/ember.apk
```

### Locally

Requirements: JDK 17, Android SDK 35, Gradle 8.11 (or just open the folder in
Android Studio Ladybug or newer and let it sync).

```bash
./gradlew :app:assembleDebug
```

The Gradle wrapper JAR is not committed. Generate it once with a local Gradle
install, or let Android Studio do it on first sync:

```bash
gradle wrapper --gradle-version 8.11.1
```

Install to a TV device or emulator:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the parser tests:

```bash
./gradlew :app:testDebugUnitTest
```

## Remote control map

| Key | Fullscreen | In the channel panel | In the guide |
|---|---|---|---|
| Up / Down | Channel up/down | Move selection | Change channel row |
| Left | Info bar | Back to categories | Earlier programme |
| Right / OK | Channel panel | Select | Later programme / play |
| 0–9 | Channel number entry | Channel number entry | — |
| Back | — | Close | Close |
| Guide / Yellow | Guide | Guide | — |
| Red | Toggle favourite | Toggle favourite | Toggle favourite |
| Green | Cycle aspect ratio | — | — |
| Menu | Quick menu | — | — |
| Last channel | Previous channel | — | — |

## Architecture

```
MainActivity ──► PlayerScreen ──► overlays (channels · guide · info · tracks · menu)
                      │
                      └─ PlayerViewModel ──► Graph (service locator)
                                                ├─ PlaylistRepository ─► M3uParser / XtreamClient ─► Room
                                                ├─ EpgRepository      ─► XmltvParser              ─► Room
                                                └─ SettingsRepository ─► DataStore
```

Three decisions worth knowing about:

**Video is the home screen.** There is no separate browse activity — the channel
list and guide are overlays over live playback. Zapping never stops the stream.

**List selection is index-driven, not focus-driven.** With 20,000-channel
playlists, Compose focus traversal fights the scroller. Each overlay owns a
selection index and handles D-pad keys itself, so the highlight, the scroll
position, and "what does OK do" are always one value.

**Now/next is computed in SQL.** `ChannelDao.observeChannels` folds the current
and next programme into the channel query as correlated subqueries — one round
trip for the whole visible list rather than a query per row.

## Provider quirks handled

These are the things that break naive IPTV players, and where each is dealt with:

- Playlists that put a comma inside `group-title` — `M3uParser.parseExtInf`
- `.xml` EPG URLs that actually serve gzip — `XmltvParser.maybeGunzip`
- XMLTV files larger than RAM — streamed in 2000-programme batches, and
  programmes for channels you don't have are dropped during parsing
- The same URL listed twice in one playlist — deduplicated before insert
- A refresh that dies halfway — channels are swapped in one transaction, so you
  never end up with an empty list
- Streams that 403 without a `Referer` — carried per channel from `#EXTVLCOPT`
  and `#EXTHTTP`
