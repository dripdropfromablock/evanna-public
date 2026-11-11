# Evanna

![Evanna — Offline hi-res music player for Android](docs/assets/evanna-banner.png)

Evanna is an offline high-resolution music player for Android. Browse music from your phone or SD card, tune it with graphic or parametric EQ, and listen through Android audio or a compatible USB DAC.

**Android 9+ · ARM64 · Development build**

## Features

### Library

- Browse by songs, albums, artists, genres, composers, recently added and folders, with cover, grid and list album layouts, sorting and an A–Z jump bar.
- Search the whole collection or filter inside any view.
- Add multiple folders through Android's folder picker; rescan one or all, cancel a scan, and exclude individual subfolders.
- Favorites, recently played and most played, built from on-device listening history.
- Playlists: create, rename, reorder, save the current queue, and find missing songs; M3U8 import and export.
- Sidecar CUE sheets split single-file albums into individually playable tracks.
- Play any single local file without adding it to the library.
- Decodes FLAC, ALAC, WAV, AIFF, WavPack, Monkey's Audio, TTA, MP3, AAC, Ogg Vorbis, Opus and DSD64 (DSF/DIFF, converted to PCM).

### Playback and queue

- Now Playing sheet with swipeable Lyrics, Song and Queue pages, plus a floating mini-player.
- Editable queue with play next, add, reorder and remove, alongside History and Playing Next views; shuffle and repeat off/song/queue.
- Gapless continuation across compatible tracks; the queue and position restore paused after a restart.
- Sleep timer: 15/30/45/60/90 minutes, end of this song, or end of this album.
- Background playback with notification and lock-screen controls and artwork.

### Lyrics and artwork

- Embedded synced lyrics with auto-scroll and tap-a-line-to-seek, word-by-word karaoke fill where word timestamps exist, and a plain-text fallback.
- Embedded covers in lists and the player; the Now Playing backdrop recolours from the album art.

### EQ and sound processing (Direct USB)

- Graphic EQ with 5–32 bands plus bass and treble, and a live response curve.
- Parametric EQ with up to 34 bands, six filter types, per-band channel routing, and a preamp with automatic headroom.
- Named presets with per-DAC links and import of your own `ParametricEQ.txt`.
- Headphone crossfeed: off, light, medium or strong.
- Loudness leveling (off/album/track) through the DAC's hardware gain, with an offline library measurement pass.

### USB DAC and outputs

- Direct USB output that bypasses Android's mixer, in Strict or Compatibility mode, with a per-song "play converted once" override.
- Per-DAC profiles: sample-rate ceiling, starting volume, volume limit and volume-key step.
- DAC hardware volume from a slider or the phone's volume keys, with a dB readout and control readback verification.
- Android audio route, plus an Android 14+ bit-perfect mixer option when a compatible USB mixer is present (hardware qualification pending).

### Transparency and privacy

- Audio quality window showing the source format, any processing and what Evanna observed at the output, with minimal and advanced signal-path views.
- On-device crash and ANR reports: review, share a copy, or delete; nothing is uploaded.
- Fully offline: the app ships without the internet permission.

### Comfort and accessibility

- Three looks (Evanna dark, Dark, Light) and adjustable animation speed that honours the system animator scale.
- TalkBack labels on controls, 48 dp touch targets, and layouts verified at 200% font scale.
- Searchable settings.

## Get started

1. Install the debug APK on an ARM64 device running Android 9 or newer.
2. Open **Library**, add a music folder, and grant access in Android’s folder picker. Repeat for any other folders you want in the library.
3. Select a track and choose an audio output when prompted. For direct USB playback, connect a compatible DAC, grant USB access, and set a low starting volume.

| Output | How it works |
| --- | --- |
| Android audio | Plays through Android’s system audio path. |
| Direct USB | Sends audio through Evanna’s USB audio path to a compatible DAC. Format conversion and sample processing depend on the selected mode and source. |

## Build from source

Install JDK 17, Android SDK 36, NDK `28.2.13676358`, and CMake `3.22.1`. Configure the SDK in Android Studio or set `ANDROID_HOME`.

