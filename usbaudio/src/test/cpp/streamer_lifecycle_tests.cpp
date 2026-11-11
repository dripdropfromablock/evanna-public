// Evanna: real UsbStreamer/JNI code with an injected libusb transport, never a physical DAC.
#include <libusb.h>
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <future>
#include <mutex>
#include <map>
#include <vector>
#include <cmath>
#include <samplerate.h>
#include "../../main/cpp/UsbStreamer.h"

using namespace std::chrono_literals;
static std::atomic<int> checks{0};
#define CHECK(value) do { ++checks; if (!(value)) { \
    std::printf("FAIL line %d: %s\n", __LINE__, #value); std::fflush(stdout); std::exit(1); } } while (0)

// Model libusb's event lock and asynchronous cancellation: submission/cancellation
// never invokes a callback inline, and completion keeps the event lock until return.
struct libusb_context { std::mutex events; };
struct libusb_device_handle { libusb_context* context; };
static std::mutex queueMutex;
static std::condition_variable queueChanged;
static std::vector<libusb_transfer*> pending;
static bool deliver = false, holdEventReturn = false, callbackReturned = false;
static std::atomic<int> submits{0};
static int failSubmitAt = 0;
static int clockReply = LIBUSB_ERROR_NOT_SUPPORTED;
static int selectorPin = 1;
static int clockSets = 0;
static int clockReadback = -1;
static uint32_t clockRangeRate = 48000;
static int allocationAttempt = 0, failAllocationAt = 0;
static std::atomic<int> allocated{0}, freed{0}, closed{0}, exited{0}, idle{0};
static bool feedbackUpdates = true;
static uint32_t feedbackWord = 48u << 14; // full-speed 48 frames/ms, USB 10.14 encoding
static auto feedbackDue = std::chrono::steady_clock::now();
static int feedbackPacketStatus = LIBUSB_TRANSFER_COMPLETED;
static unsigned feedbackLength = 3;
static int dataFault = 0;
static int dataCredits = 0;
static std::vector<uint8_t> completedAudio;
static bool holdFeedback = false, feedbackHeld = false;
struct VolumeControl {
    int16_t min = -60 * 256, max = 0, step = 256, current = -12 * 256;
    bool failSet = false, mismatch = false, failCurrent = false, failReadback = false;
    int sets = 0;
    int failureCode = LIBUSB_ERROR_IO;
    int rangeCount = 1;
};
static std::map<uint16_t, VolumeControl> volumes;
static std::vector<uint16_t> volumeWrites;
static int muteWrites = 0, muteState = 0;
static std::atomic<int> audioCompletions{0}, nonzeroAudioSubmissions{0};

extern "C" {
int libusb_set_option(libusb_context*, libusb_option, ...) { return 0; }
int libusb_init_context(libusb_context** ctx, const libusb_init_option*, int) { *ctx = new libusb_context; return 0; }
void libusb_set_log_cb(libusb_context*, libusb_log_cb, int) {}
int libusb_wrap_sys_device(libusb_context* ctx, intptr_t, libusb_device_handle** h) {
    *h = new libusb_device_handle{ctx}; return 0;
}
int libusb_claim_interface(libusb_device_handle*, int) { return 0; }
int libusb_release_interface(libusb_device_handle*, int) { return 0; }
int libusb_attach_kernel_driver(libusb_device_handle*, int) { return 0; }
libusb_device* libusb_get_device(libusb_device_handle*) { return nullptr; }
int libusb_get_device_speed(libusb_device*) { return LIBUSB_SPEED_FULL; }
int libusb_set_interface_alt_setting(libusb_device_handle*, int, int alt) { if (!alt) ++idle; return 0; }
int libusb_control_transfer(libusb_device_handle*, uint8_t type, uint8_t request, uint16_t value, uint16_t index,
                            unsigned char* bytes, uint16_t length, unsigned int) {
    const uint16_t key = uint16_t((index & 0xff00) | (value & 0xff));
    if (type == 0xa1 && request == 1 && index == 0x2800 && length == 1) {
        if (selectorPin < 0) return LIBUSB_ERROR_IO;
        bytes[0] = uint8_t(selectorPin);
        return 1;
    }
    if (type == 0xa1 && request == 1 && value == 0x0100 && length == 4 && clockReadback >= 0) {
        for (int b = 0; b < 4; ++b) bytes[b] = uint8_t(uint32_t(clockReadback) >> (8 * b));
        return 4;
    }
    const auto found = volumes.find(key);
    if (found != volumes.end() && (type == 0xa1 || type == 0x21)) {
        auto& volume = found->second;
        if ((value >> 8) == 1 && length == 1) {
            if (type == 0x21) ++muteWrites;
            else bytes[0] = uint8_t(muteState);
            return 1;
        }
        if ((value >> 8) != 2) return LIBUSB_ERROR_NOT_SUPPORTED;
        const auto put = [&](int offset, int16_t v) { bytes[offset] = uint8_t(v); bytes[offset + 1] = uint8_t(uint16_t(v) >> 8); };
        if (type == 0x21 && length == 2) {
            volumeWrites.push_back(key);
            ++volume.sets;
            if (volume.failSet) return volume.failureCode;
            volume.current = int16_t(bytes[0] | (bytes[1] << 8));
            return 2;
        }
        if (request == 2 && length == 8) {
            bytes[0] = uint8_t(volume.rangeCount); bytes[1] = 0;
            put(2, volume.min); put(4, volume.max); put(6, volume.step);
            return 8;
        }
        if (length != 2) return LIBUSB_ERROR_NOT_SUPPORTED;
        if (request == 0x82) put(0, volume.min);
        else if (request == 0x83) put(0, volume.max);
        else if (request == 0x84) put(0, volume.step);
        else if (request == 0x81 || request == 1) {
            if (volume.failCurrent || (volume.failReadback && volume.sets)) return LIBUSB_ERROR_IO;
            put(0, int16_t(volume.current + (volume.mismatch && volume.sets ? volume.step : 0)));
        } else return LIBUSB_ERROR_NOT_SUPPORTED;
        return 2;
    }
    if (type == 0xa1 && request == 2 && (length == 2 || length == 14)) {
        std::fill(bytes, bytes + length, 0);
        bytes[0] = 1;
        if (length == 14) for (int b = 0; b < 4; ++b)
            bytes[2 + b] = bytes[6 + b] = uint8_t(clockRangeRate >> (8 * b));
        return length; // one discrete UAC2 clock rate: 48 kHz
    }
    if (type == 0x22 || type == 0x21) { ++clockSets; return clockReply; }
    return LIBUSB_ERROR_NOT_SUPPORTED;
}
const char* libusb_error_name(int) { return "injected transport result"; }
libusb_transfer* libusb_alloc_transfer(int packets) {
    if (++allocationAttempt == failAllocationAt) return nullptr;
    ++allocated;
    return static_cast<libusb_transfer*>(std::calloc(1, sizeof(libusb_transfer) + packets * sizeof(libusb_iso_packet_descriptor)));
}
void libusb_free_transfer(libusb_transfer* transfer) {
    std::lock_guard<std::mutex> lock(queueMutex);
    CHECK(std::find(pending.begin(), pending.end(), transfer) == pending.end());
    ++freed; std::free(transfer);
}
int libusb_submit_transfer(libusb_transfer* transfer) {
    std::lock_guard<std::mutex> lock(queueMutex);
    if (++submits == failSubmitAt) return LIBUSB_ERROR_IO;
    CHECK(std::find(pending.begin(), pending.end(), transfer) == pending.end());
    if (!(transfer->endpoint & 0x80) &&
        std::any_of(transfer->buffer, transfer->buffer + transfer->length, [](uint8_t byte) { return byte != 0; }))
        ++nonzeroAudioSubmissions;
    transfer->status = LIBUSB_TRANSFER_COMPLETED;
    pending.push_back(transfer);
    return 0;
}
int libusb_cancel_transfer(libusb_transfer* transfer) {
    std::lock_guard<std::mutex> lock(queueMutex);
    if (std::find(pending.begin(), pending.end(), transfer) == pending.end()) return LIBUSB_ERROR_NOT_FOUND;
    transfer->status = LIBUSB_TRANSFER_CANCELLED;
    queueChanged.notify_all();
    return 0;
}
void libusb_interrupt_event_handler(libusb_context*) { queueChanged.notify_all(); }
int libusb_handle_events_timeout_completed(libusb_context* ctx, timeval*, int*) {
    std::unique_lock<std::mutex> events(ctx->events);
    std::unique_lock<std::mutex> lock(queueMutex);
    const auto cancelled = [] {
        return std::find_if(pending.begin(), pending.end(), [](auto* t) { return t->status == LIBUSB_TRANSFER_CANCELLED; });
    };
    const auto feedback = [] {
        return std::find_if(pending.begin(), pending.end(), [](auto* t) {
            return feedbackUpdates && (t->endpoint & 0x80) && t->status == LIBUSB_TRANSFER_COMPLETED &&
                std::chrono::steady_clock::now() >= feedbackDue;
        });
    };
    const auto data = [] {
        return std::find_if(pending.begin(), pending.end(), [](auto* t) {
            return (dataFault || dataCredits > 0) && !(t->endpoint & 0x80) && t->status == LIBUSB_TRANSFER_COMPLETED;
        });
    };
    queueChanged.wait_for(lock, 5ms, [&] {
        return (deliver && cancelled() != pending.end()) || feedback() != pending.end() || data() != pending.end();
    });
    auto at = deliver ? cancelled() : pending.end();
    if (at == pending.end()) at = feedback();
    if (at == pending.end()) at = data();
    if (at == pending.end()) return 0;
    auto* transfer = *at;
    pending.erase(at);
    if (transfer->status == LIBUSB_TRANSFER_COMPLETED && (transfer->endpoint & 0x80)) {
        feedbackDue = std::chrono::steady_clock::now() + 5ms;
        for (int i = 0; i < transfer->num_iso_packets; ++i) {
            auto& packet = transfer->iso_packet_desc[i];
            packet.status = static_cast<libusb_transfer_status>(feedbackPacketStatus);
            packet.actual_length = feedbackLength;
            auto* bytes = libusb_get_iso_packet_buffer(transfer, unsigned(i));
            for (int b = 0; b < 3; ++b) bytes[b] = uint8_t(feedbackWord >> (8 * b));
        }
        if (holdFeedback) {
            feedbackHeld = true;
            queueChanged.notify_all();
            queueChanged.wait(lock, [] { return !holdFeedback; });
        }
    } else if (transfer->status == LIBUSB_TRANSFER_COMPLETED) {
        if (dataCredits > 0) --dataCredits;
        for (int i = 0; i < transfer->num_iso_packets; ++i) {
            auto& packet = transfer->iso_packet_desc[i];
            packet.status = LIBUSB_TRANSFER_COMPLETED;
            packet.actual_length = packet.length;
        }
        if (dataFault == 1) transfer->iso_packet_desc[0].status = LIBUSB_TRANSFER_ERROR;
        if (dataFault == 2) --transfer->iso_packet_desc[0].actual_length;
        if (dataFault == 3) transfer->status = LIBUSB_TRANSFER_NO_DEVICE;
        if (dataFault == 0 || dataFault == 4) {
            for (int i = 0; i < transfer->num_iso_packets; ++i) {
                const auto* bytes = libusb_get_iso_packet_buffer(transfer, unsigned(i));
                completedAudio.insert(completedAudio.end(), bytes, bytes + transfer->iso_packet_desc[i].length);
            }
        }
        dataFault = 0;
    }
    const bool audioCompletion = transfer->status == LIBUSB_TRANSFER_COMPLETED && !(transfer->endpoint & 0x80);
    lock.unlock();
    transfer->callback(transfer);
    lock.lock();
    if (audioCompletion) ++audioCompletions;
    callbackReturned = pending.empty();
    queueChanged.notify_all();
    if (callbackReturned) queueChanged.wait(lock, [] { return !holdEventReturn; });
    return 0;
}
void libusb_close(libusb_device_handle* h) { ++closed; delete h; }
void libusb_exit(libusb_context* ctx) { ++exited; delete ctx; }
}

// Synthetic UAC1, fixed 48 kHz stereo 16-bit synchronous output; no volume control.
static const uint8_t descriptors[] = {
    9, 2, 0, 0, 2, 1, 0, 0x80, 50,
    9, 4, 0, 0, 0, 1, 1, 0, 0,
    9, 0x24, 1, 0, 1, 0, 0, 1, 1,
    9, 4, 1, 0, 0, 1, 2, 0, 0,
    9, 4, 1, 1, 1, 1, 2, 0, 0,
    7, 0x24, 1, 1, 1, 1, 0,
    11, 0x24, 2, 1, 2, 2, 16, 1, 0x80, 0xbb, 0,
    9, 5, 3, 0x0d, 0xc0, 0, 1, 0, 0,
};

static void allowCallbacks() {
    std::lock_guard<std::mutex> lock(queueMutex);
    deliver = true;
    holdEventReturn = false;
    queueChanged.notify_all();
}

static void reset() {
    CHECK(pending.empty());
    deliver = holdEventReturn = callbackReturned = false;
    submits = failSubmitAt = 0;
    clockReply = LIBUSB_ERROR_NOT_SUPPORTED;
    selectorPin = 1; clockSets = 0; clockReadback = -1; clockRangeRate = 48000;
    allocationAttempt = failAllocationAt = 0;
    feedbackUpdates = true;
    feedbackWord = 48u << 14;
    feedbackDue = std::chrono::steady_clock::now();
    feedbackPacketStatus = LIBUSB_TRANSFER_COMPLETED;
    feedbackLength = 3;
    dataFault = 0;
    dataCredits = 0; completedAudio.clear();
    holdFeedback = feedbackHeld = false;
    volumes.clear(); volumeWrites.clear(); muteWrites = muteState = 0;
    audioCompletions = nonzeroAudioSubmissions = 0;
    allocated = freed = closed = exited = idle = 0;
}

static void addVolumeGraph(std::vector<uint8_t>& raw, bool master = true) {
    raw.insert(raw.begin() + 27, {
        12, 0x24, 2, 1, 1, 1, 0, 2, 3, 0, 0, 0,
        10, 0x24, 6, 2, 1, 1, uint8_t(master ? 3 : 0), uint8_t(master ? 0 : 2), uint8_t(master ? 0 : 2), 0,
        9, 0x24, 3, 3, 2, 3, 0, 2, 0,
    });
}

static bool openAndStart(UsbStreamer& output, bool feedback = false, bool volume = false, uint32_t sourceRate = 48000) {
    std::vector<uint8_t> raw(std::begin(descriptors), std::end(descriptors));
    if (feedback) {
        raw[40] = 2; // streaming alternate setting exposes data and feedback endpoints
        raw[raw.size() - 6] = 0x05; // asynchronous data endpoint
        raw.back() = 0x83;
        raw.insert(raw.end(), {9, 5, 0x83, 0x11, 3, 0, 1, 0, 0});
    }
    if (volume) { addVolumeGraph(raw); volumes.emplace(0x0200, VolumeControl{}); }
    UsbStreamer::StreamInfo info;
    std::string error;
    CHECK(output.open(-1, raw.data(), raw.size(), error));
    const bool started = output.start(sourceRate, 16, 2, 0, nullptr, info, error);
    return started && (!volume || output.setVolume(-30 * 256));
}

extern "C" jboolean Java_com_truesample_usbaudio_NativeBridge_nativeStop(JNIEnv*, jobject, jlong);
extern "C" jboolean Java_com_truesample_usbaudio_NativeBridge_nativeFeedbackReady(JNIEnv*, jobject, jlong);
extern "C" jboolean Java_com_truesample_usbaudio_NativeBridge_nativeVolumeVerified(JNIEnv*, jobject, jlong);
extern "C" void Java_com_truesample_usbaudio_NativeBridge_nativeClose(JNIEnv*, jobject, jlong);
static int exceptions = 0;

static void timedOutCloseRetainsOwnerAndUnblocksWriter(JNIEnv& env, bool feedback) {
    reset();
    auto* output = new UsbStreamer;
    CHECK(openAndStart(*output, feedback, true));
    CHECK(allocated == (feedback ? 10 : 8));
    std::promise<void> writing;
    auto writer = std::async(std::launch::async, [&] {
        std::vector<int32_t> silence(48000 * 2);
        writing.set_value();
        return output->write(silence.data(), 48000);
    });
    writing.get_future().wait();
    CHECK(writer.wait_for(50ms) == std::future_status::timeout);
    const auto handle = reinterpret_cast<jlong>(output);
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeStop(&env, nullptr, handle) == JNI_FALSE);
    CHECK(writer.wait_for(1s) == std::future_status::ready && writer.get() == -1);
    CHECK(freed == 0 && idle == 0 && closed == 0 && exited == 0);
    exceptions = 0;
    Java_com_truesample_usbaudio_NativeBridge_nativeClose(&env, nullptr, handle);
    CHECK(exceptions == 1 && closed == 0 && exited == 0 && freed == 0);
    UsbStreamer::StreamInfo info;
    std::string error;
    const int before = submits;
    CHECK(!output->start(48000, 16, 2, 0, nullptr, info, error));
    CHECK(submits == before && idle == 0 && freed == 0);
    allowCallbacks();
    exceptions = 0;
    Java_com_truesample_usbaudio_NativeBridge_nativeClose(&env, nullptr, handle);
    CHECK(exceptions == 0);
    CHECK(freed == allocated && closed == 1 && exited == 1);
}

