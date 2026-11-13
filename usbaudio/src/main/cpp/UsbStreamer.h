// Modified for Evanna (2026-11-02): checked writes, transport ownership and bounded USB completion drain.
// Modified for Evanna (2026-11-03): two-transfer start buffer before PCM and underrun counting.
// Modified for Evanna (2026-11-04): planned/observed processing and pre-conversion clipping statistics.
// Modified for Evanna (2026-11-05): stereo crossfeed after the equalizer.
// Modified for Evanna (2026-11-13): equalizer removed; the app's sound engine processes PCM before write().
// Owns the libusb handle for one USB Audio Class 1 DAC and streams PCM to it
// with isochronous transfers, bypassing Android's audio stack.
#pragma once

#include <atomic>
#include <condition_variable>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "uac/Crossfeed.h"
#include "uac/Pcm.h"
#include "uac/UacDescriptors.h"

struct libusb_context;
struct libusb_device_handle;
struct libusb_transfer;
struct SRC_STATE_tag;

class UsbStreamer {
public:
    struct StreamInfo {
        int outputIndex = -1;
        uint32_t sampleRate = 0;  // source
        uint32_t outputRate = 0;  // what the DAC runs at
        uint32_t deviceRate = 0;  // read back from the DAC, 0 if it cannot report it
        uint32_t subslotBytes = 0;
        uint32_t bitResolution = 0;
        bool bitPerfect = false;
        bool resampled = false;
        bool processed = false;
    };

    struct VolumeRange {
        bool available = false;
        int16_t min = 0, max = 0, res = 0, cur = 0;  // 1/256 dB units
    };

    struct Stats {
        uint64_t framesSent = 0;
        uint64_t underruns = 0;
        uint64_t transferErrors = 0;
        bool disconnected = false;
        uint64_t framesQueued = 0;
        uint64_t framesCompleted = 0;
        bool inputEnded = false;
        uint64_t clippedSamples = 0; // Processed float values outside full scale, before conversion.
    };

    // How a source format would be played on this DAC.
    struct Plan {
        int outputIndex = -1;
        uint32_t outputRate = 0;
        uint32_t dacBits = 0;
        bool resampling = false;
        bool lossless = false;  // Rate and precision; effects are reported separately.
        bool processed = false;
    };

    // One way the DAC can run: a playback format at a sample rate.
    struct Mode {
        int outputIndex = -1;
        uint32_t sampleRate = 0;
        uint32_t bitResolution = 0;
        uint32_t subslotBytes = 0;
        uint32_t channels = 0;
    };

    ~UsbStreamer();

    // `fd` comes from UsbDeviceConnection.getFileDescriptor(); the caller keeps it
    // open until close(). The interfaces must already be claimed by Java.
    bool open(int fd, const uint8_t* rawDescriptors, size_t length, std::string& error);
    const uac::UacDevice& device() const { return device_; }

    // Every mode the DAC can actually stream (rate x format, within USB bandwidth).
    std::vector<Mode> modes() const;

    // resampleQuality: 0 = best, 1 = medium, 2 = fast (libsamplerate sinc converters).
    // fixedMode: play every song in this mode; nullptr picks the best mode per song.
    bool start(uint32_t sampleRate, uint32_t sourceBits, uint32_t channels, int resampleQuality, const Mode* fixedMode,
               StreamInfo& info, std::string& error);
    // Works out how start() would play this format, without touching the DAC.
    bool plan(uint32_t sampleRate, uint32_t sourceBits, uint32_t channels, const Mode* fixedMode, Plan& plan,
              std::string& error) const;
    // Blocks until all frames are queued. Returns frames queued, or -1 if the
    // stream stopped or the device went away.
    long write(const int32_t* interleaved, size_t frames);
    uint32_t writeChannels() const { return streaming_ ? channels_ : 0; }
    // Seals input and verifies queued PCM transfer completion within one 1500 ms budget.
    bool drain();
    // False retains the complete stream/connection for a later cleanup attempt.
    bool stop();
    bool close();

    // Stereo crossfeed after any resampling (bs2b cut Hz / feed dB); cutHz 0 turns it off.
    // Safe to call from any thread; takes effect at the next written chunk.
    void setCrossfeed(double cutHz, double feedDb);

    VolumeRange volumeRange();
    bool setVolume(int32_t value256);
    bool volumeVerified() const { return volumeVerified_ && feedbackReady(); }
    // Configuration acknowledgement, not a substitute for device-rate readback or physical measurement.
    bool clockConfigured() const { return clockConfigured_ && !disconnected_; }
    Stats stats() const;
    // Cached stream health; false before startup, after stop, or after stale/failed feedback.
    bool feedbackReady() const;

private:
    static void onTransferDone(libusb_transfer* transfer);
    static void onFeedbackDone(libusb_transfer* transfer);
    bool startFeedback(const uac::OutputFormat& format, uint32_t rate, uint32_t packetsPerSecond, std::string& error);
    void loadUac2Rates();
    uint8_t verifiedClockSource(const uac::OutputFormat& format);
    std::vector<uint32_t> readClockRates(uint8_t clockSource);
    bool setUac2Rate(size_t outputIndex, uint32_t rate, uint32_t& deviceRate, std::string& error);
    void fillTransfer(libusb_transfer* transfer);
    bool push(const int32_t* interleaved, size_t frames, bool dither);  // dither, pack, queue for USB
    bool pushProcessedFloat(const float* interleaved, size_t frames);
    void applyPendingCrossfeed();
    bool resampleAndPush(size_t frames, bool endOfInput);  // frames already in resampleIn_
    void eventLoop();
    void failTransfer(const char* why = "unspecified", int detail = 0);
    bool feedbackExpired(int64_t nowMs) const;
    bool drainHealthy() const;
    bool drainWorkAllowed();
    uint32_t packetsPerSecond(const uac::OutputFormat& format) const;
    bool fitsBandwidth(const uac::OutputFormat& format, uint32_t rate) const;
    int controlFeature(uint8_t request, uint8_t control, uint8_t channel, uint8_t* data, uint16_t length, uint8_t unitId = 0);
    VolumeRange channelVolumeRange(uint8_t channel);
    std::vector<uint8_t> volumeChannels() const;

