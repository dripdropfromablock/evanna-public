# FFmpeg 9.0.2 — Evanna offline decoder

Original, unpatched upstream source: [ffmpeg-9.0.2.tar.xz](https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz), retained beside this file with its detached signature and the upstream release public key.

SHA256: `8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e`.
Signature checked 2026-11-02: `FCF986EA15E6E293A5644F10B4322F04D67658D8`, matching the fingerprint on the [official download page](https://ffmpeg.org/download.html). GPG's uncertified-owner warning is expected; the fingerprint was compared separately.

Only LGPL 2.1-or-later components are enabled, following the [FFmpeg licensing checklist](https://ffmpeg.org/legal.html). The archive contains upstream copyright notices and complete license texts; `COPYING.LGPLv2.1` is also retained separately. No upstream source patches. Evanna's JNI bridge is original code in `app/src/main/cpp`.

## Build

On Windows, use Git for Windows Bash, GNU Make 4.3 bundled in NDK **28.2.13676358**, and that NDK's Clang. No MSYS installation, host compiler download, upstream-app build script or WSL is needed:

```powershell
$env:ANDROID_NDK_ROOT=Join-Path $env:ANDROID_HOME 'ndk/28.2.13676358'
& 'C:/Program Files/Git/usr/bin/bash.exe' scripts/build-ffmpeg.sh
```

Run from the repository root. The script verifies the source SHA256, extracts under ignored `.tools/ffmpeg`, configures and builds Android API 28 shared libraries. Pass an ABI as the first argument: `arm64-v8a` (default), `armeabi-v7a`, `x86` or `x86_64`. The output is under `android/<abi>/`. The configure prefix is portable, with installation staged through DESTDIR. The full configure command is in the script; `config.h`, `config_components.h` and `SHA256SUMS` accompany the binaries. `include` contains matching public headers. The configure host-compiler check uses target Clang because this minimal configuration has no enabled host code generators (programs, assembly and hardcoded tables disabled). Do not enable one without providing a real host compiler.

Enabled demuxers: WAV, AIFF, FLAC, MOV/MP4 (ALAC/AAC), MP3, ADTS AAC, Ogg, WavPack (`wv`), Monkey's Audio (`ape`), TTA, DSF and IFF/DSDIFF (`iff`).
Enabled decoders: signed PCM 16/24/32 LE/BE, floating PCM 32/64 LE/BE, FLAC, ALAC, MP3 float, native AAC, Vorbis, native Opus, WavPack, Monkey's Audio, TTA and DSD (`dsd_lsbf`, `dsd_msbf` and their planar forms) — all LGPL in this tree; rebuilt 2026-11-05 with the same source and flags. Parsers: FLAC, MPEG audio, AAC, Vorbis, Opus. The script fails if a required decoder is disabled by unmet dependencies.
Disabled: GPL, nonfree, version3, networking, **all protocols**, programs, external auto-detection, AVDevice, AVFilter, SWScale and assembly. No external codec dependency. SWResample is enabled as the native Opus decoder's internal SILK-to-48-kHz helper, separately shared; Evanna's JNI bridge does not call it to convert source PCM to a DAC rate. Little-endian Android builds dynamically link to `libavformat.so`, `libavcodec.so`, `libavutil.so`, `libswresample.so`, 16 KiB ELF load-page alignment and GNU build IDs. JNI AVIO accepts one local regular descriptor and refuses secondary resource opens; media cannot select a protocol.

`--disable-asm` keeps this first build simple; music-file decoding endurance/CPU measurements remain pending. The UI validates at most ten seconds of fixed-format mono/stereo audio. Lossy or floating output is rounded/saturated to S32, marked processed and never assigned a guessed original integer precision. Seeking reopens and decodes forward under the existing work limits; it is deliberately linear pending validated indexed seek/preroll. RF64/BW64 containers are rejected. This is not a general FFmpeg distribution or a completed music player.

## Library replacement and source distribution

The libraries stay separate `.so` files, dynamically imported by CMake and packaged under `lib/<abi>` in the APK. There is no native signature/hash allowlist blocking modified LGPL libraries. For a compatible replacement, unpack this exact source archive, apply your modifications, rebuild with the script/recorded configuration and public ABI, replace the four libraries and matching headers here, then build Evanna with `./gradlew.bat :app:assembleDebug`. Sign the resulting APK with a key you control (Gradle uses your local debug key for a debug build). The original app source, JNI sources and Gradle/CMake build materials are in this repository; do not share production signing keys.

A different signing key cannot update an already installed app signed by another key. The [tested replacement procedure](../../docs/native-library-replacement.md) uses a separate application ID and a generated test key. Both a modified FFmpeg library and a modified libusb library were accepted by Android and exercised by the decoder probe. See the [validation record](../../docs/validation/native-library-replacement.md) for the environment and hashes.

When distributing APKs, provide this matching complete source archive, any actual patches and build materials from the same distribution location, working notices/source links, and terms preserving the LGPL modification/reverse-engineering rights. The upstream source link alone is not a completed source-distribution/relinking compliance process. The original Evanna application is GPL-3.0-only; the FFmpeg libraries retain LGPL-2.1-or-later.