static void zeroInFlightStillWaitsForEventReturn() {
    reset();
    UsbStreamer output;
    CHECK(openAndStart(output));
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        deliver = holdEventReturn = true;
    }
    auto stopped = std::async(std::launch::async, [&] { return output.stop(); });
    {
        std::unique_lock<std::mutex> lock(queueMutex);
        CHECK(queueChanged.wait_for(lock, 5s, [] { return callbackReturned; }));
    }
    CHECK(stopped.wait_for(5s) == std::future_status::ready && !stopped.get());
    CHECK(freed == 0 && idle == 0 && closed == 0 && exited == 0);
    allowCallbacks();
    CHECK(output.stop());
    CHECK(freed == allocated);
    CHECK(output.close());
}

static void partialSubmissionRetainsPendingTransfers(int failAt, bool feedback = false) {
    reset();
    failSubmitAt = failAt;
    UsbStreamer output;
    CHECK(!openAndStart(output, feedback));
    CHECK(freed == (failAt == 1 ? allocated.load() : 0));
    CHECK(closed == 0 && exited == 0);
    allowCallbacks();
    CHECK(output.close());
    CHECK(freed == allocated && closed == 1 && exited == 1);
}

static void stopRestartAndDoubleClose() {
    reset();
    UsbStreamer output;
    CHECK(openAndStart(output));
    allowCallbacks();
    CHECK(output.stop());
    CHECK(freed == allocated && closed == 0 && exited == 0);
    UsbStreamer::StreamInfo info;
    std::string error;
    CHECK(output.start(48000, 16, 2, 0, nullptr, info, error));
    CHECK(output.close());
    CHECK(output.close());
    CHECK(freed == allocated && closed == 1 && exited == 1);
}

