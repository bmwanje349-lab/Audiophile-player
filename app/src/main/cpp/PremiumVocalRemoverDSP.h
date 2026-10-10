#pragma once

/*
    PremiumVocalRemoverDSP.h  (v2 - "Wiener centre extraction")
    -----------------------------------------------------------
    C++17, header-only hybrid vocal-removal post processor.

    Two operating modes
      1. Neural-stem mode (unchanged contract)
         A separator (MDX-Net 9482) supplies a sample-aligned vocal stem which
         is subtracted from the mix.
      2. Spectral mode ("Fast Live", no network weights needed)
         A causal STFT centre extractor.  This replaces the previous
         threshold-mask fallback, which only engaged when >= 92 % of a bin's
         energy was centred and could not drop a bin below -18 dB.  On a real
         mixed test clip that old fallback cut the vocal by ~8 dB but ALSO
         cut the backing track by ~7 dB (worse than not processing at all).

         v2 algorithm (all per STFT bin, N = 2048, hop = 512):
           M = (L+R)/2, S = (L-R)/2
           PM, PS      3x3 time/frequency smoothed |M|^2, |S|^2
           w           = max(PM - kappa*PS, 0) / PM
                         (Wiener estimate of the common-to-both-channels
                          component: centred content shows up in M but not S)
           harmonic    h = Hm^2 / (Hm^2 + Pm^2) where Hm / Pm are the time /
                         frequency medians of |M| (HPSS).  Centred drums and
                         bass transients are percussive, voice is harmonic.
           band        smooth window over the vocal range (focus control)
           mask        = depth * band * (1 - tp*(1-h)) * w
                         with fast-attack / slower-release smoothing
           L' = L - mask*M,   R' = R - mask*M

         Measured on a real clip against the MDX stems (6 s excerpt):
           old fallback : vocal -8 dB (regression), accompaniment -7 dB
           this version : vocal -7 dB, accompaniment -0.9 dB (SDR 7.7 dB vs
                          4.6 dB).  With a dead-centre vocal: -14.5 dB vocal,
                          -0.9 dB accompaniment.
         A centre extractor can never remove stereo reverb / widened vocals;
         that limit (~8 dB on the test clip even with an oracle) is why the
         neural path remains the high-quality mode.

    The class does NOT contain neural-network weights.
*/

#include <algorithm>
#include <atomic>
#include <array>
#include <cmath>
#include <complex>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <vector>

#include "ProfessionalStereoWidenerDSP_v10.h"

namespace ProfessionalDSP {

class PremiumVocalRemoverDSP {
public:
    static constexpr std::size_t kFFTSize = 2048;
    static constexpr std::size_t kHop = 512;
    static constexpr std::size_t kBins = kFFTSize / 2 + 1;
    /* Future frames needed by the harmonic/percussive time-median (9 taps). */
    static constexpr std::size_t kLookFrames = 4;
    static constexpr std::size_t kRingFrames = 2 * kLookFrames + 1;
    /* Algorithmic delay of the spectral path in samples (analysis window
       fill + look-ahead frames).  The neural path is delayed by the same
       amount so that switching modes never changes the player latency. */
    static constexpr std::size_t kAlgorithmicLatency =
        kFFTSize + kLookFrames * kHop - 1;

    struct MeterSnapshot {
        float removalAmount = 0.0f;
        float centerSuppressionDb = 0.0f;
        float outputPeakDbTP = -120.0f;
        bool neuralStemActive = false;
    };

    void prepare(double sampleRate) noexcept {
        fs_ = static_cast<float>(std::max(8000.0, sampleRate));

        depth_.prepare(fs_, 25.0f, 1.0f);
        focus_.prepare(fs_, 30.0f, 0.5f);
        transientProtection_.prepare(fs_, 35.0f, 0.70f);
        stemGain_.prepare(fs_, 20.0f, 1.0f);
        dryWet_.prepare(fs_, 25.0f, 1.0f);
        outputTrim_.prepare(fs_, 25.0f, 1.0f);

        limiter_.prepare(fs_);
        limiter_.setCeilingDb(-1.0f);
        /* The library default (1.7 dB) silently removed ~2.7 dB of output
           level in karaoke mode.  0.25 dB is the same margin the stereo
           widener already ships with. */
        limiter_.setSafetyMarginDb(0.25f);

        buildTables();
        reset();
    }

