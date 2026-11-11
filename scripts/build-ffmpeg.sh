#!/usr/bin/env bash
set -euo pipefail
# Run in Git Bash on Windows; source and binaries remain separate from Evanna code.
export PATH="/usr/bin:$PATH"
root=$(cd "$(dirname "$0")/.." && pwd)
ndk=${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to Android NDK 28.2.13676358}
ndk=$(cygpath -u "$ndk")
abi=${1:-arm64-v8a}
case "$abi" in
    arm64-v8a) arch=aarch64; cpu=armv8-a; triple=aarch64-linux-android ;;
    armeabi-v7a) arch=arm; cpu=armv7-a; triple=armv7a-linux-androideabi ;;
    x86) arch=x86; cpu=i686; triple=i686-linux-android ;;
    x86_64) arch=x86_64; cpu=x86-64; triple=x86_64-linux-android ;;
    *) echo "Unsupported Android ABI: $abi" >&2; exit 1 ;;
esac
source_dir=$(cygpath -u "${FFMPEG_SOURCE_DIR:-$root/.tools/ffmpeg/$abi/ffmpeg-9.0.2}")
prefix=$(cygpath -u "${FFMPEG_PREFIX:-$root/third_party/ffmpeg/android/$abi}")
archive="$root/third_party/ffmpeg/ffmpeg-9.0.2.tar.xz"
echo '8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e  '"$archive" | sha256sum -c -
if [[ ! -f "$source_dir/configure" ]]; then
    mkdir -p "$source_dir"
    tar -xf "$archive" --strip-components=1 -C "$source_dir"
fi
toolchain="$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
export PATH="$toolchain:$PATH"
cd "$source_dir"
./configure --prefix=/ --target-os=android --arch="$arch" --cpu="$cpu" \
    --enable-cross-compile --cc="clang --target=${triple}28" \
    --cxx="clang++ --target=${triple}28" --ar=llvm-ar --nm=llvm-nm \
    --host-cc="clang --target=${triple}28" \
    --ranlib=llvm-ranlib --strip=llvm-strip --pkg-config=false \
    --enable-shared --disable-static --disable-symver --enable-pic \
    --extra-ldflags='-Wl,-z,max-page-size=16384 -Wl,--build-id=sha1' \
    --disable-everything --disable-autodetect --disable-network --disable-protocols \
    --disable-gpl --disable-nonfree --disable-version3 --disable-programs --disable-doc \
    --disable-avdevice --disable-avfilter --disable-swscale --enable-swresample \
    --disable-asm --disable-debug \
    --enable-decoder=pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f32be,pcm_f64le,pcm_f64be,flac,alac,mp3float,aac,vorbis,opus,wavpack,ape,tta,dsd_lsbf,dsd_msbf,dsd_lsbf_planar,dsd_msbf_planar \
    --enable-demuxer=wav,aiff,flac,mov,mp3,aac,ogg,wv,ape,tta,dsf,iff --enable-parser=flac,mpegaudio,aac,vorbis,opus
for required in AAC ALAC FLAC MP3FLOAT VORBIS OPUS PCM_F32LE PCM_F64LE WAVPACK APE TTA DSD_LSBF DSD_MSBF DSD_LSBF_PLANAR DSD_MSBF_PLANAR; do
    grep -q "^#define CONFIG_${required}_DECODER 1$" config_components.h || { echo "Missing required decoder: $required" >&2; exit 1; }
done
"$ndk/prebuilt/windows-x86_64/bin/make.exe" -j"${JOBS:-4}"
"$ndk/prebuilt/windows-x86_64/bin/make.exe" DESTDIR="$prefix" install
sed 's/[[:blank:]]*$//' config.h > "$prefix/config.h"
cp config_components.h "$prefix/"
cd "$prefix"
sha256sum lib/*.so > SHA256SUMS
