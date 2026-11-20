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
| AndroidX Activity, Compose, Media3 and WebKit | Pins are in `gradle/libs.versions.toml` | Apache-2.0. Runtime source attribution is in the bundled notices. |
| Kotlin, kotlinx.coroutines and Guava | Resolved through the pinned Gradle dependencies | Apache-2.0. Runtime source attribution is in the bundled notices. |
| Gradle wrapper | 8.14.3 | Wrapper files retain their original notices and the distribution checksum. |
| JUnit | 4.13.2, test dependency | EPL-1.0. Test-only dependency. |
| EasyList ad-server domains | [Pinned EasyList source](https://github.com/easylist/easylist/blob/096b9a690e709ef13c2ffb9549654162470e6218/easylist/easylist_adservers.txt) | GPL-3.0-or-later. The EasyList authors are credited in the bundled notices and the domain list. Evanna extracted unqualified hostname rules and added three common ad domains. |
| libtorrent4j and libtorrent | [libtorrent4j 2.1.0-39 on Maven Central](https://repo.maven.apache.org/maven2/org/libtorrent4j/libtorrent4j/2.1.0-39/) and [libtorrent 2.1.0 source](https://github.com/arvidn/libtorrent/tree/24a3adf35d3e689bde700371337aad61b160bafa) | MIT and BSD-3-Clause. The Java wrapper and Android native library provide optional BitTorrent downloads. Both notices are bundled in the app. |
| OpenSSL and Boost in the BitTorrent library | [OpenSSL 3.5.9](https://github.com/openssl/openssl/tree/openssl-3.5.9), [Boost 1.90.0](https://www.boost.org/releases/1.90.0/) | Apache-2.0 and BSL-1.0. OpenSSL has thread support enabled. |
| WebRTC components in the BitTorrent library | [libdatachannel](https://github.com/paullouisageneau/libdatachannel/tree/a4d3211da33bc1114ae50d2dd12f4186b67aaba4), [libjuice](https://github.com/paullouisageneau/libjuice/tree/5948a4162d37bc213d6051b67ee2876ccc5a99a6), [usrsctp](https://github.com/paullouisageneau/usrsctp/tree/fec583d54493f879d2ae44a743423bf8a04371ab), [plog](https://github.com/SergiusTheBest/plog/tree/94899e0b926ac1b0f4750bfbd495167b4a6ae9ef), [try_signal](https://github.com/arvidn/try_signal/tree/105cce59972f925a33aa6b1c3109e4cd3caf583d) | MPL-2.0, BSD-3-Clause and MIT. The corresponding licence and source notices are bundled with the app. |

The ARM64, ARMv7 and x86-64 BitTorrent binaries come from the same published build. [Pinned inputs and build records](https://github.com/shibicb72-design/Orbis/tree/b205c1a5d82eacf4baba8af89376fd9d2fc084c7/native/libtorrent4j), [Published run](https://github.com/shibicb72-design/Orbis/actions/runs/43144313707)

Evanna built the 32-bit x86 binary from the same inputs using NDK r29, `i686-linux-android28-clang`, the `linux-elf` OpenSSL target and the upstream `clang-x86` Boost.Build configuration. OpenSSL's configuration defined `OPENSSL_THREADS`. Its SHA-256 is `49a639359b5f5da6d459f8699283264cd7ceb24121fd28d3340d7d957fcd8c95`. [Upstream x86 build settings](https://github.com/aldenml/libtorrent4j/blob/v2.1.0-39/.github/workflows/android.yml#L152-L209)

## USB module modifications

Evanna adds validated PCM/JNI contracts, descriptor parsing, transport and clock readiness checks, verified hardware volume, cancellation and cleanup, equalizer and crossfeed controls, sample-rate conversion, processing evidence, and tests. The original Apache-2.0 licence and upstream provenance are retained in the module. libusb and libsamplerate source files retain their original notices.

The JNI decoder integration and application/service code are original Evanna code. The full upstream TrueSample application is outside this source snapshot.

## Data and graphics

The bundled headphone correction database is omitted. AutoEq text files are selected by users and remain subject to their owners' rights. The importer is original Evanna code.

The app's launcher is an original geometric vector. Interface icons come from the Apache-2.0 AndroidX icon dependency. Decoder fixtures use generated samples and tones. Artwork fixtures use original solid-colour shapes. The [README banner](docs/assets/evanna-banner.png) is supplied by the project owner. The snapshot omits screenshots, design mockups and four upstream documentation images. The upstream library code and required licence notices are retained. [Fixture provenance](docs/test-fixtures.md)

## Distribution materials

The app packages the complete licence texts under `app/src/main/assets/licenses/` and attribution in `third-party-notices.txt`. These are accessible from the offline notices screen.

FFmpeg's build disables GPL, nonfree, version3, network protocols and external autodetection. libusb and FFmpeg remain separate shared libraries. APK releases must include matching source and build materials and preserve library modification and replacement rights. [Source delivery and replacement procedure](docs/native-library-replacement.md)