    libusb_context* ctx_ = nullptr;
    libusb_device_handle* handle_ = nullptr;
    uac::UacDevice device_;
    const uac::FeatureUnit* featureUnit_ = nullptr;
    std::vector<uint8_t> clockSources_;  // UAC2: clock source per output format
    bool highSpeed_ = false;

    // Async feedback (touched only on the libusb event thread once streaming)
    uint32_t unitsPerPacket_ = 1;
    uint32_t nominalUnitQ16_ = 0;
    int feedbackShift_ = 0;
    std::atomic<bool> feedbackSeen_{false};
    std::atomic<bool> feedbackRequired_{false};
    std::atomic<int64_t> feedbackStartedMs_{0};
    std::atomic<int64_t> feedbackUpdatedMs_{0};
    std::vector<std::pair<uint16_t, int16_t>> savedVolume_; // (feature-unit ID << 8 | channel), restored on close
    std::atomic<bool> volumeVerified_{false};
    std::atomic<bool> clockConfigured_{false};
    int32_t verifiedVolume256_ = 0; // Serialized control-lane value; callbacks only read the atomic verification flag.

    // Active stream
    const uac::OutputFormat* format_ = nullptr;
    std::unique_ptr<uac::PacketScheduler> scheduler_;
    std::unique_ptr<uac::RingBuffer> ring_;
    std::vector<libusb_transfer*> transfers_;
    std::vector<uint64_t> transferPcmFrames_; // Event-thread-owned, one count per current submission.
    std::vector<std::vector<uint8_t>> transferBuffers_;
    std::vector<uint8_t> packBuffer_;
    std::unique_ptr<uac::Ditherer> ditherer_;  // set only when the DAC has fewer bits than the source
    std::vector<int32_t> ditherBuffer_;
    uint32_t packetsPerTransfer_ = 0;
    uint32_t frameBytes_ = 0;
    uint32_t sourceSubslot_ = 0;
    uint32_t channels_ = 0;
    size_t maxPushFrames_ = 0;
    SRC_STATE_tag* resampler_ = nullptr;  // set only when the DAC lacks the source rate
    double resampleRatio_ = 1.0;
    std::vector<float> resampleIn_;
    std::vector<float> resampleOut_;
    std::vector<int32_t> resampledInt_;
    uint32_t outputRate_ = 0;
    bool ditherIntegerPath_ = false;

    // Crossfeed: settings written by any thread, filter owned by the writing thread.
    uac::Crossfeed cf_;
    mutable std::mutex cfMutex_;
    double cfCutHz_ = 0, cfFeedDb_ = 0;
    std::atomic<bool> cfDirty_{false};

    std::thread eventThread_;
    std::mutex eventMutex_; // Hold callbacks until all startup state and initial transfers are ready.
    std::atomic<bool> eventThreadRun_{false};
    std::atomic<bool> eventThreadFinished_{true};
    std::atomic<bool> streaming_{false};
    std::atomic<bool> primed_{false};  // Evanna: PCM flows (and underruns count) once primeBytes_ are buffered or input ends
    size_t primeBytes_ = 0;
    std::atomic<bool> inputEnded_{false};
    bool wrotePcm_ = false; // Writer lane only, including SRC input which has not generated output yet.
    bool drainCompleted_ = false; // Writer lane only; repeated drains never flush SRC twice.
    std::chrono::steady_clock::time_point drainDeadline_{};
    std::atomic<int> inFlight_{0};

    std::mutex spaceMutex_;
    std::condition_variable spaceCv_;
    std::mutex flowMutex_; // Serialize ring publication/consumption with EOF padding classification only.
    uint64_t trailingUnderruns_ = 0; // flowMutex_: becomes real if more PCM arrives, padding if EOF follows.

    std::atomic<uint64_t> framesSent_{0};
    std::atomic<uint64_t> framesQueued_{0};
    std::atomic<uint64_t> framesCompleted_{0};
    std::atomic<uint64_t> underruns_{0};
    std::atomic<uint64_t> transferErrors_{0};
    int badTransfersInARow_ = 0;  // event thread only
    int dropReports_ = 0;         // event thread only: caps the drop log lines per stream
    std::atomic<uint64_t> clippedSamples_{0};
    std::atomic<bool> disconnected_{false};
};