    void reset() noexcept {
        inFill_ = 0;
        inL_.fill(0.0f);
        inR_.fill(0.0f);
        framesIn_ = 0;

        for (auto& f : specL_) f.fill(Cpx(0.0f, 0.0f));
        for (auto& f : specR_) f.fill(Cpx(0.0f, 0.0f));
        for (auto& f : ringMag_) f.fill(0.0f);
        for (auto& f : ringPm_) f.fill(0.0f);
        for (auto& f : ringPs_) f.fill(0.0f);
        prevMask_.fill(0.0f);
        maskOut_.fill(0.0f);

        olaL_.fill(0.0f);
        olaR_.fill(0.0f);
        olaEnv_.fill(0.0f);
        outFifoL_.fill(0.0f);
        outFifoR_.fill(0.0f);
        outRead_ = outWrite_ = outCount_ = 0;

        limiter_.reset();

        depth_.setImmediate(depthTarget_.load(std::memory_order_relaxed));
        focus_.setImmediate(focusTarget_.load(std::memory_order_relaxed));
        transientProtection_.setImmediate(
            transientTarget_.load(std::memory_order_relaxed));
        stemGain_.setImmediate(stemGainTarget_.load(std::memory_order_relaxed));
        dryWet_.setImmediate(dryWetTarget_.load(std::memory_order_relaxed));
        outputTrim_.setImmediate(
            outputGainTarget_.load(std::memory_order_relaxed));

        meterRemoval_.store(0.0f, std::memory_order_relaxed);
        meterSuppressionDb_.store(0.0f, std::memory_order_relaxed);
        meterPeakDb_.store(-120.0f, std::memory_order_relaxed);

        lastOutputPeak_ = 0.0f;
        stemActive_ = false;
        currentDepth_ = depthTarget_.load(std::memory_order_relaxed);
        currentFocus_ = focusTarget_.load(std::memory_order_relaxed);
        currentTransientProtection_ =
            transientTarget_.load(std::memory_order_relaxed);
        bandFocus_ = -1.0f;

        delayL_.fill(0.0f);
        delayR_.fill(0.0f);
        delayWrite_ = 0;
        delayCount_ = 0;

        neuralDelayL_.fill(0.0f);
        neuralDelayR_.fill(0.0f);
        neuralDelayWrite_ = 0;
        neuralDelayCount_ = 0;
        neuralDelayOutputL_ = 0.0f;
        neuralDelayOutputR_ = 0.0f;

        removalMeter_ = 0.0f;
        suppressionMeterDb_ = 0.0f;
    }

    void setDepth(float value) noexcept {
        depthTarget_.store(clampf(value, 0.0f, 1.0f), std::memory_order_relaxed);
    }

    void setVocalFocus(float value) noexcept {
        focusTarget_.store(clampf(value, 0.0f, 1.0f), std::memory_order_relaxed);
    }

    void setTransientProtection(float value) noexcept {
        transientTarget_.store(clampf(value, 0.0f, 1.0f), std::memory_order_relaxed);
    }

    void setVocalStemGainDb(float db) noexcept {
        stemGainTarget_.store(dbToGain(clampf(db, -12.0f, 6.0f)), std::memory_order_relaxed);
    }

    void setDryWet(float value) noexcept {
        dryWetTarget_.store(clampf(value, 0.0f, 1.0f), std::memory_order_relaxed);
    }

    void setOutputGainDb(float db) noexcept {
        outputGainTarget_.store(dbToGain(clampf(db, -12.0f, 6.0f)), std::memory_order_relaxed);
    }

    void setOutputCeilingDb(float db) noexcept {
        ceilingTarget_.store(clampf(db, -12.0f, 0.0f), std::memory_order_relaxed);
    }

