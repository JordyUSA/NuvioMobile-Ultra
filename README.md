<div align="center">

  <img src="https://github.com/tapframe/NuvioTV/blob/main/assets/brand/app_logo_wordmark.png" alt="NuvioMobile Ultra" width="320" />

  <h1>NuvioMobile Ultra</h1>

  <p>
    A Kotlin Multiplatform build of NuvioMobile for Android and iOS, focused on getting media <em>off</em> the phone and onto whatever screen is in the room — Chromecast, DLNA televisions, converted files you can keep — without giving up the polish of the Enhanced fork it grew out of.
  </p>

  <p>
    <a href="https://github.com/JordyUSA/NuvioMobile-Ultra/actions/workflows/build-android.yml"><img src="https://img.shields.io/github/actions/workflow/status/JordyUSA/NuvioMobile-Ultra/build-android.yml?style=for-the-badge&label=Android%20Build" alt="Android build status" /></a>
    <a href="https://github.com/JordyUSA/NuvioMobile-Ultra/blob/enhanced/LICENSE"><img src="https://img.shields.io/github/license/JordyUSA/NuvioMobile-Ultra?style=for-the-badge" alt="License" /></a>
    <a href="https://github.com/JordyUSA/NuvioMobile-Ultra/stargazers"><img src="https://img.shields.io/github/stars/JordyUSA/NuvioMobile-Ultra?style=for-the-badge" alt="Stars" /></a>
  </p>

  <p>
    <a href="#new-in-ultra">New in Ultra</a> | <a href="#download">Download</a> | <a href="#build-from-source">Build from source</a> | <a href="#documentation">Docs</a> | <a href="#credits">Credits</a>
  </p>

</div>

## Overview

NuvioMobile Ultra is a fork of NuvioMobile Enhanced, which is itself a fork of the original
NuvioMobile. It keeps everything both projects already do — Stremio-style addons, debrid, Trakt,
Live TV, downloads — and adds a casting and media-processing layer that neither had.

The lineage, so it is obvious what came from where:

