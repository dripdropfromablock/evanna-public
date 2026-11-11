# Evanna

Evanna is an offline high-resolution Android music player with direct USB DAC output, Android audio output, a local music library, parametric and graphic EQ, and manual AutoEq text import.

The app reads artwork and lyrics from the user's music files. It supports a floating mini player, queue editing, shuffle and repeat, saved playlists, local diagnostics, and per-DAC settings. Direct USB processing and output details are shown in the Audio quality dialog.

## Build

Use JDK 17 or newer, Android SDK 36, Android NDK 28.2.13676358 and CMake 3.22.1. Configure the SDK in Android Studio or set `ANDROID_HOME`. A local `local.properties` file is ignored by Git.

~~~powershell
./gradlew.bat :app:assembleDebug
~~~

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Evanna targets ARM64 devices running Android 9 or newer.

## Use

Add a music folder through Android's folder picker. Select an output when prompted, then choose a track. Direct USB requires a compatible DAC and explicit USB access. Set a low starting volume before playback.

EQ can be edited manually or imported from a user-selected `ParametricEQ.txt` file. The project distributes no headphone correction database.

## Tests

~~~powershell
./gradlew.bat :app:testDebugUnitTest :usbaudio:testDebugUnitTest :app:lintDebug :usbaudio:lintDebug
./gradlew.bat :app:assembleDebugAndroidTest
./scripts/run-host-audio-tests.ps1
~~~

Android instrumentation requires an explicitly selected test device. Native audio and USB probes have separate setup and gain checks in their scripts. Use synthetic fixtures for decoder tests. [Fixture provenance](docs/test-fixtures.md)

## Licences

Original Evanna application code and original project materials are GPL-3.0-only unless a file states otherwise. Vendored libraries and tools retain their existing licences and attribution. [Project licence](LICENSE) · [Attribution](NOTICE) · [Third-party inventory](THIRD_PARTY.md)

Distribute the matching complete source and build materials alongside APK releases. The source includes the retained FFmpeg archive, native sources, headers, configuration and build scripts. [Native library replacement and source delivery](docs/native-library-replacement.md)