    void setNeuralStemMode(bool enabled) noexcept {
        stemModeTarget_.store(enabled, std::memory_order_relaxed);
    }

    /*
        Process a block.  If neural stem mode is on and vocalL/R are supplied,
        the stem is subtracted from the mix; otherwise the spectral centre
        extractor runs and vocalL/R may be nullptr.  The output always lags
        the input by latencySamples().
    */
    void processBlock(const float* mixL,
                      const float* mixR,
                      const float* vocalL,
                      const float* vocalR,
                      float* outL,
                      float* outR,
                      std::size_t n) noexcept
    {
        if (!mixL || !mixR || !outL || !outR || n == 0) return;

        depth_.setTarget(depthTarget_.load(std::memory_order_relaxed));
        focus_.setTarget(focusTarget_.load(std::memory_order_relaxed));
        transientProtection_.setTarget(
            transientTarget_.load(std::memory_order_relaxed));
        stemGain_.setTarget(stemGainTarget_.load(std::memory_order_relaxed));
        dryWet_.setTarget(dryWetTarget_.load(std::memory_order_relaxed));
        outputTrim_.setTarget(outputGainTarget_.load(std::memory_order_relaxed));
        const float ceilingTarget = ceilingTarget_.load(std::memory_order_relaxed);

        const bool useStem = stemModeTarget_.load(std::memory_order_relaxed) &&
                             vocalL && vocalR;
        stemActive_.store(useStem, std::memory_order_relaxed);

        for (std::size_t i = 0; i < n; ++i) {
            const float d = dryWet_.process();
            const float outputGain = outputTrim_.process();
            const float stemGain = stemGain_.process();
            currentDepth_ = depth_.process();
            currentFocus_ = focus_.process();
            currentTransientProtection_ = transientProtection_.process();

            float L = safeSample(mixL[i]);
            float R = safeSample(mixR[i]);

            if (useStem) {
                const float vL = safeSample(vocalL[i]);
                const float vR = safeSample(vocalR[i]);

                /* The separator's stem is authoritative; stemGain and depth
                   are the only controls.  (Inferring gain from mix/stem
                   correlation would over-subtract correlated backing.) */
                const float removedL = L - stemGain * vL;
                const float removedR = R - stemGain * vR;

                L += d * currentDepth_ * (removedL - L);
                R += d * currentDepth_ * (removedR - R);

                alignNeuralPath(L, R);
                L = neuralDelayOutputL_;
                R = neuralDelayOutputR_;
            } else {
                float delayedDryL = 0.0f;
                float delayedDryR = 0.0f;
                delayAndGetStereo(L, R, delayedDryL, delayedDryR);

                pushSample(L, R);

                float wetL = 0.0f;
                float wetR = 0.0f;
                popOutput(wetL, wetR);

                L = delayedDryL + d * (wetL - delayedDryL);
                R = delayedDryR + d * (wetR - delayedDryR);
            }

            L *= outputGain;
            R *= outputGain;

            limiter_.setCeilingDb(ceilingTarget);
            limiter_.process(L, R);

            outL[i] = L;
            outR[i] = R;
        }

        const float peak = limiter_.getOutputTruePeak();
        lastOutputPeak_ = std::max(lastOutputPeak_ * 0.997f, peak);
        meterPeakDb_.store(gainToDb(std::max(lastOutputPeak_, 1.0e-12f)),
                           std::memory_order_relaxed);
        meterRemoval_.store(removalMeter_, std::memory_order_relaxed);
        meterSuppressionDb_.store(suppressionMeterDb_, std::memory_order_relaxed);
    }

    MeterSnapshot getMeters() const noexcept {
        MeterSnapshot m;
        m.removalAmount = meterRemoval_.load(std::memory_order_relaxed);
        m.centerSuppressionDb = meterSuppressionDb_.load(std::memory_order_relaxed);
        m.outputPeakDbTP = meterPeakDb_.load(std::memory_order_relaxed);
        m.neuralStemActive = stemActive_.load(std::memory_order_relaxed);
        return m;
    }

