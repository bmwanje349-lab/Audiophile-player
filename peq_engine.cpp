#include "peq_engine.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <array>
#include <cstring>
#include <limits>

namespace peq {
namespace {

constexpr double kEpsilon = 1e-12;
constexpr double kMinFreq = 10.0;
constexpr double kMaxQ = 20.0;
constexpr double kMinQ = 0.05;
constexpr float kMinGainDb = -36.0f;
constexpr float kMaxGainDb = 36.0f;
constexpr float kMinLimiterCeilingDb = -24.0f;
constexpr float kMaxLimiterCeilingDb = -0.01f;
constexpr float kMinSafetyDb = 0.0f;
constexpr float kMaxSafetyDb = 2.0f;
constexpr float kMaxLookaheadMs = 42.0f;
constexpr float kGraphicFrequenciesHz[kGraphicBands] = {
    31.0f, 62.0f, 125.0f, 250.0f, 500.0f,
    1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f
};
constexpr float kGraphicQ = 1.40f;

// ITU-R BS.1770 Annex 2 style 4x oversampling interpolator, 12 taps per phase.
// The four phases are the fractional-delay branches. The coefficient values are
// given by the recommendation; they sum close to unity in the passband and are
// suitable for true-peak estimation at 4x the original sample rate.
constexpr float kTpFir[4][kTruePeakHistory] = {
    {
        0.001708984375f,  0.010986328125f, -0.0196533203125f,  0.033203125f,
       -0.0594482421875f, 0.1373291015625f,  0.97216796875f, -0.102294921875f,
        0.047607421875f, -0.026611328125f,  0.014892578125f, -0.00830078125f
    },
    {
       -0.0291748046875f,  0.029296875f, -0.0517578125f,  0.089111328125f,
       -0.16650390625f,  0.465087890625f,  0.77978515625f, -0.2003173828125f,
        0.1015625f, -0.0582275390625f, 0.0330810546875f, -0.0189208984375f
    },
    {
       -0.0189208984375f,  0.0330810546875f, -0.0582275390625f, 0.1015625f,
       -0.2003173828125f,  0.77978515625f, 0.465087890625f, -0.16650390625f,
        0.089111328125f, -0.0517578125f, 0.029296875f, -0.0291748046875f
    },
    {
       -0.00830078125f,  0.014892578125f, -0.026611328125f, 0.047607421875f,
       -0.102294921875f, 0.97216796875f, 0.1373291015625f, -0.0594482421875f,
        0.033203125f, -0.0196533203125f, 0.010986328125f, 0.001708984375f
    }
};

inline bool finite(double v) {
    return std::isfinite(v);
}

inline float clampFinite(float v, float lo, float hi, float fallback) {
    if (!std::isfinite(v)) return fallback;
    return std::min(std::max(v, lo), hi);
}

inline float finiteSample(float v) {
    return std::isfinite(v) ? v : 0.0f;
}

 } // namespace

void Engine::fft(std::complex<double>* data, int n, bool inverse) {
    if (!data || n <= 1) return;

    // Bit-reversal permutation.
    for (int i = 1, j = 0; i < n; ++i) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(data[i], data[j]);
    }

    for (int len = 2; len <= n; len <<= 1) {
        const double angle = (inverse ? 2.0 : -2.0) * kPi / static_cast<double>(len);
        const std::complex<double> wlen(std::cos(angle), std::sin(angle));
        for (int i = 0; i < n; i += len) {
            std::complex<double> w(1.0, 0.0);
            const int half = len >> 1;
            for (int j = 0; j < half; ++j) {
                const std::complex<double> u = data[i + j];
                const std::complex<double> v = data[i + j + half] * w;
                data[i + j] = u + v;
                data[i + j + half] = u - v;
                w *= wlen;
            }
        }
    }

    if (inverse) {
        const double invN = 1.0 / static_cast<double>(n);
        for (int i = 0; i < n; ++i) data[i] *= invN;
    }
}

double Engine::responseDbFromCoeffs(
        const Coeffs* coeffs, int numBands, double freq, double sr) {
    if (!coeffs || numBands <= 0 || sr <= 0.0) return 0.0;
    const double f = std::clamp(freq, 0.0, sr * 0.5);
    const double w = 2.0 * kPi * f / sr;
    const std::complex<double> z1 = std::polar(1.0, -w);
    const std::complex<double> z2 = z1 * z1;
    double db = 0.0;
    for (int b = 0; b < numBands; ++b) {
        const Coeffs& c = coeffs[b];
        if (!c.active) continue;
        const std::complex<double> num = c.b0 + c.b1 * z1 + c.b2 * z2;
        const std::complex<double> den = 1.0 + c.a1 * z1 + c.a2 * z2;
        const double denMag = std::max(std::abs(den), kEpsilon);
        const double mag = std::abs(num) / denMag;
        db += 20.0 * std::log10(std::max(mag, 1e-12));
    }
    return db;
}

int Engine::normalizeLinearPhaseTaps(int taps) {
    if (taps <= kMinLinearPhaseTaps) return kMinLinearPhaseTaps;
    if (taps >= kMaxLinearPhaseTaps) return kMaxLinearPhaseTaps;
    taps = (taps / 2) * 2 + 1; // odd length => integer group delay.
    if (taps < kMinLinearPhaseTaps) taps = kMinLinearPhaseTaps;
    if (taps > kMaxLinearPhaseTaps) taps = kMaxLinearPhaseTaps;
    return taps;
}

void Engine::rebuildAutoGainLocked() {
    const double sr = sampleRate_.load(std::memory_order_relaxed);
    const double maxF = std::min(20000.0, sr * 0.49);
    if (!(maxF > 20.0)) {
        autoMakeupGainDb_.store(0.0f, std::memory_order_relaxed);
        return;
    }

    // The compensation curve must describe the same effective EQ chain that
    // the audio thread is actually using. The Graphic EQ and PEQ are separate
    // banks, so their responses are multiplied in the linear domain (added in
    // dB) only when their respective stages are enabled.
    const bool peqOn = peqEnabled_.load(std::memory_order_relaxed);
    const bool graphicOn = graphicEnabled_.load(std::memory_order_relaxed);

    if (!peqOn && !graphicOn) {
        autoMakeupGainDb_.store(0.0f, std::memory_order_relaxed);
        return;
    }

    // Log-frequency RMS compensation: equal weight per octave, using the full
    // active EQ response rather than program content. This is deliberately
    // stable and deterministic; it is not BS.1770 loudness normalization.
    constexpr int kBins = 192;
    double meanPower = 0.0;
    for (int i = 0; i < kBins; ++i) {
        const double t = (static_cast<double>(i) + 0.5) / static_cast<double>(kBins);
        const double f = 20.0 * std::pow(maxF / 20.0, t);

        double db = 0.0;
        if (peqOn) {
            db += responseDbFromCoeffs(pending_, numBands_, f, sr);
        }
        if (graphicOn) {
            db += responseDbFromCoeffs(graphicPending_, kGraphicBands, f, sr);
        }

        // Convert the combined response to power.
        const double g = std::pow(10.0, db / 20.0);
        meanPower += g * g;
    }

    meanPower /= static_cast<double>(kBins);
    double makeup = meanPower > 1e-18 ? -10.0 * std::log10(meanPower) : 0.0;
    makeup = std::clamp(makeup, -12.0, 12.0);
    autoMakeupGainDb_.store(static_cast<float>(makeup), std::memory_order_relaxed);
}