static void directDestructorWaitsForCallbacks() {
    reset();
    auto* output = new UsbStreamer;
    CHECK(openAndStart(*output));
    auto destroyed = std::async(std::launch::async, [output] { delete output; });
    {
        std::unique_lock<std::mutex> lock(queueMutex);
        CHECK(queueChanged.wait_for(lock, 5s, [] {
            return !pending.empty() && pending.front()->status == LIBUSB_TRANSFER_CANCELLED;
        }));
    }
    CHECK(destroyed.wait_for(1500ms) == std::future_status::timeout);
    CHECK(freed == 0 && closed == 0 && exited == 0);
    allowCallbacks();
    CHECK(destroyed.wait_for(5s) == std::future_status::ready);
    destroyed.get();
    CHECK(freed == allocated && closed == 1 && exited == 1);
}

static std::vector<uint8_t> uac2ClockFixture() {
    return {
            9, 2, 0, 0, 2, 1, 0, 0x80, 50,
            9, 4, 0, 0, 0, 1, 1, 0x20, 0,
            9, 0x24, 1, 0, 2, 4, 0, 0, 0,
            8, 0x24, 0x0a, 0x29, 3, 7, 0, 0,
            17, 0x24, 2, 1, 1, 1, 0, 0x29, 2, 3, 0, 0, 0, 0, 0, 0, 0,
            9, 4, 1, 0, 0, 1, 2, 0x20, 0,
            9, 4, 1, 1, 1, 1, 2, 0x20, 0,
            16, 0x24, 1, 1, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0,
            6, 0x24, 2, 1, 2, 16,
            7, 5, 3, 0x0d, 0xc0, 0, 1,
    };
}

static void clockSettingMustComplete(bool uac2) {
    for (int reply : {int(LIBUSB_ERROR_IO), 0, 1, 2, 3, 4}) {
        reset();
        clockReply = reply;
        auto raw = std::vector<uint8_t>(std::begin(descriptors), std::end(descriptors));
        raw.insert(raw.end(), {7, 0x25, 1, 1, 0, 0, 0});
        if (uac2) raw = uac2ClockFixture();
        UsbStreamer output;
        std::string error;
        UsbStreamer::StreamInfo info;
        CHECK(output.open(-1, raw.data(), raw.size(), error));
        const bool success = reply == (uac2 ? 4 : 3);
        CHECK(output.start(48000, 16, 2, 0, nullptr, info, error) == success);
        CHECK(output.clockConfigured() == success);
        if (success) {
            CHECK(info.deviceRate == 0); // Missing readback remains unknown after successful configuration.
            allowCallbacks();
        } else CHECK(submits == 0 && allocated == 0);
        CHECK(output.close());
        CHECK(!output.clockConfigured());
    }
}

static void unsupportedModesSubmitNothing() {
    for (int variant : {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}) {
        reset();
        auto raw = std::vector<uint8_t>(std::begin(descriptors), std::end(descriptors));
        if (variant == 0) raw[raw.size() - 6] = 0x05; // async, no explicit feedback
        if (variant == 1) raw[raw.size() - 3] = 0; // invalid interval
        if (variant == 2) raw[raw.size() - 3] = 5; // unsupported interval scheduling
        if (variant == 3) raw[57] = 0; // zero subslot
        if (variant == 4) raw[58] = 24; // precision exceeds 16-bit slot
        if (variant == 5) {
            raw[52] = 14; raw[59] = 0; // continuous range with min 48 kHz > max 44.1 kHz
            raw.insert(raw.begin() + 63, {0x44, 0xac, 0});
        }
        if (variant == 6) raw[raw.size() - 6] = 0x01; // unspecified sync type
        if (variant >= 7) {
            raw[raw.size() - 6] = 0x05; raw.back() = 0x83;
            raw.insert(raw.end(), {9, 5, 0x83, 0x11, 3, 0, 1, 0, 0});
            if (variant == 7) raw[raw.size() - 5] = 2; // short feedback packet
            if (variant == 8) raw[raw.size() - 3] = 4; // driver-unsupported full-speed feedback interval
            if (variant == 9) raw[71] = raw[raw.size() - 7] = 0x80; // reserved endpoint zero
        }
        UsbStreamer output;
        std::string error;
        UsbStreamer::StreamInfo info;
        UsbStreamer::Plan plan;
        CHECK(output.open(-1, raw.data(), raw.size(), error));
        CHECK(output.modes().empty());
        CHECK(!output.plan(48000, 16, 2, nullptr, plan, error));
        UsbStreamer::Mode fixed{0, 48000};
        CHECK(!output.plan(48000, 16, 2, &fixed, plan, error));
        CHECK(!output.start(48000, 16, 2, 0, nullptr, info, error));
        CHECK(!output.start(48000, 16, 2, 0, &fixed, info, error));
        CHECK(submits == 0 && allocated == 0);
        CHECK(output.close());
    }
}

