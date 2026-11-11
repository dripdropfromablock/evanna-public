# Third-party inventory

Original Evanna application code is GPL-3.0-only. Third-party components retain their own licence terms, copyright notices and source attribution.

| Component | Source and version | Licence and retained materials |
| --- | --- | --- |
| TrueSample USB module | [Pinned upstream source](https://github.com/devang-altf4/truesample-player/tree/cf23a00030cd2d27c91f667737f0ca9aa30256a4/usbaudio) | Apache-2.0. Complete module and licence are under `usbaudio/`. Evanna modifications are identified in file headers and described below. |
| libusb | [1.0.29](https://github.com/libusb/libusb/tree/v1.0.29) | LGPL-2.1-or-later. Library source, Android configuration and COPYING are under `third_party/libusb/`. Built as a separate shared library. |
| libsamplerate | [0.2.2](https://github.com/libsndfile/libsamplerate/tree/0.2.2) | BSD-2-Clause. Library source and COPYING are under `third_party/libsamplerate/`. |
| FFmpeg | [9.0.2](https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz) | LGPL-2.1-or-later build. The complete unmodified archive, signature, public key, licence, headers, configurations and binary hashes are under `third_party/ffmpeg/`. |
| libebur128 | [Pinned 1.2.6 source](https://github.com/jiixyj/libebur128/tree/67b33abe1558160ed76ada1322329b0e9e058b02) | MIT, with BSD-3-Clause for the bundled queue header. Complete source and notices are under `third_party/libebur128/`. |
| Android C++ runtime | Android NDK 28.2.13676358, Clang 19.0.1 based on r530567e | Apache-2.0 with LLVM exceptions and legacy libc++/libc++abi notices. Corresponding notice blocks are in `app/src/main/assets/licenses/NDK-libcxx.txt`. |
| AndroidX Activity, Compose and Media3 | Pins are in `gradle/libs.versions.toml` | Apache-2.0. Runtime source attribution is in the bundled notices. |
| Kotlin, kotlinx.coroutines and Guava | Resolved through the pinned Gradle dependencies | Apache-2.0. Runtime source attribution is in the bundled notices. |
| Gradle wrapper | 8.14.3 | Wrapper files retain their original notices and the distribution checksum. |
| JUnit | 4.13.2, test dependency | EPL-1.0. Test-only dependency. |

## USB module modifications

Evanna adds validated PCM/JNI contracts, descriptor parsing, transport and clock readiness checks, verified hardware volume, cancellation and cleanup, equalizer and crossfeed controls, sample-rate conversion, processing evidence, and tests. The original Apache-2.0 licence and upstream provenance are retained in the module. libusb and libsamplerate source files retain their original notices.

The JNI decoder integration and application/service code are original Evanna code. The full upstream TrueSample application is outside this source snapshot.

## Data and graphics

The bundled headphone correction database is omitted. AutoEq text files are selected by users and remain subject to their owners' rights. The importer is original Evanna code.

The app's launcher is an original geometric vector. Interface icons come from the Apache-2.0 AndroidX icon dependency. Decoder fixtures use generated samples and tones. Artwork fixtures use original solid-colour shapes. The [README banner](docs/assets/evanna-banner.png) is supplied by the project owner. The snapshot omits screenshots, design mockups and four upstream documentation images. The upstream library code and required licence notices are retained. [Fixture provenance](docs/test-fixtures.md)

## Distribution materials

The app packages the complete licence texts under `app/src/main/assets/licenses/` and attribution in `third-party-notices.txt`. These are accessible from the offline notices screen.

FFmpeg's build disables GPL, nonfree, version3, network protocols and external autodetection. libusb and FFmpeg remain separate shared libraries. APK releases must include matching source and build materials and preserve library modification and replacement rights. [Source delivery and replacement procedure](docs/native-library-replacement.md)