    std::size_t latencySamples() const noexcept {
        /* Both neural and spectral paths are aligned to the same delay. */
        return limiter_.latencySamples() + kAlgorithmicLatency;
    }

private:
    using Cpx = std::complex<float>;

    /* ---------------------------------------------------------------- */
    /* Tables / FFT                                                      */
    /* ---------------------------------------------------------------- */
    void buildTables() noexcept {
        for (std::size_t n = 0; n < kFFTSize; ++n) {
            /* periodic Hann; hop N/4 => sum of squares = 1.5 */
            window_[n] = 0.5f - 0.5f * std::cos(kTwoPi * static_cast<float>(n) /
                                                static_cast<float>(kFFTSize));
        }
        for (std::size_t k = 0; k < kFFTSize / 2; ++k) {
            const double a = -2.0 * 3.14159265358979323846 *
                             static_cast<double>(k) /
                             static_cast<double>(kFFTSize);
            twiddle_[k] = Cpx(static_cast<float>(std::cos(a)),
                              static_cast<float>(std::sin(a)));
        }
        std::size_t bits = 0;
        while ((static_cast<std::size_t>(1) << bits) < kFFTSize) ++bits;
        for (std::size_t i = 0; i < kFFTSize; ++i) {
            std::size_t r = 0;
            for (std::size_t b = 0; b < bits; ++b)
                if (i & (static_cast<std::size_t>(1) << b))
                    r |= static_cast<std::size_t>(1) << (bits - 1 - b);
            bitrev_[i] = static_cast<std::uint16_t>(r);
        }
    }

    /* In-place iterative radix-2 FFT on kFFTSize points. */
    void fft(std::array<Cpx, kFFTSize>& a, bool inverse) const noexcept {
        for (std::size_t i = 0; i < kFFTSize; ++i) {
            const std::size_t j = bitrev_[i];
            if (i < j) std::swap(a[i], a[j]);
        }
        for (std::size_t len = 2; len <= kFFTSize; len <<= 1) {
            const std::size_t half = len >> 1;
            const std::size_t step = kFFTSize / len;
            for (std::size_t i = 0; i < kFFTSize; i += len) {
                for (std::size_t j = 0; j < half; ++j) {
                    Cpx w = twiddle_[j * step];
                    if (inverse) w = std::conj(w);
                    const Cpx u = a[i + j];
                    const Cpx v = a[i + j + half] * w;
                    a[i + j] = u + v;
                    a[i + j + half] = u - v;
                }
            }
        }
        if (inverse) {
            const float invN = 1.0f / static_cast<float>(kFFTSize);
            for (auto& x : a) x *= invN;
        }
    }

    /* ---------------------------------------------------------------- */
    /* Delay lines (dry path + neural path alignment)                    */
    /* ---------------------------------------------------------------- */
    void delayAndGetStereo(float L, float R,
                           float& outL, float& outR) noexcept {
        constexpr std::size_t kDelay = kAlgorithmicLatency;
        delayL_[delayWrite_] = L;
        delayR_[delayWrite_] = R;

        if (delayCount_ >= kDelay) {
            const std::size_t read =
                (delayWrite_ + delayL_.size() - kDelay) % delayL_.size();
            outL = delayL_[read];
            outR = delayR_[read];
        } else {
            outL = 0.0f;
            outR = 0.0f;
        }

        if (delayCount_ < delayL_.size()) ++delayCount_;
        delayWrite_ = (delayWrite_ + 1) % delayL_.size();
    }

    void alignNeuralPath(float L, float R) noexcept {
        constexpr std::size_t kDelay = kAlgorithmicLatency;
        neuralDelayL_[neuralDelayWrite_] = L;
        neuralDelayR_[neuralDelayWrite_] = R;

        if (neuralDelayCount_ >= kDelay) {
            const std::size_t read =
                (neuralDelayWrite_ + neuralDelayL_.size() - kDelay) %
                neuralDelayL_.size();
            neuralDelayOutputL_ = neuralDelayL_[read];
            neuralDelayOutputR_ = neuralDelayR_[read];
        } else {
            neuralDelayOutputL_ = 0.0f;
            neuralDelayOutputR_ = 0.0f;
        }

        if (neuralDelayCount_ < neuralDelayL_.size()) ++neuralDelayCount_;
        neuralDelayWrite_ = (neuralDelayWrite_ + 1) % neuralDelayL_.size();
    }