static void allocationFailuresCleanUp() {
    for (int failAt = 1; failAt <= 10; ++failAt) {
        reset();
        failAllocationAt = failAt;
        UsbStreamer output;
        CHECK(!openAndStart(output, true));
        if (failAt <= 8) CHECK(submits == 0 && freed == allocated);
        else CHECK(freed == 0 && closed == 0);
        allowCallbacks();
        CHECK(output.close());
        CHECK(freed == allocated && closed == 1 && exited == 1);
    }
}

static void adaptiveModeRemainsEligible() {
    reset();
    auto raw = std::vector<uint8_t>(std::begin(descriptors), std::end(descriptors));
    raw[raw.size() - 6] = 0x09;
    UsbStreamer output;
    UsbStreamer::StreamInfo info;
    std::string error;
    CHECK(output.open(-1, raw.data(), raw.size(), error));
    CHECK(output.modes().size() == 1);
    CHECK(output.start(48000, 16, 2, 0, nullptr, info, error));
    allowCallbacks();
    CHECK(output.close());
}

static void missingFeedbackCannotStart() {
    reset();
    feedbackUpdates = false;
    allowCallbacks();
    UsbStreamer output;
    CHECK(!openAndStart(output, true));
    CHECK(output.stats().framesSent == 0);
    CHECK(output.close());
}

static void waitForTransportFailure(UsbStreamer& output) {
    const auto deadline = std::chrono::steady_clock::now() + 3s;
    while (output.feedbackReady() && std::chrono::steady_clock::now() < deadline)
        std::this_thread::sleep_for(5ms);
    CHECK(!output.feedbackReady());
}

static void unusableFeedbackCannotStart() {
    for (int scenario : {0, 1, 2, 3}) {
        reset();
        if (scenario == 0) feedbackWord = 0;
        if (scenario == 1) feedbackWord = 50u << 14; // plausible rate, but exceeds endpoint capacity
        if (scenario == 2) feedbackLength = 4; // exceeds the advertised 3-byte buffer
        if (scenario == 3) feedbackPacketStatus = LIBUSB_TRANSFER_ERROR;
        allowCallbacks();
        UsbStreamer output;
        CHECK(!openAndStart(output, true));
        CHECK(!output.feedbackReady());
        CHECK(output.stats().framesSent == 0);
        CHECK(output.close());
    }
}

static void staleFeedbackUnblocksWriter() {
    for (bool sendZeros : {false, true}) {
        reset();
        UsbStreamer output;
        CHECK(!output.feedbackReady());
        CHECK(openAndStart(output, true, true));
        CHECK(output.feedbackReady());
        CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeFeedbackReady(
            nullptr, nullptr, reinterpret_cast<jlong>(&output)) == JNI_TRUE);
        auto writer = std::async(std::launch::async, [&] {
            std::vector<int32_t> silence(48000 * 2);
            return output.write(silence.data(), 48000);
        });
        CHECK(writer.wait_for(50ms) == std::future_status::timeout);
        {
            std::lock_guard<std::mutex> lock(queueMutex);
            feedbackUpdates = sendZeros;
            feedbackWord = 0;
        }
        CHECK(writer.wait_for(3s) == std::future_status::ready && writer.get() == -1);
        waitForTransportFailure(output);
        CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeFeedbackReady(
            nullptr, nullptr, reinterpret_cast<jlong>(&output)) == JNI_FALSE);
        // Wait for the event-loop watchdog as well as the writer's freshness gate.
        const auto deadline = std::chrono::steady_clock::now() + 1s;
        while (!output.stats().transferErrors && std::chrono::steady_clock::now() < deadline)
            std::this_thread::sleep_for(5ms);
        CHECK(output.stats().transferErrors > 0);
        int32_t silence[2]{};
        CHECK(output.write(silence, 1) == -1);
        allowCallbacks();
        CHECK(output.close());
        CHECK(!output.feedbackReady());
    }
}

static void feedbackResubmitFailureStopsStream() {
    reset();
    UsbStreamer output;
    CHECK(openAndStart(output, true));
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        failSubmitAt = submits + 1;
    }
    waitForTransportFailure(output);
    CHECK(output.stats().transferErrors > 0);
    allowCallbacks();
    CHECK(output.close());
}

static void packetFailuresStopStream() {
    for (int fault : {1, 2, 3}) {
        reset();
        UsbStreamer output;
        CHECK(openAndStart(output));
        CHECK(output.feedbackReady()); // Synchronous output does not require feedback packets.
        {
            std::lock_guard<std::mutex> lock(queueMutex);
            dataFault = fault;
            queueChanged.notify_all();
        }
        waitForTransportFailure(output);
        CHECK(fault == 3 ? output.stats().disconnected : output.stats().transferErrors > 0);
        allowCallbacks();
        CHECK(output.close());
    }
}

static void lateValidFeedbackCannotReviveStream() {
    reset();
    UsbStreamer output;
    CHECK(openAndStart(output, true));
    {
        std::unique_lock<std::mutex> lock(queueMutex);
        holdFeedback = true;
        CHECK(queueChanged.wait_for(lock, 3s, [] { return feedbackHeld; }));
    }
    // Deliberately age the last accepted feedback while the event handler is held.
    std::this_thread::sleep_for(600ms);
    CHECK(!output.feedbackReady());
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        holdFeedback = false;
        queueChanged.notify_all();
    }
    const auto deadline = std::chrono::steady_clock::now() + 1s;
    while (!output.stats().transferErrors && std::chrono::steady_clock::now() < deadline)
        std::this_thread::sleep_for(5ms);
    CHECK(!output.feedbackReady() && output.stats().transferErrors > 0);
    allowCallbacks();
    CHECK(output.close());
}

static void invalidFirstValueDoesNotPoisonFeedbackFormat() {
    reset();
    feedbackWord = 1;
    holdFeedback = true;
    allowCallbacks();
    UsbStreamer output;
    auto started = std::async(std::launch::async, [&] { return openAndStart(output, true); });
    {
        std::unique_lock<std::mutex> lock(queueMutex);
        CHECK(queueChanged.wait_for(lock, 3s, [] { return feedbackHeld; }));
        feedbackWord = 48u << 14;
        holdFeedback = false;
        queueChanged.notify_all();
    }
    CHECK(started.wait_for(3s) == std::future_status::ready && started.get());
    CHECK(output.feedbackReady());
    CHECK(output.close());
}

static void openingDoesNotChangeHardwareVolume() {
    reset();
    auto raw = std::vector<uint8_t>(std::begin(descriptors), std::end(descriptors));
    addVolumeGraph(raw);
    volumes.emplace(0x0200, VolumeControl{});
    UsbStreamer output;
    std::string error;
    CHECK(output.open(-1, raw.data(), raw.size(), error));
    CHECK(volumeWrites.empty() && muteWrites == 0);
    CHECK(!output.volumeVerified() && !output.setVolume(-30 * 256));
    CHECK(!output.volumeRange().available);
    CHECK(output.close());
}

static void allVolumeControlsMustVerify() {
    for (int scenario = 0; scenario < 9; ++scenario) {
        reset();
        auto raw = std::vector<uint8_t>(std::begin(descriptors), std::end(descriptors));
        addVolumeGraph(raw, false);
        volumes.emplace(0x0201, VolumeControl{});
        volumes.emplace(0x0202, VolumeControl{});
        auto& right = volumes.at(0x0202);
        if (scenario == 0) right.current = -18 * 256;
        if (scenario == 1) right.failSet = true;
        if (scenario == 2) right.mismatch = true;
        if (scenario == 3) right.failReadback = true;
        if (scenario == 4) right.failCurrent = true;
        if (scenario == 5) right.step = 0;
        if (scenario == 6) right.max = right.min;
        if (scenario == 7) right.step = 512;
        UsbStreamer output;
        UsbStreamer::StreamInfo info;
        std::string error;
        CHECK(output.open(-1, raw.data(), raw.size(), error));
        CHECK(volumeWrites.empty());
        CHECK(output.start(48000, 16, 2, 0, nullptr, info, error));
        int32_t sample[2]{65536, -65536};
        CHECK(output.write(sample, 1) == -1 && !output.volumeVerified());
        const int value = -30 * 256 + (scenario == 8 ? 1 : 0);
        const bool success = scenario == 0 || scenario == 7;
        CHECK(output.setVolume(value) == success);
        CHECK(output.volumeVerified() == success);
        CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeVolumeVerified(
            nullptr, nullptr, reinterpret_cast<jlong>(&output)) == (success ? JNI_TRUE : JNI_FALSE));
        if (scenario >= 4 && scenario != 7) CHECK(volumeWrites.empty());
        if (scenario == 7) CHECK(!output.volumeRange().available);
        if (scenario == 0) {
            CHECK(output.write(sample, 1) == 1);
            CHECK(output.volumeRange().available && output.volumeRange().cur == value);
            CHECK(output.setVolume(-24 * 256));
        }
        right.failSet = right.mismatch = right.failReadback = right.failCurrent = false;
        allowCallbacks();
        CHECK(output.close());
        CHECK(!output.volumeVerified());
        CHECK(volumes.at(0x0201).current == -12 * 256 && right.current == (scenario == 0 ? -18 : -12) * 256);
        CHECK(muteWrites == 0);
    }
}

