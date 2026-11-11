// Modified for Evanna (2026-11-02): checked PCM, verified clock/volume and bounded USB completion drain.
// Modified for Evanna (2026-11-03): PCM starts after a two-transfer buffer so a converted first chunk cannot underrun.
// Modified for Evanna (2026-11-04): effect planning and finite/clipping checks before float conversion.
// Modified for Evanna (2026-11-05): stereo crossfeed after the equalizer, planned and reported as processing.
#include "UsbStreamer.h"

#include <libusb.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>

#include "Log.h"
#include "samplerate.h"

namespace {

void onLibusbLog(libusb_context*, enum libusb_log_level level, const char* message) {
    const size_t len = std::strlen(message);
    const int length = int(len > 0 && message[len - 1] == '\n' ? len - 1 : len);
    ualog::write(level == LIBUSB_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "libusb: %.*s", length,
                 message);
}

constexpr int kTransfersInFlight = 8;
constexpr int kFeedbackTransfers = 2;
constexpr int kFeedbackPackets = 4;
constexpr uint32_t kTransferMillis = 10;   // audio per transfer
constexpr uint32_t kRingMillis = 500;      // decoded audio buffered ahead of USB
constexpr auto kDrainTimeout = std::chrono::milliseconds(1500);
// Diagnostic bounds proposed in the engineering guide; physical DAC timing remains to be measured.
constexpr int64_t kFirstFeedbackMs = 1000;
constexpr int64_t kStaleFeedbackMs = 500;
int64_t monotonicMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

// UAC1 class requests
constexpr uint8_t kSetCur = 0x01;
constexpr uint8_t kGetCur = 0x81;
constexpr uint8_t kGetMin = 0x82;
constexpr uint8_t kGetMax = 0x83;
constexpr uint8_t kGetRes = 0x84;
constexpr uint8_t kMuteControl = 0x01;
constexpr uint8_t kVolumeControl = 0x02;
constexpr uint8_t kSamplingFreqControl = 0x01;

// UAC2 class requests (bRequest; direction comes from bmRequestType)
constexpr uint8_t kUac2Cur = 0x01;
constexpr uint8_t kUac2Range = 0x02;
constexpr uint8_t kClockSelectorControl = 0x01;

constexpr uint8_t kReqOutInterface = 0x21;
constexpr uint8_t kReqInInterface = 0xA1;
constexpr uint8_t kReqOutEndpoint = 0x22;
constexpr uint8_t kReqInEndpoint = 0xA2;

int16_t le16(const uint8_t* p) { return int16_t(p[0] | (p[1] << 8)); }
uint32_t le32(const uint8_t* p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}

}  // namespace

UsbStreamer::~UsbStreamer() {
    // JNI retains failed cleanup; direct C++ destruction must also wait without spinning on control errors.
    while (!close()) std::this_thread::sleep_for(std::chrono::milliseconds(20));
}

bool UsbStreamer::open(int fd, const uint8_t* raw, size_t length, std::string& error) {
    device_ = uac::parseDescriptors(raw, length);
    if (device_.uacVersion == 0) {
        error = "This USB device is not a USB Audio device.";
        return false;
    }
    if (device_.outputs.empty()) {
        error = "The DAC reports no playback formats.";
        return false;
    }

    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    int rc = libusb_init_context(&ctx_, nullptr, 0);
    if (rc != LIBUSB_SUCCESS) {
        error = std::string("libusb init failed: ") + libusb_error_name(rc);
        ctx_ = nullptr;
        return false;
    }
    libusb_set_log_cb(ctx_, onLibusbLog, LIBUSB_LOG_CB_CONTEXT);
    libusb_set_option(ctx_, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);
    rc = libusb_wrap_sys_device(ctx_, static_cast<intptr_t>(fd), &handle_);
    if (rc != LIBUSB_SUCCESS) {
        error = std::string("libusb could not open the DAC: ") + libusb_error_name(rc);
        close();
        return false;
    }

    // Java already force-claimed these (detaching Android's driver); this only
    // updates libusb's own bookkeeping so alt-setting changes are allowed.
    rc = libusb_claim_interface(handle_, device_.controlInterface);
    LOGI("claim interface %d: %s", device_.controlInterface, libusb_error_name(rc));
    for (const uac::OutputFormat& f : device_.outputs) {
        rc = libusb_claim_interface(handle_, f.interfaceNumber);
        LOGI("claim interface %u: %s", f.interfaceNumber, libusb_error_name(rc));
        if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_BUSY) {
            error = std::string("Could not claim the streaming interface: ") + libusb_error_name(rc);
            close();
            return false;
        }
    }

    highSpeed_ = libusb_get_device_speed(libusb_get_device(handle_)) >= LIBUSB_SPEED_HIGH;
    if (device_.uacVersion == 2) {
        loadUac2Rates();
        if (std::all_of(device_.outputs.begin(), device_.outputs.end(),
                        [](const uac::OutputFormat& f) { return f.rates.empty(); })) {
            error = "The DAC did not report which sample rates it supports.";
            close();
            return false;
        }
    }

    LOGI("opened DAC:\n%s", uac::describe(device_).c_str());
    return true;
}