    /* ---------------------------------------------------------------- */
    /* Streaming STFT plumbing                                           */
    /* ---------------------------------------------------------------- */
    void pushSample(float L, float R) noexcept {
        /* inL_/inR_ hold the most recent kFFTSize samples in order. */
        if (inFill_ < kFFTSize) {
            inL_[inFill_] = L;
            inR_[inFill_] = R;
            ++inFill_;
        } else {
            /* Window is full: it is slid by one hop when a frame is
               consumed, so this branch only runs while consuming. */
            std::copy(inL_.begin() + kHop, inL_.end(), inL_.begin());
            std::copy(inR_.begin() + kHop, inR_.end(), inR_.begin());
            inFill_ = kFFTSize - kHop;
            inL_[inFill_] = L;
            inR_[inFill_] = R;
            ++inFill_;
        }

        if (inFill_ == kFFTSize) {
            analyseFrame();
            ++framesIn_;
            if (framesIn_ > kLookFrames) {
                processCenterFrame(framesIn_ - 1 - kLookFrames);
            }
            /* Slide for the next hop. */
            std::copy(inL_.begin() + kHop, inL_.end(), inL_.begin());
            std::copy(inR_.begin() + kHop, inR_.end(), inR_.begin());
            inFill_ = kFFTSize - kHop;
        }
    }

    bool popOutput(float& L, float& R) noexcept {
        if (outCount_ == 0) {
            L = 0.0f;
            R = 0.0f;
            return false;
        }
        L = outFifoL_[outRead_];
        R = outFifoR_[outRead_];
        outRead_ = (outRead_ + 1) % outFifoL_.size();
        --outCount_;
        return true;
    }

    void pushOutput(float L, float R) noexcept {
        if (outCount_ >= outFifoL_.size()) {
            outRead_ = (outRead_ + 1) % outFifoL_.size();
            --outCount_;
        }
        outFifoL_[outWrite_] = L;
        outFifoR_[outWrite_] = R;
        outWrite_ = (outWrite_ + 1) % outFifoL_.size();
        ++outCount_;
    }

    /* Forward transform of the newest kFFTSize samples (L in the real part,
       R in the imaginary part of one complex FFT) and storage of the
       per-frame statistics in the ring. */
    void analyseFrame() noexcept {
        const std::size_t slot = framesIn_ % kRingFrames;

        for (std::size_t n = 0; n < kFFTSize; ++n) {
            fftBuf_[n] = Cpx(inL_[n] * window_[n], inR_[n] * window_[n]);
        }
        fft(fftBuf_, false);

        auto& sl = specL_[slot];
        auto& sr = specR_[slot];
        auto& mag = ringMag_[slot];
        auto& pm = ringPm_[slot];
        auto& ps = ringPs_[slot];

        for (std::size_t k = 0; k < kBins; ++k) {
            const Cpx z = fftBuf_[k];
            const Cpx zc = std::conj(fftBuf_[(kFFTSize - k) % kFFTSize]);
            const Cpx l = 0.5f * (z + zc);
            /* (z - zc) / (2i) = -i/2 * (z - zc) */
            const Cpx diff = z - zc;
            const Cpx r(0.5f * diff.imag(), -0.5f * diff.real());
            sl[k] = l;
            sr[k] = r;

            const Cpx m = 0.5f * (l + r);
            const Cpx s = 0.5f * (l - r);
            const float m2 = std::norm(m);
            pm[k] = m2;
            ps[k] = std::norm(s);
            mag[k] = std::sqrt(m2);
        }
    }