void Engine::rebuildLinearFirLocked() {
    const double sr = sampleRate_.load(std::memory_order_relaxed);
    const int taps = normalizeLinearPhaseTaps(linearPhaseTaps_.load(std::memory_order_relaxed));
    const int delay = (taps - 1) / 2;
    linearPhaseDelayFrames_.store(delay, std::memory_order_relaxed);

    std::array<std::complex<double>, kLinearPhaseFftSize> spectrum{};
    const int nyquist = kLinearPhaseFftSize / 2;

    for (int k = 0; k <= nyquist; ++k) {
        const double f = sr * static_cast<double>(k) / static_cast<double>(kLinearPhaseFftSize);
        const double db = responseDbFromCoeffs(pending_, numBands_, f, sr);
        const double magnitude = std::clamp(std::pow(10.0, db / 20.0), 1e-6, 16.0);
        const double phase = -2.0 * kPi * static_cast<double>(k * delay) /
                             static_cast<double>(kLinearPhaseFftSize);
        spectrum[k] = std::polar(magnitude, phase);
        if (k > 0 && k < nyquist) spectrum[kLinearPhaseFftSize - k] = std::conj(spectrum[k]);
    }

    fft(spectrum.data(), kLinearPhaseFftSize, true);

    for (int n = 0; n < taps; ++n) {
        pendingFir_[n] = static_cast<float>(spectrum[n].real());
    }
    for (int n = 0; n < taps / 2; ++n) {
        const float v = 0.5f * (pendingFir_[n] + pendingFir_[taps - 1 - n]);
        pendingFir_[n] = v;
        pendingFir_[taps - 1 - n] = v;
    }
    for (int n = taps; n < kMaxLinearPhaseTaps; ++n) pendingFir_[n] = 0.0f;

    // Correct the small frequency-sampling scale error at 1 kHz whenever that
    // reference point is not near a deep notch/null.
    const double refF = std::clamp(1000.0, 20.0, sr * 0.49);
    const double targetMag = std::pow(
        10.0,
        responseDbFromCoeffs(pending_, numBands_, refF, sr) / 20.0);
    if (targetMag > 1e-4) {
        double wr = 0.0, wi = 0.0;
        const double w = 2.0 * kPi * refF / sr;
        for (int n = 0; n < taps; ++n) {
            const double a = -w * static_cast<double>(n);
            wr += static_cast<double>(pendingFir_[n]) * std::cos(a);
            wi += static_cast<double>(pendingFir_[n]) * std::sin(a);
        }
        const double actual = std::sqrt(wr * wr + wi * wi);
        if (actual > 1e-6) {
            const float scale = static_cast<float>(std::clamp(targetMag / actual, 0.25, 4.0));
            for (int n = 0; n < taps; ++n) pendingFir_[n] *= scale;
        }
    }

    firDirty_.store(true, std::memory_order_release);
}

float Engine::dbToLin(float db) {
    db = clampFinite(db, -60.0f, 60.0f, 0.0f);
    return std::pow(10.0f, db / 20.0f);
}

double Engine::safeExp(double x) {
    if (x < -745.0) return 0.0;
    if (x > 709.0) return std::numeric_limits<double>::infinity();
    return std::exp(x);
}

double Engine::smoothingCoeffMs(double ms, double sr) {
    ms = std::max(0.0, std::isfinite(ms) ? static_cast<double>(ms) : 0.0);
    if (ms <= 0.0 || sr <= 0.0) return 1.0;
    const double tauSamples = (ms * 0.001) * sr;
    return 1.0 - safeExp(-1.0 / std::max(tauSamples, 1e-9));
}

Coeffs Engine::identityCoeffs() {
    return Coeffs{};
}

bool Engine::effectivelyIdentity(const Coeffs& c) {
    return std::fabs(c.b0 - 1.0) < 1e-8 &&
           std::fabs(c.b1) < 1e-8 &&
           std::fabs(c.b2) < 1e-8 &&
           std::fabs(c.a1) < 1e-8 &&
           std::fabs(c.a2) < 1e-8;
}