bool UsbStreamer::start(uint32_t sampleRate, uint32_t sourceBits, uint32_t channels, int resampleQuality,
                        const Mode* fixedMode, StreamInfo& info, std::string& error) {
    if (!stop()) {
        error = "Previous USB transfers are still cancelling; retry cleanup before restarting.";
        return false;
    }
    if (!handle_) {
        error = "DAC is not open.";
        return false;
    }

    Plan p;
    if (!plan(sampleRate, sourceBits, channels, fixedMode, p, error)) return false;
    const int best = p.outputIndex;
    const uint32_t outRate = p.outputRate;
    const bool resampling = p.resampling;
    const bool lossless = p.lossless;

    format_ = &device_.outputs[best];
    const uac::OutputFormat& f = *format_;
    featureUnit_ = uac::findPlaybackFeatureUnit(device_, f);
    uint32_t deviceRate = 0;
    // UAC2: the rate lives on the clock and is changed while the interface is idle (alt 0).
    if (device_.uacVersion == 2 && !setUac2Rate(size_t(best), outRate, deviceRate, error)) {
        format_ = nullptr;
        return false;
    }
    int rc = libusb_set_interface_alt_setting(handle_, f.interfaceNumber, f.altSetting);
    if (rc != LIBUSB_SUCCESS) {
        error = std::string("Could not select the DAC's playback mode: ") + libusb_error_name(rc);
        format_ = nullptr;
        return false;
    }

    if (device_.uacVersion == 1 && (f.sampleRateControl || f.continuousRates || f.rates.size() > 1)) {
        uint8_t data[3] = {uint8_t(outRate), uint8_t(outRate >> 8), uint8_t(outRate >> 16)};
        rc = libusb_control_transfer(handle_, kReqOutEndpoint, kSetCur, kSamplingFreqControl << 8,
                                     f.endpointAddress, data, 3, 1000);
        if (rc != 3) {
            error = "Could not configure the DAC sample rate (failed or short SET_CUR).";
            stop();
            return false;
        }
        uint8_t back[3] = {};
        rc = libusb_control_transfer(handle_, kReqInEndpoint, kGetCur, kSamplingFreqControl << 8,
                                     f.endpointAddress, back, 3, 1000);
        if (rc == 3) deviceRate = uint32_t(back[0] | (back[1] << 8) | (back[2] << 16));
    }
    if (deviceRate != 0 && deviceRate != outRate) {
        char msg[128];
        std::snprintf(msg, sizeof msg, "The DAC was asked for %u Hz but switched to %u Hz.", outRate, deviceRate);
        error = msg;
        libusb_set_interface_alt_setting(handle_, f.interfaceNumber, 0);
        format_ = nullptr;
        return false;
    }

    // The previous stream's playback thread has finished (see UsbAudioOutput), so its
    // resampler can be replaced safely here.
    if (resampler_) resampler_ = src_delete(resampler_);
    const size_t chunkFrames = sampleRate / 100;  // 10 ms of source audio per processing step
    if (resampling) {
        int err = 0;
        resampler_ = src_new(std::clamp(resampleQuality, 0, 2), int(channels), &err);
        if (!resampler_) {
            error = std::string("Could not create the resampler: ") + src_strerror(err);
            libusb_set_interface_alt_setting(handle_, f.interfaceNumber, 0);
            format_ = nullptr;
            return false;
        }
        resampleRatio_ = double(outRate) / sampleRate;
        maxPushFrames_ = size_t(double(chunkFrames) * resampleRatio_) + 64;
        resampleOut_.resize(maxPushFrames_ * channels);
    } else {
        maxPushFrames_ = chunkFrames;
    }
    // Float path (resampler, equalizer and/or crossfeed). Always ready: the EQ can be switched on mid-song.
    resampleIn_.resize(chunkFrames * channels);
    resampledInt_.resize(std::max(maxPushFrames_, chunkFrames) * channels);
    outputRate_ = outRate;
    {
        std::lock_guard<std::mutex> lock(eqMutex_);
        eq_.configure(eqBands_, eqPreampDb_, outRate, channels);
        eq_.reset();  // new stream: no memory of the previous song
        cf_.configure(cfCutHz_, cfFeedDb_, outRate, channels);
        cf_.reset();
        eqDirty_ = false;
        eqActiveFlag_ = eq_.active();
    }

    channels_ = channels;
    const uint32_t pps = packetsPerSecond(f);
    frameBytes_ = f.frameBytes();
    // Async DACs may ask for a little more than nominal; never exceed what a packet carries.
    const uint32_t capFrames = std::max<uint32_t>(f.maxPacketBytes / frameBytes_, 1);
    scheduler_ = std::make_unique<uac::PacketScheduler>(outRate, pps, capFrames);
    sourceSubslot_ = f.subslotBytes;
    packetsPerTransfer_ = std::max<uint32_t>(1, pps * kTransferMillis / 1000);
    // A converted first chunk is shorter than one transfer; wait for two before PCM starts.
    primeBytes_ = 2 * size_t(packetsPerTransfer_) * scheduler_->maxFrames() * frameBytes_;
    ring_ = std::make_unique<uac::RingBuffer>(size_t(outRate) * frameBytes_ * kRingMillis / 1000);
    packBuffer_.resize(std::max(maxPushFrames_, chunkFrames) * frameBytes_);
    // Processed (float) audio is always dithered to the DAC's depth; the untouched integer
    // path only when the source has more bits than the DAC.
    if (f.bitResolution >= 32) {
        ditherer_.reset();
    } else {
        ditherer_ = std::make_unique<uac::Ditherer>(f.bitResolution);
        ditherBuffer_.resize(std::max(maxPushFrames_, chunkFrames) * f.channels);
    }
    ditherIntegerPath_ = !lossless;

    const size_t transferBytes = size_t(packetsPerTransfer_) * capFrames * frameBytes_;
    transferBuffers_.assign(kTransfersInFlight, std::vector<uint8_t>(transferBytes));
    transferPcmFrames_.assign(kTransfersInFlight, 0);
    for (int i = 0; i < kTransfersInFlight; ++i) {
        libusb_transfer* t = libusb_alloc_transfer(int(packetsPerTransfer_));
        if (!t) {
            error = "Could not allocate USB audio transfers.";
            stop();
            return false;
        }
        libusb_fill_iso_transfer(t, handle_, f.endpointAddress, transferBuffers_[i].data(), int(transferBytes),
                                 int(packetsPerTransfer_), &UsbStreamer::onTransferDone, this, 0);
        transfers_.push_back(t);
    }

    framesSent_ = 0;
    framesQueued_ = 0;
    framesCompleted_ = 0;
    inputEnded_ = false;
    wrotePcm_ = false;
    drainCompleted_ = false;
    trailingUnderruns_ = 0;
    underruns_ = 0;
    transferErrors_ = 0;
    clippedSamples_ = 0;
    primed_ = false;
    feedbackRequired_ = f.syncType == uac::SyncType::Async;
    feedbackSeen_ = false;
    feedbackUpdatedMs_ = 0;
    feedbackStartedMs_ = monotonicMs();
    std::unique_lock<std::mutex> startup(eventMutex_);
    eventThreadRun_ = true;
    eventThreadFinished_ = false;
    try {
        eventThread_ = std::thread(&UsbStreamer::eventLoop, this);
    } catch (...) {
        eventThreadRun_ = false;
        eventThreadFinished_ = true;
        startup.unlock();
        stop();
        error = "Could not start USB event handling.";
        return false;
    }
    streaming_ = true;
    for (libusb_transfer* t : transfers_) {
        fillTransfer(t);
        ++inFlight_;
        rc = libusb_submit_transfer(t);
        if (rc != LIBUSB_SUCCESS) {
            --inFlight_;
            error = std::string("Could not start USB streaming: ") + libusb_error_name(rc);
            startup.unlock();
            stop();
            return false;
        }
    }
    if (f.syncType == uac::SyncType::Async && !startFeedback(f, outRate, pps, error)) {
        startup.unlock();
        stop();
        return false;
    }
    startup.unlock();
    if (feedbackRequired_) {
        std::unique_lock<std::mutex> lock(spaceMutex_);
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(kFirstFeedbackMs);
        while (!feedbackSeen_ && streaming_ && std::chrono::steady_clock::now() < deadline)
            spaceCv_.wait_for(lock, std::chrono::milliseconds(5));
        lock.unlock();
        if (!feedbackReady()) {
            if (streaming_) failTransfer();
            error = "USB feedback did not become ready before the startup deadline.";
            stop();
            return false;
        }
    }

    clockConfigured_ = true;
    info.outputIndex = best;
    info.sampleRate = sampleRate;
    info.outputRate = outRate;
    info.deviceRate = deviceRate;
    info.subslotBytes = f.subslotBytes;
    info.bitResolution = f.bitResolution;
    info.processed = eq_.active() || cf_.active();
    info.bitPerfect = lossless && !info.processed;
    info.resampled = resampling;
    LOGI("streaming %u Hz %u-bit source -> %u Hz, alt %u (%u-bit in %u-byte slots)%s%s, %u packets/s", sampleRate,
         sourceBits, outRate, f.altSetting, f.bitResolution, f.subslotBytes, resampling ? ", resampled" : "",
         ditherer_ ? ", dithered" : "", pps);
    return true;
}