static void masterAndChannelControlsAreAllChecked() {
    reset();
    auto raw = std::vector<uint8_t>(std::begin(descriptors), std::end(descriptors));
    addVolumeGraph(raw);
    raw[27 + 12 + 7] = raw[27 + 12 + 8] = 2;
    for (uint16_t key : {0x0200, 0x0201, 0x0202}) volumes.emplace(key, VolumeControl{});
    UsbStreamer output;
    UsbStreamer::StreamInfo info;
    std::string error;
    CHECK(output.open(-1, raw.data(), raw.size(), error));
    CHECK(output.start(48000, 16, 2, 0, nullptr, info, error));
    CHECK(output.setVolume(-30 * 256));
    CHECK(volumeWrites == std::vector<uint16_t>({0x0200, 0x0201, 0x0202}));
    CHECK(volumes.at(0x0200).current == -30 * 256);
    CHECK(volumes.at(0x0201).current == 0 && volumes.at(0x0202).current == 0);
    CHECK(output.volumeRange().available && output.volumeRange().cur == -30 * 256);
    allowCallbacks();
    CHECK(output.close());
}

static void failedRestorationRetainsConnection() {
    for (int failure : {0, 1, 2}) {
        reset();
        UsbStreamer output;
        CHECK(openAndStart(output, false, true));
        auto& volume = volumes.at(0x0200);
        volume.failSet = failure == 0;
        volume.mismatch = failure == 1;
        volume.failCurrent = failure == 2;
        allowCallbacks();
        CHECK(!output.close());
        CHECK(closed == 0 && exited == 0 && !output.volumeVerified());
        volume.failSet = volume.mismatch = volume.failCurrent = false;
        CHECK(output.close());
        CHECK(closed == 1 && exited == 1 && volume.current == -12 * 256);
    }
}

static void missingOrPartialControlsKeepPcmBlocked() {
    for (bool partial : {false, true}) {
        reset();
        auto raw = std::vector<uint8_t>(std::begin(descriptors), std::end(descriptors));
        if (partial) {
            addVolumeGraph(raw, false);
            raw[27 + 12 + 8] = 0; // right-channel volume absent and no master
            volumes.emplace(0x0201, VolumeControl{});
        }
        UsbStreamer output;
        UsbStreamer::StreamInfo info;
        std::string error;
        CHECK(output.open(-1, raw.data(), raw.size(), error));
        CHECK(output.start(48000, 16, 2, 0, nullptr, info, error));
        CHECK(!output.setVolume(-30 * 256) && !output.volumeVerified());
        CHECK(volumeWrites.empty());
        int32_t sample[2]{};
        CHECK(output.write(sample, 1) == -1);
        allowCallbacks();
        CHECK(output.close());
    }
}

static void uac2VolumeRangeAndReadback() {
    for (int scenario : {0, 1, 2, 3}) {
        reset();
        clockReply = 4;
        auto raw = uac2ClockFixture();
        raw.insert(raw.begin() + 52, {
            18, 0x24, 6, 2, 1, uint8_t(scenario == 3 ? 4 : 12), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            12, 0x24, 3, 3, 2, 3, 0, 2, 0x29, 0, 0, 0,
        });
        auto& volume = volumes.emplace(0x0200, VolumeControl{}).first->second;
        if (scenario == 1) volume.rangeCount = 2;
        if (scenario == 2) volume.failReadback = true;
        UsbStreamer output;
        UsbStreamer::StreamInfo info;
        std::string error;
        CHECK(output.open(-1, raw.data(), raw.size(), error));
        CHECK(output.start(48000, 16, 2, 0, nullptr, info, error));
        CHECK(output.setVolume(-30 * 256) == (scenario == 0));
        CHECK(output.volumeVerified() == (scenario == 0));
        if (scenario == 1 || scenario == 3) CHECK(volumeWrites.empty());
        volume.failReadback = false;
        allowCallbacks();
        CHECK(output.close());
        CHECK(volume.current == -12 * 256);
    }
}

static void volumeFollowsSelectedModeAndRestoresEveryUnit() {
    reset();
    auto raw = std::vector<uint8_t>(std::begin(descriptors), std::end(descriptors));
    addVolumeGraph(raw);
    raw.insert(raw.begin() + 27, {
        12, 0x24, 2, 4, 1, 1, 0, 2, 3, 0, 0, 0,
        10, 0x24, 6, 5, 4, 1, 2, 0, 0, 0,
        9, 0x24, 3, 6, 2, 3, 0, 5, 0,
    });
    raw.insert(raw.end(), {
        9, 4, 1, 2, 1, 1, 2, 0, 0,
        7, 0x24, 1, 4, 1, 1, 0,
        11, 0x24, 2, 1, 2, 2, 16, 1, 0x80, 0xbb, 0,
        9, 5, 3, 0x0d, 0xc0, 0, 1, 0, 0,
    });
    volumes.emplace(0x0200, VolumeControl{});
    volumes.emplace(0x0500, VolumeControl{}).first->second.current = -18 * 256;
    UsbStreamer output;
    UsbStreamer::StreamInfo info;
    std::string error;
    CHECK(output.open(-1, raw.data(), raw.size(), error));
    CHECK(output.start(48000, 16, 2, 0, nullptr, info, error));
    CHECK(output.setVolume(-30 * 256));
    CHECK(volumeWrites == std::vector<uint16_t>{0x0200});
    allowCallbacks();
    UsbStreamer::Mode fixed{1, 48000};
    CHECK(output.start(48000, 16, 2, 0, &fixed, info, error));
    CHECK(!output.volumeVerified());
    CHECK(output.setVolume(-24 * 256));
    CHECK(volumeWrites == std::vector<uint16_t>({0x0200, 0x0500}));
    CHECK(output.close());
    CHECK(volumes.at(0x0200).current == -12 * 256 && volumes.at(0x0500).current == -18 * 256);
}

static void volumeDoesNotUnmuteOrClampInvalidRequests() {
    reset();
    UsbStreamer output;
    CHECK(openAndStart(output, false, true));
    const auto writes = volumeWrites.size();
    for (int value : {-32769, 32768, -61 * 256, 1}) {
        CHECK(!output.setVolume(value));
        CHECK(!output.volumeVerified());
        CHECK(volumeWrites.size() == writes);
    }
    muteState = 1;
    CHECK(!output.setVolume(-30 * 256));
    CHECK(muteWrites == 0 && volumeWrites.size() == writes);
    muteState = 0;
    allowCallbacks();
    CHECK(output.close());
}

static void unplugDuringRestorationStillReleasesConnection() {
    reset();
    UsbStreamer output;
    CHECK(openAndStart(output, false, true));
    auto& volume = volumes.at(0x0200);
    volume.failSet = true;
    volume.failureCode = LIBUSB_ERROR_NO_DEVICE;
    allowCallbacks();
    CHECK(output.close());
    CHECK(output.stats().disconnected && closed == 1 && exited == 1);
}

