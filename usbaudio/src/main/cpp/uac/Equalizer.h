// Modified for Evanna (2026-11-07): low/high/band-pass filters added in parallel (Poweramp's topology), per-band
// channels and a public design() matching Poweramp's reference vectors.
// Biquad equalizer (RBJ Audio EQ Cookbook filters), processed in double precision.
// Header-only, no platform dependencies, so it can be unit-tested.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace uac {

struct EqBand {
    // Low pass, High pass and Band pass boost or cut a range: their output, scaled by 10^(dB/20) - 1, is added to the dry
    // signal, as in Poweramp's graphic EQ. Peak and shelves then run as a chain on that sum.
    enum class Type : int { Peak = 0, LowShelf = 1, HighShelf = 2, LowPass = 3, HighPass = 4, BandPass = 5 };
    Type type = Type::Peak;
    double frequency = 1000;  // Hz
    double gainDb = 0;
    double q = 1.0;
    int channels = 0;  // 0 both, 1 left (first channel) only, 2 right (second channel) only
};

// Normalised biquad taps (a0 = 1).
struct Biquad {
    double b0, b1, b2, a1, a2;
};

class Equalizer {
public:
    static bool parallel(EqBand::Type t) {
        return t == EqBand::Type::LowPass || t == EqBand::Type::HighPass || t == EqBand::Type::BandPass;
    }

