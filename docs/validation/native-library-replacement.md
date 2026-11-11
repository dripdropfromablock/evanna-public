# Native library replacement validation

Recorded 2026-11-11 against the open-source preparation sources. The [reproduction procedure](../native-library-replacement.md) includes the exact source modifications and commands.

## Environment and source

- Android NDK 28.2.13676358, Clang 19.0.1 based on r530567e, CMake 3.22.1 and build-tools 36.0.0.
- Android emulator API 37 with 16,384-byte pages, x86_64 host and ARM64 translation through `libndk_translation.so`.
- The app and libraries target Android API 28 ARM64.
- The unmodified FFmpeg archive hash matched `8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e` before extraction.
- Both source modifications added `-evanna-replacement-probe` to version strings. The normal source archive, source trees and shipped libraries were kept intact.
- FFmpeg configured as LGPL 2.1-or-later with GPL, nonfree, version3, network protocols and external autodetection disabled.

## Replacement, signing and execution

The verifier first rejected the normal Evanna application ID before installation. The isolated application used `com.evanna.player.lgplreplacement`.

The app ZIP entries for libavutil and libusb were replaced with the rebuilt libraries. All other native library entries retained their original hashes. The replacement APK passed 16 KiB ZIP alignment verification and APK v3 signature verification. The app and instrumentation APK used a generated test key whose certificate differed from the original debug key.

| APK entry | Original SHA-256 | Modified SHA-256 |
| --- | --- | --- |
| `lib/arm64-v8a/libavutil.so` | `446504b31d8c8eacd31205e8897a4a5abcfa0096c64a5321855097d2c772c5ca` | `36671f5b250d8cb99f6e0b41ecda6e9883a380273092855b5bde87f88fb3a50d` |
| `lib/arm64-v8a/libusb-1.0.so` | `8c0c526cc4ae94194f636bc1f0cbcca9254a1589ba876a7a4d7de55c2683054a` | `7ad1aeb1bca1d7f5f2905c700591df901352893e5d709ebb7d9ad02a076d7484` |

The generated replacement signing certificate differed from the original debug certificate.

Both APKs installed successfully. Instrumentation explicitly loaded `libusbaudio.so`, resolving the replaced libusb dependency, then ran the existing decoder probe with the replaced FFmpeg dependency.

The probe passed all 17 synthetic lossless fixtures across WAV, AIFF, FLAC, ALAC, WavPack, APE and TTA with exact literal S32 PCM comparisons. It also passed EOF, partial-read, descriptor-ownership, closed/cancelled-decoder, invalid-buffer, pipe and corrupt-input rejection checks. Android reported `INSTRUMENTATION_CODE: -1`, the runner's success result.

This test opened no audio output or USB device. It verifies replacement, re-signing and decoder execution. Hardware USB behavior and listening qualification use the existing separate device checks.

## App packaging and licence checks

The preparation build passed 409 app JVM tests, 6 USB-module JVM tests and both modules' lint checks. Both app and instrumentation APKs built.

The isolated app APK contained the complete GPL-3.0 text matching the root `LICENSE` byte for byte and omitted `assets/auto_eq.db`. Manual AutoEq text parsing remains covered by `EqualizerConfigTest`. The final normal application build also passed all 415 JVM tests and both modules' lint checks. APK inspection confirmed that every bundled licence text matches its source file and that the normal native library hashes match the original build. libebur128, queue-header and C++ runtime notices are accessible in the licence screen.

A manual UI check opened the headphone card's import action, selected a synthetic text file through Android's document picker, and confirmed the success message plus a -6 dB preamp and one 1 kHz peak band in the parametric editor. The removed bundled-list action was absent.

Local reproduction logs and the generated test key are under ignored `.tools/lgpl-replacement/` and `.tools/open-source-validation/`. Public release source delivery follows the documented procedure and must use the matching public release commit.