```powershell
git clone https://github.com/dripdropfromablock/evanna-public.git
cd evanna-public
.\gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. With Android platform-tools installed and a device connected, run `adb install app/build/outputs/apk/debug/app-debug.apk`.

## Run checks

```powershell
.\gradlew.bat :app:testDebugUnitTest :usbaudio:testDebugUnitTest :app:lintDebug :usbaudio:lintDebug
.\gradlew.bat :app:assembleDebugAndroidTest
.\scripts\run-host-audio-tests.ps1
```

Android instrumentation requires an explicitly selected test device. See [fixture provenance](https://github.com/dripdropfromablock/evanna-public/blob/main/docs/test-fixtures.md) for the synthetic decoder fixtures.

## Tech stack

| Layer | Technology | Version / evidence |
|---|---|---|
| Language (app) | Kotlin, JVM target 17 | 2.2.0 — `gradle/libs.versions.toml` |
| UI | Jetpack Compose (Foundation + material-icons-core, Activity Compose) — no Material3 dependency; custom design system | Compose BOM 2025.08.00, Activity 1.10.1 |
| Async | kotlinx-coroutines-android | 1.10.2 |
| Media session | AndroidX Media3 **session** only (decoding is custom FFmpeg JNI, not ExoPlayer) | 1.9.4 |
| Native decode | C++ JNI → prebuilt **FFmpeg 9.0.2** (`libavcodec`, `libavformat`, `libavutil`, `libswresample`) + **libebur128** loudness metering | `app/src/main/cpp/CMakeLists.txt` |
| USB audio | C++ **libusb** UAC1/UAC2 streamer with native EQ, crossfeed, PCM validation, hardware volume; **libsamplerate** for sample-rate conversion | `usbaudio/src/main/cpp/CMakeLists.txt` |
| Build | Gradle Kotlin DSL 8.14.3 (wrapper), AGP 8.10.1, version catalog, CMake 3.22.1, NDK 28.2.13676358 (`c++_shared` app / `c++_static` usbaudio) | `settings.gradle.kts`, `app/build.gradle.kts`, `usbaudio/build.gradle.kts` |
| SDK levels | compileSdk 36; app minSdk 28 / targetSdk 36; usbaudio minSdk 26 | |
| Tests | JUnit 4.13.2; custom instrumentation runner `com.evanna.player.playback.UsbAdapterProbe`; on-device audio fixtures | |
| Tooling | PowerShell (device/test scripts), Python (fixture generators), Bash (FFmpeg build) | `scripts/` |

### Gradle modules

- `:app` — the player application (`com.evanna.player`): Compose UI, library/queue, playback orchestration, FFmpeg decoder JNI, loudness metering.
- `:usbaudio` — Android library (`com.truesample.usbaudio`): direct USB transport via libusb, UAC descriptor parsing, PCM write contracts, native EQ/crossfeed, hardware volume.

### Notes

- The repo is Kotlin-first for UI and logic, but the entire audio path (decode → loudness → SRC → EQ/crossfeed → USB UAC) is native C/C++ over vendored libraries.
- FFmpeg is consumed as prebuilt arm64 binaries (`third_party/ffmpeg/android/arm64-v8a/lib/`), rebuilt with `scripts/build-ffmpeg.sh` from the bundled `ffmpeg-9.0.2.tar.xz`; it is not compiled in-tree by Gradle.

## License

The [public Evanna source](https://github.com/dripdropfromablock/evanna-public) licenses original application code and original project materials under GPL-3.0-only unless a file states otherwise. Vendored libraries and tools retain their respective licenses and attribution.

Distribute the matching complete source and build materials alongside APK releases, including the retained FFmpeg archive, native sources, headers, configuration, and build scripts.

[GPL-3.0 license](https://github.com/dripdropfromablock/evanna-public/blob/main/LICENSE) · [Attribution](https://github.com/dripdropfromablock/evanna-public/blob/main/NOTICE) · [Third-party inventory](THIRD_PARTY.md) · [Native library replacement and source delivery](https://github.com/dripdropfromablock/evanna-public/blob/main/docs/native-library-replacement.md)
