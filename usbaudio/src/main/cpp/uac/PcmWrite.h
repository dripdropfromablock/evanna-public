// Added for Evanna (2026-11-01): validation shared by JNI and pure native tests.
#pragma once

#include <cstddef>
#include <cstdint>
#include <limits>

namespace uac {

inline bool validPcmSource(int64_t rate, int64_t bits, int64_t channels) {
    return rate > 0 && rate <= UINT32_MAX && bits > 0 && bits <= 32 && channels > 0 && channels <= 255;
}

inline const char* validatePcmWrite(const void* pcm, int64_t capacity, int64_t frames, uint32_t channels) {
    if (frames < 0) return "frames must not be negative";
    if (capacity < 0) return "write() needs a direct ByteBuffer";
    if (frames == 0) return nullptr;
    if (!pcm || reinterpret_cast<uintptr_t>(pcm) % alignof(int32_t) != 0) return "PCM buffer is null or misaligned";
    if (channels == 0 || channels > 255) return "Invalid active channel count";
    const uint64_t frameBytes = uint64_t(channels) * sizeof(int32_t);
    if (uint64_t(frames) > std::numeric_limits<size_t>::max() / frameBytes) return "PCM byte count overflows";
    if (uint64_t(frames) * frameBytes > uint64_t(capacity)) return "PCM buffer is shorter than the requested frames";
    return nullptr;
}

}  // namespace uac