Coeffs Engine::computeCoeffs(const BandParams& p, double sr) {
    Coeffs c = identityCoeffs();
    if (!p.enabled || sr <= 0.0) return c;

    int type = p.type;
    if (type < kPeaking || type > kNotch) type = kPeaking;

    const bool hasGain = (type == kPeaking || type == kLowShelf || type == kHighShelf);
    const float gainDb = clampFinite(p.gainDb, kMinGainDb, kMaxGainDb, 0.0f);
    if (hasGain && std::fabs(gainDb) < 0.0005f) return c;

    const double maxFreq = std::max(kMinFreq, sr * 0.49);
    const double f = std::clamp(
        std::isfinite(static_cast<double>(p.freq)) ? static_cast<double>(p.freq) : 1000.0,
        kMinFreq,
        maxFreq);

    const double w0 = 2.0 * kPi * f / sr;
    const double cw = std::cos(w0);
    const double sw = std::sin(w0);
    const double A = std::pow(10.0, static_cast<double>(gainDb) / 40.0);

    double b0 = 1.0, b1 = 0.0, b2 = 0.0;
    double a0 = 1.0, a1 = 0.0, a2 = 0.0;

    if (type == kLowShelf || type == kHighShelf) {
        // For shelf filters, q is interpreted as shelf slope S.
        const double S = std::clamp(
            std::isfinite(static_cast<double>(p.q)) ? static_cast<double>(p.q) : 0.707,
            0.1,
            2.0);
        const double alpha = (sw * 0.5) *
            std::sqrt(std::max(0.0, (A + 1.0 / A) * (1.0 / S - 1.0) + 2.0));
        const double beta = 2.0 * std::sqrt(A) * alpha;

        if (type == kLowShelf) {
            b0 = A * ((A + 1.0) - (A - 1.0) * cw + beta);
            b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cw);
            b2 = A * ((A + 1.0) - (A - 1.0) * cw - beta);
            a0 = (A + 1.0) + (A - 1.0) * cw + beta;
            a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cw);
            a2 = (A + 1.0) + (A - 1.0) * cw - beta;
        } else {
            b0 = A * ((A + 1.0) + (A - 1.0) * cw + beta);
            b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cw);
            b2 = A * ((A + 1.0) + (A - 1.0) * cw - beta);
            a0 = (A + 1.0) - (A - 1.0) * cw + beta;
            a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cw);
            a2 = (A + 1.0) - (A - 1.0) * cw - beta;
        }
    } else {
        const double q = std::clamp(
            std::isfinite(static_cast<double>(p.q)) ? static_cast<double>(p.q) : 1.0,
            kMinQ,
            kMaxQ);
        const double alpha = sw / (2.0 * q);

        switch (type) {
            case kHighPass:
                b0 = (1.0 + cw) * 0.5;
                b1 = -(1.0 + cw);
                b2 = (1.0 + cw) * 0.5;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cw;
                a2 = 1.0 - alpha;
                break;
            case kLowPass:
                b0 = (1.0 - cw) * 0.5;
                b1 = 1.0 - cw;
                b2 = (1.0 - cw) * 0.5;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cw;
                a2 = 1.0 - alpha;
                break;
            case kNotch:
                b0 = 1.0;
                b1 = -2.0 * cw;
                b2 = 1.0;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cw;
                a2 = 1.0 - alpha;
                break;
            case kPeaking:
            default:
                b0 = 1.0 + alpha * A;
                b1 = -2.0 * cw;
                b2 = 1.0 - alpha * A;
                a0 = 1.0 + alpha / A;
                a1 = -2.0 * cw;
                a2 = 1.0 - alpha / A;
                break;
        }
    }

    if (!finite(a0) || std::fabs(a0) < kEpsilon ||
        !finite(b0) || !finite(b1) || !finite(b2) || !finite(a1) || !finite(a2)) {
        return c;
    }

    c.b0 = b0 / a0;
    c.b1 = b1 / a0;
    c.b2 = b2 / a0;
    c.a1 = a1 / a0;
    c.a2 = a2 / a0;

    // Strict second-order Schur stability region with a tiny positive margin.
    constexpr double margin = 1e-10;
    const bool stable = finite(c.a1) && finite(c.a2) &&
                        (1.0 + c.a1 + c.a2 > margin) &&
                        (1.0 - c.a1 + c.a2 > margin) &&
                        (1.0 - c.a2 > margin);
    if (!stable) return identityCoeffs();

    c.active = !effectivelyIdentity(c);
    return c;
}

Engine::Engine(int numBands)
    : numBands_(std::min(std::max(numBands, 1), kMaxBands)) {
    for (int i = 0; i < kMaxBands; ++i) {
        params_[i] = BandParams{};
        pending_[i] = identityCoeffs();
        target_[i] = identityCoeffs();
        smoothed_[i] = identityCoeffs();
    }
    for (int i = 0; i < kGraphicBands; ++i) {
        graphicParams_[i] = BandParams{};
        graphicParams_[i].type = kPeaking;
        graphicParams_[i].freq = kGraphicFrequenciesHz[i];
        graphicParams_[i].q = kGraphicQ;
        graphicParams_[i].enabled = false;
        graphicPending_[i] = identityCoeffs();
        graphicTarget_[i] = identityCoeffs();
        graphicSmoothed_[i] = identityCoeffs();
    }
    activeFir_[0] = 1.0f;
    setSampleRate(48000);
    limiterLookaheadFrames_ = 96;
    limiterTotalDelayFrames_.store(kTruePeakDelayFrames + limiterLookaheadFrames_, std::memory_order_release);
    limiterRingSize_ = limiterTotalDelayFrames_.load(std::memory_order_relaxed) + 1;
    lastConfiguredLookaheadFrames_ = -1;
}

void Engine::setSampleRate(int sr) {
    if (sr <= 0) return;
    std::lock_guard<std::mutex> lk(mutex_);
    const double newSr = static_cast<double>(sr);
    sampleRate_.store(newSr, std::memory_order_relaxed);

    for (int i = 0; i < numBands_; ++i) {
        pending_[i] = computeCoeffs(params_[i], newSr);
    }
    for (int i = 0; i < kGraphicBands; ++i) {
        graphicParams_[i].freq = kGraphicFrequenciesHz[i];
        graphicParams_[i].q = kGraphicQ;
        graphicPending_[i] = computeCoeffs(graphicParams_[i], newSr);
    }
    rebuildAutoGainLocked();
    if (linearPhaseEnabled_.load(std::memory_order_relaxed)) rebuildLinearFirLocked();

    const double gainCoeff = smoothingCoeffMs(
        gainSmoothingMs_.load(std::memory_order_relaxed), newSr);
    inputGainCoeff_ = static_cast<float>(gainCoeff);
    outputGainCoeff_ = static_cast<float>(gainCoeff);
    limiterReleaseCoeff_ = static_cast<float>(smoothingCoeffMs(
        limiterReleaseMs_.load(std::memory_order_relaxed), newSr));

    dirty_.store(true, std::memory_order_release);
    resetRequested_.store(true, std::memory_order_release);
}

void Engine::setBand(int index, const BandParams& p) {
    if (index < 0 || index >= numBands_) return;

    std::lock_guard<std::mutex> lk(mutex_);
    const double sr = sampleRate_.load(std::memory_order_relaxed);

    BandParams clean = p;
    clean.freq = clampFinite(clean.freq, 10.0f,
                             static_cast<float>(sr * 0.49), 1000.0f);
    clean.gainDb = clampFinite(clean.gainDb, kMinGainDb, kMaxGainDb, 0.0f);
    clean.q = clampFinite(clean.q, 0.05f, 20.0f, 1.0f);
    if (clean.type < kPeaking || clean.type > kNotch) clean.type = kPeaking;

    params_[index] = clean;
    pending_[index] = computeCoeffs(clean, sr);
    rebuildAutoGainLocked();
    if (linearPhaseEnabled_.load(std::memory_order_relaxed)) rebuildLinearFirLocked();
    dirty_.store(true, std::memory_order_release);
}

void Engine::setPeqEnabled(bool on) {
    std::lock_guard<std::mutex> lk(mutex_);
    peqEnabled_.store(on, std::memory_order_relaxed);
    rebuildAutoGainLocked();
}

