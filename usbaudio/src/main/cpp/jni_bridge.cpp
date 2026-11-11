// Modified for Evanna (2026-11-02): checked PCM, safe ownership and USB completion/readiness queries.
// Modified for Evanna (2026-11-04): explicit processed flags and clipping counter in the JNI protocol.
// Modified for Evanna (2026-11-05): crossfeed setter.
// Modified for Evanna (2026-11-07): equalizer bands carry a channel value; unknown types/channels are skipped.
// JNI glue for com.truesample.usbaudio.NativeBridge.
#include <jni.h>

#include <string>
#include <exception>
#include <vector>

#include "Log.h"
#include "UsbStreamer.h"
#include "uac/PcmWrite.h"

namespace {

void throwUsbAudio(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("com/truesample/usbaudio/UsbAudioException");
    if (cls) env->ThrowNew(cls, message.c_str());
}

UsbStreamer* streamer(jlong handle) { return reinterpret_cast<UsbStreamer*>(handle); }

jintArray toIntArray(JNIEnv* env, const std::vector<jint>& values) {
    jintArray array = env->NewIntArray(jsize(values.size()));
    env->SetIntArrayRegion(array, 0, jsize(values.size()), values.data());
    return array;
}

}  // namespace

#define JNI_FN(name) Java_com_truesample_usbaudio_NativeBridge_##name

extern "C" {

JNIEXPORT jlong JNICALL JNI_FN(nativeOpen)(JNIEnv* env, jobject, jint fd, jbyteArray raw) {
    const jsize length = env->GetArrayLength(raw);
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(raw, 0, length, reinterpret_cast<jbyte*>(bytes.data()));

    auto* s = new UsbStreamer();
    std::string error;
    if (!s->open(fd, bytes.data(), bytes.size(), error)) {
        delete s;
        throwUsbAudio(env, error);
        return 0;
    }
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeDescribe)(JNIEnv* env, jobject, jlong h) {
    return env->NewStringUTF(uac::describe(streamer(h)->device()).c_str());
}

// Flattened per output: iface, alt, endpoint, channels, subslot, bits, syncType,
// maxPacketBytes, continuous, minRate, maxRate, rateCount, rates...
JNIEXPORT jintArray JNICALL JNI_FN(nativeFormats)(JNIEnv* env, jobject, jlong h) {
    std::vector<jint> out;
    for (const uac::OutputFormat& f : streamer(h)->device().outputs) {
        out.insert(out.end(), {f.interfaceNumber, f.altSetting, f.endpointAddress, f.channels, f.subslotBytes,
                               f.bitResolution, jint(f.syncType), f.maxPacketBytes, f.continuousRates ? 1 : 0,
                               jint(f.minRate), jint(f.maxRate), jint(f.rates.size())});
        for (uint32_t r : f.rates) out.push_back(jint(r));
    }
    return toIntArray(env, out);
}

// Returns outputIndex, deviceRate, subslotBytes, bitResolution, bitPerfect, outputRate, resampled, processed.
// modeIndex/modeRate pick a fixed output mode; modeIndex < 0 means automatic.
JNIEXPORT jintArray JNICALL JNI_FN(nativeStart)(JNIEnv* env, jobject, jlong h, jint rate, jint bits, jint channels,
                                               jint quality, jint modeIndex, jint modeRate) {
    if (!h || !uac::validPcmSource(rate, bits, channels)) {
        throwUsbAudio(env, "Invalid handle or PCM source format");
        return nullptr;
    }
    UsbStreamer::StreamInfo info;
    std::string error;
    UsbStreamer::Mode mode{modeIndex, uint32_t(modeRate)};
    if (!streamer(h)->start(uint32_t(rate), uint32_t(bits), uint32_t(channels), int(quality),
                            modeIndex >= 0 ? &mode : nullptr, info, error)) {
        throwUsbAudio(env, error);
        return nullptr;
    }
    return toIntArray(env, {info.outputIndex, jint(info.deviceRate), jint(info.subslotBytes),
                            jint(info.bitResolution), info.bitPerfect ? 1 : 0, jint(info.outputRate),
                            info.resampled ? 1 : 0, info.processed ? 1 : 0});
}

JNIEXPORT jint JNICALL JNI_FN(nativeWrite)(JNIEnv* env, jobject, jlong h, jobject buffer, jint frames) {
    if (!h || !buffer || frames < 0) {
        throwUsbAudio(env, "Invalid write handle, buffer or frame count");
        return -1;
    }
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (capacity < 0) {
        throwUsbAudio(env, "write() needs a direct ByteBuffer");
        return -1;
    }
    if (frames == 0) return 0;
    auto* output = streamer(h);
    const uint32_t channels = output->writeChannels();
    if (channels == 0) return -1;
    auto* pcm = static_cast<const int32_t*>(env->GetDirectBufferAddress(buffer));
    if (const char* error = uac::validatePcmWrite(pcm, capacity, frames, channels)) {
        throwUsbAudio(env, error);
        return -1;
    }
    try {
        return jint(output->write(pcm, size_t(frames)));
    } catch (const std::exception& error) {
        throwUsbAudio(env, error.what());
    } catch (...) {
        throwUsbAudio(env, "Native PCM write failed");
    }
    return -1;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeDrain)(JNIEnv* env, jobject, jlong h) {
    if (!h) return JNI_FALSE;
    try { return streamer(h)->drain() ? JNI_TRUE : JNI_FALSE; }
    catch (const std::exception& error) { throwUsbAudio(env, error.what()); }
    catch (...) { throwUsbAudio(env, "Native USB drain failed"); }
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeFeedbackReady)(JNIEnv*, jobject, jlong h) {
    return h && streamer(h)->feedbackReady() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeVolumeVerified)(JNIEnv*, jobject, jlong h) {
    return h && streamer(h)->volumeVerified() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeClockConfigured)(JNIEnv*, jobject, jlong h) {
    return h && streamer(h)->clockConfigured() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeStop)(JNIEnv*, jobject, jlong h) {
    return !h || streamer(h)->stop() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeClose)(JNIEnv* env, jobject, jlong h) {
    if (!h) return;
    if (!streamer(h)->close()) {
        throwUsbAudio(env, "USB cleanup is incomplete; keep the connection and retry close.");
        return;
    }
    delete streamer(h);
}

// Returns available, min, max, res, cur (1/256 dB).
JNIEXPORT jintArray JNICALL JNI_FN(nativeVolumeRange)(JNIEnv* env, jobject, jlong h) {
    const UsbStreamer::VolumeRange r = h ? streamer(h)->volumeRange() : UsbStreamer::VolumeRange{};
    return toIntArray(env, {r.available ? 1 : 0, r.min, r.max, r.res, r.cur});
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeSetVolume)(JNIEnv*, jobject, jlong h, jint value) {
    return h && streamer(h)->setVolume(value) ? JNI_TRUE : JNI_FALSE;
}

// Returns submitted, underruns, errors, disconnected, queued, completed, sealed-input, clipped float values.
JNIEXPORT jlongArray JNICALL JNI_FN(nativeStats)(JNIEnv* env, jobject, jlong h) {
    const UsbStreamer::Stats s = streamer(h)->stats();
    const jlong values[8] = {jlong(s.framesSent), jlong(s.underruns), jlong(s.transferErrors), s.disconnected ? 1 : 0,
        jlong(s.framesQueued), jlong(s.framesCompleted), s.inputEnded ? 1 : 0, jlong(s.clippedSamples)};
    jlongArray array = env->NewLongArray(8);
    if (array) env->SetLongArrayRegion(array, 0, 8, values);
    return array;
}

}  // extern "C"

