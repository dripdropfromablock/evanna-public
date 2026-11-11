# Native library replacement and source delivery

Evanna's original application code is GPL-3.0-only. FFmpeg and libusb retain LGPL-2.1-or-later, and the remaining components retain the licences recorded in [THIRD_PARTY.md](../THIRD_PARTY.md). Users may modify the libraries, relink or repackage the app, and sign their builds with their own keys.

## Source materials for each release

Distribute the exact source used to build an APK alongside that APK, on the same release page. A source archive from the public release commit must include these materials.

| Component | Corresponding materials |
| --- | --- |
| Evanna and its JNI bridges | `app/src/`, `usbaudio/src/`, Gradle files and wrapper, and build scripts |
| FFmpeg 9.0.2 | `third_party/ffmpeg/ffmpeg-9.0.2.tar.xz`, retained signature and key, `COPYING.LGPLv2.1`, headers, binary hashes, `config.h`, `config_components.h` and `scripts/build-ffmpeg.sh` |
| libusb 1.0.29 | Corresponding `third_party/libusb/` code, including `COPYING` and Android configuration, plus `usbaudio/src/main/cpp/CMakeLists.txt` |
| libsamplerate and libebur128 | Their complete source trees and notices under `third_party/` |
| C++ runtime | Android NDK `28.2.13676358`, with its libc++/libc++abi notices packaged in the app |

The retained FFmpeg source archive has SHA-256 `8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e`. The normal build uses unmodified FFmpeg and libusb sources. Native libraries remain separate APK entries. FFmpeg's build disables GPL, nonfree, version3, networking and protocols.

Generate the archive from the reviewed public release commit and publish it beside the APK.

~~~powershell
git archive --format=zip --prefix=evanna/ --output=evanna-source.zip <public-release-tag>
~~~

Include actual library patches when distributing modified versions. Preserve the applicable licence texts and modification notices. The complete source includes the app code and build materials needed to produce a modified executable. An upstream download link by itself does not provide the application's corresponding source. [GNU LGPL 2.1, section 6](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.en.html) · [FFmpeg distribution checklist](https://ffmpeg.org/legal.html)

## Build the four release APKs

Run the pinned Gradle wrapper with ABI splits enabled.

~~~powershell
./gradlew.bat :app:assembleRelease -PevannaAbiSplits=true
~~~

The unsigned APKs are in app/build/outputs/apk/release/. Use Android SDK zipalign and apksigner with your own signing key. Keep the same key for updates to your installed builds. For a replacement targeting an ABI other than ARM64, pass that ABI to scripts/build-ffmpeg.sh, rebuild libusb with the corresponding ANDROID_ABI, and replace the libraries under lib/<abi>/ in the matching APK. The example below exercises ARM64.

## Rebuild compatible replacements

Use the SDK/NDK versions in the README. Run these commands from the repository root. The test sources and outputs are under the ignored `.tools/lgpl-replacement/` directory.

~~~powershell
$validation = Join-Path $PWD '.tools/lgpl-replacement'
New-Item -ItemType Directory -Force -Path "$validation/ffmpeg-source" | Out-Null
tar -xf third_party/ffmpeg/ffmpeg-9.0.2.tar.xz -C "$validation/ffmpeg-source"
if (-not (Test-Path -LiteralPath "$validation/libusb-source")) {
    Copy-Item -LiteralPath third_party/libusb -Destination "$validation/libusb-source" -Recurse
}

$ffVersion = "$validation/ffmpeg-source/ffmpeg-9.0.2/libavutil/version.c"
$text = [IO.File]::ReadAllText($ffVersion)
$text = $text.Replace('return FFMPEG_VERSION;', 'return FFMPEG_VERSION "-evanna-replacement-probe";')
[IO.File]::WriteAllText($ffVersion, $text)

$usbVersion = "$validation/libusb-source/libusb/version.h"
$text = [IO.File]::ReadAllText($usbVersion)
$text = $text.Replace('#define LIBUSB_RC ""', '#define LIBUSB_RC "-evanna-replacement-probe"')
[IO.File]::WriteAllText($usbVersion, $text)

$env:ANDROID_NDK_ROOT = Join-Path $env:ANDROID_HOME 'ndk/28.2.13676358'
$env:FFMPEG_SOURCE_DIR = "$validation/ffmpeg-source/ffmpeg-9.0.2"
$env:FFMPEG_PREFIX = "$validation/ffmpeg-libs"
& 'C:/Program Files/Git/bin/bash.exe' scripts/build-ffmpeg.sh

$cmakeDir = Join-Path $env:ANDROID_HOME 'cmake/3.22.1/bin'
& "$cmakeDir/cmake.exe" -S usbaudio/src/main/cpp -B "$validation/usb-build" -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$cmakeDir/ninja.exe" `
    "-DCMAKE_TOOLCHAIN_FILE=$env:ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" `
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release `
    "-DLIBUSB_ROOT=$validation/libusb-source" `
    '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,--build-id=sha1'
& "$cmakeDir/cmake.exe" --build "$validation/usb-build" --target usb-1.0 --parallel 2
~~~

The two edits add a version suffix without changing the libraries' public ABI. They are verification modifications, not the libraries distributed in a normal Evanna build. The FFmpeg source and output overrides are optional. Omitting them uses the standard source and output locations. The libusb CMake override similarly defaults to the retained source tree.

## Repackage, sign and run the test

An Android signing key identifies the app's installer. A different key cannot update an installation signed with the original key. The verifier therefore requires a separate application ID, `com.evanna.player.lgplreplacement`. It rejects a normal Evanna APK before installation. It installs only on the explicitly supplied ADB serial.

Create a Gradle initialization script for the isolated test build.

~~~powershell
@'
gradle.beforeProject { project ->
    project.pluginManager.withPlugin('com.android.application') {
        project.extensions.getByName('androidComponents').finalizeDsl { android ->
            android.defaultConfig.applicationId = 'com.evanna.player.lgplreplacement'
        }
    }
}
'@ | Set-Content -LiteralPath "$validation/isolated.gradle"
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest `
    --init-script "$validation/isolated.gradle"
./scripts/test-library-replacement.ps1 `
    -AppApk app/build/outputs/apk/debug/app-debug.apk `
    -TestApk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk `
    -AvutilLibrary "$validation/ffmpeg-libs/lib/libavutil.so" `
    -LibusbLibrary "$validation/usb-build/libusb-1.0.so" `
    -DeviceSerial emulator-5556 -AndroidSdk $env:ANDROID_HOME
~~~

Use an authorized ARM64 device, or an emulator with ARM64 translation. Replace the example serial with the intended test target.

The verifier checks that both supplied libraries differ from the original APK, replaces their ZIP entries, aligns native libraries to 16 KiB, and signs the app and instrumentation APK with a generated test key. It verifies signatures, alignment and replacement hashes before installation. The instrumentation loads `libusbaudio.so` and its replaced libusb dependency, then runs the existing 17-file lossless decoder probe using the replaced FFmpeg dependency. The probe checks literal PCM samples and error handling without opening an audio output.

The generated signing key and logs stay under `.tools/lgpl-replacement/`. The key uses a documented test password and is solely for this isolated verification app. Keep release signing keys separate. Rebuild normally without the initialization script when producing a regular Evanna APK.

The [validation record](validation/native-library-replacement.md) records the tested environment, library hashes and results.