static void unplugDuringUncalibratedSilenceReleasesCallbacks() {
    reset();
    UsbStreamer output;
    CHECK(openAndStart(output));
    CHECK(output.clockConfigured() && output.feedbackReady() && !output.volumeVerified());
    {
        std::unique_lock<std::mutex> lock(queueMutex);
        dataFault = 3; // physical removal reported by a data completion
        queueChanged.notify_all();
        CHECK(queueChanged.wait_for(lock, 3s, [&] { return output.stats().disconnected; }));
    }
    CHECK(output.stats().disconnected);
    CHECK(!output.clockConfigured() && !output.feedbackReady() && !output.volumeVerified());
    CHECK(output.stats().framesSent == 0 && nonzeroAudioSubmissions == 0);
    allowCallbacks();
    CHECK(output.stop());
    CHECK(output.close());
    CHECK(volumeWrites.empty() && muteWrites == 0 && allocated == freed && closed == 1 && exited == 1);
}

static void uncalibratedActiveRefillsStaySilent() {
    reset();
    UsbStreamer output;
    std::vector<uint8_t> raw(std::begin(descriptors), std::end(descriptors));
    addVolumeGraph(raw);
    volumes.emplace(0x0200, VolumeControl{});
    std::string error;
    CHECK(output.open(-1, raw.data(), raw.size(), error));
    for (int cycle = 0; cycle < 2; ++cycle) {
        UsbStreamer::StreamInfo info;
        CHECK(output.start(48000, 16, 2, 0, nullptr, info, error));
        CHECK(output.clockConfigured() && output.feedbackReady() && !output.volumeVerified());
        const int before = audioCompletions;
        {
            std::unique_lock<std::mutex> lock(queueMutex);
            dataFault = 4;
            queueChanged.notify_all();
            CHECK(queueChanged.wait_for(lock, 3s, [&] { return audioCompletions > before; }));
        }
        CHECK(nonzeroAudioSubmissions == 0 && volumeWrites.empty() && muteWrites == 0);
        CHECK(output.stats().framesSent == 0 && output.stats().underruns == 0);
        allowCallbacks();
        CHECK(output.stop());
        CHECK(!output.clockConfigured() && !output.volumeVerified());
    }
    CHECK(output.close());
    CHECK(volumeWrites.empty() && muteWrites == 0 && nonzeroAudioSubmissions == 0);
}

static void volumeFailureKeepsQueuedPcmOutOfTransfers() {
    for (bool loseVerification : {false, true}) {
        reset();
        UsbStreamer output;
        CHECK(openAndStart(output, false, true));
        std::vector<int32_t> sample(960 * 2, 65536); // Exactly the two-transfer start buffer.
        CHECK(output.write(sample.data(), 960) == 960);
        if (loseVerification) {
            volumes.at(0x0200).failCurrent = true;
            CHECK(!output.setVolume(-30 * 256));
            volumes.at(0x0200).failCurrent = false;
        }
        {
            std::unique_lock<std::mutex> lock(queueMutex);
            dataFault = 4; // one ordinary data completion, followed by a refill
            queueChanged.notify_all();
            CHECK(queueChanged.wait_for(lock, 3s, [] { return audioCompletions > 0; }));
        }
        CHECK(nonzeroAudioSubmissions == (loseVerification ? 0 : 1));
        CHECK(output.stats().framesSent == (loseVerification ? 0u : 480u));
        allowCallbacks();
        CHECK(output.close());
    }
}

static void volumeQueryInvalidatesContradictoryReadback() {
    for (bool missing : {false, true}) {
        reset();
        UsbStreamer output;
        CHECK(openAndStart(output, false, true));
        auto& volume = volumes.at(0x0200);
        if (missing) volume.failCurrent = true;
        else volume.current = -24 * 256;
        CHECK(!output.volumeRange().available);
        CHECK(!output.volumeVerified());
        int32_t sample[2]{};
        CHECK(output.write(sample, 1) == -1);
        volume.failCurrent = false;
        allowCallbacks();
        CHECK(output.close());
    }
}

static void selectorMustBeKnownAndUnchanged() {
    for (int pin : {-1, 0, 3, 2, 1}) {
        reset();
        clockReply = 4;
        auto raw = uac2ClockFixture();
        raw[42] = 0x28; // terminal uses selector 0x28
        raw.insert(raw.begin() + 35, {
            8, 0x24, 0x0a, 0x2a, 3, 7, 0, 0,
            9, 0x24, 0x0b, 0x28, 2, 0x29, 0x2a, 3, 0,
        });
        if (pin != 2) selectorPin = pin;
        UsbStreamer output;
        UsbStreamer::StreamInfo info;
        std::string error;
        if (pin < 1 || pin > 2) CHECK(!output.open(-1, raw.data(), raw.size(), error));
        else {
            CHECK(output.open(-1, raw.data(), raw.size(), error));
            if (pin == 2) selectorPin = 2;
            CHECK(output.start(48000, 16, 2, 0, nullptr, info, error) == (pin == 1));
            CHECK(output.clockConfigured() == (pin == 1));
            if (pin == 1) allowCallbacks();
        }
        if (pin != 1) CHECK(clockSets == 0 && submits == 0);
        CHECK(output.close());
    }
}

static void unverifiedClockMultipliersAndReadOnlyRatesFail() {
    for (int scenario : {0, 1, 2, 3}) {
        reset();
        clockReply = 4;
        auto raw = uac2ClockFixture();
        if (scenario == 0) {
            raw[42] = 0x28;
            raw.insert(raw.begin() + 35, {7, 0x24, 0x0c, 0x28, 0x29, 0x0f, 0});
        } else raw[32] = 1; // read-only frequency control
        if (scenario == 2) clockReadback = 48000;
        if (scenario == 3) clockReadback = 96000;
        UsbStreamer output;
        UsbStreamer::StreamInfo info;
        std::string error;
        if (scenario == 0) CHECK(!output.open(-1, raw.data(), raw.size(), error));
        else {
            CHECK(output.open(-1, raw.data(), raw.size(), error));
            CHECK(output.start(48000, 16, 2, 0, nullptr, info, error) == (scenario == 2));
            CHECK(output.clockConfigured() == (scenario == 2));
            if (scenario == 2) { CHECK(info.deviceRate == 48000); allowCallbacks(); }
        }
        CHECK(clockSets == 0);
        CHECK(output.close());
    }
}

static void overflowingAdvertisedRateDoesNotFitBandwidth() {
    reset();
    clockRangeRate = UINT32_MAX;
    const auto raw = uac2ClockFixture();
    UsbStreamer output;
    UsbStreamer::Plan plan;
    std::string error;
    CHECK(output.open(-1, raw.data(), raw.size(), error));
    CHECK(output.modes().empty());
    CHECK(!output.plan(48000, 16, 2, nullptr, plan, error));
    CHECK(clockSets == 0 && submits == 0);
    CHECK(output.close());
}

static void giveData(int count, int fault = 0) {
    std::lock_guard<std::mutex> lock(queueMutex);
    dataCredits = count; dataFault = fault; queueChanged.notify_all();
}

static void waitForEof(UsbStreamer& output) {
    const auto until = std::chrono::steady_clock::now() + 3s;
    while (!output.stats().inputEnded && std::chrono::steady_clock::now() < until) std::this_thread::sleep_for(1ms);
    CHECK(output.stats().inputEnded);
}

