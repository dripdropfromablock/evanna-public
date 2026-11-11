#pragma once
#include "ebur128.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <new>
#include <stdexcept>

namespace evanna {
struct LoudnessResult {double lufs; double peak; uint64_t frames;};
class LoudnessMeter {
    ebur128_state* state_=nullptr;
    uint64_t frames_=0,maxFrames_;
    // libebur128 1.2.6 rewrites shared tables on every init; protect readers as well as constructors.
    static std::mutex& libraryMutex() {static std::mutex value;return value;}
    static void checked(int status) {if(status!=EBUR128_SUCCESS)throw std::runtime_error("LOUDNESS_METER_FAILED");}
public:
    LoudnessMeter(unsigned rate,unsigned channels):maxFrames_(uint64_t(rate)*604800) {
        if(rate<8000 || rate>768000 || channels<1 || channels>2)throw std::invalid_argument("LOUDNESS_INVALID_FORMAT");
        std::lock_guard<std::mutex> lock(libraryMutex());
        state_=ebur128_init(channels,rate,EBUR128_MODE_I|EBUR128_MODE_SAMPLE_PEAK|EBUR128_MODE_HISTOGRAM);
        if(!state_)throw std::bad_alloc();
        if(channels==1 && ebur128_set_channel(state_,0,EBUR128_CENTER)!=EBUR128_SUCCESS) {
            ebur128_destroy(&state_);throw std::runtime_error("LOUDNESS_INVALID_CHANNEL");
        }
    }
    ~LoudnessMeter() {std::lock_guard<std::mutex> lock(libraryMutex());if(state_)ebur128_destroy(&state_);}
    LoudnessMeter(const LoudnessMeter&)=delete;
    LoudnessMeter& operator=(const LoudnessMeter&)=delete;
    void add(const int32_t* pcm,unsigned frames) {
        if(!pcm || frames==0 || frames>4096 || frames>maxFrames_-frames_)throw std::invalid_argument("LOUDNESS_INVALID_BLOCK");
        std::lock_guard<std::mutex> lock(libraryMutex());
        checked(ebur128_add_frames_int(state_,pcm,frames));frames_+=frames;
    }
    LoudnessResult result() const {
        if(frames_==0)throw std::invalid_argument("LOUDNESS_EMPTY_SOURCE");
        std::lock_guard<std::mutex> lock(libraryMutex());
        double lufs=0,peak=0;checked(ebur128_loudness_global(state_,&lufs));
        for(unsigned channel=0;channel<state_->channels;++channel) {
            double value=0;checked(ebur128_sample_peak(state_,channel,&value));peak=std::max(peak,value);
        }
        if((!std::isfinite(lufs) && !(std::isinf(lufs) && lufs<0)) || !std::isfinite(peak) || peak<0 || peak>1)
            throw std::runtime_error("LOUDNESS_INVALID_RESULT");
        return {lufs,peak,frames_};
    }
};
}