void Engine::setGraphicEqEnabled(bool on) {
    std::lock_guard<std::mutex> lk(mutex_);
    graphicEnabled_.store(on, std::memory_order_relaxed);
    rebuildAutoGainLocked();
}

void Engine::setGraphicBand(int index, float gainDb, float q, bool enabled) {
    if (index < 0 || index >= kGraphicBands) return;
    std::lock_guard<std::mutex> lk(mutex_);
    const double sr = sampleRate_.load(std::memory_order_relaxed);
    BandParams p;
    p.type = kPeaking;
    p.freq = kGraphicFrequenciesHz[index];
    p.gainDb = clampFinite(gainDb, -12.0f, 12.0f, 0.0f);
    p.q = clampFinite(q, 0.25f, 4.0f, kGraphicQ);
    p.enabled = enabled && std::fabs(p.gainDb) > 0.0001f;
    graphicParams_[index] = p;
    graphicPending_[index] = computeCoeffs(p, sr);
    rebuildAutoGainLocked();
    dirty_.store(true, std::memory_order_release);
}

void Engine::setGraphicBands(const float* gainsDb, int count, float q) {
    if (!gainsDb || count <= 0) return;

    std::lock_guard<std::mutex> lk(mutex_);
    const double sr = sampleRate_.load(std::memory_order_relaxed);
    const float cleanQ = clampFinite(q, 0.25f, 4.0f, kGraphicQ);
    const int n = std::min(count, kGraphicBands);

    for (int i = 0; i < kGraphicBands; ++i) {
        BandParams p;
        p.type = kPeaking;
        p.freq = kGraphicFrequenciesHz[i];
        const float requested = (i < n) ? gainsDb[i] : 0.0f;
        p.gainDb = clampFinite(requested, -12.0f, 12.0f, 0.0f);
        p.q = cleanQ;
        p.enabled = std::fabs(p.gainDb) > 0.0001f;
        graphicParams_[i] = p;
        graphicPending_[i] = computeCoeffs(p, sr);
    }

    rebuildAutoGainLocked();
    dirty_.store(true, std::memory_order_release);
}

void Engine::setInputGainDb(float db) {
    inputGainDb_.store(clampFinite(db, -24.0f, 24.0f, 0.0f), std::memory_order_relaxed);
}

void Engine::setOutputGainDb(float db) {
    outputGainDb_.store(clampFinite(db, -24.0f, 24.0f, 0.0f), std::memory_order_relaxed);
}

void Engine::setBypass(bool on) {
    bypass_.store(on, std::memory_order_relaxed);
}

void Engine::setLimiterEnabled(bool on) {
    limiterEnabled_.store(on, std::memory_order_relaxed);
}

void Engine::setLimiterCeilingDb(float db) {
    limiterCeilingDb_.store(
        clampFinite(db, kMinLimiterCeilingDb, kMaxLimiterCeilingDb,
                    kDefaultCeilingDb),
        std::memory_order_relaxed);
}

void Engine::setLimiterSafetyDb(float db) {
    limiterSafetyDb_.store(
        clampFinite(db, kMinSafetyDb, kMaxSafetyDb, kDefaultSafetyDb),
        std::memory_order_relaxed);
}

void Engine::setLimiterLookaheadMs(float ms) {
    limiterLookaheadMs_.store(
        clampFinite(ms, 0.0f, kMaxLookaheadMs, 2.0f),
        std::memory_order_relaxed);
}

void Engine::setLimiterReleaseMs(float ms) {
    limiterReleaseMs_.store(
        clampFinite(ms, 10.0f, 2000.0f, 180.0f),
        std::memory_order_relaxed);
}

void Engine::setFilterSmoothingMs(float ms) {
    filterSmoothingMs_.store(
        clampFinite(ms, 0.0f, 250.0f, 8.0f),
        std::memory_order_relaxed);
}

void Engine::setGainSmoothingMs(float ms) {
    gainSmoothingMs_.store(
        clampFinite(ms, 0.0f, 250.0f, 5.0f),
        std::memory_order_relaxed);
}

void Engine::setAutoGainEnabled(bool on) {
    autoGainEnabled_.store(on, std::memory_order_relaxed);
}

void Engine::setAutoGainAmount(float amount) {
    autoGainAmount_.store(clampFinite(amount, 0.0f, 1.0f, 1.0f), std::memory_order_relaxed);
}

void Engine::setLinearPhaseEnabled(bool on) {
    {
        std::lock_guard<std::mutex> lk(mutex_);
        linearPhaseEnabled_.store(on, std::memory_order_relaxed);
        if (on) {
            rebuildLinearFirLocked();
        }
    }
    firHardSyncRequested_.store(true, std::memory_order_release);
    resetRequested_.store(true, std::memory_order_release);
    dirty_.store(true, std::memory_order_release);
}

void Engine::setLinearPhaseTaps(int taps) {
    const int clean = normalizeLinearPhaseTaps(taps);
    {
        std::lock_guard<std::mutex> lk(mutex_);
        linearPhaseTaps_.store(clean, std::memory_order_relaxed);
        if (linearPhaseEnabled_.load(std::memory_order_relaxed)) {
            rebuildLinearFirLocked();
        }
    }
    linearPhaseDelayFrames_.store((clean - 1) / 2, std::memory_order_relaxed);
    firHardSyncRequested_.store(true, std::memory_order_release);
    resetRequested_.store(true, std::memory_order_release);
    firDirty_.store(true, std::memory_order_release);
}

void Engine::reset() {
    resetRequested_.store(true, std::memory_order_release);
}

void Engine::responseDb(const float* freqs, float* outDb, int n) const {
    if (!freqs || !outDb || n <= 0) return;
    std::lock_guard<std::mutex> lk(mutex_);

    const double sr = sampleRate_.load(std::memory_order_relaxed);
    for (int i = 0; i < n; ++i) {
        const double f = std::clamp(
            std::isfinite(static_cast<double>(freqs[i])) ? static_cast<double>(freqs[i]) : 1000.0,
            0.01,
            sr * 0.499);
        const double w = 2.0 * kPi * f / sr;
        const std::complex<double> z1 = std::polar(1.0, -w);
        const std::complex<double> z2 = z1 * z1;

        double db = 0.0;
        if (peqEnabled_.load(std::memory_order_relaxed)) {
            for (int b = 0; b < numBands_; ++b) {
                const Coeffs& c = pending_[b];
                if (!c.active) continue;

                const std::complex<double> num = c.b0 + c.b1 * z1 + c.b2 * z2;
                const std::complex<double> den = 1.0 + c.a1 * z1 + c.a2 * z2;
                const double mag = std::abs(num / (den + std::complex<double>(kEpsilon, 0.0)));
                db += 20.0 * std::log10(std::max(mag, 1e-12));
            }
        }
        if (graphicEnabled_.load(std::memory_order_relaxed)) {
            for (int b = 0; b < kGraphicBands; ++b) {
                const Coeffs& c = graphicPending_[b];
                if (!c.active) continue;
                const std::complex<double> num = c.b0 + c.b1 * z1 + c.b2 * z2;
                const std::complex<double> den = 1.0 + c.a1 * z1 + c.a2 * z2;
                const double mag = std::abs(num / (den + std::complex<double>(kEpsilon, 0.0)));
                db += 20.0 * std::log10(std::max(mag, 1e-12));
            }
        }
        outDb[i] = static_cast<float>(db);
    }
}

