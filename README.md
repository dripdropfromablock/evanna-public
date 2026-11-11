# Evanna

![Evanna — Offline hi-res music player for Android](docs/assets/evanna-banner.png)

Evanna is an offline high-resolution music player for Android. Browse music from your phone or SD card, tune it with graphic or parametric EQ, and listen through Android audio or a compatible USB DAC.

**Android 9+ · ARM64 · Development build**

## Features

- **Local library** — Add multiple folders through Android’s folder picker. Browse and search your collection, rescan individual folders, and sync saved folders at startup.
- **Now Playing** — View embedded artwork and lyrics, use the floating mini-player, edit the queue, shuffle or repeat tracks, and save playlists. The queue restores paused after a restart.
- **Equalizer** — Adjust the graphic EQ or set up parametric filters manually. To import an AutoEq preset, select your own `ParametricEQ.txt` file.
- **USB DAC output** — Use Evanna’s direct USB audio path with a compatible DAC, save per-DAC settings, and inspect source, processing, and output details in **Audio quality**.
- **Local diagnostics** — Review diagnostic reports on your device and choose when to share a copy.

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

## License

The [public Evanna source](https://github.com/dripdropfromablock/evanna-public) licenses original application code and original project materials under GPL-3.0-only unless a file states otherwise. Vendored libraries and tools retain their respective licenses and attribution.

Distribute the matching complete source and build materials alongside APK releases, including the retained FFmpeg archive, native sources, headers, configuration, and build scripts.

[GPL-3.0 license](https://github.com/dripdropfromablock/evanna-public/blob/main/LICENSE) · [Attribution](https://github.com/dripdropfromablock/evanna-public/blob/main/NOTICE) · [Third-party inventory](THIRD_PARTY.md) · [Native library replacement and source delivery](https://github.com/dripdropfromablock/evanna-public/blob/main/docs/native-library-replacement.md)