bool UsbStreamer::plan(uint32_t sampleRate, uint32_t sourceBits, uint32_t channels, const Mode* fixedMode,
                       Plan& out, std::string& error) const {
    if (!handle_) {
        error = "DAC is not open.";
        return false;
    }
    auto processing = [&](uint32_t rate) {
        std::lock_guard<std::mutex> lock(eqMutex_);
        uac::Equalizer preview;
        preview.configure(eqBands_, eqPreampDb_, rate, channels);
        uac::Crossfeed crossfeed;
        crossfeed.configure(cfCutHz_, cfFeedDb_, rate, channels);
        return preview.active() || crossfeed.active();
    };
    auto fits = [&](const uac::OutputFormat& f, uint32_t rate) {
        return f.channels == channels && fitsBandwidth(f, rate);
    };

    // A mode the user picked: convert every song to it.
    if (fixedMode) {
        const int i = fixedMode->outputIndex;
        if (i < 0 || size_t(i) >= device_.outputs.size() || !fits(device_.outputs[size_t(i)], fixedMode->sampleRate)) {
            char msg[160];
            std::snprintf(msg, sizeof msg, "The chosen output mode can't play this %u-channel song.", channels);
            error = msg;
            return false;
        }
        const uac::OutputFormat& f = device_.outputs[size_t(i)];
        out.outputIndex = i;
        out.outputRate = fixedMode->sampleRate;
        out.resampling = out.outputRate != sampleRate;
        out.lossless = !out.resampling && f.bitResolution >= sourceBits;
        out.processed = processing(out.outputRate);
        out.dacBits = f.bitResolution;
        return true;
    }

    // Play at the source rate when the DAC has it; otherwise resample to the best rate it does have.
    std::vector<uint32_t> candidates;
    for (const uac::OutputFormat& f : device_.outputs) {
        if (f.continuousRates) {
            if (f.minRate == 0 || f.minRate > f.maxRate) continue;
            candidates.insert(candidates.end(), {std::clamp(sampleRate, f.minRate, f.maxRate), f.minRate, f.maxRate});
        } else {
            candidates.insert(candidates.end(), f.rates.begin(), f.rates.end());
        }
    }
    candidates.erase(std::remove_if(candidates.begin(), candidates.end(),
                                    [&](uint32_t r) {
                                        return std::none_of(device_.outputs.begin(), device_.outputs.end(),
                                                            [&](const uac::OutputFormat& f) { return fits(f, r); });
                                    }),
                     candidates.end());
    const uint32_t outRate = uac::chooseOutputRate(sampleRate, candidates);
    if (outRate == 0) {
        char msg[160];
        std::snprintf(msg, sizeof msg, "The DAC has no %u-channel output.", channels);
        error = msg;
        return false;
    }
    const bool resampling = outRate != sampleRate;

    // Prefer the smallest format that holds the source losslessly; otherwise the
    // deepest one available, with dither.
    int best = -1;
    int deepest = -1;
    for (size_t i = 0; i < device_.outputs.size(); ++i) {
        const uac::OutputFormat& f = device_.outputs[i];
        if (!fits(f, outRate)) continue;
        if (deepest < 0 || f.bitResolution > device_.outputs[deepest].bitResolution) deepest = int(i);
        if (f.bitResolution < sourceBits) continue;
        if (best < 0 || f.subslotBytes < device_.outputs[best].subslotBytes) best = int(i);
    }
    out.lossless = best >= 0 && !resampling;
    out.outputIndex = (resampling || best < 0) ? deepest : best;
    out.outputRate = outRate;
    out.resampling = resampling;
    out.dacBits = device_.outputs[out.outputIndex].bitResolution;
    out.processed = processing(out.outputRate);
    return true;
}

bool UsbStreamer::fitsBandwidth(const uac::OutputFormat& f, uint32_t rate) const {
    if (f.formatTag != 1 || rate == 0 || !f.supportsRate(rate) ||
        f.channels == 0 || f.subslotBytes < 1 || f.subslotBytes > 4 ||
        f.bitResolution < 1 || f.bitResolution > 8 * f.subslotBytes ||
        f.endpointAddress < 1 || f.endpointAddress > 15 ||
        f.interval < 1 || f.interval > (highSpeed_ ? 4 : 1)) return false;
    if (f.syncType == uac::SyncType::None) return false;
    if (f.syncType == uac::SyncType::Async &&
        (f.feedbackEndpoint < 0x81 || f.feedbackEndpoint > 0x8f ||
         f.feedbackMaxPacket < 3 || f.feedbackMaxPacket > 4 ||
         f.feedbackInterval < 1 || f.feedbackInterval > (highSpeed_ ? 4 : 1))) return false;
    const uint32_t pps = packetsPerSecond(f);
    const uint64_t maxFrames = (uint64_t(rate) + pps - 1) / pps;
    return maxFrames * f.frameBytes() <= f.maxPacketBytes;
}