void Engine::syncParams() {
    const bool needParams = dirty_.load(std::memory_order_acquire);
    const bool needFir = firDirty_.load(std::memory_order_acquire);
    if (!needParams && !needFir) return;

    std::unique_lock<std::mutex> lk(mutex_, std::try_to_lock);
    if (!lk.owns_lock()) return;

    if (needParams) {
        for (int i = 0; i < numBands_; ++i) target_[i] = pending_[i];
        for (int i = 0; i < kGraphicBands; ++i) graphicTarget_[i] = graphicPending_[i];
        dirty_.store(false, std::memory_order_release);
    }

    if (needFir && linearPhaseEnabled_.load(std::memory_order_relaxed)) {
        const int taps = normalizeLinearPhaseTaps(linearPhaseTaps_.load(std::memory_order_relaxed));
        const double sr = sampleRate_.load(std::memory_order_relaxed);
        const int transition = std::max(1, static_cast<int>(std::lround(
            filterSmoothingMs_.load(std::memory_order_relaxed) * 0.001 * sr)));

        const bool tapCountChanged = taps != linearFirTapsActive_;
        const bool hardSync = firHardSyncRequested_.exchange(false, std::memory_order_acq_rel);
        if (tapCountChanged) {
            std::fill(activeFir_, activeFir_ + kMaxLinearPhaseTaps, 0.0f);
            activeFir_[0] = 1.0f;
            linearFirTapsActive_ = taps;
            clearLinearFirState();
        }
        for (int i = 0; i < kMaxLinearPhaseTaps; ++i) targetFir_[i] = pendingFir_[i];
        if (hardSync || tapCountChanged) {
            for (int i = 0; i < kMaxLinearPhaseTaps; ++i) activeFir_[i] = targetFir_[i];
            firTransitionTotal_ = 0;
            firTransitionRemaining_ = 0;
            clearLinearFirState();
        } else {
            firTransitionTotal_ = transition;
            firTransitionRemaining_ = transition;
        }
        firDirty_.store(false, std::memory_order_release);
    } else if (needFir) {
        firDirty_.store(false, std::memory_order_release);
    }
}

void Engine::resetLimiter() {
    std::memset(limiterDelay_, 0, sizeof(limiterDelay_));
    std::memset(truePeakHistory_, 0, sizeof(truePeakHistory_));
    std::memset(limiterPeakDeque_, 0, sizeof(limiterPeakDeque_));
    std::memset(limiterIndexDeque_, 0, sizeof(limiterIndexDeque_));

    limiterDequeHead_ = 0;
    limiterDequeTail_ = 0;
    limiterWritePos_ = 0;
    limiterReadPos_ = 0;
    limiterPendingFrames_.store(0, std::memory_order_relaxed);
    limiterSampleIndex_ = 0;
    limiterGain_ = 1.0f;
}

void Engine::clearLinearFirState() {
    std::memset(linearFirState_, 0, sizeof(linearFirState_));
    linearFirWritePos_ = 0;
}

void Engine::clearStates() {
    std::fill(&z1_[0][0], &z1_[0][0] + kMaxBands * kMaxChannels, 0.0);
    std::fill(&z2_[0][0], &z2_[0][0] + kMaxBands * kMaxChannels, 0.0);
    std::fill(&graphicZ1_[0][0], &graphicZ1_[0][0] + kGraphicBands * kMaxChannels, 0.0);
    std::fill(&graphicZ2_[0][0], &graphicZ2_[0][0] + kGraphicBands * kMaxChannels, 0.0);

    inputGain_ = dbToLin(inputGainDb_.load(std::memory_order_relaxed));
    outputGain_ = dbToLin(outputGainDb_.load(std::memory_order_relaxed));
    peqWet_ = peqEnabled_.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    graphicWet_ = graphicEnabled_.load(std::memory_order_relaxed) ? 1.0f : 0.0f;

    resetLimiter();
    clearLinearFirState();
}

void Engine::configureLimiterIfNeeded() {
    const double sr = sampleRate_.load(std::memory_order_relaxed);
    const float ms = limiterLookaheadMs_.load(std::memory_order_relaxed);
    const int requested = static_cast<int>(std::lround(
        std::max(0.0f, std::min(ms, kMaxLookaheadMs)) * 0.001f * static_cast<float>(sr)));
    const int clamped = std::min(std::max(requested, 0), kMaxLookaheadFrames);

    limiterReleaseCoeff_ = static_cast<float>(smoothingCoeffMs(
        limiterReleaseMs_.load(std::memory_order_relaxed), sr));

    if (clamped != lastConfiguredLookaheadFrames_) {
        limiterLookaheadFrames_ = clamped;
        limiterTotalDelayFrames_.store(kTruePeakDelayFrames + limiterLookaheadFrames_, std::memory_order_release);
        limiterRingSize_ = limiterTotalDelayFrames_.load(std::memory_order_relaxed) + 1;
        limiterRingSize_ = std::min(limiterRingSize_, kMaxLimiterRingFrames);
        limiterTotalDelayFrames_.store(limiterRingSize_ - 1, std::memory_order_release);
        lastConfiguredLookaheadFrames_ = clamped;
        resetLimiter();
    }
}

float Engine::truePeakFrame(const float* frame, int channels, bool feedZero) {
    float linkedPeak = 0.0f;

    for (int ch = 0; ch < channels; ++ch) {
        float* h = truePeakHistory_[ch];
        for (int i = 0; i < kTruePeakHistory - 1; ++i) h[i] = h[i + 1];
        h[kTruePeakHistory - 1] = feedZero ? 0.0f : finiteSample(frame[ch]);

        float peak = std::fabs(h[5]);
        for (int phase = 0; phase < 4; ++phase) {
            double sum = 0.0;
            for (int i = 0; i < kTruePeakHistory; ++i) {
                sum += static_cast<double>(h[i]) * static_cast<double>(kTpFir[phase][i]);
            }
            peak = std::max(peak, static_cast<float>(std::fabs(sum)));
        }
        linkedPeak = std::max(linkedPeak, peak);
    }

    return linkedPeak;
}

