#include "LoudnessMeter.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <thread>
#include <vector>

static evanna::LoudnessResult tone(unsigned channels, double db, unsigned chunk=4096, bool quiet=false, unsigned seconds=10) {
    evanna::LoudnessMeter meter(48000,channels);
    const double amplitude=std::pow(10.0,db/20.0);
    std::vector<int32_t> pcm(chunk*channels);
    for(unsigned start=0;start<48000*seconds;start+=chunk) {
        const unsigned frames=std::min(chunk,48000*seconds-start);
        for(unsigned frame=0;frame<frames;++frame)for(unsigned channel=0;channel<channels;++channel)
            pcm[frame*channels+channel]=quiet ? 0 : static_cast<int32_t>(std::llround(std::sin((start+frame)*2.0*3.14159265358979323846*1000.0/48000)*amplitude*2147483647.0));
        meter.add(pcm.data(),frames);
    }
    return meter.result();
}
template<class F> static void rejected(F action) {bool failed=false;try {action();}catch(const std::exception&){failed=true;}assert(failed);}
int main() {
    const auto stereo=tone(2,-23);
    assert(std::abs(stereo.lufs+23.0)<0.1 && stereo.frames==480000);
    assert(std::abs(stereo.peak-std::pow(10.0,-23.0/20.0))<1e-8);
    const auto small=tone(2,-23,137);
    assert(std::abs(small.lufs-stereo.lufs)<1e-10 && small.peak==stereo.peak);
    assert(std::abs(tone(1,-23).lufs-stereo.lufs+3.0102999566)<0.11);
    assert(std::abs(tone(2,-17).lufs-stereo.lufs-6.0)<0.11);
    const auto silence=tone(2,-23,4096,true);
    assert(std::isinf(silence.lufs) && silence.lufs<0 && silence.peak==0);
    evanna::LoudnessMeter tiny(44100,1);const int32_t full[]={std::numeric_limits<int32_t>::min(),std::numeric_limits<int32_t>::max()};
    tiny.add(full,2);assert(std::isinf(tiny.result().lufs) && tiny.result().peak==1.0);
    rejected([] {evanna::LoudnessMeter invalid(0,2);});rejected([] {evanna::LoudnessMeter invalid(48000,6);});
    rejected([&] {tiny.add(nullptr,1);});rejected([&] {tiny.add(full,4097);});
    std::thread parallel([] {assert(std::abs(tone(2,-23).lufs+23.0)<0.1);});
    assert(std::abs(tone(2,-17).lufs+17.0)<0.1);parallel.join();
    std::printf("Loudness references, mono/stereo, gain, chunk invariance, silence/short data, peak, bounds and concurrent meters pass. Stereo reference %.3f LUFS.\n",stereo.lufs);
}