std::vector<UsbStreamer::Mode> UsbStreamer::modes() const {
    static const uint32_t kCommonRates[] = {44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000};
    std::vector<Mode> result;
    if (!handle_) return result;
    for (size_t i = 0; i < device_.outputs.size(); ++i) {
        const uac::OutputFormat& f = device_.outputs[i];
        std::vector<uint32_t> rates = f.rates;
        if (f.continuousRates) {
            for (uint32_t r : kCommonRates) {
                if (r >= f.minRate && r <= f.maxRate) rates.push_back(r);
            }
        }
        for (uint32_t r : rates) {
            if (fitsBandwidth(f, r)) result.push_back({int(i), r, f.bitResolution, f.subslotBytes, f.channels});
        }
    }
    return result;
}

uint8_t UsbStreamer::verifiedClockSource(const uac::OutputFormat& format) {
    uint8_t id = format.clockId;
    for (int hops = 0; hops < 8; ++hops) {
        const auto* clock = uac::findClock(device_, id);
        if (!clock) return 0;
        if (clock->kind == uac::Clock::Kind::Source) return id;
        // Clock multipliers need ratio verification before they can be supported.
        if (clock->kind != uac::Clock::Kind::Selector || clock->inputs.empty()) return 0;
        uint8_t pin = 0;
        const int rc = libusb_control_transfer(handle_, kReqInInterface, kUac2Cur, kClockSelectorControl << 8,
                                               uint16_t((id << 8) | device_.controlInterface), &pin, 1, 1000);
        if (rc != 1 || pin < 1 || size_t(pin) > clock->inputs.size()) return 0;
        id = clock->inputs[size_t(pin) - 1];
    }
    return 0;
}

void UsbStreamer::loadUac2Rates() {
    clockSources_.assign(device_.outputs.size(), 0);
    std::vector<std::pair<uint8_t, std::vector<uint32_t>>> cache;
    for (size_t i = 0; i < device_.outputs.size(); ++i) {
        uac::OutputFormat& f = device_.outputs[i];
        const uint8_t source = verifiedClockSource(f);
        clockSources_[i] = source;
        if (source == 0) {
            LOGW("alt %u: no clock source reachable from clock %u", f.altSetting, f.clockId);
            continue;
        }
        auto it = std::find_if(cache.begin(), cache.end(), [&](const auto& e) { return e.first == source; });
        if (it == cache.end()) {
            cache.push_back({source, readClockRates(source)});
            it = cache.end() - 1;
        }
        f.rates = it->second;
    }
}

std::vector<uint32_t> UsbStreamer::readClockRates(uint8_t source) {
    const uint16_t index = uint16_t((source << 8) | device_.controlInterface);
    // RANGE: first the number of sub-ranges, then all of them (as Linux does).
    uint8_t head[2] = {};
    int rc = libusb_control_transfer(handle_, kReqInInterface, kUac2Range, kSamplingFreqControl << 8, index, head,
                                     2, 1000);
    if (rc == 2) {
        const size_t ranges = size_t(head[0] | (head[1] << 8));
        std::vector<uint8_t> buf(2 + 12 * ranges);
        rc = libusb_control_transfer(handle_, kReqInInterface, kUac2Range, kSamplingFreqControl << 8, index,
                                     buf.data(), uint16_t(buf.size()), 1000);
        if (rc >= 2) {
            std::vector<uint32_t> rates = uac::ratesFromRange(buf.data(), size_t(rc));
            if (!rates.empty()) return rates;
        }
    }
    LOGW("clock %u: RANGE request failed (%s), using its current rate", source,
         rc < 0 ? libusb_error_name(rc) : "short reply");
    uint8_t cur[4] = {};
    rc = libusb_control_transfer(handle_, kReqInInterface, kUac2Cur, kSamplingFreqControl << 8, index, cur, 4, 1000);
    if (rc == 4 && le32(cur) != 0) return {le32(cur)};
    return {};
}

bool UsbStreamer::setUac2Rate(size_t outputIndex, uint32_t rate, uint32_t& deviceRate, std::string& error) {
    const uint8_t source = outputIndex < clockSources_.size() ? clockSources_[outputIndex] : 0;
    if (source == 0) {
        error = "The DAC's clock could not be found.";
        return false;
    }
    if (verifiedClockSource(device_.outputs[outputIndex]) != source) {
        error = "The DAC clock path is unknown or changed; reopen the connection.";
        return false;
    }
    const uint16_t index = uint16_t((source << 8) | device_.controlInterface);
    auto readRate = [&]() -> uint32_t {
        uint8_t cur[4] = {};
        const int rc = libusb_control_transfer(handle_, kReqInInterface, kUac2Cur, kSamplingFreqControl << 8, index,
                                               cur, 4, 1000);
        return rc == 4 ? le32(cur) : 0;
    };
    uint32_t current = readRate();
    if (current != rate) {
        const uac::Clock* clock = uac::findClock(device_, source);
        if (clock && !clock->frequencyWritable) {
            error = "The read-only DAC clock could not be verified at the requested rate.";
            return false;
        }
        uint8_t data[4] = {uint8_t(rate), uint8_t(rate >> 8), uint8_t(rate >> 16), uint8_t(rate >> 24)};
        const int rc = libusb_control_transfer(handle_, kReqOutInterface, kUac2Cur, kSamplingFreqControl << 8, index,
                                               data, 4, 1000);
        if (rc != 4) {
            error = "Could not configure the DAC clock (failed or short SET_CUR).";
            return false;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(20));  // let the DAC's clock relock
        current = readRate();
    }
    deviceRate = current;
    if (current != 0 && current != rate) {
        char msg[128];
        std::snprintf(msg, sizeof msg, "The DAC was asked for %u Hz but its clock runs at %u Hz.", rate, current);
        error = msg;
        return false;
    }
    LOGI("clock %u at %u Hz", source, current);
    return true;
}