    /* Small fixed-size median helpers (insertion sort). */
    template <std::size_t Taps>
    static float medianOf(std::array<float, Taps>& v) noexcept {
        for (std::size_t i = 1; i < Taps; ++i) {
            const float x = v[i];
            std::size_t j = i;
            while (j > 0 && v[j - 1] > x) {
                v[j] = v[j - 1];
                --j;
            }
            v[j] = x;
        }
        return v[Taps / 2];
    }

    void updateBandTables(float focus) noexcept {
        if (std::fabs(focus - bandFocus_) < 0.01f) return;
        bandFocus_ = focus;

        /* focus 0 = wide, gentle removal range; focus 1 = concentrate on the
           core vocal range and leave more low / high content untouched. */
        const float lo0 = 110.0f + 90.0f * focus;
        const float lo1 = 240.0f + 120.0f * focus;
        const float hi0 = 9500.0f - 3500.0f * focus;
        const float hi1 = 13500.0f - 3500.0f * focus;
        const float frameHz = fs_ / static_cast<float>(kFFTSize);

        for (std::size_t k = 0; k < kBins; ++k) {
            const float f = frameHz * static_cast<float>(k);
            const float lo = smoothstep(lo0, lo1, f);
            const float hi = 1.0f - smoothstep(hi0, hi1, f);
            bandWeight_[k] = lo * hi;
        }
    }

    float medianTimeMag(std::size_t center, std::size_t k) const noexcept {
        std::array<float, kRingFrames> v{};
        for (std::size_t i = 0; i < kRingFrames; ++i) {
            /* frames center-4 .. center+4 (all present in the ring) */
            const std::size_t f = center + i + kRingFrames - kLookFrames;
            v[i] = ringMag_[f % kRingFrames][k];
        }
        return medianOf<kRingFrames>(v);
    }