extern "C" JNIEXPORT void JNICALL JNI_FN(nativeSetLogFile)(JNIEnv* env, jobject, jstring path) {
    if (!path) {
        ualog::setFile(nullptr);
        return;
    }
    const char* p = env->GetStringUTFChars(path, nullptr);
    ualog::setFile(p);
    env->ReleaseStringUTFChars(path, p);
}

// Returns outputRate, dacBits, resampled, bitPerfect, processed — or null for an unsupported format.
extern "C" JNIEXPORT jintArray JNICALL JNI_FN(nativePlan)(JNIEnv* env, jobject, jlong h, jint rate, jint bits,
                                                        jint channels, jint modeIndex, jint modeRate) {
    if (!h || !uac::validPcmSource(rate, bits, channels)) {
        throwUsbAudio(env, "Invalid handle or PCM source format");
        return nullptr;
    }
    UsbStreamer::Plan p;
    std::string error;
    UsbStreamer::Mode mode{modeIndex, uint32_t(modeRate)};
    if (!streamer(h)->plan(uint32_t(rate), uint32_t(bits), uint32_t(channels), modeIndex >= 0 ? &mode : nullptr, p,
                           error)) {
        return nullptr;
    }
    return toIntArray(env, {jint(p.outputRate), jint(p.dacBits), p.resampling ? 1 : 0, p.lossless && !p.processed ? 1 : 0, p.processed ? 1 : 0});
}

// Flattened per mode: outputIndex, sampleRate, bitResolution, subslotBytes, channels.
extern "C" JNIEXPORT jintArray JNICALL JNI_FN(nativeModes)(JNIEnv* env, jobject, jlong h) {
    std::vector<jint> out;
    for (const UsbStreamer::Mode& m : streamer(h)->modes()) {
        out.insert(out.end(), {m.outputIndex, jint(m.sampleRate), jint(m.bitResolution), jint(m.subslotBytes),
                               jint(m.channels)});
    }
    return toIntArray(env, out);
}

// bands: flattened (type, frequencyHz, gainDb, q, channels) per band; an empty array turns the EQ off.
// A band with an unknown type or channel value is skipped.
extern "C" JNIEXPORT void JNICALL JNI_FN(nativeSetEqualizer)(JNIEnv* env, jobject, jlong h, jfloatArray bands,
                                                           jfloat preampDb) {
    const jsize n = env->GetArrayLength(bands);
    std::vector<float> flat(static_cast<size_t>(n));
    env->GetFloatArrayRegion(bands, 0, n, flat.data());
    std::vector<uac::EqBand> list;
    for (size_t i = 0; i + 4 < flat.size(); i += 5) {
        const int type = int(flat[i]), channels = int(flat[i + 4]);
        if (type < 0 || type > 5 || channels < 0 || channels > 2) continue;
        uac::EqBand b;
        b.type = static_cast<uac::EqBand::Type>(type);
        b.frequency = flat[i + 1];
        b.gainDb = flat[i + 2];
        b.q = flat[i + 3];
        b.channels = channels;
        list.push_back(b);
    }
    streamer(h)->setEqualizer(list, preampDb);
}

// cutHz 0 turns crossfeed off; out-of-range values also leave it off (see uac::Crossfeed).
extern "C" JNIEXPORT void JNICALL JNI_FN(nativeSetCrossfeed)(JNIEnv*, jobject, jlong h, jint cutHz, jfloat feedDb) {
    streamer(h)->setCrossfeed(cutHz, feedDb);
}