bool UsbStreamer::startFeedback(const uac::OutputFormat& f, uint32_t rate, uint32_t pps, std::string& error) {
    // Feedback is per (micro)frame; a data packet may span several when bInterval > 1.
    const uint32_t unitsPerSecond = highSpeed_ ? 8000 : 1000;
    unitsPerPacket_ = std::max<uint32_t>(1, unitsPerSecond / pps);
    nominalUnitQ16_ = uint32_t((uint64_t(rate) << 16) / unitsPerSecond);
    feedbackShift_ = uac::kUnknownShift;
    feedbackSeen_ = false;
    const int packetSize = f.feedbackMaxPacket ? f.feedbackMaxPacket : (highSpeed_ ? 4 : 3);
    for (int i = 0; i < kFeedbackTransfers; ++i) {
        transferBuffers_.emplace_back(size_t(packetSize) * kFeedbackPackets);
        libusb_transfer* t = libusb_alloc_transfer(kFeedbackPackets);
        if (!t) {
            error = "Could not allocate USB feedback transfers.";
            return false;
        }
        libusb_fill_iso_transfer(t, handle_, f.feedbackEndpoint, transferBuffers_.back().data(),
                                 packetSize * kFeedbackPackets, kFeedbackPackets, &UsbStreamer::onFeedbackDone, this,
                                 0);
        libusb_set_iso_packet_lengths(t, unsigned(packetSize));
        transfers_.push_back(t);
        ++inFlight_;
        const int rc = libusb_submit_transfer(t);
        if (rc != LIBUSB_SUCCESS) {
            --inFlight_;
            error = std::string("Could not submit USB feedback: ") + libusb_error_name(rc);
            return false;
        }
    }
    LOGI("async: listening to feedback endpoint 0x%02x", f.feedbackEndpoint);
    return true;
}

void UsbStreamer::onFeedbackDone(libusb_transfer* t) {
    auto* self = static_cast<UsbStreamer*>(t->user_data);
    if (self->streaming_ && self->feedbackExpired(monotonicMs())) self->failTransfer();
    bool resubmit = false;
    switch (t->status) {
        case LIBUSB_TRANSFER_COMPLETED: {
            bool accepted = false;
            for (int i = 0; i < t->num_iso_packets; ++i) {
                const libusb_iso_packet_descriptor& p = t->iso_packet_desc[i];
                if (p.status != LIBUSB_TRANSFER_COMPLETED || p.actual_length > p.length) {
                    self->failTransfer();
                    break;
                }
                if (p.actual_length != 3 && p.actual_length != 4) continue;
                int candidateShift = self->feedbackShift_;
                const uint32_t perUnit = uac::decodeFeedback(libusb_get_iso_packet_buffer(t, unsigned(i)),
                                                             p.actual_length, self->highSpeed_, self->nominalUnitQ16_,
                                                             candidateShift);
                if (perUnit == 0) continue;
                const uint64_t perPacket = uint64_t(perUnit) * self->unitsPerPacket_;
                const uint32_t cap = self->format_->maxPacketBytes / self->frameBytes_;
                if ((perPacket + 65535) / 65536 > cap) {
                    self->failTransfer();
                    break;
                }
                self->scheduler_->setFeedback(uint32_t(perPacket));
                self->feedbackShift_ = candidateShift;
                self->feedbackUpdatedMs_ = monotonicMs();
                accepted = true;
            }
            if (accepted && self->streaming_) {
                self->feedbackSeen_ = true;
                self->spaceCv_.notify_all();
            }
            resubmit = self->streaming_;
            break;
        }
        case LIBUSB_TRANSFER_NO_DEVICE:
            self->disconnected_ = true;
            self->streaming_ = false;
            break;
        case LIBUSB_TRANSFER_CANCELLED:
            if (self->streaming_) self->failTransfer();
            break;
        default:
            self->failTransfer();
            break;
    }
    if (resubmit) {
        const int rc = libusb_submit_transfer(t);
        if (rc == LIBUSB_SUCCESS) return;
        if (rc == LIBUSB_ERROR_NO_DEVICE) self->disconnected_ = true;
        self->failTransfer();
    }
    --self->inFlight_;
    self->spaceCv_.notify_all();
}

uint32_t UsbStreamer::packetsPerSecond(const uac::OutputFormat& f) const {
    const int speed = libusb_get_device_speed(libusb_get_device(handle_));
    const uint32_t shift = std::clamp<uint32_t>(f.interval, 1, 16) - 1;
    return (speed >= LIBUSB_SPEED_HIGH ? 8000u : 1000u) >> std::min<uint32_t>(shift, 3);
}

void UsbStreamer::fillTransfer(libusb_transfer* t) {
    const auto at = std::find(transfers_.begin(), transfers_.end(), t);
    if (at == transfers_.end()) { failTransfer(); return; }
    auto& pcmFrames = transferPcmFrames_[size_t(at - transfers_.begin())];
    pcmFrames = 0;
    uint8_t* buf = t->buffer;
    size_t offset = 0;
    for (int i = 0; i < t->num_iso_packets; ++i) {
        const size_t want = size_t(scheduler_->next()) * frameBytes_;
        size_t got;
        {
            std::lock_guard<std::mutex> flow(flowMutex_);
            got = volumeVerified_ && (primed_ || inputEnded_) ? ring_->read(buf + offset, want) : 0;
            if (got < want && primed_ && volumeVerified_) {
                ++underruns_;
                ++trailingUnderruns_;
            }
        }
        if (got < want) {
            std::fill(buf + offset + got, buf + offset + want, uint8_t(0));  // silence
        }
        pcmFrames += got / frameBytes_;
        t->iso_packet_desc[i].length = static_cast<unsigned int>(want);
        offset += want;
    }
    spaceCv_.notify_one();
}