static void drainRequiresCompletedPcmNotAnEmptyRing() {
    reset(); UsbStreamer output;
    CHECK(openAndStart(output, false, true));
    int32_t pcm[2]{536870912, -536870912};
    CHECK(output.write(pcm, 1) == 1);
    giveData(1); // One frame is below the start buffer: the refill stays silent and counts no gap.
    const auto held = std::chrono::steady_clock::now() + 3s;
    while (audioCompletions < 1 && std::chrono::steady_clock::now() < held) std::this_thread::sleep_for(1ms);
    CHECK(output.stats().framesQueued == 1 && output.stats().framesSent == 0 && output.stats().underruns == 0);
    auto draining = std::async(std::launch::async, [&] { return output.drain(); });
    waitForEof(output);
    giveData(1); // End of input releases the held frame into the next refill.
    const auto until = std::chrono::steady_clock::now() + 3s;
    while (output.stats().framesSent != 1 && std::chrono::steady_clock::now() < until) std::this_thread::sleep_for(1ms);
    CHECK(output.stats().framesQueued == 1 && output.stats().framesSent == 1 && output.stats().framesCompleted == 0);
    CHECK(draining.wait_for(100ms) == std::future_status::timeout);
    giveData(7); // Remaining silence, including the first refill, must not be mistaken for that PCM transfer.
    const auto silent = std::chrono::steady_clock::now() + 3s;
    while (audioCompletions < 9 && std::chrono::steady_clock::now() < silent) std::this_thread::sleep_for(1ms);
    CHECK(output.stats().framesCompleted == 0);
    CHECK(draining.wait_for(30ms) == std::future_status::timeout);
    giveData(1);
    CHECK(draining.wait_for(2s) == std::future_status::ready && draining.get());
    CHECK(output.stats().framesCompleted == 1 && output.stats().underruns == 0);
    CHECK(output.drain());
    CHECK(output.write(pcm, 1) == -1);
    allowCallbacks(); CHECK(output.stop());
    CHECK(!output.drain());
    UsbStreamer::StreamInfo info; std::string error;
    CHECK(output.start(48000, 16, 2, 0, nullptr, info, error));
    CHECK(!output.stats().inputEnded && output.stats().framesQueued == 0 && output.stats().framesCompleted == 0);
    CHECK(output.drain()); // Empty silence needs no volume approval or PCM write.
    CHECK(output.close());
}

static void drainFailureAndStopNeverClaimCompletion() {
    for (int scenario = 0; scenario < 6; ++scenario) {
        reset(); UsbStreamer output;
        CHECK(openAndStart(output, false, true));
        int32_t pcm[2]{536870912, -536870912};
        CHECK(output.write(pcm, 1) == 1);
        auto draining = std::async(std::launch::async, [&] { return output.drain(); });
        waitForEof(output); // End of input releases a frame held below the start buffer.
        giveData(1);
        const auto until = std::chrono::steady_clock::now() + 3s;
        while (output.stats().framesSent != 1 && std::chrono::steady_clock::now() < until) std::this_thread::sleep_for(1ms);
        CHECK(output.stats().framesSent == 1);
        {
            std::lock_guard<std::mutex> lock(queueMutex);
            auto pcmTransfer = std::find_if(pending.begin(), pending.end(), [](auto* t) {
                return !(t->endpoint & 0x80) && std::any_of(t->buffer, t->buffer + t->length, [](uint8_t byte) { return byte != 0; });
            });
            CHECK(pcmTransfer != pending.end());
            std::rotate(pending.begin(), pcmTransfer, pcmTransfer + 1);
        }
        if (scenario >= 1 && scenario <= 3) giveData(1, scenario); // packet error, short packet, unplug
        if (scenario == 4) {
            std::lock_guard<std::mutex> lock(queueMutex);
            failSubmitAt = submits + 1; dataCredits = 1; queueChanged.notify_all();
        }
        if (scenario == 5) { allowCallbacks(); CHECK(output.stop()); }
        CHECK(draining.wait_for(2500ms) == std::future_status::ready && !draining.get());
        CHECK(!output.drain());
        if (scenario >= 1 && scenario <= 3) CHECK(output.stats().framesCompleted == 0);
        if (scenario == 0) CHECK(freed == 0); // Deadline never frees in-flight ownership.
        allowCallbacks(); CHECK(output.close()); CHECK(freed == allocated);
    }
}

static void outOfOrderCompletionAndRealGaps() {
    reset(); UsbStreamer output;
    CHECK(openAndStart(output, false, true));
    std::vector<int32_t> pcm(1000 * 2, 536870912);
    CHECK(output.write(pcm.data(), 1000) == 1000);
    giveData(3);
    const auto until = std::chrono::steady_clock::now() + 3s;
    while (output.stats().framesSent < 1000 && std::chrono::steady_clock::now() < until) std::this_thread::sleep_for(1ms);
    CHECK(output.stats().framesSent == 1000);
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        auto partial = std::find_if(pending.begin(), pending.end(), [](auto* t) {
            return !(t->endpoint & 0x80) && t->buffer[1] != 0 && t->buffer[201] == 0;
        });
        CHECK(partial != pending.end());
        std::rotate(pending.begin(), partial, partial + 1);
    }
    auto draining = std::async(std::launch::async, [&] { return output.drain(); });
    waitForEof(output); giveData(1);
    const auto completion = std::chrono::steady_clock::now() + 3s;
    while (output.stats().framesCompleted == 0 && std::chrono::steady_clock::now() < completion) std::this_thread::sleep_for(1ms);
    CHECK(output.stats().framesCompleted == 40);
    CHECK(draining.wait_for(30ms) == std::future_status::timeout);
    giveData(16);
    CHECK(draining.wait_for(2s) == std::future_status::ready && draining.get());
    allowCallbacks(); CHECK(output.close());

    reset(); UsbStreamer gap;
    CHECK(openAndStart(gap, false, true));
    CHECK(gap.write(pcm.data(), 1000) == 1000); giveData(3); // Fills the start buffer, then starves in the third refill.
    const auto starved = std::chrono::steady_clock::now() + 3s;
    while (gap.stats().underruns == 0 && std::chrono::steady_clock::now() < starved) std::this_thread::sleep_for(1ms);
    CHECK(gap.stats().underruns > 0);
    CHECK(gap.write(pcm.data(), 1) == 1); // Resumed PCM makes the preceding gap real.
    CHECK(!gap.drain() && gap.stats().underruns > 0);
    allowCallbacks(); CHECK(gap.close());
}

static void readinessLossDuringDrainFails() {
    for (bool staleFeedback : {false, true}) {
        reset(); UsbStreamer output;
        CHECK(openAndStart(output, staleFeedback, true));
        int32_t pcm[2]{536870912, -536870912};
        CHECK(output.write(pcm, 1) == 1);
        auto draining = std::async(std::launch::async, [&] { return output.drain(); });
        waitForEof(output);
        if (staleFeedback) {
            std::lock_guard<std::mutex> lock(queueMutex);
            feedbackUpdates = false;
        } else {
            CHECK(!output.setVolume(32768)); // Invalid control request revokes cached verification.
            CHECK(!output.volumeVerified());
        }
        CHECK(draining.wait_for(2s) == std::future_status::ready && !draining.get());
        CHECK(output.stats().framesCompleted == 0 && !output.drain());
        allowCallbacks(); CHECK(output.close());
    }
}

static void resamplerDrainCompletesTheReferenceTail() {
    reset(); UsbStreamer output;
    CHECK(openAndStart(output, false, true, 44100));
    std::vector<int32_t> pcm(4410 * 2, 0);
    pcm[0] = pcm[pcm.size() - 40] = 536870912;
    pcm[1] = pcm[pcm.size() - 39] = -536870912;
    std::vector<float> input(pcm.size()), reference(6000 * 2);
    for (size_t i = 0; i < pcm.size(); ++i) input[i] = float(double(pcm[i]) / 2147483648.0);
    SRC_DATA data{};
    data.data_in = input.data(); data.input_frames = 4410;
    data.data_out = reference.data(); data.output_frames = 6000;
    data.src_ratio = 48000.0 / 44100; data.end_of_input = 1;
    CHECK(src_simple(&data, SRC_SINC_BEST_QUALITY, 2) == 0 && data.output_frames_gen > 0);
    CHECK(output.write(pcm.data(), 4410) == 4410);
    CHECK(output.stats().framesQueued < uint64_t(data.output_frames_gen));
    auto draining = std::async(std::launch::async, [&] { return output.drain(); });
    waitForEof(output);
    const auto flushed = std::chrono::steady_clock::now() + 1s;
    while (output.stats().framesQueued < uint64_t(data.output_frames_gen) && std::chrono::steady_clock::now() < flushed)
        std::this_thread::sleep_for(1ms);
    CHECK(output.stats().framesQueued == uint64_t(data.output_frames_gen));
    giveData(64);
    const bool ready = draining.wait_for(2500ms) == std::future_status::ready;
    const bool drained = ready && draining.get();
    if (!drained) {
        const auto stats = output.stats();
        std::printf("SRC drain ready=%d queued=%llu sent=%llu completed=%llu underruns=%llu errors=%llu reference=%ld\n", ready,
            (unsigned long long)stats.framesQueued, (unsigned long long)stats.framesSent, (unsigned long long)stats.framesCompleted,
            (unsigned long long)stats.underruns, (unsigned long long)stats.transferErrors, data.output_frames_gen);
    }
    CHECK(drained);
    giveData(0);
    const auto stats = output.stats();
    CHECK(stats.framesQueued == uint64_t(data.output_frames_gen) && stats.framesCompleted == stats.framesQueued && stats.framesSent == stats.framesQueued);
    CHECK(stats.underruns == 0 && stats.transferErrors == 0);
    std::vector<uint8_t> bytes;
    { std::lock_guard<std::mutex> lock(queueMutex); bytes = completedAudio; }
    auto sample = [&](size_t n) { const int raw = int(bytes[n * 2]) | (int(bytes[n * 2 + 1]) << 8); return raw >= 32768 ? raw - 65536 : raw; };
    size_t first = 0;
    while (first + 1 < bytes.size() / 4 && std::abs(sample(first * 2)) < 1000) ++first;
    CHECK((first + size_t(data.output_frames_gen)) * 4 <= bytes.size());
    for (long frame = 0; frame < data.output_frames_gen; ++frame) for (int ch = 0; ch < 2; ++ch)
        CHECK(std::abs(sample((first + size_t(frame)) * 2 + ch) - double(reference[size_t(frame) * 2 + ch]) * 32768.0) <= 3.5);
    allowCallbacks(); CHECK(output.close());
}