- [`NuvioMedia/NuvioMobile`](https://github.com/NuvioMedia/NuvioMobile) — the original app and the
  entire feature surface described under [Built on NuvioMobile](#built-on-nuviomobile).
- [`yesnt10/NuvioMobile-Enhanced`](https://github.com/yesnt10/NuvioMobile-Enhanced) — the Enhanced
  fork: AI assistant, Live TV, libmpv, release calendar, and the performance work listed under
  [Inherited from Enhanced](#inherited-from-enhanced).
- `JordyUSA/NuvioMobile-Ultra` — this repository. Everything under
  [New in Ultra](#new-in-ultra) was added here.

Current version: **`0.3.1 (102)`**. Both platforms read this from a single file,
`iosApp/Configuration/Version.xcconfig` — the Android build fails outright if `MARKETING_VERSION`
or `CURRENT_PROJECT_VERSION` is missing, so the two can never drift apart.

## New in Ultra

### Casting

Ultra can send what you are watching to a television, and it works out how to do that instead of
hoping the receiver copes.

- **Chromecast sender stack** on Android and iOS: session management, media probing, a local HTTP
  server, and a receiver picker wired into the player itself
  (`composeApp/src/commonMain/kotlin/com/nuvio/app/features/cast/`,
  `iosApp/iosApp/Cast/CastBridge.swift`). The Google Cast iOS SDK is vendored, with the local
  network entitlement documented in [`docs/ios-multicast-entitlement.md`](docs/ios-multicast-entitlement.md).
- **A delivery planner that picks the cheapest thing that will actually play.**
  `CastDeliveryPlanner` is a pure function of the media probe and the receiver's capabilities, so
  it is unit tested without a device in the room. It returns one of four outcomes:
  - `DIRECT` — hand the receiver the URL and let it pull the bytes.
  - `REMUX` — codecs are fine, the container is not (the Matroska case). Repackage, no re-encode.
  - `TRANSCODE` — at least the video has to be re-encoded, against bitrate ceilings per output
    height (8 Mbps at 1080p, 5 at 720p, 2.5 at 480p).
  - `UNSUPPORTED` — say so plainly rather than starting a cast that will fail silently.
- **A receiver capability matrix** per device class, so a Chromecast Gen 2 and an Ultra are not
  treated as the same target (`CastReceiverCapabilities.kt`, `CastReceiverResolution.kt`).
- **A local server for sources the receiver cannot reach itself** — local files, torrent-backed
  streams and anything on a loopback address. A Chromecast resolves URLs from its own network
  position, not the phone's (`CastLocalServer.kt`, `iosApp/iosApp/Cast/CastLocalServer.swift`).
- **DLNA/UPnP-AV casting alongside Chromecast**, on both platforms: SSDP discovery plus SOAP
  AVTransport control (`features/cast/dlna/`, `iosApp/iosApp/Cast/DlnaTransport.swift`), with
  discovery widened so more televisions are found.
- **Real codec probing on iOS** through FFprobe rather than AVFoundation, which reports profiles,
  bit depth and subtitle tracks that AVFoundation will not.

The Android-only MediaCodec hardware transcoding path — encoder detection, resolution downscaling,
HEVC→H.264 conversion, progress UI — is documented separately in
[`CHROMECAST_TRANSCODING_IMPLEMENTATION.md`](CHROMECAST_TRANSCODING_IMPLEMENTATION.md).

### FFmpeg on both platforms

FFmpegKit was retired and its Maven artifacts withdrawn, so Ultra builds and vendors its own.

- `composeApp/libs/lib-ffmpeg-kit-v6.0.aar` for Android and
  `iosApp/Frameworks/FFmpegKit/ffmpegkit.framework` for iOS (a minimal GPL build carrying libx264),
  each reproducible from the workflows that made them:
  [`build-ffmpeg-kit.yml`](.github/workflows/build-ffmpeg-kit.yml) and
  [`build-ffmpegkit-ios.yml`](.github/workflows/build-ffmpegkit-ios.yml).
- On Android, bundling FFmpeg is **opt-in** via `-Pnuvio.android.ffmpeg=true`. Without it the build
  compiles the `androidNoFfmpeg` source set and falls back to Media3 Transformer, which handles the
  common cases but cannot decode formats the handset's own MediaCodec rejects. Both configurations
  are built by CI so neither can rot.

### Built-in video converter

A downloaded file that will not play in the car, on the TV, or in someone else's player can be
converted in the app — no desktop round trip (`features/converter/`).

- **Five presets**, each a starting point rather than a promise:

  | Preset | What it does |
  | --- | --- |
  | Remux only | Container-only fix (MKV → MP4). Copies both tracks, runs at roughly disk speed, loses nothing. |
  | Universal MP4 | H.264 + AAC in MP4 at 1080p/8 Mbps — the combination essentially no player rejects. |
  | Phone space saver | 720p, 2.5 Mbps, capped at 30 fps. The frame-rate cap matters: 60 fps at that bitrate falls apart. |
  | High quality 1080p | 12 Mbps on purpose — for people who would rather spend storage than quality. |
  | Audio only | Strips the video track. |

  Plus a **Custom** spec exposing container, video codec, resolution, frame rate, video bitrate,
  audio codec, audio bitrate, channels, subtitle handling and hardware acceleration.
- **The planner reconciles your preset against reality.** `ConversionPlanner` checks the preset
  against what the source actually contains and what this device can encode, so "cap 1080p" never
  upscales a 720p file and a codec the handset lacks is downgraded rather than attempted. When it
  changes something, it tells you what and why.
- **Encoder discovery merges two sources**: Media3's `EncoderUtil` reports the device's real
  MediaCodec encoders, and FFmpeg — when bundled — reports itself via an `-encoders` probe.
- **A queue you can manage**: reordering, crash recovery that repairs an interrupted queue on next
  launch, live progress with speed multiplier, fps and ETA, progress notifications, an optional
  replace-the-original step, sharing the result, and batch conversion of a whole selection.

### Downloads, redesigned

- Tabs for **All / Active / Completed / Failed**, with separate queue and history sections.
- **Multi-select with a bulk action bar** — convert, delete, share, cancel.
- **Storage summary and a low-storage warning** before a download fails halfway, plus a low-power
  warning (iOS Low Power Mode, read through a Swift bridge).
- **Resolution badge**, a per-item overflow menu, sharing, and downloaded-episode badges that show
  up on the series detail screen.
- **Failures that say what happened** instead of a generic error: no connection, timed out, link
  expired, not found, server error, out of storage, could not write the file, unsupported format.
- Download statistics throttled to one update per second, so a long list stops thrashing.

### Media info

Any completed download can report what is actually inside it — container, duration, size, and each
video, audio and subtitle track with codec, resolution, frame rate, bitrate, dynamic range, bit
depth, channels, sample rate and language, alongside the source and provider it came from.

- **Android** reads real files with **MediaInfoLib**, through a vendored AAR and a JNI shim
  (`composeApp/libs/lib-mediainfo-dd11d79.aar`, `composeApp/src/androidMain/jni/mediainfo_jni.cpp`,
  built by [`build-mediainfo.yml`](.github/workflows/build-mediainfo.yml)), falling back to the Cast
  prober for anything it cannot open directly.
- **iOS** uses FFprobe, which is already vendored for casting and reports the same fields.
- The JSON parsing is shared common code and unit tested (`MediaInfoJson.kt`).

### Caching

- **Shared plumbing** underneath all of it: explicit cache directories, storage accounting, and a
  background monitor (`core/storage/AppCacheDirectories.kt`, `core/storage/StorageUsage.kt`,
  `core/sync/AppBackgroundMonitor.kt`).
- **Posters and avatars persist**, in a directory the app owns with a fixed, reportable ceiling.
  Coil's defaults put artwork in the reclaimable platform cache with a size derived from free
  space — which meant the library went blank exactly when you were offline and could not refetch.
- **Streaming video is cached to disk**, and the buffered range is drawn on the seek bar. This
  cache is deliberately short-lived: wiped when the player closes, when the app starts and when it
  leaves the foreground, so it never accumulates across sessions. The start-up wipe is what
  recovers the space after a crash.
- **Settings you can act on**: a stream cache toggle and size limit under Playback, and image and
  video cache usage with a Clear action under Advanced.

### Appearance, player and settings

- **Five new themes** — Slate and Mocha in the classic set, Cosmos, Citrus and Midnight in the
  enhanced set.
- **Subtitles**: sizing widened to 4sp steps, and the old outline on/off toggle replaced by a full
  **edge style** picker — none, outline, drop shadow, outline and shadow, raised, depressed — plus
  independent text and background opacity.
- **Auto-rotate setting**, and content kept clear of camera cutouts on both platforms.
- **Greyed-out controls now say why.** A disabled setting explains what is blocking it — an
  external player, a distribution that cannot ship the feature, a conflicting toggle — rather than
  just dimming and leaving you to guess.
- **iOS trailers play in the app** regardless of which distribution flavor you are running.

### Engineering

- CI, added from nothing: [`build-android.yml`](.github/workflows/build-android.yml) (a matrix of
  with-FFmpeg and without), [`build-ipa.yml`](.github/workflows/build-ipa.yml), the two FFmpegKit
  builds, [`build-mediainfo.yml`](.github/workflows/build-mediainfo.yml), and
  [`android-release.yml`](.github/workflows/android-release.yml) with dry-run/draft/publish modes
  and generated release notes (`scripts/generate-release-notes.sh`).
- New unit tests covering the cast delivery planner, adaptive manifests, the DLNA protocol, the
  conversion planner and presets, converter queue repair and reordering, and MediaInfo JSON parsing.

## Inherited from Enhanced

Everything the Enhanced fork brought over the original, still here:

| Area | What it adds |
| --- | --- |
| AI assistant | Gemini, OpenRouter, Cerebras and Groq support, grounded web search, formatted markdown replies. |
| Live TV | M3U browsing, favorites, channel switching, category filters, XMLTV EPG, a recent channel card. |
| Player | libmpv playback engine on Android, tap-to-seek, reliable progress sync, finish-time overlay, tracks preserved across quality changes. |
| Library | Release calendar and release radar, with clearer handling of current and future entries. |
| Downloads | Speed and ETA readouts, faster Android downloads, chunked iOS writes. |
| Profiles | Poster hydration, completed-insights including anime, an app icon picker with adaptive launcher icons. |
| Performance | Season-level episode enrichment caching, localized hero artwork resolved before first render. |

## Built on NuvioMobile

The application underneath is NuvioMobile, and Ultra carries all of it: Stremio-style addon
support, catalogs and search, debrid services, Trakt and MDBList sync, P2P streaming, downloads,
CloudStream extension compatibility, multiple profiles, watch progress and continue-watching,
notifications, deep links, and the in-app updater.

## Download

No tagged release has been published on this repository yet — builds come from GitHub Actions in
the meantime, and tagged builds will appear on the
[Releases page](https://github.com/JordyUSA/NuvioMobile-Ultra/releases) once published.

**Android**

- Run the **Build Android Release** workflow manually — `dry-run` builds and uploads the release
  APKs as an artifact, `draft` and `publish` also create the GitHub release; or
- take the `nuvio-debug-apk` artifact from a **Build Android** run (uploaded by the with-FFmpeg
  job, kept for 14 days).

**iOS**

- Open a **Build iOS IPA** run, download `NuvioMobile.ipa`, and sideload it with AltStore or
  Sideloadly. Step-by-step instructions are in [`iOS_SIDELOAD_GUIDE.md`](iOS_SIDELOAD_GUIDE.md).
- Free developer certificates expire after seven days, so a sideloaded build needs re-signing that
  often.

## Build From Source

The `MPVKit` submodule is required, so clone recursively:

```bash
git clone --recurse-submodules https://github.com/JordyUSA/NuvioMobile-Ultra.git
cd NuvioMobile-Ultra

# Android, using the Media3 Transformer fallback
./gradlew :androidApp:assembleFullDebug

# Android, bundling the vendored FFmpegKit AAR
./gradlew :androidApp:assembleFullDebug -Pnuvio.android.ffmpeg=true
```

On Windows, use `.\gradlew.bat` in place of `./gradlew`.

The Android app has a `distribution` flavor dimension: **`full`** carries the CloudStream extension
support and native libraries, **`playstore`** does not. `assembleFullDebug` is what CI builds, and
`:androidApp` — not `:composeApp` — is the application module.

Shared unit tests:

```bash
./gradlew :composeApp:allTests
```

iOS is built from `iosApp/` in Xcode, or through the **Build iOS IPA** workflow.

Where things live:

- `composeApp/` — the shared Kotlin Multiplatform / Compose Multiplatform module
  - `src/commonMain/` — shared UI, features, repositories and app logic
  - `src/androidMain/`, `src/iosMain/` — platform integrations
  - `src/androidFfmpeg/`, `src/androidNoFfmpeg/` — the two Android FFmpeg configurations
  - `src/commonTest/`, `src/androidHostTest/` — tests
  - `libs/` — the vendored FFmpegKit and MediaInfo AARs
- `androidApp/` — the Android application module
- `iosApp/` — the native iOS entry point, Swift bridges (`Cast/`, `Convert/`) and vendored
  `Frameworks/`

## Documentation

- [`CONTRIBUTING.md`](CONTRIBUTING.md) — how to contribute
- [`CHROMECAST_TRANSCODING_IMPLEMENTATION.md`](CHROMECAST_TRANSCODING_IMPLEMENTATION.md) — the
  Android hardware transcoding pipeline
- [`DEPLOYMENT_GUIDE.md`](DEPLOYMENT_GUIDE.md) — CI build and IPA distribution flow
- [`iOS_SIDELOAD_GUIDE.md`](iOS_SIDELOAD_GUIDE.md) — installing an unsigned iOS build
- [`docs/ios-multicast-entitlement.md`](docs/ios-multicast-entitlement.md) — local network access
  for Cast and DLNA discovery
- [`CLOUDSTREAM_CROSS_PLATFORM_COMPATIBILITY.md`](CLOUDSTREAM_CROSS_PLATFORM_COMPATIBILITY.md) —
  CloudStream extension support

## Credits

- Original project: [NuvioMedia/NuvioMobile](https://github.com/NuvioMedia/NuvioMobile)
- Enhanced fork: [yesnt10/NuvioMobile-Enhanced](https://github.com/yesnt10/NuvioMobile-Enhanced)
- This fork: [JordyUSA/NuvioMobile-Ultra](https://github.com/JordyUSA/NuvioMobile-Ultra)
- Shared brand asset: [tapframe/NuvioTV](https://github.com/tapframe/NuvioTV)

Vendored third-party components: [FFmpegKit](https://github.com/arthenica/ffmpeg-kit) (built here
with libx264, which is GPL — see [`LICENSE`](LICENSE)),
[MediaInfoLib](https://github.com/MediaArea/MediaInfoLib), the
[Google Cast SDK](https://developers.google.com/cast), and
[MPVKit](https://github.com/NuvioMedia/MPVKit).

## Legal & DMCA

NuvioMobile Ultra functions as a client-side interface for browsing metadata and playing media
provided by user-installed extensions and/or user-provided sources. It is intended for content the
user owns or is otherwise authorized to access.

The project does not host, store, or distribute media content and is not affiliated with
third-party extensions, catalogs, sources, or content providers.

For the full legal policy and disclaimer, see the upstream legal page:

- [Legal & Disclaimer](https://nuvioapp.space/legal)

## Star History

<a href="https://www.star-history.com/#JordyUSA/NuvioMobile-Ultra&type=date&legend=top-left">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=JordyUSA/NuvioMobile-Ultra&type=date&theme=dark&legend=top-left" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=JordyUSA/NuvioMobile-Ultra&type=date&legend=top-left" />
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=JordyUSA/NuvioMobile-Ultra&type=date&legend=top-left" />
  </picture>
</a>