    void processCenterFrame(std::size_t c) noexcept {
        /* c is the frame index (>= 0) located kLookFrames behind the newest
           frame; its neighbours c-4 .. c+4 are all in the ring (for c < 4
           the older slots still hold the zero-initialised startup frames). */
        const std::size_t sc = c % kRingFrames;
        const std::size_t sp = (c + kRingFrames - 1) % kRingFrames;
        const std::size_t sn = (c + 1) % kRingFrames;

        const float depth = currentDepth_;
        const float tp = currentTransientProtection_;
        updateBandTables(currentFocus_);

        constexpr float kKappa = 1.0f;
        constexpr float kAttack = 0.80f;
        constexpr float kRelease = 0.25f;
        constexpr std::size_t kFreqMed = 17;

        /* Frequency-smoothed (3 taps) PM and PS for the three frames. */
        const auto& pmP = ringPm_[sp];
        const auto& pmC = ringPm_[sc];
        const auto& pmN = ringPm_[sn];
        const auto& psP = ringPs_[sp];
        const auto& psC = ringPs_[sc];
        const auto& psN = ringPs_[sn];
        const auto& magC = ringMag_[sc];

        std::array<float, kFreqMed> fv{};

        double originalEnergy = 0.0;
        double removedEnergy = 0.0;

        for (std::size_t k = 0; k < kBins; ++k) {
            const std::size_t k0 = k > 0 ? k - 1 : 0;
            const std::size_t k2 = k + 1 < kBins ? k + 1 : kBins - 1;

            const float PM =
                (pmP[k0] + pmP[k] + pmP[k2] +
                 pmC[k0] + pmC[k] + pmC[k2] +
                 pmN[k0] + pmN[k] + pmN[k2]) * (1.0f / 9.0f);
            const float PS =
                (psP[k0] + psP[k] + psP[k2] +
                 psC[k0] + psC[k] + psC[k2] +
                 psN[k0] + psN[k] + psN[k2]) * (1.0f / 9.0f);

            const float w = std::max(PM - kKappa * PS, 0.0f) /
                            (PM + 1.0e-12f);

            float cur = 0.0f;
            if (bandWeight_[k] > 0.0f && w > 0.0f && depth > 0.0f) {
                /* harmonic / percussive split of the mid magnitude */
                const float H = medianTimeMag(c, k);
                for (std::size_t i = 0; i < kFreqMed; ++i) {
                    long idx = static_cast<long>(k) +
                               static_cast<long>(i) -
                               static_cast<long>(kFreqMed / 2);
                    if (idx < 0) idx = 0;
                    if (idx >= static_cast<long>(kBins))
                        idx = static_cast<long>(kBins) - 1;
                    fv[i] = magC[static_cast<std::size_t>(idx)];
                }
                const float Pc = medianOf<kFreqMed>(fv);
                const float h2 = H * H;
                const float hm = h2 / (h2 + Pc * Pc + 1.0e-12f);
                const float hmw = 1.0f - tp * (1.0f - hm);

                cur = clampf(depth * bandWeight_[k] * hmw * w, 0.0f, 1.0f);
            }

            const float prev = prevMask_[k];
            const float a = cur > prev ? kAttack : kRelease;
            prevMask_[k] = prev + (cur - prev) * a;
        }

        /* 3-tap frequency smoothing of the time-smoothed mask. */
        for (std::size_t k = 0; k < kBins; ++k) {
            const std::size_t k0 = k > 0 ? k - 1 : 0;
            const std::size_t k2 = k + 1 < kBins ? k + 1 : kBins - 1;
            maskOut_[k] = clampf(
                (prevMask_[k0] + prevMask_[k] + prevMask_[k2]) *
                    (1.0f / 3.0f),
                0.0f, 1.0f);
        }

        /* Apply:  L' = L - mask*M ,  R' = R - mask*M, then inverse FFT of
           the packed (L' + i R') spectrum. */
        const auto& sl = specL_[sc];
        const auto& sr = specR_[sc];

        for (std::size_t k = 0; k < kBins; ++k) {
            const Cpx m = 0.5f * (sl[k] + sr[k]);
            const Cpx rem = maskOut_[k] * m;
            const Cpx lo = sl[k] - rem;
            const Cpx ro = sr[k] - rem;

            originalEnergy += static_cast<double>(std::norm(m));
            removedEnergy += static_cast<double>(std::norm(rem));

            /* z_k = lo + i*ro ;  z_{N-k} = conj(lo) + i*conj(ro) */
            const Cpx ri(-ro.imag(), ro.real());          /* i * ro      */
            fftBuf_[k] = lo + ri;
            if (k != 0 && k != kFFTSize / 2) {
                const Cpx loc = std::conj(lo);
                const Cpx roc = std::conj(ro);
                const Cpx ric(-roc.imag(), roc.real());   /* i * conj(ro) */
                fftBuf_[kFFTSize - k] = loc + ric;
            }
        }
        /* DC / Nyquist bins of each real channel are real: z = lo.re + i*ro.re */
        fftBuf_[0] = Cpx(sl[0].real() - maskOut_[0] * 0.5f * (sl[0].real() + sr[0].real()),
                         sr[0].real() - maskOut_[0] * 0.5f * (sl[0].real() + sr[0].real()));
        {
            const std::size_t ny = kFFTSize / 2;
            const float mny = 0.5f * (sl[ny].real() + sr[ny].real());
            fftBuf_[ny] = Cpx(sl[ny].real() - maskOut_[ny] * mny,
                              sr[ny].real() - maskOut_[ny] * mny);
        }
        fft(fftBuf_, true);

        for (std::size_t n = 0; n < kFFTSize; ++n) {
            const float w = window_[n];
            olaL_[n] += fftBuf_[n].real() * w;
            olaR_[n] += fftBuf_[n].imag() * w;
            olaEnv_[n] += w * w;
        }

        for (std::size_t n = 0; n < kHop; ++n) {
            const float norm = std::max(olaEnv_[n], 1.0e-6f);
            pushOutput(olaL_[n] / norm, olaR_[n] / norm);
        }

        std::copy(olaL_.begin() + kHop, olaL_.end(), olaL_.begin());
        std::copy(olaR_.begin() + kHop, olaR_.end(), olaR_.begin());
        std::copy(olaEnv_.begin() + kHop, olaEnv_.end(), olaEnv_.begin());
        std::fill(olaL_.end() - kHop, olaL_.end(), 0.0f);
        std::fill(olaR_.end() - kHop, olaR_.end(), 0.0f);
        std::fill(olaEnv_.end() - kHop, olaEnv_.end(), 0.0f);

        const float removal = clampf(
            static_cast<float>(removedEnergy /
                               std::max(originalEnergy, 1.0e-20)),
            0.0f, 1.0f);
        const float db = -10.0f *
            std::log10(std::max(1.0f - removal, 1.0e-6f));
        removalMeter_ = 0.90f * removalMeter_ + 0.10f * removal;
        suppressionMeterDb_ = 0.90f * suppressionMeterDb_ + 0.10f * db;
    }