void Engine::updateLimiterGain(float windowPeak) {
    const bool enabled = limiterEnabled_.load(std::memory_order_relaxed);
    if (!enabled) {
        limiterGain_ = 1.0f;
        return;
    }

    const float userCeilingDb = limiterCeilingDb_.load(std::memory_order_relaxed);
    const float safetyDb = limiterSafetyDb_.load(std::memory_order_relaxed);
    const float effectiveCeiling = dbToLin(userCeilingDb - safetyDb);

    const float desired = (windowPeak > effectiveCeiling && windowPeak > 1e-12f)
        ? effectiveCeiling / windowPeak
        : 1.0f;

    if (desired < limiterGain_) {
        // Pre-attenuation is instantaneous; lookahead provides the time needed
        // to apply it before the protected sample reaches the output.
        limiterGain_ = std::max(0.0f, std::min(desired, 1.0f));
    } else {
        // Release is one-pole and can only move gain upward; therefore it never
        // becomes less attenuated than the current protection requirement.
        limiterGain_ += (1.0f - limiterGain_) * limiterReleaseCoeff_;
        limiterGain_ = std::min(std::max(limiterGain_, 0.0f), 1.0f);
    }
}

void Engine::advanceLimiterDetectorWithZero(int channels) {
    channels = std::clamp(channels, 1, kMaxChannels);
    const uint64_t idx = limiterSampleIndex_++;
    if (idx < static_cast<uint64_t>(kTruePeakDelayFrames)) return;

    const float tp = truePeakFrame(nullptr, channels, true);
    const uint64_t center = idx - static_cast<uint64_t>(kTruePeakDelayFrames);
    const uint64_t firstValid = center >= static_cast<uint64_t>(limiterLookaheadFrames_)
        ? center - static_cast<uint64_t>(limiterLookaheadFrames_)
        : 0;

    while (limiterDequeHead_ < limiterDequeTail_ &&
           limiterIndexDeque_[limiterDequeHead_] < firstValid) {
        ++limiterDequeHead_;
    }

    while (limiterDequeHead_ < limiterDequeTail_ &&
           limiterPeakDeque_[limiterDequeTail_ - 1] <= tp) {
        --limiterDequeTail_;
    }

    limiterPeakDeque_[limiterDequeTail_] = tp;
    limiterIndexDeque_[limiterDequeTail_] = center;
    ++limiterDequeTail_;

    updateLimiterGain(limiterPeakDeque_[limiterDequeHead_]);

    if (limiterDequeHead_ > 0 && limiterDequeHead_ >= limiterDequeTail_ / 2) {
        const int count = limiterDequeTail_ - limiterDequeHead_;
        for (int i = 0; i < count; ++i) {
            limiterPeakDeque_[i] = limiterPeakDeque_[limiterDequeHead_ + i];
            limiterIndexDeque_[i] = limiterIndexDeque_[limiterDequeHead_ + i];
        }
        limiterDequeHead_ = 0;
        limiterDequeTail_ = count;
    }
}

bool Engine::processLimiterFrame(const float* in, float* out, int channels) {
    const bool enabled = limiterEnabled_.load(std::memory_order_relaxed);

    // Store the fully EQ'd frame before advancing the detector. The output ring
    // always holds real audio, never the detector's zero padding.
    const int writePos = limiterWritePos_;
    float* delayed = limiterDelay_[writePos];
    for (int ch = 0; ch < channels; ++ch) delayed[ch] = finiteSample(in[ch]);
    for (int ch = channels; ch < kMaxChannels; ++ch) delayed[ch] = 0.0f;

    if (enabled) {
        const uint64_t idx = limiterSampleIndex_++;
        if (idx >= static_cast<uint64_t>(kTruePeakDelayFrames)) {
            const float tp = truePeakFrame(in, channels, false);
            const uint64_t center = idx - static_cast<uint64_t>(kTruePeakDelayFrames);
            const uint64_t firstValid = center >= static_cast<uint64_t>(limiterLookaheadFrames_)
                ? center - static_cast<uint64_t>(limiterLookaheadFrames_)
                : 0;

            while (limiterDequeHead_ < limiterDequeTail_ &&
                   limiterIndexDeque_[limiterDequeHead_] < firstValid) {
                ++limiterDequeHead_;
            }

            while (limiterDequeHead_ < limiterDequeTail_ &&
                   limiterPeakDeque_[limiterDequeTail_ - 1] <= tp) {
                --limiterDequeTail_;
            }

            limiterPeakDeque_[limiterDequeTail_] = tp;
            limiterIndexDeque_[limiterDequeTail_] = center;
            ++limiterDequeTail_;

            updateLimiterGain(limiterPeakDeque_[limiterDequeHead_]);

            if (limiterDequeHead_ > 0 && limiterDequeHead_ >= limiterDequeTail_ / 2) {
                const int count = limiterDequeTail_ - limiterDequeHead_;
                for (int i = 0; i < count; ++i) {
                    limiterPeakDeque_[i] = limiterPeakDeque_[limiterDequeHead_ + i];
                    limiterIndexDeque_[i] = limiterIndexDeque_[limiterDequeHead_ + i];
                }
                limiterDequeHead_ = 0;
                limiterDequeTail_ = count;
            }
        } else {
            // Still advance the FIR history for stream alignment.
            (void)truePeakFrame(in, channels, false);
            limiterGain_ = 1.0f;
        }
    } else {
        ++limiterSampleIndex_;
        limiterGain_ = 1.0f;
    }

    limiterPendingFrames_.fetch_add(1, std::memory_order_relaxed);
    limiterWritePos_ = (limiterWritePos_ + 1) % limiterRingSize_;

    if (limiterPendingFrames_.load(std::memory_order_relaxed) <=
        limiterTotalDelayFrames_.load(std::memory_order_relaxed)) return false;

    const int readPos = limiterReadPos_;
    const float gain = limiterGain_;
    for (int ch = 0; ch < channels; ++ch) {
        out[ch] = limiterDelay_[readPos][ch] * gain;
    }

    limiterReadPos_ = (limiterReadPos_ + 1) % limiterRingSize_;
    limiterPendingFrames_.fetch_sub(1, std::memory_order_relaxed);
    return true;
}

