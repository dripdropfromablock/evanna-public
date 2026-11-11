// Headphone crossfeed after the Bauer stereophonic-to-binaural (bs2b) design published by Boris Mikhaylov
// (https://bs2b.sourceforge.net/, MIT): a lowpass of each channel is fed to the other ear and a matching
// high-boost shapes the direct signal. Original Evanna implementation; no libbs2b source is included.
// Header-only, double precision, so it can be unit-tested.
#pragma once

#include <cmath>
#include <complex>
#include <cstddef>

namespace uac {

class Crossfeed {
public:
    // bs2b settings: lowpass cut (300-2000 Hz) and feed level (1-15 dB; a higher level crosses less sound).
    // Anything else, or a stream that is not stereo, turns it off. Switching on starts from silence;
    // changing settings while on keeps the filter memory, so there is no step.
    void configure(double cutHz, double feedDb, double sampleRate, size_t channels) {
        const bool on = channels == 2 && cutHz >= 300 && cutHz <= 2000 && feedDb >= 1 && feedDb <= 15 &&
            sampleRate >= 8000 && sampleRate <= 768000;
        if (!on) {
            active_ = false;
            return;
        }
        const double lowDb = feedDb * -5.0 / 6.0 - 3.0;   // crossed level at low frequencies
        const double highDb = feedDb / 6.0 - 3.0;         // direct level at low frequencies (0 dB at highs)
        const double lowGain = std::pow(10.0, lowDb / 20.0);
        const double shelf = 1.0 - std::pow(10.0, highDb / 20.0);
        const double highCut = cutHz * std::pow(2.0, (lowDb - 20.0 * std::log10(shelf)) / 12.0);
        const double x = std::exp(-2 * M_PI * cutHz / sampleRate);
        const double xh = std::exp(-2 * M_PI * highCut / sampleRate);
        loA0_ = lowGain * (1 - x);
        loB1_ = x;
        hiA0_ = 1 - shelf * (1 - xh);
        hiA1_ = -xh;
        hiB1_ = xh;
        gain_ = 1.0 / (1.0 - shelf + lowGain);  // in-phase content keeps unity gain
        sampleRate_ = sampleRate;
        if (!active_) reset();
        active_ = true;
    }

    void reset() { lo_[0] = lo_[1] = hi_[0] = hi_[1] = previous_[0] = previous_[1] = 0; }

    bool active() const { return active_; }

    // In-place on interleaved stereo float samples; does nothing while off.
    void process(float* samples, size_t frames) {
        if (!active_) return;
        for (size_t f = 0; f < frames; ++f) {
            float* s = samples + 2 * f;
            const double in[2] = {s[0], s[1]};
            for (int c = 0; c < 2; ++c) {
                lo_[c] = loA0_ * in[c] + loB1_ * lo_[c];
                hi_[c] = hiA0_ * in[c] + hiA1_ * previous_[c] + hiB1_ * hi_[c];
                previous_[c] = in[c];
            }
            s[0] = float((hi_[0] + lo_[1]) * gain_);
            s[1] = float((hi_[1] + lo_[0]) * gain_);
        }
    }

    // Largest steady sine gain into one ear at `frequency`, both inputs at the worst phase. For tests.
    double sineGain(double frequency) const {
        const std::complex<double> z = std::polar(1.0, -2 * M_PI * frequency / sampleRate_);  // z^-1
        const std::complex<double> lo = loA0_ / (1.0 - loB1_ * z);
        const std::complex<double> hi = (hiA0_ + hiA1_ * z) / (1.0 - hiB1_ * z);
        return gain_ * (std::abs(hi) + std::abs(lo));
    }

private:
    bool active_ = false;
    double loA0_ = 0, loB1_ = 0, hiA0_ = 1, hiA1_ = 0, hiB1_ = 0, gain_ = 1, sampleRate_ = 48000;
    double lo_[2] = {}, hi_[2] = {}, previous_[2] = {};
};

}  // namespace uac