static void blockedConverterTailSharesDrainDeadlineAndStop() {
    for (bool stop : {false, true}) {
        reset(); UsbStreamer output;
        CHECK(openAndStart(output, false, true, 44100));
        std::vector<int32_t> pcm(30120 * 2, 536870912);
        CHECK(output.write(pcm.data(), 30120) == 30120);
        CHECK(output.stats().framesQueued < 32768); // Rounded 500 ms ring holds 32768 stereo 16-bit frames.
        const auto started = std::chrono::steady_clock::now();
        auto draining = std::async(std::launch::async, [&] { return output.drain(); });
        waitForEof(output);
        const auto full = started + 1s;
        while (output.stats().framesQueued < 32768 && std::chrono::steady_clock::now() < full) std::this_thread::sleep_for(1ms);
        CHECK(output.stats().framesQueued == 32768 && output.stats().framesCompleted == 0);
        CHECK(draining.wait_for(30ms) == std::future_status::timeout);
        if (stop) { allowCallbacks(); CHECK(output.stop()); }
        CHECK(draining.wait_for(2s) == std::future_status::ready && !draining.get());
        CHECK(std::chrono::steady_clock::now() - started < 2500ms);
        if (!stop) CHECK(output.stats().transferErrors > 0 && freed == 0);
        allowCallbacks(); CHECK(output.close());
    }
}

// Evanna 2026-11-03: a first converted 10 ms chunk is shorter than one transfer (filter delay); on the
// phone the first refill came up short, was counted as an underrun and stopped playback at start.
static void resampledStartWaitsForATransferBeforeCountingStarvation() {
    reset(); UsbStreamer output;
    CHECK(openAndStart(output, false, true, 44100)); // 44.1 kHz source on the 48 kHz fixture: 480 frames per transfer
    std::vector<int32_t> pcm(441 * 2, 536870912);
    CHECK(output.write(pcm.data(), 441) == 441);
    CHECK(output.stats().framesQueued < 480);
    giveData(1);
    const auto settle = std::chrono::steady_clock::now() + 1s;
    while (audioCompletions < 1 && std::chrono::steady_clock::now() < settle) std::this_thread::sleep_for(1ms);
    CHECK(audioCompletions >= 1);
    CHECK(output.stats().underruns == 0 && output.feedbackReady());
    std::vector<int32_t> more(4410 * 2, 536870912);
    CHECK(output.write(more.data(), 4410) == 4410);
    giveData(3);
    const auto sent = std::chrono::steady_clock::now() + 3s;
    while (output.stats().framesSent < 3 * 480 && std::chrono::steady_clock::now() < sent) std::this_thread::sleep_for(1ms);
    CHECK(output.stats().framesSent >= 3 * 480 && output.stats().underruns == 0);
    allowCallbacks(); CHECK(output.close());
}

static void converterTailDoesNotEraseAnEarlierGap() {
    reset(); UsbStreamer output;
    CHECK(openAndStart(output, false, true, 44100));
    std::vector<int32_t> pcm(4410 * 2, 536870912);
    CHECK(output.write(pcm.data(), 4410) == 4410);
    giveData(10);
    const auto gap = std::chrono::steady_clock::now() + 3s;
    while (output.stats().underruns == 0 && std::chrono::steady_clock::now() < gap) std::this_thread::sleep_for(1ms);
    CHECK(output.stats().underruns > 0);
    const auto before = output.stats().framesQueued;
    CHECK(!output.drain()); // Queuing the withheld SRC tail makes preceding starvation a real gap.
    CHECK(output.stats().framesQueued > before && output.stats().underruns > 0);
    allowCallbacks(); CHECK(output.close());
}

int main() {
    drainRequiresCompletedPcmNotAnEmptyRing();
    drainFailureAndStopNeverClaimCompletion();
    outOfOrderCompletionAndRealGaps();
    readinessLossDuringDrainFails();
    resamplerDrainCompletesTheReferenceTail();
    blockedConverterTailSharesDrainDeadlineAndStop();
    resampledStartWaitsForATransferBeforeCountingStarvation();
    converterTailDoesNotEraseAnEarlierGap();
    unplugDuringUncalibratedSilenceReleasesCallbacks();
    uncalibratedActiveRefillsStaySilent();
    overflowingAdvertisedRateDoesNotFitBandwidth();
    selectorMustBeKnownAndUnchanged();
    unverifiedClockMultipliersAndReadOnlyRatesFail();
    openingDoesNotChangeHardwareVolume();
    allVolumeControlsMustVerify();
    masterAndChannelControlsAreAllChecked();
    failedRestorationRetainsConnection();
    missingOrPartialControlsKeepPcmBlocked();
    uac2VolumeRangeAndReadback();
    volumeFollowsSelectedModeAndRestoresEveryUnit();
    volumeDoesNotUnmuteOrClampInvalidRequests();
    unplugDuringRestorationStillReleasesConnection();
    volumeFailureKeepsQueuedPcmOutOfTransfers();
    volumeQueryInvalidatesContradictoryReadback();
    missingFeedbackCannotStart();
    unusableFeedbackCannotStart();
    staleFeedbackUnblocksWriter();
    feedbackResubmitFailureStopsStream();
    packetFailuresStopStream();
    lateValidFeedbackCannotReviveStream();
    invalidFirstValueDoesNotPoisonFeedbackFormat();
    clockSettingMustComplete(false);
    clockSettingMustComplete(true);
    unsupportedModesSubmitNothing();
    adaptiveModeRemainsEligible();
    allocationFailuresCleanUp();
    JNINativeInterface api{};
    api.FindClass = [](JNIEnv*, const char*) { return reinterpret_cast<jclass>(1); };
    api.ThrowNew = [](JNIEnv*, jclass, const char*) { ++exceptions; return jint(0); };
    JNIEnv env{&api};
    timedOutCloseRetainsOwnerAndUnblocksWriter(env, false);
    timedOutCloseRetainsOwnerAndUnblocksWriter(env, true);
    zeroInFlightStillWaitsForEventReturn();
    for (int failAt : {1, 4, 8}) partialSubmissionRetainsPendingTransfers(failAt);
    for (int failAt : {9, 10}) partialSubmissionRetainsPendingTransfers(failAt, true);
    stopRestartAndDoubleClose();
    directDestructorWaitsForCallbacks();
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeStop(&env, nullptr, 0) == JNI_TRUE);
    Java_com_truesample_usbaudio_NativeBridge_nativeClose(&env, nullptr, 0);
    std::printf("Lifecycle: %d checks passed (injected USB transport)\n", checks.load());
}