int Engine::processFloat(float* data, int frames, int channels) {
    if (!data || frames <= 0 || channels <= 0 || channels > kMaxChannels) return 0;

    syncParams();
    configureLimiterIfNeeded();

    if (resetRequested_.exchange(false, std::memory_order_acq_rel)) {
        clearStates();
    }

    const bool bypass = bypass_.load(std::memory_order_relaxed);
    if (bypass) {
        if (!wasBypassed_) {
            clearStates();
            wasBypassed_ = true;
        }
        return frames;
    }

    if (wasBypassed_) {
        clearStates();
        wasBypassed_ = false;
    }

    const double sr = sampleRate_.load(std::memory_order_relaxed);
    const float targetIn = dbToLin(inputGainDb_.load(std::memory_order_relaxed));
    const float autoMakeup = autoGainEnabled_.load(std::memory_order_relaxed)
        ? autoMakeupGainDb_.load(std::memory_order_relaxed) *
          autoGainAmount_.load(std::memory_order_relaxed)
        : 0.0f;
    const float targetOut = dbToLin(
        outputGainDb_.load(std::memory_order_relaxed) + autoMakeup);
    const float filterAlpha = static_cast<float>(smoothingCoeffMs(
        filterSmoothingMs_.load(std::memory_order_relaxed), sr));
    const float gainAlpha = static_cast<float>(smoothingCoeffMs(
        gainSmoothingMs_.load(std::memory_order_relaxed), sr));

    inputGainCoeff_ = gainAlpha;
    outputGainCoeff_ = gainAlpha;

    float filtered[kMaxChannels];
    float limited[kMaxChannels];
    int outputFrames = 0;

    for (int f = 0; f < frames; ++f) {
        inputGain_ += (targetIn - inputGain_) * inputGainCoeff_;
        outputGain_ += (targetOut - outputGain_) * outputGainCoeff_;

        float* inFrame = data + static_cast<size_t>(f) * channels;
        for (int ch = 0; ch < channels; ++ch) {
            filtered[ch] = finiteSample(inFrame[ch]) * inputGain_;
        }

        const bool graphicOn = graphicEnabled_.load(std::memory_order_relaxed);
        const bool peqOn = peqEnabled_.load(std::memory_order_relaxed);
        const float wetAlpha = gainAlpha;
        graphicWet_ += ((graphicOn ? 1.0f : 0.0f) - graphicWet_) * wetAlpha;
        peqWet_ += ((peqOn ? 1.0f : 0.0f) - peqWet_) * wetAlpha;

        // Stage 1: independent 10-band Graphic EQ.
        if (graphicWet_ > 1e-6f) {
            float dry[kMaxChannels];
            for (int ch = 0; ch < channels; ++ch) dry[ch] = filtered[ch];

            for (int b = 0; b < kGraphicBands; ++b) {
                Coeffs& s = graphicSmoothed_[b];
                const Coeffs& t = graphicTarget_[b];
                s.b0 += (t.b0 - s.b0) * filterAlpha;
                s.b1 += (t.b1 - s.b1) * filterAlpha;
                s.b2 += (t.b2 - s.b2) * filterAlpha;
                s.a1 += (t.a1 - s.a1) * filterAlpha;
                s.a2 += (t.a2 - s.a2) * filterAlpha;
                s.active = !effectivelyIdentity(s);
                if (!s.active) continue;

                for (int ch = 0; ch < channels; ++ch) {
                    const double x = static_cast<double>(filtered[ch]);
                    double z1 = graphicZ1_[b][ch];
                    double z2 = graphicZ2_[b][ch];
                    const double y = s.b0 * x + z1;
                    z1 = s.b1 * x - s.a1 * y + z2;
                    z2 = s.b2 * x - s.a2 * y;
                    graphicZ1_[b][ch] = (std::fabs(z1) < 1e-30) ? 0.0 : z1;
                    graphicZ2_[b][ch] = (std::fabs(z2) < 1e-30) ? 0.0 : z2;
                    filtered[ch] = finiteSample(static_cast<float>(y));
                }
            }
            // Smooth stage bypass without changing its own filter topology.
            for (int ch = 0; ch < channels; ++ch) {
                filtered[ch] = dry[ch] + (filtered[ch] - dry[ch]) * graphicWet_;
            }
        } else if (!graphicOn) {
            std::memset(graphicZ1_, 0, sizeof(graphicZ1_));
            std::memset(graphicZ2_, 0, sizeof(graphicZ2_));
        }

        // Stage 2: advanced PEQ. Its state/quality is independent of the Graphic EQ.
        if (peqWet_ > 1e-6f) {
            float dry[kMaxChannels];
            for (int ch = 0; ch < channels; ++ch) dry[ch] = filtered[ch];

            if (linearPhaseEnabled_.load(std::memory_order_relaxed)) {
                if (firTransitionRemaining_ > 0) {
                    const float alpha = 1.0f - static_cast<float>(firTransitionRemaining_) /
                        static_cast<float>(std::max(1, firTransitionTotal_));
                    for (int ch = 0; ch < channels; ++ch) {
                        linearFirState_[ch][linearFirWritePos_] = filtered[ch];
                        double y = 0.0;
                        int idx = linearFirWritePos_;
                        for (int i = 0; i < linearFirTapsActive_; ++i) {
                            const float c = activeFir_[i] + alpha * (targetFir_[i] - activeFir_[i]);
                            y += static_cast<double>(c) * static_cast<double>(linearFirState_[ch][idx]);
                            if (--idx < 0) idx = linearFirTapsActive_ - 1;
                        }
                        filtered[ch] = finiteSample(static_cast<float>(y));
                    }
                    --firTransitionRemaining_;
                    if (firTransitionRemaining_ <= 0) {
                        for (int i = 0; i < kMaxLinearPhaseTaps; ++i) activeFir_[i] = targetFir_[i];
                    }
                } else {
                    for (int ch = 0; ch < channels; ++ch) {
                        linearFirState_[ch][linearFirWritePos_] = filtered[ch];
                        double y = 0.0;
                        int idx = linearFirWritePos_;
                        for (int i = 0; i < linearFirTapsActive_; ++i) {
                            y += static_cast<double>(activeFir_[i]) * static_cast<double>(linearFirState_[ch][idx]);
                            if (--idx < 0) idx = linearFirTapsActive_ - 1;
                        }
                        filtered[ch] = finiteSample(static_cast<float>(y));
                    }
                }
                if (++linearFirWritePos_ >= linearFirTapsActive_) linearFirWritePos_ = 0;
            } else {
                for (int b = 0; b < numBands_; ++b) {
                    Coeffs& s = smoothed_[b];
                    const Coeffs& t = target_[b];
                    s.b0 += (t.b0 - s.b0) * filterAlpha;
                    s.b1 += (t.b1 - s.b1) * filterAlpha;
                    s.b2 += (t.b2 - s.b2) * filterAlpha;
                    s.a1 += (t.a1 - s.a1) * filterAlpha;
                    s.a2 += (t.a2 - s.a2) * filterAlpha;
                    s.active = !effectivelyIdentity(s);
                    if (!s.active) continue;

                    for (int ch = 0; ch < channels; ++ch) {
                        const double x = static_cast<double>(filtered[ch]);
                        double z1 = z1_[b][ch];
                        double z2 = z2_[b][ch];
                        const double y = s.b0 * x + z1;
                        z1 = s.b1 * x - s.a1 * y + z2;
                        z2 = s.b2 * x - s.a2 * y;
                        z1_[b][ch] = (std::fabs(z1) < 1e-30) ? 0.0 : z1;
                        z2_[b][ch] = (std::fabs(z2) < 1e-30) ? 0.0 : z2;
                        filtered[ch] = finiteSample(static_cast<float>(y));
                    }
                }
            }

            // In minimum-phase mode the bypass crossfade is exactly time-aligned.
            // In linear-phase mode the user should configure PEQ mode before playback;
            // the FIR path is intentionally kept fully delayed rather than mixing an
            // undelayed dry signal with a delayed wet signal.
            if (!linearPhaseEnabled_.load(std::memory_order_relaxed)) {
                for (int ch = 0; ch < channels; ++ch) {
                    filtered[ch] = dry[ch] + (filtered[ch] - dry[ch]) * peqWet_;
                }
            }
        } else if (!peqOn) {
            std::memset(z1_, 0, sizeof(z1_));
            std::memset(z2_, 0, sizeof(z2_));
            clearLinearFirState();
        }

        for (int ch = 0; ch < channels; ++ch) {
            filtered[ch] = finiteSample(filtered[ch] * outputGain_);
        }

        if (processLimiterFrame(filtered, limited, channels)) {
            float* outFrame = data + static_cast<size_t>(outputFrames) * channels;
            for (int ch = 0; ch < channels; ++ch) outFrame[ch] = finiteSample(limited[ch]);
            ++outputFrames;
        }
    }

    return outputFrames;
}