    float fs_ = 48000.0f;

    SmoothedValue depth_;
    SmoothedValue focus_;
    SmoothedValue transientProtection_;
    SmoothedValue stemGain_;
    SmoothedValue dryWet_;
    SmoothedValue outputTrim_;

    StereoLinkedTruePeakLimiter limiter_;

    std::atomic<float> depthTarget_{1.0f};
    std::atomic<float> focusTarget_{0.5f};
    std::atomic<float> transientTarget_{0.70f};
    std::atomic<float> stemGainTarget_{1.0f};
    std::atomic<float> dryWetTarget_{1.0f};
    std::atomic<float> outputGainTarget_{1.0f};
    std::atomic<float> ceilingTarget_{-1.0f};
    std::atomic<bool> stemModeTarget_{false};
    std::atomic<bool> stemActive_{false};

    float currentDepth_ = 1.0f;
    float currentFocus_ = 0.5f;
    float currentTransientProtection_ = 0.70f;
    float bandFocus_ = -1.0f;

    std::array<float, kFFTSize> window_{};
    std::array<Cpx, kFFTSize / 2> twiddle_{};
    std::array<std::uint16_t, kFFTSize> bitrev_{};
    std::array<Cpx, kFFTSize> fftBuf_{};
    std::array<float, kBins> bandWeight_{};

    std::array<float, kFFTSize> inL_{};
    std::array<float, kFFTSize> inR_{};
    std::size_t inFill_ = 0;
    std::size_t framesIn_ = 0;

    std::array<std::array<Cpx, kBins>, kRingFrames> specL_{};
    std::array<std::array<Cpx, kBins>, kRingFrames> specR_{};
    std::array<std::array<float, kBins>, kRingFrames> ringMag_{};
    std::array<std::array<float, kBins>, kRingFrames> ringPm_{};
    std::array<std::array<float, kBins>, kRingFrames> ringPs_{};
    std::array<float, kBins> prevMask_{};
    std::array<float, kBins> maskOut_{};

    std::array<float, kFFTSize> olaL_{};
    std::array<float, kFFTSize> olaR_{};
    std::array<float, kFFTSize> olaEnv_{};
    std::array<float, kFFTSize * 4> outFifoL_{};
    std::array<float, kFFTSize * 4> outFifoR_{};
    std::size_t outRead_ = 0;
    std::size_t outWrite_ = 0;
    std::size_t outCount_ = 0;

    std::array<float, 8192> delayL_{};
    std::array<float, 8192> delayR_{};
    std::size_t delayWrite_ = 0;
    std::size_t delayCount_ = 0;

    std::array<float, 8192> neuralDelayL_{};
    std::array<float, 8192> neuralDelayR_{};
    std::size_t neuralDelayWrite_ = 0;
    std::size_t neuralDelayCount_ = 0;
    float neuralDelayOutputL_ = 0.0f;
    float neuralDelayOutputR_ = 0.0f;

    float removalMeter_ = 0.0f;
    float suppressionMeterDb_ = 0.0f;
    float lastOutputPeak_ = 0.0f;

    std::atomic<float> meterRemoval_{0.0f};
    std::atomic<float> meterSuppressionDb_{0.0f};
    std::atomic<float> meterPeakDb_{-120.0f};
};

} // namespace ProfessionalDSP