void UsbStreamer::onTransferDone(libusb_transfer* t) {
    auto* self = static_cast<UsbStreamer*>(t->user_data);
    const auto at = std::find(self->transfers_.begin(), self->transfers_.end(), t);
    const size_t index = size_t(at - self->transfers_.begin());
    const bool known = at != self->transfers_.end();
    const uint64_t pcmFrames = known ? self->transferPcmFrames_[index] : 0;
    if (!known) self->failTransfer();
    else self->transferPcmFrames_[index] = 0;
    if (self->streaming_ && self->feedbackRequired_ && self->feedbackExpired(monotonicMs())) self->failTransfer();
    bool resubmit = false;
    bool completed = false;
    switch (t->status) {
        case LIBUSB_TRANSFER_COMPLETED:
            completed = known;
            for (int i = 0; i < t->num_iso_packets; ++i) {
                const auto& packet = t->iso_packet_desc[i];
                if (packet.status != LIBUSB_TRANSFER_COMPLETED || packet.actual_length != packet.length) {
                    self->failTransfer();
                    completed = false;
                    break;
                }
            }
            resubmit = self->streaming_;
            break;
        case LIBUSB_TRANSFER_NO_DEVICE:
            self->disconnected_ = true;
            self->streaming_ = false;
            break;
        case LIBUSB_TRANSFER_CANCELLED:
            if (self->streaming_) self->failTransfer();
            break;
        default:
            self->failTransfer();
            break;
    }
    if (resubmit) {
        self->fillTransfer(t);
        const int rc = libusb_submit_transfer(t);
        if (rc == LIBUSB_SUCCESS) {
            self->framesSent_ += self->transferPcmFrames_[index];
            // Publish completion after the callback's packet and resubmission decisions.
            if (completed) self->framesCompleted_ += pcmFrames;
            self->spaceCv_.notify_all();
            return;
        }
        LOGE("resubmit failed: %s", libusb_error_name(rc));
        if (rc == LIBUSB_ERROR_NO_DEVICE) self->disconnected_ = true;
        self->failTransfer();
    }
    if (completed) self->framesCompleted_ += pcmFrames;
    --self->inFlight_;
    self->spaceCv_.notify_all();
}

void UsbStreamer::eventLoop() {
    setpriority(PRIO_PROCESS, 0, -19);  // ANDROID_PRIORITY_URGENT_AUDIO
    while (eventThreadRun_ || inFlight_ > 0) {
        {
            std::lock_guard<std::mutex> lock(eventMutex_);
            timeval tv{0, 20 * 1000};
            libusb_handle_events_timeout_completed(ctx_, &tv, nullptr);
        }
        if (streaming_ && feedbackRequired_ && feedbackExpired(monotonicMs())) failTransfer();
    }
    eventThreadFinished_ = true;
}

void UsbStreamer::failTransfer() {
    ++transferErrors_;
    streaming_ = false;
    spaceCv_.notify_all();
}

bool UsbStreamer::feedbackExpired(int64_t nowMs) const {
    const int64_t updated = feedbackUpdatedMs_;
    return nowMs - (updated ? updated : feedbackStartedMs_.load()) >=
        (updated ? kStaleFeedbackMs : kFirstFeedbackMs);
}

bool UsbStreamer::feedbackReady() const {
    return streaming_ && !disconnected_ && transferErrors_ == 0 &&
        (!feedbackRequired_ || (feedbackSeen_ && !feedbackExpired(monotonicMs())));
}

long UsbStreamer::write(const int32_t* pcm, size_t frames) {
    if (inputEnded_ || !volumeVerified()) return -1;
    if (frames > 0) wrotePcm_ = true;
    const size_t channels = channels_;
    const size_t chunkFrames = resampleIn_.size() / channels;
    for (size_t done = 0; done < frames;) {
        applyPendingEqualizer();
        const size_t n = std::min(chunkFrames, frames - done);
        const int32_t* src = pcm + done * channels;
        bool ok;
        if (!resampler_ && !eq_.active() && !cf_.active()) {
            ok = push(src, n, ditherIntegerPath_);  // untouched samples: bit-perfect when the format allows
        } else {
            src_int_to_float_array(src, resampleIn_.data(), int(n * channels));
            if (resampler_) {
                ok = resampleAndPush(n, false);
            } else {
                if (eq_.active()) eq_.process(resampleIn_.data(), n);
                cf_.process(resampleIn_.data(), n);
                ok = pushProcessedFloat(resampleIn_.data(), n);
            }
        }
        if (!ok) return -1;
        done += n;
    }
    return long(frames);
}

void UsbStreamer::setEqualizer(const std::vector<uac::EqBand>& bands, double preampDb) {
    std::lock_guard<std::mutex> lock(eqMutex_);
    eqBands_ = bands;
    eqPreampDb_ = preampDb;
    eqDirty_ = true;
}

bool UsbStreamer::equalizerActive() const { return eqActiveFlag_; }

void UsbStreamer::setCrossfeed(double cutHz, double feedDb) {
    std::lock_guard<std::mutex> lock(eqMutex_);
    cfCutHz_ = cutHz;
    cfFeedDb_ = feedDb;
    eqDirty_ = true;
}

// Runs on the writing thread only, between chunks, so filters never change mid-buffer.
void UsbStreamer::applyPendingEqualizer() {
    if (!eqDirty_.load(std::memory_order_acquire)) return;
    std::lock_guard<std::mutex> lock(eqMutex_);
    eq_.configure(eqBands_, eqPreampDb_, outputRate_, channels_);
    cf_.configure(cfCutHz_, cfFeedDb_, outputRate_, channels_);
    eqDirty_ = false;
    eqActiveFlag_ = eq_.active();
    LOGI("equalizer %s: %zu bands, preamp %.1f dB; crossfeed %s", eq_.active() ? "on" : "off", eqBands_.size(),
         eqPreampDb_, cf_.active() ? "on" : "off");
}

bool UsbStreamer::resampleAndPush(size_t frames, bool endOfInput) {
    const long channels = long(channels_);
    SRC_DATA d{};
    d.data_in = resampleIn_.data();
    d.input_frames = long(frames);
    d.data_out = resampleOut_.data();
    d.output_frames = long(resampleOut_.size()) / channels;
    d.end_of_input = endOfInput ? 1 : 0;
    d.src_ratio = resampleRatio_;
    while (true) {
        if (!volumeVerified() || !drainWorkAllowed()) return false;
        const int err = src_process(resampler_, &d);
        if (err != 0) {
            LOGE("resampler failed: %s", src_strerror(err));
            return false;
        }
        if (d.output_frames_gen > 0) {
            if (eq_.active()) eq_.process(resampleOut_.data(), size_t(d.output_frames_gen));
            cf_.process(resampleOut_.data(), size_t(d.output_frames_gen));
            if (!pushProcessedFloat(resampleOut_.data(), size_t(d.output_frames_gen))) return false;
        }
        d.data_in += d.input_frames_used * channels;
        d.input_frames -= d.input_frames_used;
        const bool progressed = d.input_frames_used > 0 || d.output_frames_gen > 0;
        if (!progressed) return d.input_frames == 0;
        if (d.input_frames == 0 && !endOfInput) return true;
    }
}