    // RBJ coefficients; pass types leave their gain out (configure scales them). Like Poweramp, a frequency at or above
    // half the rate is clamped to rate/2 - 1. Matches Poweramp's 12 reference vectors within 1e-12.
    static Biquad design(const EqBand& b, double fs) {
        const double nyquist = std::floor(fs / 2);
        const double f = std::fabs(b.frequency) >= nyquist ? nyquist - 1 : std::fabs(b.frequency);
        const double a = std::pow(10.0, b.gainDb / 40.0);
        const double w0 = 2 * M_PI * f / fs;
        const double cw = std::cos(w0);
        const double alpha = std::sin(w0) / (2 * std::max(b.q, 0.05));
        const double sa = 2 * std::sqrt(a) * alpha;
        double b0, b1, b2, a0, a1, a2;
        switch (b.type) {
            case EqBand::Type::LowPass:
                b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2;
                a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha;
                break;
            case EqBand::Type::HighPass:
                b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2;
                a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha;
                break;
            case EqBand::Type::BandPass:  // constant 0 dB peak gain
                b0 = alpha; b1 = 0; b2 = -alpha;
                a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha;
                break;
            case EqBand::Type::LowShelf:
                b0 = a * ((a + 1) - (a - 1) * cw + sa);
                b1 = 2 * a * ((a - 1) - (a + 1) * cw);
                b2 = a * ((a + 1) - (a - 1) * cw - sa);
                a0 = (a + 1) + (a - 1) * cw + sa;
                a1 = -2 * ((a - 1) + (a + 1) * cw);
                a2 = (a + 1) + (a - 1) * cw - sa;
                break;
            case EqBand::Type::HighShelf:
                b0 = a * ((a + 1) + (a - 1) * cw + sa);
                b1 = -2 * a * ((a - 1) + (a + 1) * cw);
                b2 = a * ((a + 1) + (a - 1) * cw - sa);
                a0 = (a + 1) - (a - 1) * cw + sa;
                a1 = 2 * ((a - 1) - (a + 1) * cw);
                a2 = (a + 1) - (a - 1) * cw - sa;
                break;
            case EqBand::Type::Peak:
            default:
                b0 = 1 + alpha * a;
                b1 = -2 * cw;
                b2 = 1 - alpha * a;
                a0 = 1 + alpha / a;
                a1 = -2 * cw;
                a2 = 1 - alpha / a;
                break;
        }
        return {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
    }

    // Replaces the filters. Bands with no gain, or at/above 0.49 x the rate, are skipped.
    void configure(const std::vector<EqBand>& bands, double preampDb, double sampleRate, size_t channels) {
        std::vector<Section> next;
        for (const EqBand& b : bands) {
            if (std::fabs(b.gainDb) < 0.01 || b.frequency <= 0 || b.frequency >= sampleRate * 0.49) continue;
            Section s{design(b, sampleRate), parallel(b.type), b.type, b.channels};
            if (s.parallel) {
                const double d = std::pow(10.0, b.gainDb / 20.0) - 1.0;
                s.k.b0 *= d; s.k.b1 *= d; s.k.b2 *= d;
            }
            next.push_back(s);
        }
        // Pass bands first, then the chain in band order; process() relies on this split.
        parallelCount_ = size_t(std::stable_partition(next.begin(), next.end(), [](const Section& s) { return s.parallel; }) - next.begin());
        // Keep the filter memory while a slider is being dragged (same layout), so changes don't click.
        const bool sameLayout = next.size() == sections_.size() && channels == channels_ && sampleRate == sampleRate_ &&
            std::equal(next.begin(), next.end(), sections_.begin(),
                       [](const Section& x, const Section& y) { return x.type == y.type && x.channels == y.channels; });
        sections_ = std::move(next);
        if (!sameLayout) state_.assign(sections_.size() * channels * 2, 0.0);
        preamp_ = std::pow(10.0, preampDb / 20.0);
        channels_ = channels;
        sampleRate_ = sampleRate;
    }

    void reset() { std::fill(state_.begin(), state_.end(), 0.0); }

    bool active() const { return !sections_.empty() || std::fabs(preamp_ - 1.0) > 1e-9; }

    // In-place on interleaved float samples.
    void process(float* samples, size_t frames) {
        const size_t count = sections_.size();
        for (size_t f = 0; f < frames; ++f) {
            for (size_t c = 0; c < channels_; ++c) {
                const double x = double(samples[f * channels_ + c]) * preamp_;
                double y = x;
                for (size_t s = 0; s < parallelCount_; ++s) {  // each pass band filters the dry input; outputs add up
                    if (sections_[s].applies(c)) y += run(sections_[s].k, &state_[(s * channels_ + c) * 2], x);
                }
                for (size_t s = parallelCount_; s < count; ++s) {
                    if (sections_[s].applies(c)) y = run(sections_[s].k, &state_[(s * channels_ + c) * 2], y);
                }
                samples[f * channels_ + c] = float(y);
            }
        }
    }

    // Combined gain in dB at `frequency` on one channel, for tests (the app draws its own curve).
    double responseDb(double frequency, double sampleRate, size_t channel = 0) const {
        const double w = 2 * M_PI * frequency / sampleRate;
        const double cw = std::cos(w), c2w = std::cos(2 * w), sw = std::sin(w), s2w = std::sin(2 * w);
        double sumRe = 1, sumIm = 0, magnitude = preamp_;
        for (const Section& s : sections_) {
            if (!s.applies(channel)) continue;
            // H(e^jw) from the normalised coefficients
            const double nr = s.k.b0 + s.k.b1 * cw + s.k.b2 * c2w, ni = -(s.k.b1 * sw + s.k.b2 * s2w);
            const double dr = 1 + s.k.a1 * cw + s.k.a2 * c2w, di = -(s.k.a1 * sw + s.k.a2 * s2w);
            const double den = dr * dr + di * di;
            if (s.parallel) {
                sumRe += (nr * dr + ni * di) / den;
                sumIm += (ni * dr - nr * di) / den;
            } else {
                magnitude *= std::sqrt((nr * nr + ni * ni) / den);
            }
        }
        return 20 * std::log10(magnitude * std::hypot(sumRe, sumIm));
    }

private:
    struct Section {
        Biquad k;
        bool parallel;
        EqBand::Type type;
        int channels;
        bool applies(size_t c) const { return channels == 0 || (channels == 1 && c == 0) || (channels == 2 && c == 1); }
    };

    static double run(const Biquad& k, double* z, double x) {
        const double y = k.b0 * x + z[0];  // transposed direct form II
        z[0] = k.b1 * x - k.a1 * y + z[1];
        z[1] = k.b2 * x - k.a2 * y;
        return y;
    }

    std::vector<Section> sections_;
    std::vector<double> state_;  // two delay values per section per channel
    size_t parallelCount_ = 0;
    double preamp_ = 1.0;
    size_t channels_ = 2;
    double sampleRate_ = 0;
};

}  // namespace uac