int Engine::processS16(int16_t* data, int frames, int channels) {
    if (!data || frames <= 0 || channels <= 0 || channels > kMaxChannels) return 0;

    constexpr int kChunk = 256;
    float tmp[kChunk * kMaxChannels];

    int remaining = frames;
    int16_t* readCursor = data;
    int16_t* writeCursor = data;
    int totalOut = 0;

    while (remaining > 0) {
        const int n = std::min(remaining, kChunk);
        const int count = n * channels;

        for (int i = 0; i < count; ++i) {
            tmp[i] = static_cast<float>(readCursor[i]) * (1.0f / 32768.0f);
        }

        const int produced = processFloat(tmp, n, channels);
        const int outCount = produced * channels;
        for (int i = 0; i < outCount; ++i) {
            const float scaled = finiteSample(tmp[i]) * 32768.0f;
            const float bounded = std::min(std::max(scaled, -32768.0f), 32767.0f);
            writeCursor[i] = static_cast<int16_t>(std::lrintf(bounded));
        }

        writeCursor += outCount;
        readCursor += count;
        totalOut += produced;
        remaining -= n;
    }

    return totalOut;
}

int Engine::drainFloat(float* data, int maxFrames, int channels) {
    if (!data || maxFrames <= 0 || channels <= 0 || channels > kMaxChannels) return 0;

    int produced = 0;
    const bool enabled = limiterEnabled_.load(std::memory_order_relaxed);

    if (!enabled) {
        while (limiterPendingFrames_.load(std::memory_order_relaxed) > 0 && produced < maxFrames) {
            const int pos = limiterReadPos_;
            for (int ch = 0; ch < channels; ++ch) {
                data[static_cast<size_t>(produced) * channels + ch] = limiterDelay_[pos][ch];
            }
            limiterReadPos_ = (limiterReadPos_ + 1) % limiterRingSize_;
            limiterPendingFrames_.fetch_sub(1, std::memory_order_relaxed);
            ++produced;
        }
        return produced;
    }

    // If the stream ended before the fixed latency elapsed, advance the
    // detector with zeros until the first scheduled output gain exists.
    const int totalDelay = limiterTotalDelayFrames_.load(std::memory_order_relaxed);
    while (limiterPendingFrames_.load(std::memory_order_relaxed) > 0 &&
           limiterSampleIndex_ < static_cast<uint64_t>(totalDelay)) {
        advanceLimiterDetectorWithZero(channels);
    }

    while (limiterPendingFrames_.load(std::memory_order_relaxed) > 0 && produced < maxFrames) {
        // One zero detector frame establishes the gain for the oldest remaining
        // audio frame. Feeding zeros models the signal's finite end conservatively.
        advanceLimiterDetectorWithZero(channels);

        const int pos = limiterReadPos_;
        const float gain = limiterGain_;
        for (int ch = 0; ch < channels; ++ch) {
            const float y = limiterDelay_[pos][ch] * gain;
            data[static_cast<size_t>(produced) * channels + ch] = finiteSample(y);
        }

        limiterReadPos_ = (limiterReadPos_ + 1) % limiterRingSize_;
        limiterPendingFrames_.fetch_sub(1, std::memory_order_relaxed);
        ++produced;
    }

    return produced;
}

int Engine::drainS16(int16_t* data, int maxFrames, int channels) {
    if (!data || maxFrames <= 0 || channels <= 0 || channels > kMaxChannels) return 0;

    // The pending delay is bounded by the configured ring size, so a small
    // fixed temporary buffer is enough to reuse the float drain path.
    constexpr int kChunk = 256;
    float tmp[kChunk * kMaxChannels];

    int remaining = maxFrames;
    int16_t* out = data;
    int total = 0;
    while (remaining > 0) {
        const int n = std::min(remaining, kChunk);
        const int produced = drainFloat(tmp, n, channels);
        const int count = produced * channels;
        for (int i = 0; i < count; ++i) {
            const float scaled = finiteSample(tmp[i]) * 32768.0f;
            const float bounded = std::min(std::max(scaled, -32768.0f), 32767.0f);
            out[i] = static_cast<int16_t>(std::lrintf(bounded));
        }
        out += count;
        total += produced;
        remaining -= produced;
        if (produced == 0) break;
    }
    return total;
}

} // namespace peq