bool UsbStreamer::pushProcessedFloat(const float* pcm, size_t frames) {
    const auto inspected = uac::inspectFloatSamples(pcm, frames * channels_);
    clippedSamples_ += inspected.clipped;
    if (!inspected.finite) return false;
    src_float_to_int_array(pcm, resampledInt_.data(), int(frames * channels_));
    return push(resampledInt_.data(), frames, true);
}

bool UsbStreamer::push(const int32_t* src, size_t frames, bool dither) {
    const size_t channels = channels_;
    if (dither && ditherer_) {
        ditherer_->process(src, ditherBuffer_.data(), frames * channels);
        src = ditherBuffer_.data();
    }
    uac::packSamples(src, packBuffer_.data(), frames * channels, sourceSubslot_);
    const uint8_t* p = packBuffer_.data();
    size_t left = frames * frameBytes_;
    while (left > 0) {
        if (!volumeVerified() || !drainWorkAllowed()) return false;
        size_t w;
        {
            std::lock_guard<std::mutex> flow(flowMutex_);
            size_t space = ring_->writable();
            space -= space % frameBytes_;
            w = ring_->write(p, std::min(left, space));
            if (w > 0) {
                // More input proves any preceding starvation was a real gap, not the end of this track.
                trailingUnderruns_ = 0;
                framesQueued_ += w / frameBytes_;
                if (ring_->readable() >= primeBytes_) primed_ = true;
            }
        }
        if (w > 0) {
            p += w;
            left -= w;
            continue;
        }
        std::unique_lock<std::mutex> lock(spaceMutex_);
        spaceCv_.wait_for(lock, std::chrono::milliseconds(20));
    }
    return true;
}

bool UsbStreamer::drainHealthy() const {
    return clockConfigured() && feedbackReady() && underruns_ == 0 && (!wrotePcm_ || volumeVerified());
}

bool UsbStreamer::drainWorkAllowed() {
    if (inputEnded_ && std::chrono::steady_clock::now() >= drainDeadline_) {
        failTransfer();
        return false;
    }
    return true;
}

bool UsbStreamer::drain() {
    drainDeadline_ = std::chrono::steady_clock::now() + kDrainTimeout;
    {
        std::lock_guard<std::mutex> flow(flowMutex_);
        if (inputEnded_.exchange(true)) return drainCompleted_ && drainHealthy();
    }
    if (!clockConfigured() || !feedbackReady() || (wrotePcm_ && !volumeVerified())) return false;
    if (resampler_ && wrotePcm_ && !resampleAndPush(0, true)) return false;
    {
        std::lock_guard<std::mutex> flow(flowMutex_);
        // A producer cannot announce EOF until its final write returns; classify only that trailing starvation.
        underruns_ -= trailingUnderruns_;
        trailingUnderruns_ = 0;
        primed_ = false;
    }
    if (!drainHealthy()) return false;
    const uint64_t target = framesQueued_;
    std::unique_lock<std::mutex> lock(spaceMutex_);
    while (true) {
        if (!drainHealthy() || !drainWorkAllowed()) return false;
        if (framesCompleted_ == target && ring_ && ring_->readable() == 0) {
            drainCompleted_ = drainHealthy();
            return drainCompleted_;
        }
        spaceCv_.wait_until(lock, std::min(drainDeadline_, std::chrono::steady_clock::now() + std::chrono::milliseconds(20)));
    }
}

bool UsbStreamer::stop() {
    clockConfigured_ = false;
    volumeVerified_ = false;
    streaming_ = false;
    spaceCv_.notify_all();
    eventThreadRun_ = false;
    for (libusb_transfer* t : transfers_) libusb_cancel_transfer(t);
    if (ctx_) libusb_interrupt_event_handler(ctx_);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(1);
    while ((!eventThreadFinished_ || inFlight_ > 0) && std::chrono::steady_clock::now() < deadline) {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    if (!eventThreadFinished_ || inFlight_ > 0) {
        LOGE("USB shutdown timed out; retaining callback owner (%d transfers)", inFlight_.load());
        return false;
    }
    // A zero transfer count alone does not prove the last callback has returned.
    if (eventThread_.joinable()) eventThread_.join();
    for (libusb_transfer* t : transfers_) libusb_free_transfer(t);
    transfers_.clear();
    transferPcmFrames_.clear();
    transferBuffers_.clear();
    if (format_ && handle_ && !disconnected_) {
        libusb_set_interface_alt_setting(handle_, format_->interfaceNumber, 0);
    }
    format_ = nullptr;
    primed_ = false;
    return true;
}

bool UsbStreamer::close() {
    if (!stop()) return false;
    if (handle_) {
        if (!disconnected_) {
            for (const auto& [key, value] : savedVolume_) {
                const auto ch = uint8_t(key);
                const auto unit = uint8_t(key >> 8);
                uint8_t buf[2] = {uint8_t(value), uint8_t(uint16_t(value) >> 8)};
                if (controlFeature(kSetCur, kVolumeControl, ch, buf, 2, unit) != 2 ||
                    controlFeature(kGetCur, kVolumeControl, ch, buf, 2, unit) != 2 || le16(buf) != value) {
                    if (disconnected_) break;
                    LOGE("Could not restore volume unit %u channel %u; retaining connection", unit, ch);
                    return false;
                }
            }
        }
        savedVolume_.clear();
        // Hand the DAC back to Android's own USB audio driver.
        for (const uac::OutputFormat& f : device_.outputs) {
            const int rc = libusb_release_interface(handle_, f.interfaceNumber);
            LOGI("release interface %u: %s", f.interfaceNumber, libusb_error_name(rc));
        }
        if (device_.controlInterface >= 0) {
            int rc = libusb_release_interface(handle_, device_.controlInterface);
            LOGI("release interface %d: %s", device_.controlInterface, libusb_error_name(rc));
            if (!disconnected_) {
                rc = libusb_attach_kernel_driver(handle_, device_.controlInterface);
                LOGI("reattach Android's driver to interface %d: %s", device_.controlInterface,
                     libusb_error_name(rc));
            }
        }
        libusb_close(handle_);  // does not close the wrapped fd; Java closes the connection
        handle_ = nullptr;
    }
    if (ctx_) {
        libusb_exit(ctx_);
        ctx_ = nullptr;
    }
    featureUnit_ = nullptr;
    if (resampler_) resampler_ = src_delete(resampler_);
    return true;
}

int UsbStreamer::controlFeature(uint8_t request, uint8_t control, uint8_t channel, uint8_t* data, uint16_t length, uint8_t unitId) {
    if (!unitId && featureUnit_) unitId = featureUnit_->unitId;
    if (!handle_ || !unitId) return LIBUSB_ERROR_NOT_SUPPORTED;
    const uint8_t type = (request & 0x80) ? kReqInInterface : kReqOutInterface;
    if (device_.uacVersion == 2 && request == kGetCur) request = kUac2Cur;  // UAC2 GET CUR is 0x01 + IN
    const int rc = libusb_control_transfer(handle_, type, request, uint16_t((control << 8) | channel),
                                   uint16_t((unitId << 8) | device_.controlInterface), data, length,
                                   1000);
    if (rc == LIBUSB_ERROR_NO_DEVICE) disconnected_ = true;
    return rc;
}

std::vector<uint8_t> UsbStreamer::volumeChannels() const {
    std::vector<uint8_t> channels;
    if (!featureUnit_ || !format_) return channels;
    if (featureUnit_->masterVolume) channels.push_back(0);
    for (size_t i = 0; i < format_->channels; ++i) {
        if (i < featureUnit_->channelVolume.size() && featureUnit_->channelVolume[i]) channels.push_back(uint8_t(i + 1));
        else if (!featureUnit_->masterVolume) return {};
    }
    return channels;
}

UsbStreamer::VolumeRange UsbStreamer::channelVolumeRange(uint8_t ch) {
    VolumeRange r;
    uint8_t buf[2];
    if (device_.uacVersion == 2) {
        // A single stepped range is supported; multiple/disjoint ranges remain unavailable.
        uint8_t range[8] = {};
        const int rc = libusb_control_transfer(handle_, kReqInInterface, kUac2Range, uint16_t(kVolumeControl << 8 | ch),
                                               uint16_t((featureUnit_->unitId << 8) | device_.controlInterface),
                                               range, sizeof range, 1000);
        if (rc == LIBUSB_ERROR_NO_DEVICE) disconnected_ = true;
        if (rc != 8 || range[0] != 1 || range[1] != 0) return r;
        r.min = le16(range + 2);
        r.max = le16(range + 4);
        r.res = le16(range + 6);
    } else {
        if (controlFeature(kGetMin, kVolumeControl, ch, buf, 2) != 2) return r;
        r.min = le16(buf);
        if (controlFeature(kGetMax, kVolumeControl, ch, buf, 2) != 2) return r;
        r.max = le16(buf);
        if (controlFeature(kGetRes, kVolumeControl, ch, buf, 2) != 2) return r;
        r.res = le16(buf);
    }
    if (controlFeature(kGetCur, kVolumeControl, ch, buf, 2) != 2) return r;
    r.cur = le16(buf);
    r.available = r.min < r.max && r.res > 0 && r.cur >= r.min && r.cur <= r.max &&
        (int32_t(r.cur) - r.min) % r.res == 0;
    return r;
}

UsbStreamer::VolumeRange UsbStreamer::volumeRange() {
    VolumeRange result;
    const bool verified = volumeVerified_;
    for (uint8_t ch : volumeChannels()) {
        const auto range = channelVolumeRange(ch);
        const int32_t expected = featureUnit_->masterVolume && ch != 0 ? 0 : verifiedVolume256_;
        if (!range.available || (verified && range.cur != expected)) {
            volumeVerified_ = false;
            spaceCv_.notify_all();
            return {};
        }
        if (featureUnit_->masterVolume && ch != 0) {
            if (range.cur != 0) return {};
            continue;
        }
        if (result.available && (result.min != range.min || result.max != range.max ||
            result.res != range.res || result.cur != range.cur)) return {};
        result = range;
    }
    return result;
}

bool UsbStreamer::setVolume(int32_t value) {
    volumeVerified_ = false;
    spaceCv_.notify_all();
    if (value < -32768 || value > 32767 || !feedbackReady()) return false;
    const auto channels = volumeChannels();
    if (channels.empty()) return false;
    if (featureUnit_->masterMute) {
        uint8_t mute = 0;
        if (controlFeature(kGetCur, kMuteControl, 0, &mute, 1) != 1 || mute != 0) return false;
    }
    std::vector<VolumeRange> ranges;
    // Master controls the requested gain; per-channel controls stay at unity to preserve balance.
    const auto targetFor = [&](uint8_t channel) { return featureUnit_->masterVolume && channel != 0 ? 0 : value; };
    for (uint8_t ch : channels) {
        const auto range = channelVolumeRange(ch);
        const int32_t target = targetFor(ch);
        if (!range.available || target < range.min || target > range.max || (target - range.min) % range.res) return false;
        ranges.push_back(range);
    }
    for (size_t i = 0; i < channels.size(); ++i) {
        const auto key = uint16_t((featureUnit_->unitId << 8) | channels[i]);
        if (std::none_of(savedVolume_.begin(), savedVolume_.end(), [&](const auto& saved) { return saved.first == key; }))
            savedVolume_.push_back({key, ranges[i].cur});
    }
    for (uint8_t ch : channels) {
        const auto target = targetFor(ch);
        uint8_t buf[2] = {uint8_t(target), uint8_t(uint16_t(target) >> 8)};
        if (controlFeature(kSetCur, kVolumeControl, ch, buf, 2) != 2) return false;
    }
    for (uint8_t ch : channels) {
        uint8_t buf[2];
        if (controlFeature(kGetCur, kVolumeControl, ch, buf, 2) != 2 || le16(buf) != targetFor(ch)) return false;
    }
    verifiedVolume256_ = value;
    volumeVerified_ = feedbackReady();
    return volumeVerified_;
}

UsbStreamer::Stats UsbStreamer::stats() const {
    return Stats{framesSent_.load(), underruns_.load(), transferErrors_.load(), disconnected_.load(),
        framesQueued_.load(), framesCompleted_.load(), inputEnded_.load(), clippedSamples_.load()};
}
